package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Small results acknowledge an internal durable copy; external publication proceeds independently. */
class ResultJournal(private val root: Path, private val external: ArtifactStore,
                    private val maxBytes: Long = 128L * 1024 * 1024,
                    private val writable: () -> Boolean = { true }, start: Boolean = true,
                    private val retryClock: () -> Long = ::now,
                    private val syncDirectory: (Path) -> Unit = { FileChannel.open(it, READ).use { channel -> channel.force(true) } },
                    private val forceCompactedFile: (Path) -> Unit = { FileChannel.open(it, WRITE).use { channel -> channel.force(true) } },
                    private val deletePack: (Path) -> Boolean = Files::deleteIfExists) : ArtifactStore, AutoCloseable {
    @Serializable private data class Manifest(val job: Long, val blobs: List<ResultBlob>)
    private data class Batch(val path: Path, val manifest: Manifest, val charge: Long,
                             var readers: Int = 0, var busy: Boolean = false, var flushed: Boolean = false, var retryAt: Long = 0,
                             var individualRetry: Boolean = false, var durable: Boolean = true, val createdAt: Long = now(), var error: String? = null,
                             var superseded: Boolean = false, var retirement: Retirement? = null, var compactAfter: Long = 0)
    private data class Retirement(val remaining: MutableSet<Path>, val saved: Long)
    private data class Compaction(val inputs: List<Batch>, val sources: Map<String, Pair<ResultBlob, Batch>>, val reserved: Long)
    @Serializable data class Status(val batches: Int, val bytes: Long, val limit: Long, val lastError: String?,
                                   val oldestAgeMs: Long = 0, val drainErrors: Long = 0, val lastDrainAt: Long? = null,
                                   val compactions: Long = 0, val compactionReclaimedBytes: Long = 0,
                                   val compactionErrors: Long = 0, val lastCompactionError: String? = null,
                                   val lastDrainMs: Long? = null, val lastDrainBlobs: Int = 0)
    private val monitor = Any()
    private val batches = linkedMapOf<Path, Batch>()
    private val index = mutableMapOf<Pair<Long, String>, Batch>()
    private val pendingCompactions = linkedMapOf<Path, Pair<Batch, List<Batch>>>()
    private var used = 8192L // Directory and owner-lock allowance, plus rounded blocks charged for each pack.
    private var receiving = 0
    @Volatile private var closed = false
    @Volatile private var lastError: String? = null
    @Volatile private var loggedError: String? = null
    private var drainErrors = 0L
    private var lastDrainAt: Long? = null
    private var lastDrainMs: Long? = null
    private var lastDrainBlobs = 0
    private var compactions = 0L
    private var compactionReclaimedBytes = 0L
    private var compactionErrors = 0L
    private var lastCompactionError: String? = null
    private val channel: FileChannel
    private val ownership: java.nio.channels.FileLock
    private val workers = Executors.newScheduledThreadPool(2) { action -> Thread(action, "result-journal-drain").apply { isDaemon = true } }
    private val maintenance = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "result-journal-compaction").apply { isDaemon = true } }

    init {
        require(maxBytes >= 8192)
        require(Files.isDirectory(root.parent)) { "result journal parent must exist" }
        Files.createDirectories(root)
        syncDirectory(root.parent)
        channel = FileChannel.open(root.resolve("owner.lock"), CREATE, WRITE)
        ownership = try { checkNotNull(channel.tryLock()) { "result journal already owned" } }
            catch (failure: Exception) { channel.close(); throw failure }
        try {
            Files.list(root).use { paths -> paths.sorted().forEach { path ->
                val name = path.fileName.toString()
                when {
                    name == "owner.lock" -> Unit
                    name.startsWith(".pending-") -> Files.delete(path) // Never acknowledged before atomic publication.
                    name.matches(Regex("[1-9][0-9]*-[a-f0-9-]+\\.zip")) -> {
                        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                        val manifest = manifest(path)
                        require(name.startsWith("${manifest.job}-"))
                        val batch = Batch(path, manifest, charge(Files.size(path), manifest.blobs.size), createdAt = Files.getLastModifiedTime(path).toMillis())
                        for (blob in manifest.blobs) read(batch, blob.sha)
                        add(batch)
                    }
                    else -> error("unexpected result journal entry")
                }
            } }
            check(used <= maxBytes) { "recovered result journal exceeds its configured cap" }
            syncDirectory(root)
            recoverSuperseded()
        } catch (failure: Exception) { ownership.release(); channel.close(); workers.shutdownNow(); maintenance.shutdownNow(); throw failure }
        if (start) JournalDrainLoop(::drainGroup,
            hasWork = { synchronized(monitor) { batches.values.any { eligible(it) } } },
            closed = { closed }, schedule = { action, delay -> workers.schedule(action, delay, TimeUnit.MILLISECONDS) },
            onFailure = { failure ->
                val detail = failure.javaClass.simpleName
                if (loggedError != detail) System.err.println("result journal drain: $detail; pending data retained")
                loggedError = detail; lastError = detail
            }).start()
        if (start) maintenance.scheduleWithFixedDelay({
            try {
                if (synchronized(monitor) { !closed && (batches.size >= 256 || used >= maxBytes / 2) }) compactOne()
            } catch (failure: Exception) {
                synchronized(monitor) { compactionErrors++; lastCompactionError = failure.javaClass.simpleName }
            }
        }, 200, 200, TimeUnit.MILLISECONDS)
    }

    fun status(): Status = synchronized(monitor) {
        Status(batches.size, used, maxBytes, lastError,
            batches.values.minOfOrNull { it.createdAt }?.let { (now() - it).coerceAtLeast(0) } ?: 0, drainErrors, lastDrainAt,
            compactions, compactionReclaimedBytes, compactionErrors, lastCompactionError, lastDrainMs, lastDrainBlobs)
    }
    private fun charge(bytes: Long, blobs: Int): Long = ((bytes + 4095) / 4096 + 1) * 4096 + blobs * 512L
    private fun reservation(bytes: Long, blobs: Int): Long = charge(bytes + bytes / 16384 * 8 + blobs * 512L + 4096, blobs)
    private fun add(batch: Batch) {
        batches[batch.path] = batch; used += batch.charge
        for (blob in batch.manifest.blobs) index.putIfAbsent(batch.manifest.job to blob.sha, batch)
    }
    /** A strict, deterministic preferred-copy order makes restart coverage acyclic without a new disk format. */
    private fun recoverSuperseded() {
        val ordered = batches.values.sortedWith(compareByDescending<Batch> { it.manifest.blobs.size }.thenBy { it.path.fileName.toString() })
        index.clear()
        ordered.forEach { batch -> batch.manifest.blobs.forEach { index.putIfAbsent(batch.manifest.job to it.sha, batch) } }
        val retired = linkedMapOf<Path, MutableList<Batch>>()
        for (batch in ordered) {
            val preferred = index[batch.manifest.job to batch.manifest.blobs.first().sha] ?: continue
            if (preferred === batch || !preferred.manifest.blobs.containsAll(batch.manifest.blobs)) continue
            // The first hash chose an earlier strict winner, which covers the complete old pack.
            batch.superseded = true
            retired.getOrPut(preferred.path) { mutableListOf() }.add(batch)
        }
        for ((preferred, group) in retired) {
            val retirement = Retirement(group.mapTo(hashSetOf()) { it.path },
                (group.sumOf { it.charge } - batches.getValue(preferred).charge).coerceAtLeast(0))
            group.forEach { it.retirement = retirement }
        }
    }
    private fun check(job: Long, sha: String) { require(job > 0 && SHA256.matches(sha)) }

    override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
        require(job > 0 && blobs.size <= INLINE_FILES && blobs.values.sumOf { it.size.toLong() } <= INLINE_BYTES)
        for ((sha, bytes) in blobs) {
            check(job, sha); require(MessageDigest.getInstance("SHA-256").digest(bytes).hex() == sha) { "artifact checksum mismatch" }
        }
        synchronized(monitor) {
            check(!closed); check(writable()) { "result journal storage unavailable" }
            val unsynced = blobs.keys.mapNotNull { index[job to it] }.filter { !it.durable }.distinct()
            if (unsynced.isNotEmpty()) {
                syncDirectory(root)
                unsynced.forEach { it.durable = true }
            }
            val pending = blobs.filterKeys { (job to it) !in index }
            if (pending.isEmpty()) return
            // Reserve the worst-case stored ZIP/manifest size before creating even a temporary file.
            val reservation = reservation(pending.values.sumOf { it.size.toLong() }, pending.size)
            check(reservation <= maxBytes - used) { "result journal full; pending data retained" }
            val temporary = Files.createTempFile(root, ".pending-", ".tmp")
            val destination = root.resolve("$job-${UUID.randomUUID()}.zip")
            var published = false
            try {
                val manifest = Manifest(job, pending.map { ResultBlob(it.key, it.value.size.toLong()) })
                ZipOutputStream(java.io.BufferedOutputStream(Files.newOutputStream(temporary), 256 * 1024)).use { zip ->
                    zip.setLevel(0)
                    zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                    zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
                    for ((sha, bytes) in pending) {
                        zip.putNextEntry(ZipEntry(sha).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
                    }
                }
                val actual = charge(Files.size(temporary), pending.size); check(actual <= reservation)
                FileChannel.open(temporary, WRITE).use { it.force(true) }
                Files.move(temporary, destination, ATOMIC_MOVE); published = true
                // Until the containing directory is synced, this batch cannot acknowledge acceptance.
                syncDirectory(root)
                add(Batch(destination, manifest, actual))
            } catch (failure: Exception) {
                // If publication reached rename, retain and account the durable candidate for retry/recovery.
                if (published && destination !in batches) {
                    val recovered = manifest(destination)
                    add(Batch(destination, recovered, charge(Files.size(destination), recovered.blobs.size), durable = false))
                }
                throw failure
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    /** Reserve before receiving bytes; external I/O and slow senders never own the small-commit monitor. */
    fun putPacked(job: Long, blobs: List<ResultBlob>, input: InputStream) {
        require(job > 0); validateResultBlobs(blobs)
        var reserved = 0L
        val pending = synchronized(monitor) {
            check(!closed); check(writable()) { "result journal storage unavailable" }
            // As with putAll, a mixed retry need only retain the bytes not already held by this journal.
            // Existing references must be durable before discarding their incoming copies. A concurrent drain
            // can then reclaim them only after their external publication barrier has succeeded.
            if (blobs.any { index[job to it.sha]?.durable == false }) {
                syncDirectory(root); batches.values.forEach { it.durable = true }
            }
            val remaining = blobs.filter { (job to it.sha) !in index }
            if (remaining.isNotEmpty()) {
                reserved = reservation(remaining.sumOf { it.bytes }, remaining.size)
                check(reserved <= maxBytes - used) { "result journal full; pending data retained" }
                used += reserved; receiving++
            }
            remaining
        }
        if (pending.isEmpty()) {
            for (blob in blobs) copyBlob(blob, input, java.io.OutputStream.nullOutputStream())
            require(input.read() == -1) { "trailing result data" }; return
        }
        var temporary: Path? = null
        var accounted = false
        try {
            temporary = Files.createTempFile(root, ".pending-", ".tmp")
            val manifest = Manifest(job, pending)
            val retained = pending.mapTo(hashSetOf()) { it.sha }
            ZipOutputStream(java.io.BufferedOutputStream(Files.newOutputStream(temporary), 256 * 1024)).use { zip ->
                zip.setLevel(0)
                zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
                for (blob in blobs) {
                    if (blob.sha in retained) {
                        zip.putNextEntry(ZipEntry(blob.sha).apply { time = 0 })
                        copyBlob(blob, input, zip); zip.closeEntry()
                    } else copyBlob(blob, input, java.io.OutputStream.nullOutputStream())
                }
                require(input.read() == -1) { "trailing result data" }
            }
            val actual = charge(Files.size(temporary), pending.size); check(actual <= reserved)
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            synchronized(monitor) {
                check(!closed)
                val destination = root.resolve("$job-${UUID.randomUUID()}.zip")
                Files.move(temporary, destination, ATOMIC_MOVE)
                // Account even a failed publication barrier so retry/restart cannot lose the candidate.
                val batch = Batch(destination, manifest, actual, durable = false)
                used -= reserved; add(batch); accounted = true
                syncDirectory(root); batches.values.forEach { it.durable = true }
            }
        } finally {
            // On cleanup failure keep the reservation charged until startup removes the unacknowledged temp.
            try {
                if (temporary == null || Files.deleteIfExists(temporary) || !Files.exists(temporary)) {
                    if (!accounted) synchronized(monitor) { used -= reserved }
                }
            } finally { synchronized(monitor) { receiving-- } }
        }
    }

    private fun copyBlob(blob: ResultBlob, input: InputStream, output: java.io.OutputStream) {
        val digest = MessageDigest.getInstance("SHA-256"); var remaining = blob.bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count > 0) { "truncated result blob" }
            output.write(buffer, 0, count); digest.update(buffer, 0, count); remaining -= count
        }
        require(digest.digest().hex() == blob.sha) { "artifact checksum mismatch" }
    }

    override fun open(job: Long, sha: String, verify: Boolean): InputStream {
        return withPendingBlob(job, sha) { path, blob -> ByteArrayInputStream(read(path, blob)) }
            ?: external.open(job, sha, verify)
    }
    /** The existing copy remains pinned until the verified read finishes, including across index redirection. */
    internal fun <T : Any> withPendingBlob(job: Long, sha: String, read: (Path, ResultBlob) -> T): T? {
        check(job, sha)
        val batch = synchronized(monitor) { index[job to sha]?.also { it.readers++ } }
            ?: return null
        try { return read(batch.path, batch.manifest.blobs.single { it.sha == sha }) }
        finally { synchronized(monitor) { batch.readers--; cleanup(batch) } }
    }
    override fun size(job: Long, sha: String): Long {
        check(job, sha)
        return synchronized(monitor) { index[job to sha]?.manifest?.blobs?.single { it.sha == sha }?.bytes }
            ?: external.size(job, sha)
    }
    override fun verify(job: Long, sha: String) {
        check(job, sha)
        if (synchronized(monitor) { index.containsKey(job to sha) }) open(job, sha).use { } else external.verify(job, sha)
    }
    override fun regional(job: Long, sha: String): Boolean {
        check(job, sha)
        return !synchronized(monitor) { index.containsKey(job to sha) } && external.regional(job, sha)
    }
    // Only large preparation archives opt in; the bounded result journal remains unchanged.
    override fun reuse(job: Long, sha: String): Long? { check(job, sha); return external.reuse(job, sha) }
    override fun remember(job: Long, sha: String) { check(job, sha); external.remember(job, sha) }
    override fun offset(job: Long, sha: String): Long {
        check(job, sha)
        return synchronized(monitor) { index[job to sha]?.manifest?.blobs?.single { it.sha == sha }?.bytes }
            ?: external.offset(job, sha)
    }
    override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
        check(job, sha)
        val pending = synchronized(monitor) { index.containsKey(job to sha) }
        // Preparation metadata uses the same durable bounded journal as small solve results.
        // Partial/chunked transfers retain the resumable external-store path.
        if (!pending && offset == 0L && bytes in 0..65_536 && external.offset(job, sha) == 0L) {
            val payload = input.readNBytes(bytes.toInt() + 1)
            require(payload.size.toLong() <= bytes) { "artifact exceeds declared size" }
            if (payload.size.toLong() == bytes) {
                putAll(job, mapOf(sha to payload)); return bytes
            }
            return external.put(job, sha, bytes, offset, ByteArrayInputStream(payload))
        }
        if (!pending) return external.put(job, sha, bytes, offset, input)
        require(offset in 0..bytes && size(job, sha) == bytes); verify(job, sha); return bytes
    }
    // Compatibility for callers requiring stable filesystem paths: stream consumers never wait for this drain.
    override fun file(job: Long, sha: String): Path {
        check(job, sha)
        while (true) {
            val batch = synchronized(monitor) {
                check(!closed) { "result journal closed" }
                index[job to sha]?.also {
                    if (!it.durable) { syncDirectory(root); it.durable = true }
                }
            } ?: return external.file(job, sha)
            if (!drain(batch)) Thread.sleep(10)
        }
    }
    override fun readFile(job: Long, sha: String): Path = file(job, sha)

    /** One bounded internal quantum, independent of external drain threads. No replacement borrows cap space. */
    internal fun compactOne(): Boolean {
        val selected = synchronized(monitor) {
            if (closed) return false
            cleanup(batches.values.filter { it.superseded && it.retryAt <= retryClock() }.toList())
            pendingCompactions.values.firstOrNull()?.let { (replacement, inputs) ->
                // Retry a lost publication acknowledgement without allocating another replacement.
                if (!replacement.flushed) { syncDirectory(root); batches.values.forEach { it.durable = true } }
                finishCompaction(replacement, inputs)
                cleanup(inputs)
                return true
            }
            selectCompaction()?.also { plan ->
                used += plan.reserved; receiving++
                plan.inputs.forEach(::reserve)
            } ?: return false
        }
        var temporary: Path? = null
        var accounted = false
        try {
            fun active() { check(!closed && !Thread.currentThread().isInterrupted) { "journal compaction interrupted" } }
            active()
            temporary = Files.createTempFile(root, ".pending-", ".tmp")
            val manifest = Manifest(selected.inputs.first().manifest.job, selected.sources.values.map { it.first })
            ZipOutputStream(java.io.BufferedOutputStream(Files.newOutputStream(temporary), 256 * 1024)).use { output ->
                output.setLevel(0)
                output.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                output.write(Json.encodeToString(manifest).toByteArray()); output.closeEntry()
                for (sources in selected.sources.values.groupBy { it.second.path }.values) {
                    active()
                    ZipFile(sources.first().second.path.toFile()).use { input ->
                        for ((blob, _) in sources) {
                            active()
                            val entry = requireNotNull(input.getEntry(blob.sha)); require(entry.size == blob.bytes)
                            output.putNextEntry(ZipEntry(blob.sha).apply { time = 0 })
                            input.getInputStream(entry).use { copyBlob(blob, it, output) }
                            output.closeEntry()
                        }
                    }
                }
            }
            val actual = charge(Files.size(temporary), manifest.blobs.size)
            check(actual <= selected.reserved)
            if (actual >= selected.inputs.sumOf { it.charge }) {
                // Older compressed packs need not benefit from today's stored ZIP framing. Leave them intact.
                synchronized(monitor) { selected.inputs.forEach { it.compactAfter = retryClock() + 60_000 } }
                return false
            }
            val oldest = selected.inputs.minOf { it.createdAt }
            Files.setLastModifiedTime(temporary, FileTime.fromMillis(oldest))
            forceCompactedFile(temporary)
            synchronized(monitor) {
                active()
                val destination = root.resolve("${manifest.job}-${UUID.randomUUID()}.zip")
                Files.move(temporary, destination, ATOMIC_MOVE)
                val replacement = Batch(destination, manifest, actual, durable = false, createdAt = oldest)
                used -= selected.reserved; add(replacement); accounted = true
                pendingCompactions[destination] = replacement to selected.inputs
                syncDirectory(root); batches.values.forEach { it.durable = true }
                finishCompaction(replacement, selected.inputs)
            }
            return true
        } catch (failure: Exception) {
            synchronized(monitor) { selected.inputs.forEach { it.compactAfter = retryClock() + 5000 } }
            throw failure
        } finally {
            try {
                if (temporary == null || Files.deleteIfExists(temporary) || !Files.exists(temporary)) {
                    if (!accounted) synchronized(monitor) { used -= selected.reserved }
                }
            } finally {
                synchronized(monitor) {
                    receiving--; selected.inputs.forEach { it.busy = false; it.readers-- }
                    cleanup(selected.inputs)
                }
            }
        }
    }

    private fun selectCompaction(): Compaction? {
        val candidates = batches.values.take(1024).filter { eligible(it) && it.compactAfter <= retryClock() }.sortedBy { it.charge }
        for (sameJob in candidates.groupBy { it.manifest.job }.values) {
            val group = mutableListOf<Batch>()
            val sources = linkedMapOf<String, Pair<ResultBlob, Batch>>()
            var chosen: Compaction? = null
            for (batch in sameJob) {
                val next = LinkedHashMap(sources)
                for (blob in batch.manifest.blobs) {
                    val prior = next.putIfAbsent(blob.sha, blob to batch)
                    require(prior == null || prior.first.bytes == blob.bytes) { "journal blob length changed" }
                }
                if (next.size > 128 || next.values.sumOf { it.first.bytes } > 4L * 1024 * 1024) continue
                val reserved = reservation(next.values.sumOf { it.first.bytes }, next.size)
                if (group.isNotEmpty() && reserved > maxBytes - used) continue
                group += batch; sources.clear(); sources.putAll(next)
                if (group.size >= 2 && reserved <= maxBytes - used &&
                    charge(next.values.sumOf { it.first.bytes }, next.size) < group.sumOf { it.charge }) {
                    chosen = Compaction(group.toList(), sources.toMap(), reserved)
                }
            }
            if (chosen != null) return chosen
        }
        return null
    }

    /** Local supersession proves another durable copy, never that external publication completed. */
    private fun finishCompaction(replacement: Batch, inputs: List<Batch>) {
        check(replacement.durable || replacement.flushed)
        val retained = inputs.filter { it.path in batches && !it.flushed }
        val retirement = Retirement(retained.mapTo(hashSetOf()) { it.path },
            (retained.sumOf { it.charge } - replacement.charge).coerceAtLeast(0))
        for (old in retained) {
            old.superseded = true; old.retirement = retirement
            for (blob in old.manifest.blobs) {
                val key = old.manifest.job to blob.sha
                if (index[key] === old) {
                    if (replacement.flushed) index.remove(key) else index[key] = replacement
                }
            }
        }
        pendingCompactions.remove(replacement.path)
        lastCompactionError = null
    }

    internal fun drainOne(): Boolean = drainSelected(grouped = false)
    internal fun drainGroup(): Boolean = drainSelected(grouped = true)
    private fun drainSelected(grouped: Boolean): Boolean {
        val selected = synchronized(monitor) {
            if (closed) return false
            cleanup(batches.values.filter { (it.flushed || it.superseded) && it.retryAt <= retryClock() }.toList())
            val first = batches.values.firstOrNull { eligible(it) } ?: return false
            val group = mutableListOf(first)
            var bytes = first.manifest.blobs.sumOf { it.bytes }
            // A large existing pack is published alone; ordinary groups share one waited volume barrier.
            if (grouped && !first.individualRetry && bytes <= 4L * 1024 * 1024) {
                for (batch in batches.values) {
                    if (group.size == 128) break
                    if (batch === first || batch.manifest.job != first.manifest.job || batch.individualRetry || !eligible(batch)) continue
                    val next = batch.manifest.blobs.sumOf { it.bytes }
                    if (next > 4L * 1024 * 1024 - bytes) continue
                    group.add(batch); bytes += next
                }
            }
            group.onEach(::reserve)
        }
        return drainReserved(selected)
    }
    private fun eligible(batch: Batch): Boolean = batch.durable && !batch.busy && !batch.flushed && !batch.superseded && batch.retryAt <= retryClock()
    // Selection and reservation share the monitor so both lanes can immediately claim distinct packs.
    private fun reserve(batch: Batch) { batch.busy = true; batch.readers++ }
    private fun drain(batch: Batch): Boolean {
        synchronized(monitor) {
            if (closed || !batch.durable || batch.busy || batch.flushed || batch.superseded || batch.path !in batches) return false
            reserve(batch)
        }
        return drainReserved(listOf(batch))
    }
    private fun drainReserved(group: List<Batch>): Boolean {
        val started = System.nanoTime()
        try {
            try {
                external.publishBatch(group.first().manifest.job) { group.forEach(::publish) }
            } catch (failure: Exception) {
                synchronized(monitor) {
                    for (batch in group) {
                        batch.retryAt = retryClock() + 5000; batch.error = failure.javaClass.simpleName
                        // A bad pack cannot repeatedly trap healthy co-members in the same failing group.
                        batch.individualRetry = true
                    }
                    lastError = failure.javaClass.simpleName; drainErrors++
                }
                throw failure
            }
            synchronized(monitor) {
                // No pack may lose its journal copy/index until the entire shared barrier succeeded.
                for (batch in group) {
                    batch.flushed = true; batch.error = null
                    for (blob in batch.manifest.blobs) index.remove(batch.manifest.job to blob.sha, batch)
                }
                lastDrainAt = now()
                lastDrainMs = (System.nanoTime() - started) / 1_000_000
                lastDrainBlobs = group.sumOf { it.manifest.blobs.size }
                if (batches.values.none { it.error != null }) { lastError = null; loggedError = null }
            }
            return true
        } finally {
            synchronized(monitor) {
                // Release every reservation even if one reclamation fails after successful publication.
                group.forEach { it.busy = false; it.readers-- }
                cleanup(group)
            }
        }
    }
    private fun publish(batch: Batch) {
        val offsets = batch.manifest.blobs.associate { it.sha to external.offset(batch.manifest.job, it.sha) }
        if (batch.manifest.blobs.sumOf { it.bytes } <= INLINE_BYTES && offsets.values.all { it == 0L }) {
            external.putAll(batch.manifest.job, batch.manifest.blobs.associate { it.sha to read(batch, it.sha) })
        } else ZipFile(batch.path.toFile()).use { zip ->
            for (blob in batch.manifest.blobs) {
                val offset = offsets.getValue(blob.sha)
                require(offset in 0..blob.bytes) { "external artifact size changed" }
                if (offset == blob.bytes) {
                    // No suffix remains. The destination must still verify/finalize its copy and share the barrier.
                    external.put(batch.manifest.job, blob.sha, blob.bytes, offset, InputStream.nullInputStream())
                } else zip.getInputStream(requireNotNull(zip.getEntry(blob.sha))).use { input ->
                    input.skipNBytes(offset)
                    external.put(batch.manifest.job, blob.sha, blob.bytes, offset, input)
                }
            }
        }
    }
    private fun cleanup(batch: Batch) = cleanup(listOf(batch))
    private fun cleanup(candidates: List<Batch>) {
        val removable = candidates.filter { (it.flushed || it.superseded) && it.readers == 0 && it.path in batches }
        if (removable.isEmpty()) return
        val deleted = mutableListOf<Batch>()
        val failures = mutableListOf<Exception>()
        fun failed(batch: Batch, failure: Exception) {
            batch.retryAt = retryClock() + 5000; batch.error = failure.javaClass.simpleName
            if (batch.superseded) { lastCompactionError = batch.error; compactionErrors++ }
            else { lastError = batch.error; drainErrors++ }
        }
        for (batch in removable) try {
            deletePack(batch.path)
            deleted.add(batch)
        } catch (failure: Exception) {
            failed(batch, failure); failures.add(failure)
        }
        if (deleted.isNotEmpty()) {
            try {
                // One barrier makes every successful deletion durable before any capacity is reclaimed.
                syncDirectory(root)
                for (batch in deleted) {
                    batches.remove(batch.path); used -= batch.charge
                    batch.retirement?.let { retirement ->
                        if (retirement.remaining.remove(batch.path) && retirement.remaining.isEmpty()) {
                            compactions++; compactionReclaimedBytes += retirement.saved
                        }
                    }
                }
            } catch (failure: Exception) {
                deleted.forEach { failed(it, failure) }; failures.add(failure)
            }
        }
        if (batches.values.none { it.error != null }) { lastError = null; loggedError = null }
        if (batches.values.none { it.superseded && it.error != null }) lastCompactionError = null
        failures.firstOrNull()?.let { first -> failures.drop(1).filter { it !== first }.forEach(first::addSuppressed); throw first }
    }
    private fun read(batch: Batch, sha: String): ByteArray = read(batch.path, batch.manifest.blobs.single { it.sha == sha })
    private fun read(path: Path, blob: ResultBlob): ByteArray = ZipFile(path.toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry(blob.sha)); require(entry.size == blob.bytes)
        val bytes = zip.getInputStream(entry).use { it.readNBytes(PACKED_BYTES + 1) }
        check(bytes.size.toLong() == blob.bytes && MessageDigest.getInstance("SHA-256").digest(bytes).hex() == blob.sha) { "result journal checksum mismatch" }
        bytes
    }
    private fun manifest(path: Path): Manifest = ZipFile(path.toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry("manifest.json")); require(entry.size in 1..65536)
        val body = zip.getInputStream(entry).use { it.readNBytes(65537) }; require(body.size <= 65536)
        Json.decodeFromString<Manifest>(String(body)).also { manifest ->
            require(manifest.job > 0 && manifest.blobs.isNotEmpty() && manifest.blobs.size <= INLINE_FILES)
            require(manifest.blobs.map { it.sha }.distinct().size == manifest.blobs.size)
            validateResultBlobs(manifest.blobs)
            require(zip.entries().toList().map { it.name }.sorted() == (manifest.blobs.map { it.sha } + "manifest.json").sorted())
        }
    }
    override fun close() {
        closed = true; workers.shutdownNow(); maintenance.shutdownNow()
        // Retain ownership if an uninterruptible external write is still active; pending data stays on disk.
        if (workers.awaitTermination(5, TimeUnit.SECONDS) && maintenance.awaitTermination(5, TimeUnit.SECONDS) &&
            synchronized(monitor) { receiving == 0 && batches.values.none { it.busy || it.readers != 0 } }) {
            ownership.release(); channel.close()
        }
    }
}
