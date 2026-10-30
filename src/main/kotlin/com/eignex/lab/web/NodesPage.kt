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
internal fun nodesPage(local: LocalNode, host: HostReport, snapshot: CapacitySnapshot?, leases: List<ManualCapacityLease>, settings: CapacitySettings?, journal: ResultJournalReport? = null): Html = buildHtml {
    append(head("Nodes · eignex lab", live = true, tab = Tab.NODES))
    append(Html("<header><h1>Nodes</h1><p class=\"muted\">Native execution and cloud workers</p></header>"))
    append(Html("<section id=\"local-node\"><h2>Local node</h2><p><b>") + esc(host.host) + Html("</b> · ") + host.cores + Html(" logical CPUs · ") + local.cores + Html(" cores available to the lab</p>"))
    val tags = local.tags.sorted()
    append(Html("<p>") + (esc(NodeTags.hardwareProfile(local.tags))) + Html("</p>"))
    append(Html("<details><summary>All ") + tags.size + Html(" capability labels</summary><p class=\"muted small\">") + (esc(tags.joinToString(" · "))) + Html("</p></details>"))
    append(Html("</section>"))
    if (journal != null) {
        val s = journal.status
        append(Html("<section id=\"result-journal\" data-live><h2>Result journal</h2><p>") + s.batches + Html(" pending batches · ") + (s.bytes / 1024) + Html(" KiB reserved of ") + (s.limit / 1024 / 1024) + Html(" MiB · oldest ") + (s.oldestAgeMs / 1000) + Html("s · ") + s.drainErrors + Html(" drain errors</p>"))
        if (journal.stale) append(Html("<p class=\"warn\">Journal report is stale (") + (journal.ageMs / 1000) + Html("s old).</p>"))
        if (s.lastError != null) append(Html("<p class=\"warn\">Drain error: ") + esc(s.lastError) + Html(". Pending results are retained.</p>"))
        if (s.bytes >= s.limit * 9 / 10) append(Html("<p class=\"warn\">Journal is near capacity; uploads may wait for the external drain.</p>"))
        append(Html("</section>"))
    }
    val pools = settings?.pools ?: snapshot?.pools.orEmpty()
    val configured = settings != null
    append(Html("<section><h2>Start nodes</h2>"))
    if (configured) {
        append(Html("<form id=\"node-request\" class=\"tools\"><label>Node pool <select id=\"node-pool\" name=\"pool\" required>"))
        for (pool in pools) {
            val limit = minOf(pool.maxNodes, settings.maxVcpus / pool.vcpus)
            append(Html("<option value=\"") + esc(pool.id) + Html("\" data-limit=\"") + limit + Html("\"") + (if (limit == 0) " disabled" else "") + Html(">") + (esc(NodeTags.hardwareProfile(pool.tags))) + Html(" · ") + pool.vcpus + Html(" vCPU / ") + (pool.memoryMiB / 1024.0) + Html(" GiB · ") + esc(pool.market.tag) + Html(" · ") + esc(pool.id) + Html("</option>"))
        }
        append(Html("</select></label> <label>Keep at least <input id=\"node-count\" name=\"nodes\" type=\"number\" min=\"1\" value=\"1\" required style=\"width:5em\"> nodes</label> "))
        append(Html("<label>For <select name=\"ttlSec\"><option value=\"900\">15 minutes</option><option value=\"1800\">30 minutes</option><option value=\"3600\">1 hour</option></select></label> "))
        append(Html("<button type=\"submit\">Start / keep nodes</button></form><p id=\"node-feedback\" role=\"status\"></p>"))
        append(Html("<p class=\"muted small\">The selected pool fixes the hardware capabilities and market. The count is a minimum total for that pool, including nodes already running. "))
        append(Html("Requests share the global ") + settings.maxVcpus + Html(" vCPU limit and expire automatically. Jobs can use these nodes; releasing a request lets automatic scaling retire idle capacity.</p>"))
    } else append(Html("<p class=\"muted\">Nomad capacity settings are unavailable; manual starts are disabled.</p>"))
    append(Html("</section><div id=\"node-capacity\" data-live><section><h2>Cloud pools</h2>"))
    if (snapshot == null) append(Html("<p class=\"warn\">Capacity has not reported. Requests take effect when fresh reports resume.</p>"))
    else {
        append(Html("<p class=\"") + (if (snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy || snapshot.failures.isNotEmpty()) "warn" else "muted") + Html("\">Report age: ") + (snapshot.ageMs / 1000) + Html("s"))
        if (snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy) append(Html(" · growth is held"))
        else if (snapshot.failures.isNotEmpty()) append(Html(" · provider launch failures; capacity requests will retry"))
        append(Html("</p>"))
    }
    if (pools.isEmpty()) append(Html("<p class=\"muted\">No cloud pools configured.</p>"))
    else {
        append(Html("<div class=\"scroll\"><table><thead><tr><th>Pool / class</th><th>Hardware</th><th>Architecture / SIMD</th><th>Market</th><th>Observed / desired / limit</th></tr></thead><tbody>"))
        for (pool in pools) {
            append(Html("<tr><td>") + esc(pool.id) + Html("<br><small class=\"muted\">") + esc(pool.nodeClass) + Html("</small></td><td>") + pool.vcpus + Html(" vCPU / ") + pool.memoryMiB + Html(" MiB</td>"))
            append(Html("<td>") + (esc(NodeTags.hardwareProfile(pool.tags))) + Html("</td><td>") + esc(pool.market.tag) + Html("</td><td>") + (snapshot?.nodes?.count { it.pool == pool.id } ?: "?") + Html(" / ") + (snapshot?.decision?.desired?.get(pool.id) ?: "?") + Html(" / ") + pool.maxNodes + Html("</td></tr>"))
        }
        append(Html("</tbody></table></div>"))
    }
    append(Html("</section><section><h2>Cloud nodes and allocations</h2>"))
    if (snapshot?.nodes.isNullOrEmpty()) append(Html("<p class=\"muted\">") + (if (snapshot == null) "Node inventory is unavailable." else "No cloud nodes running.") + Html("</p>"))
    else {
        append(Html("<div class=\"scroll\"><table><thead><tr><th>Node / pool</th><th>State</th><th>Sessions / experiments</th><th>Unreserved CPU / memory</th></tr></thead><tbody>"))
        for (node in snapshot.nodes) {
            val pool = pools.singleOrNull { it.id == node.pool }
            val cpu = node.reservations.sumOf { it.budget.cpuMillis.toLong() }
            val memory = node.reservations.sumOf { it.budget.memoryMiB.toLong() }
            append(Html("<tr id=\"node-") + esc(node.id) + Html("\"><td>") + esc(node.id) + Html("<br><small class=\"muted\">") + esc(node.pool) + Html("</small></td><td>") + node.state + Html("</td><td>"))
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
    for (wait in snapshot?.decision?.waiting.orEmpty()) append(Html("<p>") + esc(wait.request) + Html(": ") + esc(wait.reason) + Html("</p>"))
    for ((pool, reason) in snapshot?.failures.orEmpty()) append(Html("<p class=\"warn\">") + esc(pool) + Html(": ") + esc(reason) + Html("</p>"))
    append(Html("</section><section><h2>Manual capacity requests</h2>"))
    if (leases.isEmpty()) append(Html("<p class=\"muted\">No active requests.</p>"))
    else {
        append(Html("<table><thead><tr><th>Pool</th><th>Minimum nodes</th><th>Expires</th><th></th></tr></thead><tbody>"))
        for (lease in leases) append(Html("<tr><td>") + esc(lease.pool) + Html("</td><td>") + lease.nodes + Html("</td><td>") + clockTime(lease.expiresAt) + Html("</td><td><button type=\"button\" data-release-capacity=\"") + esc(lease.id) + Html("\">Release</button></td></tr>"))
        append(Html("</tbody></table>"))
    }
    append(Html("</section></div>"))
    append(Html(SCRIPT))
    append(Html(NODES_SCRIPT))
    append(Html("</body></html>"))
}

private const val NODES_SCRIPT = """<script>
function nodeLimit() {
  const pool = document.getElementById('node-pool');
  const count = document.getElementById('node-count');
  if (!pool || !count) return;
  count.max = pool.selectedOptions[0]?.dataset.limit || '0';
  document.querySelector('#node-request button').disabled = Number(count.max) < 1;
}
nodeLimit();
document.getElementById('node-pool')?.addEventListener('change', nodeLimit);
document.addEventListener('submit', async function (event) {
  if (event.target.id !== 'node-request') return;
  event.preventDefault();
  const form = event.target, button = form.querySelector('button');
  const feedback = document.getElementById('node-feedback');
  button.disabled = true;
  feedback.textContent = 'Requesting capacity…';
  try {
    const data = new FormData(form);
    const response = await fetch('/capacity/leases', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({pool: data.get('pool'), nodes: Number(data.get('nodes')), ttlSec: Number(data.get('ttlSec'))})});
    if (!response.ok) throw new Error(await response.text());
    feedback.textContent = 'Capacity requested. Nodes will start as the pool reconciles.';
    await refresh();
  } catch (error) { feedback.textContent = error.message; }
  finally { nodeLimit(); }
});
document.addEventListener('click', async function (event) {
  const button = event.target.closest('[data-release-capacity]');
  if (!button) return;
  button.disabled = true;
  try {
    const response = await fetch('/capacity/leases/' + encodeURIComponent(button.dataset.releaseCapacity), {method: 'DELETE'});
    if (!response.ok && response.status !== 404) throw new Error(await response.text());
    await refresh();
  } catch (error) { alert(error.message); }
  finally { button.disabled = false; }
});
</script>"""
