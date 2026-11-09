package com.eignex.lab.capacity

import com.eignex.lab.cloud.CapacityRejected
import com.eignex.lab.cloud.ProviderError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * `capacity-adapter aws|gcp CONFIG [--once]`: the Mac's provider inventory and renewal adapter, a supervised service
 * of its own. Every ten seconds it publishes the provider's view of the capacity pools as the inventory the coordinator
 * reads ([CapacityInventory]), and, independently, it asks the coordinator for each pool's signed grant and hands it to
 * the pool's cloud renewal function. It can neither open a pool nor write a lease itself. `--once` publishes and renews
 * once and exits 1 if either failed.
 */
object CapacityAdapter {
    /** How old a published inventory may be when it is written; the coordinator accepts it for 30 seconds. */
    const val FRESHNESS_MS = 20_000L
    const val PERIOD_MS = 10_000L

    fun main(args: List<String>): Int {
        val once = "--once" in args
        val rest = args.filter { it != "--once" }
        if (rest.size != 2 || rest[0] !in setOf("gcp")) {
            System.err.println("usage: eignex-lab capacity-adapter gcp CONFIG [--once]")
            return 2
        }
        val config = Json.parseToJsonElement(Files.readString(Path.of(rest[1]))) as JsonObject
        return GcpCapacityAdapter.run(config, once)
    }

    /**
     * Run [inventory] and [renewal] each every ten seconds, each in its own thread, so publishing never waits for the
     * coordinator or a renewal function; or, [once], each once in turn, failing if either did. A failure is logged as
     * `[provider] capacity adapter held: ` and [describe]'s safe account of it.
     */
    fun loop(provider: String, once: Boolean, inventory: () -> Unit, renewal: () -> Unit, describe: (Throwable) -> String): Int {
        fun attempt(operation: () -> Unit) = try {
            operation(); true
        } catch (error: Exception) {
            println("$provider capacity adapter held: ${describe(error)}")
            System.out.flush()
            false
        }
        if (once) return if (attempt { inventory(); renewal() }) 0 else 1
        fun forever(operation: () -> Unit): Nothing {
            while (true) {
                attempt(operation)
                Thread.sleep(PERIOD_MS)
            }
        }
        Thread({ forever(inventory) }, "${provider.lowercase()}-inventory").apply { isDaemon = true }.start()
        forever(renewal)
    }

    /**
     * One pool as a provider sees it: its [instances] as (id, draining) pairs, a booting placeholder for each node the
     * provider is still bringing up to [targetSize], and why it could not, when it says, observed at [at] milliseconds.
     */
    fun observation(pool: String, instances: List<Pair<String, Boolean>>, targetSize: Int, failures: String?, at: Long): JsonObject {
        val listed = instances.map { (id, draining) ->
            buildJsonObject { put("id", id); put("pool", pool); put("draining", draining); put("countedInTarget", !draining) }
        }
        val counted = instances.count { !it.second }
        val booting = (0 until maxOf(0, targetSize - counted)).map { buildJsonObject { put("id", "booting-$it"); put("pool", pool) } }
        val reason = (failures ?: "").let { if (it.codePointCount(0, it.length) > 500) it.substring(0, it.offsetByCodePoints(0, 500)) else it }
        return validate(buildJsonObject {
            put("observedAt", at)
            put("pools", JsonArray(listOf(JsonPrimitive(pool))))
            put("instances", JsonArray(listed + booting))
            put("failures", if (reason.isEmpty()) JsonObject(emptyMap()) else buildJsonObject { put(pool, reason) })
        })
    }

    /** Several pools' observations as one inventory, as old as the oldest of them. */
    fun merge(observations: List<JsonObject>): JsonObject = validate(buildJsonObject {
        put("observedAt", observations.minOf { it.getValue("observedAt").jsonPrimitive.content.toLong() })
        put("pools", JsonArray(observations.flatMap { it.getValue("pools") as JsonArray }))
        put("instances", JsonArray(observations.flatMap { it.getValue("instances") as JsonArray }))
        put("failures", JsonObject(observations.flatMap { (it.getValue("failures") as JsonObject).entries }.associate { it.key to it.value }))
    })

