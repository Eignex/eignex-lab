let nodeRequestPending = false;
function nodeLimit(clampCount = false) {
  const pool = document.getElementById('node-pool');
  const count = document.getElementById('node-count');
  if (!pool || !count) return;
  const option = pool.selectedOptions[0];
  const limit = option && !option.disabled ? Number(option.dataset.limit) : 0;
  count.max = String(limit);
  if (clampCount && limit > 0 && Number(count.value) > limit) count.value = String(limit);
  document.querySelector('#node-request button').disabled = nodeRequestPending || limit < 1;
  const info = document.getElementById('node-pool-info');
  if (info) info.textContent = limit > 0
    ? 'Up to ' + limit + ' nodes in this pool. Each node: ' + option.dataset.vcpus + ' vCPU / ' + option.dataset.memory + ' GiB. Existing nodes count toward your minimum.'
    : 'No nodes can be requested from this pool within the configured limits.';
}
nodeLimit();
document.getElementById('node-pool')?.addEventListener('change', function () { nodeLimit(true); });
document.addEventListener('submit', async function (event) {
  if (event.target.id !== 'node-request') return;
  event.preventDefault();
  const form = event.target;
  if (nodeRequestPending || form.querySelector('button').disabled || !form.reportValidity()) return;
  const data = new FormData(form);
  const fields = form.querySelector('fieldset');
  const feedback = document.getElementById('node-feedback');
  nodeRequestPending = true;
  fields.disabled = true;
  form.setAttribute('aria-busy', 'true');
  nodeLimit();
  feedback.classList.remove('warn');
  feedback.textContent = 'Requesting capacity…';
  try {
    const response = await fetch('/capacity/leases', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({pool: data.get('pool'), nodes: Number(data.get('nodes')), ttlSec: Number(data.get('ttlSec'))})});
    if (!response.ok) throw new Error(await response.text());
    feedback.textContent = 'Capacity requested for ' + data.get('pool') + '. Nodes will start as the pool reconciles.';
    await refresh(true);
  } catch (error) {
    feedback.classList.add('warn');
    feedback.textContent = error.message;
  } finally {
    nodeRequestPending = false;
    fields.disabled = false;
    form.removeAttribute('aria-busy');
    nodeLimit();
  }
});
document.addEventListener('click', async function (event) {
  const select = event.target.closest('[data-select-pool]');
  if (select && !nodeRequestPending) {
    const pool = document.getElementById('node-pool');
    if (pool) {
      pool.value = select.dataset.selectPool;
      nodeLimit(true);
      document.getElementById('start-nodes').scrollIntoView({block: 'start'});
      document.getElementById('node-count').focus({preventScroll: true});
    }
    return;
  }
  const button = event.target.closest('[data-release-capacity]');
  if (!button || button.disabled) return;
  button.disabled = true;
  const feedback = document.getElementById('node-feedback');
  try {
    const response = await fetch('/capacity/leases/' + encodeURIComponent(button.dataset.releaseCapacity), {method: 'DELETE'});
    if (!response.ok && response.status !== 404) throw new Error(await response.text());
    // Focus moves outside the replaced live region before its button is removed.
    document.getElementById('node-pool')?.focus({preventScroll: true});
    await refresh(true);
  } catch (error) {
    if (feedback) { feedback.classList.add('warn'); feedback.textContent = error.message; }
    else alert(error.message);
  } finally { button.disabled = false; }
});
