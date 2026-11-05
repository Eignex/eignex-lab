const liveMarkup = new WeakMap();
document.querySelectorAll('[data-live]').forEach(function (part) { liveMarkup.set(part, part.innerHTML); });
function applyResultFilters() {
  document.querySelectorAll('[data-result-list]').forEach(function (list) {
    const query = (list.querySelector('[data-result-query]')?.value || '').trim().toLowerCase();
    const arm = list.querySelector('[data-result-arm]')?.value;
    const status = list.querySelector('[data-result-status]')?.value;
    const differ = list.querySelector('#differ')?.checked;
    const rows = Array.from(list.querySelectorAll('[data-result-row]'));
    let count = 0;
    const limit = Number(list.dataset.resultShown || list.dataset.resultLimit) || Infinity;
    rows.forEach(function (row) {
      const matches = row.dataset.search.toLowerCase().includes(query) &&
        (!arm || row.dataset.arm === arm) && (!status || row.dataset.status === status) &&
        (!differ || row.dataset.differ === 'true');
      if (matches) count++;
      row.hidden = !matches || count > limit;
    });
    const counter = list.querySelector('[data-result-count]');
    if (counter) counter.textContent = Math.min(count, limit) + ' of ' + count + ' matching · ' + rows.length + ' total';
    const more = list.querySelector('[data-result-more]');
    if (more) more.hidden = count <= limit;
    const empty = list.querySelector('[data-result-empty]');
    if (empty) empty.hidden = count !== 0;
  });
}
['input', 'change'].forEach(function (name) {
  document.addEventListener(name, function (e) {
    const list = e.target.closest('[data-result-list]');
    if (list) { delete list.dataset.resultShown; applyResultFilters(); }
  });
});
document.addEventListener('click', function (e) {
  const more = e.target.closest('[data-result-more]');
  if (!more) return;
  const list = more.closest('[data-result-list]');
  list.dataset.resultShown = Number(list.dataset.resultShown || list.dataset.resultLimit) + Number(list.dataset.resultLimit);
  applyResultFilters();
});
applyResultFilters();
async function act(path, body, ask) {
  if (ask && !confirm(ask)) return;
  const response = await fetch(path, body
    ? {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)}
    : {method: 'POST'});
  if (!response.ok) alert(await response.text());
  location.reload();
}
let timer = null;
let refreshPending = null;
async function refresh(force = false) {
  // Slow responses must not accumulate another poll every ten seconds.
  if (refreshPending) {
    if (!force) return;
    await refreshPending;
    return refresh(true);
  }
  if (!force && (document.hidden || String(getSelection()).length)) return;
  if (!force && document.activeElement && document.activeElement.closest('[data-live]')) return;
  refreshPending = updateLiveParts();
  try { await refreshPending; }
  finally { refreshPending = null; }
}
async function updateLiveParts() {
  const controller = new AbortController();
  const timeout = setTimeout(function () { controller.abort(); }, 30000);
  try {
    const response = await fetch(location.href, {headers: {Accept: 'text/html'}, signal: controller.signal});
    if (!response.ok) return;
    const fresh = new DOMParser().parseFromString(await response.text(), 'text/html');
    let historyChanged = false;
    fresh.querySelectorAll('[data-live]').forEach(function (part) {
      const old = document.getElementById(part.id);
      if (old) {
        const markup = part.innerHTML;
        if (liveMarkup.get(old) === markup) return;
        const scrolls = Array.from(old.querySelectorAll('.scroll')).map(function (el) { return {left: el.scrollLeft, top: el.scrollTop}; });
        const limits = new Map(Array.from(old.querySelectorAll('[data-result-list][id]')).map(function (el) { return [el.id, el.dataset.resultShown]; }));
        const disclosures = new Map(Array.from(old.querySelectorAll('details[id]')).map(function (el) { return [el.id, el.open]; }));
        const filters = new Map(Array.from(old.querySelectorAll('[data-result-list] input[id], [data-result-list] select[id]')).map(function (el) {
          return [el.id, {value: el.value, checked: el.checked}];
        }));
        old.innerHTML = markup;
        old.querySelectorAll('[data-result-list][id]').forEach(function (el) { if (limits.get(el.id)) el.dataset.resultShown = limits.get(el.id); });
        old.querySelectorAll('details[id]').forEach(function (el) { if (disclosures.has(el.id)) el.open = disclosures.get(el.id); });
        old.querySelectorAll('[data-result-list] input[id], [data-result-list] select[id]').forEach(function (el) {
          const state = filters.get(el.id);
          if (state) { el.value = state.value; el.checked = state.checked; }
        });
        liveMarkup.set(old, markup);
        if (part.id === 'history') historyChanged = true;
        old.querySelectorAll('.scroll').forEach(function (el, i) { el.scrollLeft = scrolls[i]?.left || 0; el.scrollTop = scrolls[i]?.top || 0; });
      }
    });
    if (historyChanged && typeof applyFilter === 'function') applyFilter();
    applyResultFilters();
    if (!fresh.body.hasAttribute('data-refresh')) clearInterval(timer);
  } catch (e) {}
  finally { clearTimeout(timeout); }
}
if (document.body.hasAttribute('data-refresh')) timer = setInterval(refresh, 10000);