    /**
     * [value] when it is an inventory the coordinator accepts: its pools distinct, each instance of one of them and
     * listed once; else [CapacityRejected].
     */
    fun validate(value: JsonObject): JsonObject {
        val pools = (value["pools"] as? JsonArray)?.map { (it as JsonPrimitive).content }
        if ((value["observedAt"] as? JsonPrimitive)?.content?.toLongOrNull() == null || pools.isNullOrEmpty()) {
            throw CapacityRejected("inventory needs a time and pools")
        }
        if (pools.toSet().size != pools.size) throw CapacityRejected("inventory lists a pool twice")
        val seen = mutableSetOf<Pair<String, String>>()
        for (instance in value["instances"] as JsonArray) {
            val fields = instance as JsonObject
            val pool = (fields["pool"] as? JsonPrimitive)?.content
            val id = (fields["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (pool !in pools || id == null) throw CapacityRejected("inventory instance outside its pools")
            if (!seen.add(pool!! to id)) throw CapacityRejected("inventory lists an instance twice")
        }
        if (!pools.containsAll((value["failures"] as? JsonObject)?.keys.orEmpty())) throw CapacityRejected("inventory failure outside its pools")
        return value
    }

    /** Write [value] to [path] atomically and durably, the directory private when this creates it. */
    fun writeAtomic(path: Path, value: JsonElement) {
        val directory = path.toAbsolutePath().parent
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        val temporary = Files.createTempFile(directory, ".inventory-", "")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(value.toString().toByteArray()))
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * The pool [pool] of the lab's capacity contract at [contract] (`eignex-lab capacity-export`), which must exist and
     * agree with every [expected] field, such as `maxNodes` 4; else [CapacityRejected] naming what differs. Without a
     * contract, nothing is checked.
     */
    fun checkContract(contract: String?, pool: String, expected: Map<String, Int> = emptyMap()): JsonObject? {
        if (contract == null) return null
        val value = Json.parseToJsonElement(Files.readString(Path.of(contract))) as JsonObject
        if ((value["schemaVersion"] as? JsonPrimitive)?.intOrNull != com.eignex.lab.domain.CapacityContract.SCHEMA_VERSION) {
            throw CapacityRejected("unsupported capacity contract")
        }
        val pools = (value.getValue("pools") as JsonArray).map { it as JsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        val found = pools[pool] ?: throw CapacityRejected("capacity pool $pool is not in the contract")
        val different = expected.filter { (key, mine) -> (found[key] as? JsonPrimitive)?.intOrNull != mine }
        if (different.isNotEmpty()) {
            throw CapacityRejected("capacity pool $pool differs from the contract: " +
                different.entries.joinToString(", ") { (key, mine) -> "$key $mine here, ${found[key]?.let { (it as? JsonPrimitive)?.content ?: it } ?: "None"} there" })
        }
        return found
    }

    /** The coordinator answering a grant request with a non-2xx [status]. */
    class CoordinatorStatus(val status: Int) : RuntimeException("HTTP $status")

    private val coordinatorClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Pool [pool]'s fresh signed grant from [coordinator], authorized by the private capacity [key]. */
    fun grant(coordinator: String, pool: String, key: String): JsonElement {
        val request = HttpRequest.newBuilder(URI(coordinator.trimEnd('/') + "/capacity/grant/" + pool)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $key").GET().build()
        val response = coordinatorClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw CoordinatorStatus(response.statusCode())
        return Json.parseToJsonElement(response.body())
    }

    /** A bounded name for [error]: a provider's error code, the coordinator's status, else the exception's class. */
    fun code(error: Throwable): String = when (error) {
        is ProviderError -> error.code
        is CoordinatorStatus -> "HTTP_${error.status}"
        else -> error.javaClass.simpleName
    }

    /** [task] for each of [items], at most four at a time; the first failure fails the whole. */
    fun <T, R> parallel(items: List<T>, task: (T) -> R): List<R> {
        val executor = Executors.newFixedThreadPool(minOf(4, items.size).coerceAtLeast(1)) { Thread(it).apply { isDaemon = true } }
        try {
            return executor.invokeAll(items.map { item -> Callable { task(item) } }).map { future ->
                try { future.get() } catch (error: ExecutionException) { throw error.cause ?: error }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    internal fun JsonObject.text(name: String): String = requireNotNull((this[name] as? JsonPrimitive)?.content) { "configuration lacks $name" }
    internal fun JsonObject.optional(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
