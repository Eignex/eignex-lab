package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*
import kotlinx.html.*

/** Node inventory and temporary capacity controls for configured, provider-neutral pools. */
internal fun nodesPage(
    local: LocalNode,
    host: HostReport,
    snapshot: CapacitySnapshot?,
    leases: List<ManualCapacityLease>,
    settings: CapacitySettings?,
    journal: ResultJournalReport? = null,
): Html =
    page("Nodes · eignex lab", live = true, tab = Tab.NODES, script = PageScripts.nodes) {
        header {
            h1 { text("Nodes") }
            p(classes = "muted") {
                text("Native execution and cloud workers")
            }
        }
        div {
            attributes["id"] = "node-overview"
            attributes["data-live"] = ""
            emit(nodeOverview(snapshot, leases))
        }
        nav(classes = "queue-sections") {
            attributes["aria-label"] = "Node sections"
            a(href = "#start-nodes") {
                text("Start nodes")
            }
            a(href = "#cloud-pools") {
                text("Cloud pools")
            }
            a(href = "#cloud-nodes") {
                text("Nodes & allocations")
            }
            a(href = "#capacity-requests") {
                text("Manual requests")
            }
        }
        div(classes = "node-panels") {
            section(classes = "node-panel") {
                attributes["id"] = "local-node"

                h2 { text("Local node") }
                p {
                    b { emit(host.host) }
                    text(" · ")
                    emit(host.cores)
                    text(" logical CPUs · ")
                    emit(local.cores)
                    text(" cores available to the lab")
                }
                val tags = local.tags.sorted()
                p { emit(NodeTags.hardwareProfile(local.tags)) }
                details {
                    summary {
                        text("All ")
                        emit(tags.size)
                        text(" capability labels")
                    }
                    p(classes = "muted small") {
                        emit(tags.joinToString(" · "))
                    }
                }
            }
            section(classes = "node-panel") {
                attributes["id"] = "result-journal"

                attributes["data-live"] = ""
                h2 { text("Result journal") }
                if (journal != null) {
                    val s = journal.status
                    p {
                        emit(s.batches)
                        text(" pending batches · ")
                        emit(s.bytes / 1024)
                        text(" KiB reserved of ")
                        emit(s.limit / 1024 / 1024)
                        text(" MiB · oldest ")
                        emit(s.oldestAgeMs / 1000)
                        text("s · ")
                        emit(s.drainErrors)
                        text(" drain errors")
                    }
                    s.solveAdmission
                        ?.takeIf { it.held }
                        ?.let {
                            p(classes = "warn") {
                                text(
                                    "New solve claims are waiting for the journal to drain to ",
                                )
                                emit(it.resumeAtBytes / 1024 / 1024)
                                text(" MiB. Owned rows continue; uploads may wait for space.")
                            }
                        }
                    if (journal.stale)
                        p(classes = "warn") {
                            text("Journal report is stale (")
                            emit(journal.ageMs / 1000)
                            text("s old).")
                        }
                    if (s.lastError != null)
                        p(classes = "warn") {
                            text("Drain error: ")
                            emit(s.lastError)
                            text(". Pending results are retained.")
                        }
                    if (s.bytes >= s.limit * 9 / 10)
                        p(classes = "warn") {
                            text(
                                "Journal is near capacity; uploads may wait for the external drain.",
                            )
                        }
                } else
                    p(classes = "muted") {
                        text("Journal status is unavailable.")
                    }
            }
        }
        val pools = settings?.pools ?: snapshot?.pools.orEmpty()
        val configured = settings != null
        val observed = snapshot?.nodes?.groupingBy { it.pool }?.eachCount()
        section(classes = "node-panel request-panel") {
            attributes["id"] = "start-nodes"

            h2 { text("Start or keep cloud nodes") }
            if (configured) {
                val first = pools.firstOrNull {
                    minOf(it.maxNodes, settings.maxVcpus / it.vcpus) > 0
                }
                p(classes = "muted small") {
                    text(
                        "Set a temporary minimum for one pool. The total includes nodes already running.",
                    )
                }
                form {
                    attributes["id"] = "node-request"
                    fieldSet {
                        legend {
                            attributes["class"] = "sr-only"
                            text("Request temporary cloud capacity")
                        }
                        div(classes = "node-fields") {
                            label {
                                attributes["for"] = "node-pool"
                                text("Node pool")
                                select {
                                    attributes["id"] = "node-pool"
                                    attributes["name"] = "pool"
                                    attributes["required"] = ""
                                    attributes["aria-describedby"] = "node-pool-info"
                                    for (pool in pools) {
                                        val limit =
                                            minOf(pool.maxNodes, settings.maxVcpus / pool.vcpus)
                                        option {
                                            value = pool.id
                                            attributes["data-limit"] = limit.toString()
                                            attributes["data-vcpus"] = pool.vcpus.toString()
                                            attributes["data-memory"] =
                                                (pool.memoryMiB / 1024.0).toString()
                                            disabled = limit == 0
                                            selected = pool.id == first?.id
                                            +"${pool.id} · ${NodeTags.hardwareProfile(pool.tags)} · ${pool.vcpus} vCPU / ${pool.memoryMiB / 1024.0} GiB · ${pool.market.tag}"
                                        }
                                    }
                                }
                            }
                            label {
                                htmlFor = "node-countMinimum nodes in pool"
                                input(type = InputType.number) {
                                    id = "node-count"
                                    name = "nodes"
                                    min = "1"
                                    value = "1"
                                    required = true
                                    attributes["aria-describedby"] = "node-pool-info"
                                }
                            }
                            label {
                                htmlFor = "node-durationKeep for"
                                select {
                                    id = "node-duration"
                                    name = "ttlSec"
                                    for ((seconds, label) in
                                        listOf(
                                            "900" to "15 minutes",
                                            "1800" to "30 minutes",
                                            "3600" to "1 hour",
                                        )) option {
                                        value = seconds
                                        +label
                                    }
                                }
                            }
                            button(type = ButtonType.submit) {
                                disabled = first == null
                                +"Start / keep nodes"
                            }
                        }
                    }
                }
                p(classes = "muted small") { id = "node-pool-info" }
                p {
                    id = "node-feedback"
                    attributes["role"] = "status"
                    attributes["aria-live"] = "polite"
                }

                p(classes = "muted small") {
                    text("Requests share the global ")
                    emit(settings.maxVcpus)
                    text(
                        " vCPU limit and expire automatically. Jobs can use these nodes; releasing a request lets automatic scaling retire idle capacity.",
                    )
                }
            } else
                p(classes = "muted") {
                    text("Nomad capacity settings are unavailable; manual starts are disabled.")
                }
        }
        div {
            attributes["id"] = "node-capacity"
            attributes["data-live"] = ""
            section {
                attributes["id"] = "cloud-pools"
                h2 { text("Cloud pools") }
                p(classes = "muted small") {
                    text(
                        "Observed nodes are reported by the provider. Desired is the scaling target. The pool limit is also subject to the global vCPU limit.",
                    )
                }
                for ((source, target) in snapshot?.spotFallbacks.orEmpty()) {
                    p(classes = "muted small") {
                        emit(source)
                        text(
                            ": Spot capacity unavailable (UnfulfillableCapacity); waiting work and node requests use ",
                        )
                        emit(target)
                        text(
                            " at on-demand pricing. Running workers finish on their current nodes.",
                        )
                    }
                }
                if (pools.isEmpty())
                    p(classes = "muted") {
                        text("No cloud pools configured.")
                    }
                else {
                    div(classes = "scroll") {
                        attributes["role"] = "region"
                        attributes["aria-label"] = "Cloud pools"
                        attributes["tabindex"] = "0"
                        table(classes = "pool-table") {
                            thead {
                                tr {
                                    th {
                                        attributes["scope"] = "col"
                                        text("Pool / class")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("Hardware per node")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("Architecture / SIMD")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("Market")
                                    }
                                    th(classes = "num") {
                                        attributes["scope"] = "col"

                                        text("Observed")
                                    }
                                    th(classes = "num") {
                                        attributes["scope"] = "col"

                                        text("Desired")
                                    }
                                    th(classes = "num") {
                                        attributes["scope"] = "col"

                                        text("Pool limit")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        span(classes = "sr-only") {
                                            text("Select pool")
                                        }
                                    }
                                }
                            }
                            tbody {
                                for (pool in pools) {
                                    tr {
                                        td {
                                            emit(pool.id)
                                            br {}
                                            small(classes = "muted") {
                                                emit(pool.nodeClass)
                                            }
                                        }
                                        td {
                                            emit(pool.vcpus)
                                            text(" vCPU / ")
                                            emit(pool.memoryMiB)
                                            text(" MiB")
                                        }
                                        td { emit(NodeTags.hardwareProfile(pool.tags)) }
                                        td {
                                            span(classes = "where") {
                                                emit(pool.market.tag)
                                            }
                                        }
                                        td(classes = "num") {
                                            emit(observed?.getOrDefault(pool.id, 0) ?: "?")
                                        }
                                        td(classes = "num") {
                                            emit(snapshot?.decision?.desired?.get(pool.id) ?: "?")
                                        }
                                        td(classes = "num") {
                                            emit(pool.maxNodes)
                                        }
                                        td {
                                            if (
                                                configured &&
                                                    minOf(
                                                        pool.maxNodes,
                                                        settings.maxVcpus / pool.vcpus,
                                                    ) > 0
                                            ) {
                                                button {
                                                    attributes["type"] = "button"
                                                    attributes["data-select-pool"] =
                                                        (pool.id)
                                                    attributes["aria-label"] = "Use pool ${pool.id}"
                                                    text("Use pool")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            section {
                attributes["id"] = "cloud-nodes"
                h2 { text("Cloud nodes and allocations") }
                if (snapshot?.nodes.isNullOrEmpty())
                    p(classes = "empty-state") {
                        emit(
                            if (snapshot == null) "Node inventory is unavailable."
                            else
                                "No cloud nodes running. Jobs can request capacity automatically, or you can start nodes above.",
                        )
                    }
                else {
                    div(classes = "scroll") {
                        attributes["role"] = "region"
                        attributes["aria-label"] = "Cloud nodes and allocations"
                        attributes["tabindex"] = "0"
                        table(classes = "allocation-table") {
                            thead {
                                tr {
                                    th {
                                        attributes["scope"] = "col"
                                        text("Node / pool")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("State")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("Sessions / experiments")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("Unreserved CPU / memory")
                                    }
                                }
                            }
                            tbody {
                                for (node in snapshot.nodes) {
                                    val pool = pools.singleOrNull { it.id == node.pool }
                                    val cpu =
                                        node.reservations.sumOf { it.budget.cpuMillis.toLong() }
                                    val memory =
                                        node.reservations.sumOf { it.budget.memoryMiB.toLong() }
                                    tr {
                                        attributes["id"] = "node-${node.id}"
                                        td {
                                            emit(node.id)
                                            br {}
                                            small(classes = "muted") {
                                                emit(node.pool)
                                            }
                                        }
                                        td {
                                            span(
                                                classes =
                                                    "node-state state-${node.state.name.lowercase()}",
                                            ) {
                                                emit(
                                                    node.state.name.lowercase().replaceFirstChar {
                                                        it.uppercase()
                                                    },
                                                )
                                            }
                                        }
                                        td {
                                            emit(
                                                node.reservations
                                                    .joinHtml(buildHtml { br {} }) { allocation ->
                                                        (if (allocation.job > 0)
                                                            buildHtml {
                                                                a(
                                                                    href =
                                                                        "/jobs/${(allocation.job)
                                                                                    }",
                                                                ) {
                                                                    text("job ")
                                                                    emit(allocation.job)
                                                                }
                                                            }
                                                        else
                                                            buildHtml {
                                                                text("external work")
                                                            }) +
                                                            buildHtml { text(" · ") } +
                                                            htmlText(allocation.id) +
                                                            buildHtml { text(" · ") } +
                                                            (allocation.budget.cpuMillis / 1000.0) +
                                                            buildHtml { text(" vCPU / ") } +
                                                            allocation.budget.memoryMiB +
                                                            buildHtml { text(" MiB") }
                                                    }
                                                    .let {
                                                        if (it.isEmpty())
                                                            htmlText(
                                                                if (
                                                                    node.state ==
                                                                        CapacityNodeState.READY
                                                                )
                                                                    "idle"
                                                                else "no active allocations",
                                                            )
                                                        else it
                                                    },
                                            )
                                        }
                                        td {
                                            if (node.state == CapacityNodeState.READY) {
                                                emit(
                                                    pool?.let {
                                                        (it.cpuMillis - cpu).coerceAtLeast(0) /
                                                            1000.0
                                                    } ?: "?",
                                                )
                                                text(" vCPU / ")
                                                emit(
                                                    pool?.let {
                                                        (it.availableMemoryMiB - memory)
                                                            .coerceAtLeast(0)
                                                    } ?: "?",
                                                )
                                                text(" MiB")
                                            } else text("not schedulable")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!snapshot?.decision?.waiting.isNullOrEmpty())
                    h3 { text("Waiting for capacity") }
                for (wait in snapshot?.decision?.waiting.orEmpty()) p(
                    classes = "capacity-message",
                ) {
                    code { emit(wait.request) }
                    br {}
                    emit(wait.reason)
                }
                for ((pool, reason) in snapshot?.failures.orEmpty()) p(classes = "warn") {
                    emit(pool)
                    text(": ")
                    emit(reason)
                }
            }
            section {
                attributes["id"] = "capacity-requests"
                h2 { text("Manual capacity requests") }
                p(classes = "muted small") {
                    text(
                        "Releasing a request removes its minimum. Jobs continue to run; idle nodes may retire automatically.",
                    )
                }
                if (leases.isEmpty())
                    p(classes = "empty-state") {
                        text("No active requests.")
                    }
                else {
                    div(classes = "scroll") {
                        attributes["role"] = "region"
                        attributes["aria-label"] = "Manual capacity requests"
                        attributes["tabindex"] = "0"
                        table(classes = "lease-table") {
                            thead {
                                tr {
                                    th {
                                        attributes["scope"] = "col"
                                        text("Pool")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("Minimum nodes")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        text("Expires")
                                    }
                                    th {
                                        attributes["scope"] = "col"
                                        span(classes = "sr-only") {
                                            text("Actions")
                                        }
                                    }
                                }
                            }
                            tbody {
                                for (lease in leases) tr {
                                    td { emit(lease.pool) }
                                    td { emit(lease.nodes) }
                                    td { emit(clockTime(lease.expiresAt)) }
                                    td {
                                        button {
                                            attributes["type"] = "button"
                                            attributes["data-release-capacity"] =
                                                (lease.id)
                                            attributes["aria-label"] =
                                                "Release request for ${lease.pool}"
                                            text("Release")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/** Live counts use the same inventory as the tables; missing inventory stays explicitly unknown. */
private fun nodeOverview(snapshot: CapacitySnapshot?, leases: List<ManualCapacityLease>): Html =
    buildHtml {
        div(classes = "queue-overview") {
            attributes["role"] = "group"
            attributes["aria-label"] = "Cloud capacity overview"
            val states = snapshot?.nodes?.groupingBy { it.state }?.eachCount()
            fun count(vararg kinds: CapacityNodeState): Int? = states?.let { values ->
                kinds.sumOf { values[it] ?: 0 }
            }
            val counts =
                listOf(
                    Triple(count(CapacityNodeState.READY), "Ready nodes", "DONE"),
                    Triple(
                        count(CapacityNodeState.BOOTING, CapacityNodeState.REPLACING),
                        "Starting / replacing",
                        "RUNNING",
                    ),
                    Triple(
                        count(CapacityNodeState.DRAINING, CapacityNodeState.UNAVAILABLE),
                        "Draining / unavailable",
                        "capacity-wait",
                    ),
                    Triple(leases.size, "Manual requests", "QUEUED"),
                )
            for ((count, label, cls) in counts) div(classes = "queue-stat ${cls}") {
                strong { emit(count ?: "?") }
                span { emit(label) }
            }
        }
        if (snapshot == null)
            p(classes = "capacity-message warn") {
                text(
                    "Capacity has not reported. Requests take effect when fresh reports resume.",
                )
            }
        else {
            val held = snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy
            p(
                classes =
                    "capacity-message ${if (held || snapshot.failures.isNotEmpty()) "warn" else "muted"}",
            ) {
                text("Report age: ")
                emit(snapshot.ageMs / 1000)
                text("s")
                if (held) text(" · growth is held")
                else if (snapshot.failures.isNotEmpty())
                    text(" · provider launch failures; capacity requests will retry")
                else text(" · capacity report is up to date")
            }
        }
    }
