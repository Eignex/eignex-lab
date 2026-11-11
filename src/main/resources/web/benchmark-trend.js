(function () {
  'use strict';
  let selectedJob = null, targetMethod = 'p05', listener = false;
  const labels = {'jvm-scalar': 'Portable JVM', 'jvm-simd': 'JVM SIMD', native: 'Native portable',
    'native-default': 'Native default', 'jvm-default': 'JVM default'};
  const priority = ['jvm-default', 'jvm-scalar', 'jvm-simd', 'native', 'native-default'];
  const format = value => Number(value.toPrecision(5)).toLocaleString();
  const name = value => labels[value] || value;
  const signed = value => (value >= 0 ? '+' : '') + value.toFixed(1) + '%';
  const relative = value => value.toFixed(2) + '×';
  window.renderBenchmarkTrend = function (context) {
    const {all, runs, draw, niceMax, when} = context, H = window.BenchmarkHistory;
    const history = H.history(runs), names = new Set();
    for (const r of runs) for (const m of r.benchmark?.metrics || []) names.add(H.engine(m));
    const engines = [...names].sort((a, b) => {
      const rank = v => priority.includes(v) ? priority.indexOf(v) : priority.length;
      return rank(a) - rank(b) || a.localeCompare(b);
    });
    const colors = new Map(engines.map((e, i) => [e, 'var(--series-' + (i % 5 + 1) + ')']));
    const dashes = ['', '6 3', '2 3', '8 3 2 3', '3 2'];
    const latest = [...runs].reverse().find(r => r.finished && r.benchmark) || runs[runs.length - 1];
    const selected = runs.find(r => r.job === selectedJob) || latest;
    if (!selected) return;
    selectedJob = selected.job;
    const render = () => window.renderBenchmarkTrend(context);
    const choose = r => {selectedJob = r.job; render();};
    const targetButtons = document.querySelectorAll('[data-benchmark-target]');
    targetButtons.forEach(button => {
      const on = button.dataset.benchmarkTarget === targetMethod;
      button.classList.toggle('on', on); button.setAttribute('aria-pressed', String(on));
    });
    document.getElementById('benchmark-latest').disabled = selectedJob === latest.job;
    if (!listener) {
      document.getElementById('benchmark-latest').addEventListener('click', () => choose(latest));
      targetButtons.forEach(button => button.addEventListener('click', () => {
        targetMethod = button.dataset.benchmarkTarget; render();
      }));
      listener = true;
    }
    const legend = document.getElementById('benchmark-engines'); legend.replaceChildren();
    engines.forEach((e, i) => {
      const span = document.createElement('span');
      const swatch = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      swatch.setAttribute('width', '24'); swatch.setAttribute('height', '12'); swatch.setAttribute('aria-hidden', 'true');
      const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      for (const [k, v] of Object.entries({x1: 0, x2: 24, y1: 6, y2: 6, stroke: colors.get(e), 'stroke-width': 2,
        'stroke-dasharray': dashes[i % dashes.length]})) line.setAttribute(k, v);
      swatch.append(line); span.append(swatch, document.createTextNode(name(e))); span.title = e; legend.append(span);
    });
    function summary(host, values) {
      host.replaceChildren();
      for (const {engine, text} of values) {
        const row = document.createElement('div'), label = document.createElement('span'), value = document.createElement('strong');
        label.textContent = name(engine); label.style.color = colors.get(engine); value.textContent = text;
        row.append(label, value); host.append(row);
      }
    }
    function pointText(p, performance) {
      if (!p) return 'No comparable baseline';
      if (p.count == null) return p.missing + '/' + p.coverage + ' unavailable';
      return (performance ? relative(p.ratio) + ' · ' : '') + p.count + '/' + p.coverage + ' regressed';
    }
    function regressionRows(r, theme) {
      const rows = [['commit', r.sha.slice(0, 9)], ['when', when(r.at)], ['job', '#' + r.job]];
      if (theme) rows.push(['theme', theme]);
      engines.forEach(e => {
        const p = history.point(r, e, theme);
        rows.push([name(e), pointText(p, true) + (p ? ' · baseline #' + p.baseline.job : '')]);
      });
      if (r.benchmark.failed) rows.push(['failed captures', r.benchmark.failed + ' · engine may be unknown']);
      rows.push(['paired vs previous', (r.benchmark.transitions || []).filter(t => t.confirmation === 'confirmed').length + ' confirmed changes']);
      return rows;
    }
    const failures = (r, theme) => (r.benchmark?.workloads || []).filter(w => w.status === 'failed' && (!theme || w.theme === theme));
    const themes = [...new Set(all.flatMap(r => (r.benchmark?.workloads || []).map(w => w.theme)).filter(Boolean))].sort();
    // Share the relative scale across the overview and every theme for a direct visual comparison.
    const ratios = runs.flatMap(r => [null, ...themes].flatMap(t => engines.map(e => history.point(r, e, t)?.ratio))).filter(v => v != null);
    const low = Math.max(0, Math.floor((Math.min(.9, ...ratios) - .02) * 10) / 10);
    const high = Math.ceil((Math.max(1.1, ...ratios) + .02) * 10) / 10;
    const ratioAxis = {min: low, max: high, ticks: [low, 1, high], reference: 1, fmt: v => v.toFixed(1) + '×'};
    function performance(host, theme, height) {
      const present = engines.filter(e => runs.some(r => history.point(r, e, theme)));
      const series = present.map(e => ({name: name(e), color: colors.get(e), dash: dashes[engines.indexOf(e) % dashes.length],
        value: r => history.point(r, e, theme)?.ratio}));
      draw(host, series, ratioAxis, {height, endLabels: false,
        title: (theme || 'All workloads') + ' performance relative to baseline, lower is faster',
        rows: r => regressionRows(r, theme), onSelect: choose, failures: r => failures(r, theme)});
      return present;
    }
    const counts = runs.flatMap(r => engines.map(e => history.point(r, e)?.count)).filter(v => v != null);
    const top = Math.max(1, Math.ceil(niceMax(Math.max(0, ...counts))));
    draw(document.getElementById('chart-benchmark-regressions'), engines.map((e, i) => ({
      name: name(e), color: colors.get(e), dash: dashes[i % dashes.length], value: r => history.point(r, e)?.count})),
    {max: top, ticks: [...new Set([0, Math.round(top / 2), top])], fmt: String},
    {height: 230, endLabels: false, title: 'Regressed workload count by engine', rows: r => regressionRows(r),
      onSelect: choose, failures: r => failures(r)});
    performance(document.getElementById('chart-benchmark-performance'), null, 230);
    summary(document.getElementById('benchmark-regression-summary'), engines.map(engine => ({engine, text: pointText(history.point(selected, engine), false)})));
    summary(document.getElementById('benchmark-performance-summary'), engines.map(engine => {
      const p = history.point(selected, engine);
      return {engine, text: p?.ratio != null ? relative(p.ratio) + ' baseline' : pointText(p, true)};
    }));
    const bases = [...new Set(engines.map(e => history.point(selected, e)?.baseline.job).filter(Boolean))];
    document.getElementById('benchmark-baseline').textContent = 'Inspecting ' + selected.sha.slice(0, 9) + ' · #' + selected.job +
      (selectedJob === latest.job && selected.finished ? ' · latest complete run' : selected.finished ? '' : ' · running') + ' · ' +
      (bases.length ? 'fixed baseline ' + bases.map(v => '#' + v).join(', ') : 'awaiting a complete comparable baseline');
    const grid = document.getElementById('benchmark-themes');
    if (grid) {
      grid.replaceChildren();
      const plots = themes.map(theme => {
        const cell = document.createElement('div'), title = document.createElement('h3'), chart = document.createElement('div'), values = document.createElement('div');
        cell.className = 'multiple'; title.className = 'mtitle'; title.textContent = theme;
        values.className = 'benchmark-chart-summary'; cell.append(title, chart, values); grid.append(cell);
        return {theme, chart, values};
      });
      for (const {theme, chart, values} of plots) {
        const present = performance(chart, theme, 150);
        summary(values, present.map(engine => ({engine, text: pointText(history.point(selected, engine, theme), true)})));
      }
    }
    document.getElementById('benchmark-target-description').textContent = targetMethod === 'best' ?
      'Best earlier comparable run median in the shown history. A single unusually fast run can set this target.' :
      '5th percentile of earlier comparable run medians for timing (95th for throughput). Fewer than 20 prior runs are marked as limited history. This is a historical estimate, not a confidence bound.';
    const tableContext = document.getElementById('benchmark-table-context'), jobLink = document.createElement('a');
    jobLink.href = '/jobs/' + selected.job; jobLink.textContent = '#' + selected.job;
    tableContext.replaceChildren(document.createTextNode('Current: ' + selected.sha.slice(0, 9) + ' · '), jobLink,
      document.createTextNode('. Sorted by largest slowdown. Targets exclude this run and every later run; they do not change the graph baseline or paired confirmation policy.'));
    const table = document.querySelector('.benchmark-cases');
    const selectedEngines = new Set((selected.benchmark?.metrics || []).map(H.engine));
    (selected.benchmark?.workloads || []).forEach(w => Object.keys(w.engines || {}).forEach(e => selectedEngines.add(e)));
    const columns = table.dataset.koblas === 'true' ? [
      {engine: 'jvm-simd', label: 'JVM SIMD'}, {engine: 'accelerate-jvm', label: 'Accelerate'}, {engine: 'jvm-scalar', label: 'Portable'}
    ] : engines.map(engine => ({engine, label: name(engine)}));
    const head = document.getElementById('benchmark-case-head'); head.replaceChildren();
    ['Case', ...columns.map(c => c.label)].forEach((label, i) => {
      const th = document.createElement('th'); th.scope = 'col'; th.textContent = label;
      if (i) th.title = columns[i - 1].engine; head.append(th);
    });
    const missing = columns.filter(c => !selectedEngines.has(c.engine));
    document.getElementById('benchmark-table-coverage').textContent = missing.length ?
      'No exact-engine capture in this run for ' + missing.map(c => c.label).join(', ') + '. Default-engine measurements are not substituted.' : '';
    const body = document.getElementById('benchmark-case-rows'); body.replaceChildren();
    const statusLabels = {measured: 'Measured', failed: 'Capture failed', pending: 'Pending', skipped: 'Skipped',
      unsupported: 'Unsupported', instrumented: 'Instrumented', unavailable: 'Not measured'};
    const fragment = document.createDocumentFragment();
    for (const row of H.caseTable(runs, selected, columns, targetMethod)) {
      const tr = document.createElement('tr'), caseCell = document.createElement('th'); caseCell.scope = 'row';
      const title = document.createElement('code'); title.textContent = row.workload.workload || row.workload.caseId;
      caseCell.append(title);
      const identity = row.identity && JSON.parse(row.identity);
      if (row.workload.theme || identity && identity[0] !== 'median_ns') {
        const detail = document.createElement('small');
        detail.textContent = [row.workload.theme, identity && identity[0] !== 'median_ns' ? identity[0] + ' · ' + identity[1] : null].filter(Boolean).join(' · ');
        caseCell.append(detail);
      }
      tr.append(caseCell);
      for (const cell of row.cells) {
        const td = document.createElement('td'); td.className = 'benchmark-engine-cell';
        const timing = value => value == null ? '—' : format(value) + ' ' + cell.metric.unit;
        const value = document.createElement('strong');
        value.textContent = cell.current == null ? statusLabels[cell.status] || 'Not measured' : timing(cell.current);
        td.append(value);
        if (cell.status !== 'measured' && cell.current != null) {
          const status = document.createElement('small'); status.textContent = statusLabels[cell.status] || 'Unavailable'; td.append(status);
        }
        if (cell.target != null) {
          const target = document.createElement('small'); target.textContent = 'Historical target ' + timing(cell.target); td.append(target);
        }
        const delta = document.createElement('span'); delta.className = 'benchmark-history-delta';
        delta.textContent = cell.ratio == null ? (cell.samples ? 'Comparison unavailable' : 'No comparable history') :
          signed((cell.ratio - 1) * 100) + ' vs own history';
        if (cell.ratio > 1 + row.workload.threshold && row.workload.threshold > 0) delta.classList.add('FAILED');
        td.append(delta);
        const count = document.createElement('small');
        count.textContent = cell.samples + ' prior run' + (cell.samples === 1 ? '' : 's') + (cell.limited ? ' · limited history' : '');
        td.append(count); td.title = cell.engine; tr.append(td);
      }
      fragment.append(tr);
    }
    body.append(fragment);
    if (!body.children.length) {
      const tr = document.createElement('tr'), td = document.createElement('td'); td.colSpan = columns.length + 1;
      td.textContent = 'No planned cases are available for this run.'; tr.append(td); body.append(tr);
    }
  };
})();
