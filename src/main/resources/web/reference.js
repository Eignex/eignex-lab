document.querySelectorAll('.coverage-toggle').forEach(function (button) {
  button.addEventListener('click', function () {
    const open = button.getAttribute('aria-expanded') !== 'true';
    button.setAttribute('aria-expanded', String(open));
    button.querySelector('[data-family-icon]').textContent = open ? '▾' : '▸';
    document.querySelectorAll('#reference-coverage tr[data-in]').forEach(function (row) {
      if (row.dataset.in === button.dataset.family) row.hidden = !open;
    });
  });
});
function revealReferenceConflicts() {
  if (location.hash === '#reference-conflicts') {
    const details = document.querySelector('.reference-conflicts');
    if (details) details.open = true;
  }
}
document.querySelectorAll('a[href="#reference-conflicts"]').forEach(function (link) {
  link.addEventListener('click', function () {
    const details = document.querySelector('.reference-conflicts');
    if (details) details.open = true;
  });
});
window.addEventListener('hashchange', revealReferenceConflicts);
revealReferenceConflicts();
