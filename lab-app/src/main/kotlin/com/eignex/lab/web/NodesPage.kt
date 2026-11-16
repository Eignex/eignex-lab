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

/** Node inventory and temporary capacity controls for configured, provider-neutral pools. */
internal fun nodesPage(local: LocalNode, host: HostReport, snapshot: CapacitySnapshot?, leases: List<ManualCapacityLease>, settings: CapacitySettings?, journal: ResultJournalReport? = null): Html = page("Nodes · eignex lab", live = true, tab = Tab.NODES, script = PageScripts.nodes) {
    append(Html("<header><h1>Nodes</h1><p class=\"muted\">Native execution and cloud workers</p></header>"))
    append(Html("<div id=\"node-overview\" data-live>") + nodeOverview(snapshot, leases) + Html("</div>"))
    append(Html("<nav class=\"queue-sections\" aria-label=\"Node sections\"><a href=\"#start-nodes\">Start nodes</a><a href=\"#cloud-pools\">Cloud pools</a><a href=\"#cloud-nodes\">Nodes &amp; allocations</a><a href=\"#capacity-requests\">Manual requests</a></nav><div class=\"node-panels\">"))
    append(Html("<section id=\"local-node\" class=\"node-panel\"><h2>Local node</h2><p><b>") + esc(host.host) + Html("</b> · ") + host.cores + Html(" logical CPUs · ") + local.cores + Html(" cores available to the lab</p>"))
    val tags = local.tags.sorted()
    append(Html("<p>") + (esc(NodeTags.hardwareProfile(local.tags))) + Html("</p>"))
    append(Html("<details><summary>All ") + tags.size + Html(" capability labels</summary><p class=\"muted small\">") + (esc(tags.joinToString(" · "))) + Html("</p></details>"))
    append(Html("</section>"))
    append(Html("<section id=\"result-journal\" class=\"node-panel\" data-live><h2>Result journal</h2>"))
    if (journal != null) {
        val s = journal.status
        append(Html("<p>") + s.batches + Html(" pending batches · ") + (s.bytes / 1024) + Html(" KiB reserved of ") + (s.limit / 1024 / 1024) + Html(" MiB · oldest ") + (s.oldestAgeMs / 1000) + Html("s · ") + s.drainErrors + Html(" drain errors</p>"))
        s.solveAdmission?.takeIf { it.held }?.let {
            append(Html("<p class=\"warn\">New solve claims are waiting for the journal to drain to ") + (it.resumeAtBytes / 1024 / 1024) + Html(" MiB. Owned rows continue; uploads may wait for space.</p>"))
        }
        if (journal.stale) append(Html("<p class=\"warn\">Journal report is stale (") + (journal.ageMs / 1000) + Html("s old).</p>"))
        if (s.lastError != null) append(Html("<p class=\"warn\">Drain error: ") + esc(s.lastError) + Html(". Pending results are retained.</p>"))
        if (s.bytes >= s.limit * 9 / 10) append(Html("<p class=\"warn\">Journal is near capacity; uploads may wait for the external drain.</p>"))
    } else append(Html("<p class=\"muted\">Journal status is unavailable.</p>"))
    append(Html("</section></div>"))
    val pools = settings?.pools ?: snapshot?.pools.orEmpty()
    val configured = settings != null
    val observed = snapshot?.nodes?.groupingBy { it.pool }?.eachCount()
    append(Html("<section id=\"start-nodes\" class=\"node-panel request-panel\"><h2>Start or keep cloud nodes</h2>"))
    if (configured) {
        val first = pools.firstOrNull { minOf(it.maxNodes, settings.maxVcpus / it.vcpus) > 0 }
        append(Html("<p class=\"muted small\">Set a temporary minimum for one pool. The total includes nodes already running.</p><form id=\"node-request\"><fieldset><legend class=\"sr-only\">Request temporary cloud capacity</legend><div class=\"node-fields\"><label for=\"node-pool\">Node pool<select id=\"node-pool\" name=\"pool\" required aria-describedby=\"node-pool-info\">"))
        for (pool in pools) {
            val limit = minOf(pool.maxNodes, settings.maxVcpus / pool.vcpus)
            append(Html("<option value=\"") + esc(pool.id) + Html("\" data-limit=\"") + limit + Html("\" data-vcpus=\"") + pool.vcpus + Html("\" data-memory=\"") + (pool.memoryMiB / 1024.0) + Html("\"") +
                (if (limit == 0) " disabled" else "") + (if (pool.id == first?.id) " selected" else "") + Html(">") + esc(pool.id) + Html(" · ") + esc(NodeTags.hardwareProfile(pool.tags)) + Html(" · ") + pool.vcpus + Html(" vCPU / ") + (pool.memoryMiB / 1024.0) + Html(" GiB · ") + esc(pool.market.tag) + Html("</option>"))
        }
        append(Html("</select></label><label for=\"node-count\">Minimum nodes in pool<input id=\"node-count\" name=\"nodes\" type=\"number\" min=\"1\" value=\"1\" required aria-describedby=\"node-pool-info\"></label>"))
        append(Html("<label for=\"node-duration\">Keep for<select id=\"node-duration\" name=\"ttlSec\"><option value=\"900\">15 minutes</option><option value=\"1800\">30 minutes</option><option value=\"3600\">1 hour</option></select></label><button type=\"submit\"") + (if (first == null) " disabled" else "") + Html(">Start / keep nodes</button></div></fieldset></form><p id=\"node-pool-info\" class=\"muted small\"></p><p id=\"node-feedback\" role=\"status\" aria-live=\"polite\"></p>"))
        append(Html("<p class=\"muted small\">Requests share the global ") + settings.maxVcpus + Html(" vCPU limit and expire automatically. Jobs can use these nodes; releasing a request lets automatic scaling retire idle capacity.</p>"))
    } else append(Html("<p class=\"muted\">Nomad capacity settings are unavailable; manual starts are disabled.</p>"))
    append(Html("</section><div id=\"node-capacity\" data-live><section id=\"cloud-pools\"><h2>Cloud pools</h2><p class=\"muted small\">Observed nodes are reported by the provider. Desired is the scaling target. The pool limit is also subject to the global vCPU limit.</p>"))
    for ((source, target) in snapshot?.spotFallbacks.orEmpty()) {
        append(Html("<p class=\"muted small\">") + esc(source) + Html(": Spot capacity unavailable (UnfulfillableCapacity); waiting work and node requests use ") +
            esc(target) + Html(" at on-demand pricing. Running workers finish on their current nodes.</p>"))
    }
    if (pools.isEmpty()) append(Html("<p class=\"muted\">No cloud pools configured.</p>"))
    else {
        append(Html("<div class=\"scroll\" role=\"region\" aria-label=\"Cloud pools\" tabindex=\"0\"><table class=\"pool-table\"><thead><tr><th scope=\"col\">Pool / class</th><th scope=\"col\">Hardware per node</th><th scope=\"col\">Architecture / SIMD</th><th scope=\"col\">Market</th><th scope=\"col\" class=\"num\">Observed</th><th scope=\"col\" class=\"num\">Desired</th><th scope=\"col\" class=\"num\">Pool limit</th><th scope=\"col\"><span class=\"sr-only\">Select pool</span></th></tr></thead><tbody>"))
        for (pool in pools) {
            append(Html("<tr><td>") + esc(pool.id) + Html("<br><small class=\"muted\">") + esc(pool.nodeClass) + Html("</small></td><td>") + pool.vcpus + Html(" vCPU / ") + pool.memoryMiB + Html(" MiB</td>"))
            append(Html("<td>") + esc(NodeTags.hardwareProfile(pool.tags)) + Html("</td><td><span class=\"where\">") + esc(pool.market.tag) + Html("</span></td><td class=\"num\">") + (observed?.getOrDefault(pool.id, 0) ?: "?") + Html("</td><td class=\"num\">") + (snapshot?.decision?.desired?.get(pool.id) ?: "?") + Html("</td><td class=\"num\">") + pool.maxNodes + Html("</td><td>"))
            if (configured && minOf(pool.maxNodes, settings.maxVcpus / pool.vcpus) > 0) {
                append(Html("<button type=\"button\" data-select-pool=\"") + esc(pool.id) + Html("\" aria-label=\"Use pool ") + esc(pool.id) + Html("\">Use pool</button>"))
            }
            append(Html("</td></tr>"))
        }
        append(Html("</tbody></table></div>"))
    }
    append(Html("</section><section id=\"cloud-nodes\"><h2>Cloud nodes and allocations</h2>"))
    if (snapshot?.nodes.isNullOrEmpty()) append(Html("<p class=\"empty-state\">") + (if (snapshot == null) "Node inventory is unavailable." else "No cloud nodes running. Jobs can request capacity automatically, or you can start nodes above.") + Html("</p>"))
    else {
        append(Html("<div class=\"scroll\" role=\"region\" aria-label=\"Cloud nodes and allocations\" tabindex=\"0\"><table class=\"allocation-table\"><thead><tr><th scope=\"col\">Node / pool</th><th scope=\"col\">State</th><th scope=\"col\">Sessions / experiments</th><th scope=\"col\">Unreserved CPU / memory</th></tr></thead><tbody>"))
        for (node in snapshot.nodes) {
            val pool = pools.singleOrNull { it.id == node.pool }
            val cpu = node.reservations.sumOf { it.budget.cpuMillis.toLong() }
            val memory = node.reservations.sumOf { it.budget.memoryMiB.toLong() }
            append(Html("<tr id=\"node-") + esc(node.id) + Html("\"><td>") + esc(node.id) + Html("<br><small class=\"muted\">") + esc(node.pool) + Html("</small></td><td><span class=\"node-state state-") + node.state.name.lowercase() + Html("\">") + node.state.name.lowercase().replaceFirstChar { it.uppercase() } + Html("</span></td><td>"))
            append(node.reservations.joinHtml(Html("<br>")) { allocation ->
                (if (allocation.job > 0) (Html("<a href=\"/jobs/") + allocation.job + Html("\">job ") + allocation.job + Html("</a>")) else Html("external work")) +
                    Html(" · ") + esc(allocation.id) + Html(" · ") + (allocation.budget.cpuMillis / 1000.0) + Html(" vCPU / ") +
                    allocation.budget.memoryMiB + Html(" MiB")
            }.let { if (it.isEmpty()) esc(if (node.state == CapacityNodeState.READY) "idle" else "no active allocations") else it })
            append(Html("</td><td>"))
            if (node.state == CapacityNodeState.READY) append(Html("") + (pool?.let { (it.cpuMillis - cpu).coerceAtLeast(0) / 1000.0 } ?: "?") + Html(" vCPU / ") + (pool?.let { (it.availableMemoryMiB - memory).coerceAtLeast(0) } ?: "?") + Html(" MiB"))
            else append(Html("not schedulable"))
            append(Html("</td></tr>"))
        }
        append(Html("</tbody></table></div>"))
    }
    if (!snapshot?.decision?.waiting.isNullOrEmpty()) append(Html("<h3>Waiting for capacity</h3>"))
    for (wait in snapshot?.decision?.waiting.orEmpty()) append(Html("<p class=\"capacity-message\"><code>") + esc(wait.request) + Html("</code><br>") + esc(wait.reason) + Html("</p>"))
    for ((pool, reason) in snapshot?.failures.orEmpty()) append(Html("<p class=\"warn\">") + esc(pool) + Html(": ") + esc(reason) + Html("</p>"))
    append(Html("</section><section id=\"capacity-requests\"><h2>Manual capacity requests</h2><p class=\"muted small\">Releasing a request removes its minimum. Jobs continue to run; idle nodes may retire automatically.</p>"))
    if (leases.isEmpty()) append(Html("<p class=\"empty-state\">No active requests.</p>"))
    else {
        append(Html("<div class=\"scroll\" role=\"region\" aria-label=\"Manual capacity requests\" tabindex=\"0\"><table class=\"lease-table\"><thead><tr><th scope=\"col\">Pool</th><th scope=\"col\">Minimum nodes</th><th scope=\"col\">Expires</th><th scope=\"col\"><span class=\"sr-only\">Actions</span></th></tr></thead><tbody>"))
        for (lease in leases) append(Html("<tr><td>") + esc(lease.pool) + Html("</td><td>") + lease.nodes + Html("</td><td>") + clockTime(lease.expiresAt) + Html("</td><td><button type=\"button\" data-release-capacity=\"") + esc(lease.id) + Html("\" aria-label=\"Release request for ") + esc(lease.pool) + Html("\">Release</button></td></tr>"))
        append(Html("</tbody></table></div>"))
    }
    append(Html("</section></div>"))
}

