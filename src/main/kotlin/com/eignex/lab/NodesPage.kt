package com.eignex.lab

/** Node inventory and temporary capacity controls for configured, provider-neutral pools. */
internal fun nodesPage(config: Config, host: HostReport, snapshot: CapacitySnapshot?, leases: List<ManualCapacityLease>, settings: CapacitySettings?, journal: ResultJournalReport? = null): String = buildString {
    append(head("Nodes · eignex lab", live = true, tab = Tab.NODES))
    append("<header><h1>Nodes</h1><p class=\"muted\">Native execution and cloud workers</p></header>")
    append("<section id=\"local-node\"><h2>Local node</h2><p><b>${esc(host.host)}</b> · ${host.cores} logical CPUs · ${config.cores} cores available to the lab</p>")
    val tags = config.localNodeTags.sorted()
    append("<p>${esc(NodeTags.hardwareProfile(config.localNodeTags))}</p>")
    append("<details><summary>All ${tags.size} capability labels</summary><p class=\"muted small\">${esc(tags.joinToString(" · "))}</p></details>")
    append("</section>")
    if (journal != null) {
        val s = journal.status
        append("<section id=\"result-journal\" data-live><h2>Result journal</h2><p>${s.batches} pending batches · ${s.bytes / 1024} KiB reserved of ${s.limit / 1024 / 1024} MiB · oldest ${s.oldestAgeMs / 1000}s · ${s.drainErrors} drain errors</p>")
        if (journal.stale) append("<p class=\"warn\">Journal report is stale (${journal.ageMs / 1000}s old).</p>")
        if (s.lastError != null) append("<p class=\"warn\">Drain error: ${esc(s.lastError)}. Pending results are retained.</p>")
        if (s.bytes >= s.limit * 9 / 10) append("<p class=\"warn\">Journal is near capacity; uploads may wait for the external drain.</p>")
        append("</section>")
    }
    val pools = settings?.pools ?: snapshot?.pools.orEmpty()
    val configured = settings != null
    append("<section><h2>Start nodes</h2>")
    if (configured) {
        append("<form id=\"node-request\" class=\"tools\"><label>Node pool <select id=\"node-pool\" name=\"pool\" required>")
        for (pool in pools) {
            val limit = minOf(pool.maxNodes, settings.maxVcpus / pool.vcpus)
            append("<option value=\"${esc(pool.id)}\" data-limit=\"$limit\"${if (limit == 0) " disabled" else ""}>${esc(NodeTags.hardwareProfile(pool.tags))} · ${pool.vcpus} vCPU / ${pool.memoryMiB / 1024.0} GiB · ${esc(pool.market.tag)} · ${esc(pool.id)}</option>")
        }
        append("</select></label> <label>Keep at least <input id=\"node-count\" name=\"nodes\" type=\"number\" min=\"1\" value=\"1\" required style=\"width:5em\"> nodes</label> ")
        append("<label>For <select name=\"ttlSec\"><option value=\"900\">15 minutes</option><option value=\"1800\">30 minutes</option><option value=\"3600\">1 hour</option></select></label> ")
        append("<button type=\"submit\">Start / keep nodes</button></form><p id=\"node-feedback\" role=\"status\"></p>")
        append("<p class=\"muted small\">The selected pool fixes the hardware capabilities and market. The count is a minimum total for that pool, including nodes already running. ")
        append("Requests share the global ${settings.maxVcpus} vCPU limit and expire automatically. Jobs can use these nodes; releasing a request lets automatic scaling retire idle capacity.</p>")
    } else append("<p class=\"muted\">Nomad capacity settings are unavailable; manual starts are disabled.</p>")
    append("</section><div id=\"node-capacity\" data-live><section><h2>Cloud pools</h2>")
    if (snapshot == null) append("<p class=\"warn\">Capacity has not reported. Requests take effect when fresh reports resume.</p>")
    else {
        append("<p class=\"${if (snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy || snapshot.failures.isNotEmpty()) "warn" else "muted"}\">Report age: ${snapshot.ageMs / 1000}s")
        if (snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy) append(" · growth is held")
        else if (snapshot.failures.isNotEmpty()) append(" · provider launch failures; capacity requests will retry")
        append("</p>")
    }
    if (pools.isEmpty()) append("<p class=\"muted\">No cloud pools configured.</p>")
    else {
        append("<div class=\"scroll\"><table><thead><tr><th>Pool / class</th><th>Hardware</th><th>Architecture / SIMD</th><th>Market</th><th>Observed / desired / limit</th></tr></thead><tbody>")
        for (pool in pools) {
            append("<tr><td>${esc(pool.id)}<br><small class=\"muted\">${esc(pool.nodeClass)}</small></td><td>${pool.vcpus} vCPU / ${pool.memoryMiB} MiB</td>")
            append("<td>${esc(NodeTags.hardwareProfile(pool.tags))}</td><td>${esc(pool.market.tag)}</td><td>${snapshot?.nodes?.count { it.pool == pool.id } ?: "?"} / ${snapshot?.decision?.desired?.get(pool.id) ?: "?"} / ${pool.maxNodes}</td></tr>")
        }
        append("</tbody></table></div>")
    }
    append("</section><section><h2>Cloud nodes and allocations</h2>")
    if (snapshot?.nodes.isNullOrEmpty()) append("<p class=\"muted\">${if (snapshot == null) "Node inventory is unavailable." else "No cloud nodes running."}</p>")
    else {
        append("<div class=\"scroll\"><table><thead><tr><th>Node / pool</th><th>State</th><th>Sessions / experiments</th><th>Unreserved CPU / memory</th></tr></thead><tbody>")
        for (node in snapshot.nodes) {
            val pool = pools.singleOrNull { it.id == node.pool }
            val cpu = node.reservations.sumOf { it.budget.cpuMillis.toLong() }
            val memory = node.reservations.sumOf { it.budget.memoryMiB.toLong() }
            append("<tr id=\"node-${esc(node.id)}\"><td>${esc(node.id)}<br><small class=\"muted\">${esc(node.pool)}</small></td><td>${node.state}</td><td>")
            append(node.reservations.joinToString("<br>") { allocation ->
                (if (allocation.job > 0) "<a href=\"/jobs/${allocation.job}\">job ${allocation.job}</a>" else "external work") +
                    " · ${esc(allocation.id)} · ${allocation.budget.cpuMillis / 1000.0} vCPU / ${allocation.budget.memoryMiB} MiB"
            }.ifEmpty { if (node.state == CapacityNodeState.READY) "idle" else "no active allocations" })
            append("</td><td>")
            if (node.state == CapacityNodeState.READY) append("${pool?.let { (it.cpuMillis - cpu).coerceAtLeast(0) / 1000.0 } ?: "?"} vCPU / ${pool?.let { (it.availableMemoryMiB - memory).coerceAtLeast(0) } ?: "?"} MiB")
            else append("not schedulable")
            append("</td></tr>")
        }
        append("</tbody></table></div>")
    }
    for (wait in snapshot?.decision?.waiting.orEmpty()) append("<p>${esc(wait.request)}: ${esc(wait.reason)}</p>")
    for ((pool, reason) in snapshot?.failures.orEmpty()) append("<p class=\"warn\">${esc(pool)}: ${esc(reason)}</p>")
    append("</section><section><h2>Manual capacity requests</h2>")
    if (leases.isEmpty()) append("<p class=\"muted\">No active requests.</p>")
    else {
        append("<table><thead><tr><th>Pool</th><th>Minimum nodes</th><th>Expires</th><th></th></tr></thead><tbody>")
        for (lease in leases) append("<tr><td>${esc(lease.pool)}</td><td>${lease.nodes}</td><td>${clockTime(lease.expiresAt)}</td><td><button type=\"button\" data-release-capacity=\"${esc(lease.id)}\">Release</button></td></tr>")
        append("</tbody></table>")
    }
    append("</section></div>")
    append(SCRIPT)
    append(NODES_SCRIPT)
    append("</body></html>")
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
