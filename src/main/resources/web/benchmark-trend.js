(function () {
  'use strict';
  let selectedJob = null, selectedMetric = null, targetMethod = 'p05', listener = false;
  const labels = {'jvm-scalar': 'Portable JVM', 'jvm-simd': 'JVM SIMD', native: 'Native portable',
    'native-default': 'Native default', 'jvm-default': 'JVM default'};
  const priority = ['jvm-default', 'jvm-scalar', 'jvm-simd', 'native', 'native-default'];
  const format = value => Number(value.toPrecision(5)).toLocaleString();
  const name = value => labels[value] || value;
  const signed = value => (value >= 0 ? '+' : '') + value.toFixed(1) + '%';
  window.renderBenchmarkTrend = function (context) {
    const {all, runs, draw, niceMax, when} = context, H = window.BenchmarkHistory;
    const history = H.history(runs), metrics = new Map(), names = new Set();
    for (const r of runs) for (const m of r.benchmark?.metrics || []) {
      metrics.set(H.selection(r, m), {r, m}); names.add(H.engine(m));
    }
    const engines = [...names].sort((a, b) => {
      const rank = v => priority.includes(v) ? priority.indexOf(v) : priority.length;
      return rank(a) - rank(b) || a.localeCompare(b);
    });
    const colors = new Map(engines.map((e, i) => [e, 'var(--series-' + (i % 5 + 1) + ')']));
    const dashes = ['', '6 3', '2 3', '8 3 2 3', '3 2'];
    const select = document.getElementById('benchmark-metric');
    const latest = [...runs].reverse().find(r => r.finished && r.benchmark) || runs[runs.length - 1];
    let selected = runs.find(r => r.job === selectedJob) || latest;
    if (!selected) return;
    selectedJob = selected.job;
    const commitSelect = document.getElementById('benchmark-run');
    const targetSelect = document.getElementById('benchmark-target');
    targetSelect.value = targetMethod;
    commitSelect.replaceChildren();
    for (const r of runs) {
      const option = document.createElement('option'); option.value = String(r.job);
      option.textContent = r.sha.slice(0, 9) + ' · #' + r.job + (r.finished ? '' : ' · running');
      commitSelect.append(option);
    }
    commitSelect.value = String(selectedJob);
    const render = () => window.renderBenchmarkTrend(context);
    if (!listener) {
      commitSelect.addEventListener('change', () => {selectedJob = Number(commitSelect.value); render();});
      select.addEventListener('change', () => {selectedMetric = select.value; render();});
      targetSelect.addEventListener('change', () => {targetMethod = targetSelect.value; render();});
      listener = true;
    }
    select.replaceChildren();
    for (const [key, {r, m}] of metrics) {
      const option = document.createElement('option'); option.value = key;
      option.textContent = (H.workload(r, m.caseId)?.workload || m.caseId) + ' · ' + m.unit +
        (JSON.parse(key)[1] === 'median_ns' ? '' : ' · ' + JSON.parse(key)[1]);
      select.append(option);
    }
    if (!metrics.has(selectedMetric)) {
      const first = history.changes(selected)[0];
      selectedMetric = first ? H.selection(selected, first.after) : metrics.keys().next().value;
    }
    if (selectedMetric) select.value = selectedMetric;
    for (const id of ['benchmark-engines', 'benchmark-timing-engines']) {
      const legend = document.getElementById(id); legend.replaceChildren();
      engines.forEach((e, i) => {
        const span = document.createElement('span');
        const swatch = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        swatch.setAttribute('width', '24'); swatch.setAttribute('height', '12'); swatch.setAttribute('aria-hidden', 'true');
        const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
        for (const [k, v] of Object.entries({x1: 0, x2: 24, y1: 6, y2: 6, stroke: colors.get(e), 'stroke-width': 2,
          'stroke-dasharray': dashes[i % dashes.length]})) line.setAttribute(k, v);
        swatch.append(line); span.append(swatch, document.createTextNode(name(e))); span.title = e; legend.append(span);
      });
    }
    function choose(r, theme) {
      selectedJob = r.job;
      const change = history.changes(r, theme)[0];
      if (change) selectedMetric = H.selection(r, change.after);
      render();
      document.getElementById('benchmark-affected').scrollIntoView({block: 'nearest', behavior: 'auto'});
    }
    function regressionRows(r, theme) {
      const rows = [['commit', r.sha.slice(0, 9)], ['when', when(r.at)], ['job', '#' + r.job]];
      if (theme) rows.push(['theme', theme]);
      engines.forEach(e => {
        const p = history.point(r, e, theme);
        rows.push([name(e), !p ? 'no comparable baseline' : p.count == null ?
          'incomplete · ' + p.missing + ' of ' + p.coverage + ' unavailable' :
          p.count + ' regressed / ' + p.coverage + ' measured · baseline #' + p.baseline.job]);
      });
      if (r.benchmark.failed) rows.push(['failed captures', r.benchmark.failed + ' · engine may be unknown']);
      rows.push(['paired vs previous', (r.benchmark.transitions || []).filter(t => t.confirmation === 'confirmed').length + ' confirmed changes']);
      return rows;
    }
    function regressions(host, theme, height) {
      const series = engines.map((e, i) => ({name: name(e), color: colors.get(e), dash: dashes[i % dashes.length],
        value: r => history.point(r, e, theme)?.count}));
      const values = runs.flatMap(r => engines.map(e => history.point(r, e, theme)?.count)).filter(v => v != null);
      const top = Math.max(1, Math.ceil(niceMax(Math.max(0, ...values))));
      const ticks = [...new Set([0, Math.round(top / 2), top])];
      draw(host, series, {max: top, ticks, fmt: String}, {height, endLabels: false,
        title: (theme || 'All workloads') + ' regressed workload count by engine', rows: r => regressionRows(r, theme),
        onSelect: r => choose(r, theme), failures: r => (r.benchmark?.workloads || []).filter(w => w.status === 'failed' && (!theme || w.theme === theme))});
    }
    regressions(document.getElementById('chart-benchmark-regressions'), null, 260);
    const bases = [...new Set(engines.map(e => history.point(selected, e)?.baseline.job).filter(Boolean))];
    document.getElementById('benchmark-baseline').textContent = 'Selected commit ' + selected.sha.slice(0, 9) + ' · ' +
      (bases.length ? 'fixed baseline ' + bases.map(v => '#' + v).join(', ') : 'awaiting a complete comparable baseline') +
      '. Counts are candidates; paired confirmations compare the recorded neighboring revisions.';
    const affected = document.getElementById('benchmark-affected'); affected.replaceChildren();
    const changes = history.changes(selected);
    if (!changes.length) {
      const p = document.createElement('p'); p.className = 'muted small';
      p.textContent = engines.some(e => history.point(selected, e)?.count != null) ?
        (engines.some(e => history.point(selected, e)?.count == null) ? 'Some engines lack comparable measurements. No slowdowns in the available complete measurements.' :
          'No measured workloads exceed their baseline slowdown threshold.') : 'Comparable measurements are unavailable; this is not a clean result.';
      affected.append(p);
    }
    for (const c of changes) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'benchmark-change';
      button.textContent = name(H.engine(c.after)) + ' · ' + c.workload.workload + ' · ' + signed((c.ratio - 1) * 100) + ' slower · ' + c.confirmation;
      button.addEventListener('click', () => {
        selectedMetric = H.selection(selected, c.after); render();
        document.getElementById('benchmark-detail').scrollIntoView({block: 'start', behavior: 'auto'});
      });
      if (affected.children.length === 8) {
        const details = document.createElement('details'), summary = document.createElement('summary');
        summary.textContent = 'More affected engine workloads (' + (changes.length - 8) + ')';
        details.append(summary); affected.append(details);
      }
      const details = affected.querySelector('details');
      (details || affected).append(button);
    }
    const ownMetric = r => (r.benchmark?.metrics || []).filter(m => H.selection(r, m) === selectedMetric);
    const observations = runs.flatMap(ownMetric), representative = observations[0];
    const plot = document.getElementById('chart-benchmark-metric');
    if (representative) {
      const unit = representative.unit, present = engines.filter(e => observations.some(m => H.engine(m) === e));
      const top = niceMax(Math.max(...observations.map(m => m.value)));
      const series = present.map(e => ({name: name(e), color: colors.get(e), dash: dashes[engines.indexOf(e) % dashes.length],
        value: r => ownMetric(r).find(m => H.engine(m) === e)?.value,
        lineKey: r => {const m = ownMetric(r).find(m => H.engine(m) === e); return H.segment(r) + H.workload(r, m?.caseId)?.controls;} }));
      draw(plot, series, {max: top, ticks: [0, top / 2, top], fmt: v => format(v) + ' ' + unit},
        {height: 290, endLabels: false, title: select.selectedOptions[0]?.textContent || 'Absolute timing', onSelect: r => {selectedJob = r.job; render();},
          failures: r => (r.benchmark?.workloads || []).filter(w => w.status === 'failed' &&
            (w.workload || w.caseId) === JSON.parse(selectedMetric)[0]),
          rows: r => {
            const rows = [['commit', r.sha.slice(0, 9)], ['job', '#' + r.job]], measured = ownMetric(r);
            const best = measured.length ? (representative.direction === 'higher' ? Math.max : Math.min)(...measured.map(m => m.value)) : null;
            present.forEach(e => {
              const m = measured.find(m => H.engine(m) === e);
              rows.push([name(e), m ? format(m.value) + ' ' + unit : 'unavailable']);
              if (m && best > 0 && m.value > 0 && ['lower', 'higher'].includes(m.direction)) rows.push(['relative to fastest',
                (representative.direction === 'higher' ? best / m.value : m.value / best).toFixed(2) + '× slower']);
              if (m?.provider) rows.push(['provider', m.provider]);
              if (m?.kernel) rows.push(['kernel', m.kernel]);
            });
            return rows;
          }});
      const values = ownMetric(selected), summary = document.getElementById('benchmark-selected');
      summary.replaceChildren();
      for (const e of present) {
        const m = values.find(m => H.engine(m) === e), cell = document.createElement('div');
        const label = document.createElement('span'), value = document.createElement('strong');
        label.textContent = name(e); value.textContent = m ? format(m.value) + ' ' + unit : 'Unavailable';
        cell.append(label, value); summary.append(cell);
      }
      summary.setAttribute('aria-label', 'Absolute timings at commit ' + selected.sha.slice(0, 9));
    } else {
      plot.textContent = 'No successful measurements yet.';
      document.getElementById('benchmark-selected').textContent = '';
    }
    const grid = document.getElementById('benchmark-themes');
    if (grid) {
      grid.replaceChildren(); const plots = [];
      const themes = [...new Set(all.flatMap(r => (r.benchmark?.workloads || []).map(w => w.theme)).filter(Boolean))].sort();
      for (const theme of themes) {
        const cell = document.createElement('div'), title = document.createElement('div'), chart = document.createElement('div');
        cell.className = 'multiple'; title.className = 'mtitle'; title.textContent = theme;
        cell.append(title, chart); grid.append(cell); plots.push({theme, chart});
      }
      for (const {theme, chart} of plots) regressions(chart, theme, 170);
    }
    document.getElementById('benchmark-target-description').textContent = targetMethod === 'best' ?
      'Best earlier comparable run median in the shown history. A single unusually fast run can set this target.' :
      '5th percentile of earlier comparable run medians for timing (95th for throughput). Fewer than 20 prior runs are marked as limited history. This is a historical estimate, not a confidence bound.';
    const tableContext = document.getElementById('benchmark-table-context'), jobLink = document.createElement('a');
    jobLink.href = '/jobs/' + selected.job; jobLink.textContent = '#' + selected.job;
    tableContext.replaceChildren(document.createTextNode('Current: ' + selected.sha.slice(0, 9) + ' · '), jobLink,
      document.createTextNode('. Sorted by largest slowdown. Targets exclude this run and every later run; they do not change the graph baseline or paired confirmation policy.'));
    const body = document.getElementById('benchmark-case-rows'); body.replaceChildren();
    const statusLabels = {measured: 'Measured', failed: 'Capture failed', pending: 'Pending', skipped: 'Skipped',
      unsupported: 'Unsupported', instrumented: 'Instrumented', unavailable: 'Unavailable'};
    const fragment = document.createDocumentFragment();
    for (const row of H.caseComparisons(runs, selected, targetMethod)) {
      const tr = document.createElement('tr'), caseCell = document.createElement('td');
      const title = document.createElement(row.selection ? 'button' : 'code');
      title.textContent = row.workload.workload || row.workload.caseId;
      if (row.selection) {
        title.type = 'button'; title.className = 'benchmark-case-link';
        title.addEventListener('click', () => {
          selectedMetric = row.selection; render();
          document.getElementById('benchmark-detail').scrollIntoView({block: 'start', behavior: 'auto'});
        });
      }
      caseCell.append(title);
      if (row.workload.theme) {
        const theme = document.createElement('small'); theme.textContent = row.workload.theme; caseCell.append(theme);
      }
      tr.append(caseCell);
      function cell(text, numeric = false) {
        const td = document.createElement('td'); td.textContent = text;
        if (numeric) td.className = 'num'; tr.append(td); return td;
      }
      cell(row.engine === 'unknown' ? 'Unknown' : name(row.engine));
      const timing = v => v == null ? '—' : format(v) + ' ' + row.metric.unit;
      cell(timing(row.current), true); cell(timing(row.target), true);
      const delta = cell(row.ratio == null ? '—' : signed((row.ratio - 1) * 100), true);
      if (row.ratio > 1 + row.workload.threshold && row.workload.threshold > 0) delta.classList.add('FAILED');
      const count = cell(String(row.samples), true);
      if (row.limited) {
        const note = document.createElement('small'); note.textContent = 'limited history'; count.append(note);
      }
      cell(statusLabels[row.status] || 'Unavailable');
      fragment.append(tr);
    }
    body.append(fragment);
    if (!body.children.length) {
      const tr = document.createElement('tr'), td = document.createElement('td'); td.colSpan = 7;
      td.textContent = 'No planned cases are available for this run.'; tr.append(td); body.append(tr);
    }
  };
})();