/** Live counts use the same inventory as the tables; missing inventory stays explicitly unknown. */
private fun nodeOverview(snapshot: CapacitySnapshot?, leases: List<ManualCapacityLease>): Html = buildHtml {
    append(Html("<div class=\"queue-overview\" role=\"group\" aria-label=\"Cloud capacity overview\">"))
    val states = snapshot?.nodes?.groupingBy { it.state }?.eachCount()
    fun count(vararg kinds: CapacityNodeState): Int? = states?.let { values -> kinds.sumOf { values[it] ?: 0 } }
    val counts = listOf(
        Triple(count(CapacityNodeState.READY), "Ready nodes", "DONE"),
        Triple(count(CapacityNodeState.BOOTING, CapacityNodeState.REPLACING), "Starting / replacing", "RUNNING"),
        Triple(count(CapacityNodeState.DRAINING, CapacityNodeState.UNAVAILABLE), "Draining / unavailable", "capacity-wait"),
        Triple(leases.size, "Manual requests", "QUEUED"),
    )
    for ((count, label, cls) in counts) append(Html("<div class=\"queue-stat ") + cls + Html("\"><strong>") + (count ?: "?") + Html("</strong><span>") + label + Html("</span></div>"))
    append(Html("</div>"))
    if (snapshot == null) append(Html("<p class=\"capacity-message warn\">Capacity has not reported. Requests take effect when fresh reports resume.</p>"))
    else {
        val held = snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy
        append(Html("<p class=\"capacity-message ") + (if (held || snapshot.failures.isNotEmpty()) "warn" else "muted") + Html("\">Report age: ") + (snapshot.ageMs / 1000) + Html("s"))
        if (held) append(Html(" · growth is held"))
        else if (snapshot.failures.isNotEmpty()) append(Html(" · provider launch failures; capacity requests will retry"))
        else append(Html(" · capacity report is up to date"))
        append(Html("</p>"))
    }
}
