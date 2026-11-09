let chip = 'all';
function applyFilter() {
  const q = document.getElementById('q');
  const text = q ? q.value.trim().toLowerCase() : '';
  const rows = document.querySelectorAll('#history tr[data-state]');
  let visible = 0;
  rows.forEach(function (row) {
    row.hidden = !((!text || row.dataset.text.includes(text)) && (chip === 'all' || row.dataset.state === chip));
    if (!row.hidden) visible++;
  });
  const count = document.getElementById('history-count');
  const message = visible + ' of ' + rows.length + ' runs shown';
  if (count && count.textContent !== message) count.textContent = message;
  const empty = document.getElementById('history-empty');
  if (empty) empty.hidden = rows.length === 0 || visible > 0;
  const clear = document.getElementById('clear-history');
  if (clear) clear.hidden = !text && chip === 'all';
}
document.getElementById('q')?.addEventListener('input', applyFilter);
document.querySelectorAll('[data-chip]').forEach(function (button) {
  button.addEventListener('click', function () {
    chip = button.dataset.chip;
    document.querySelectorAll('[data-chip]').forEach(function (b) {
      b.classList.toggle('on', b === button);
      b.setAttribute('aria-pressed', String(b === button));
    });
    applyFilter();
  });
});
document.getElementById('clear-history')?.addEventListener('click', function () {
  const q = document.getElementById('q');
  if (q) q.value = '';
  document.querySelector('[data-chip="all"]')?.click();
  q?.focus();
});
applyFilter();
