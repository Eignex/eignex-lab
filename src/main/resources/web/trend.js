(function () {
  // A run still going is charted as projected: its shares so far, of the problems the last finished run of the same
  // problems ran, overall and per theme, so its counts sit beside the others' instead of dropping every line. Without
  // such a run to scale by, it is charted only once it ends. The table below lists it as it goes.
  const data = document.getElementById('trend-data');
  if (!data) return;
  const all = JSON.parse(data.textContent);
  const runs = all.map(function (r, i) {
    if (r.finished || r.benchmark) return r;
    const base = all.slice(0, i).reverse().find(function (p) { return p.finished && p.spec === r.spec; });
    if (!base) return null;
    const themes = {};
    for (const t of Object.keys(base.themes || {})) {
      const own = (r.themes || {})[t];
      if (own) themes[t] = {problems: base.themes[t].problems, solved: own.solved, proven: own.proven};
    }
    return Object.assign({}, r, {problems: base.problems, themes: themes, projectedFrom: r.problems});
  }).filter(Boolean);
  const tip = document.getElementById('tip');
  const NS = 'http://www.w3.org/2000/svg';
  let xMode = 'commit';
  try { xMode = localStorage.getItem('trend-x') === 'date' ? 'date' : 'commit'; } catch (e) {}
  // The small multiples are by theme, the groups the sweep set was drawn by.
  const themes = Array.from(new Set(runs.flatMap(function (r) { return Object.keys(r.themes || {}); }))).sort();
  const benchmarkMode = all.some(function (r) { return r.benchmark; });
  const metricKey = function (m) { return JSON.stringify([m.caseId, m.name, m.unit, m.direction]); };
  const metricSelect = document.getElementById('benchmark-metric');
  if (metricSelect) {
    const seen = new Map();
    all.forEach(function (r) { (r.benchmark?.metrics || []).forEach(function (m) { seen.set(metricKey(m), m); }); });
    for (const [key, metric] of seen) {
      const option = document.createElement('option');
      option.value = key;
      option.textContent = metric.caseId + ' · ' + metric.name + ' (' + metric.unit + ')';
      metricSelect.appendChild(option);
    }
    metricSelect.addEventListener('change', render);
  }
  const benchmarkGroups = new Map();
  all.forEach(function (r) {
    const workloads = r.benchmark?.workloads || [];
    workloads.forEach(function (w) {
      if (!w.theme) return;
      const metrics = (r.benchmark?.metrics || []).filter(function (m) { return m.caseId === w.caseId; });
      // A theme with only failures still needs a chart on which to flag them.
      if (!benchmarkGroups.has(w.theme)) benchmarkGroups.set(w.theme, new Map());
      metrics.forEach(function (m) {
        benchmarkGroups.get(w.theme).set(JSON.stringify([m.name, m.unit, m.direction]), m);
      });
    });
  });
  const groupWorkloads = function (r, theme) {
    return (r.benchmark?.workloads || []).filter(function (w) { return w.theme === theme; });
  };
  function groupValue(r, theme, metric) {
    const workloads = groupWorkloads(r, theme);
    if (!metric || !workloads.length || workloads.some(function (w) { return w.status !== 'success'; })) return null;
    const values = [];
    for (const id of new Set(workloads.map(function (w) { return w.caseId; }))) {
      const samples = (r.benchmark?.metrics || []).filter(function (m) {
        return m.caseId === id && m.name === metric.name && m.unit === metric.unit && m.direction === metric.direction;
      });
      if (!samples.length) return null;
      values.push(samples.reduce(function (sum, m) { return sum + m.value; }, 0) / samples.length);
    }
    return values.reduce(function (sum, value) { return sum + value; }, 0) / values.length;
  }
  function failures(r, theme, caseId) {
    return (r.benchmark?.workloads || []).filter(function (w) {
      return w.status === 'failed' && (!theme || w.theme === theme) && (!caseId || w.caseId === caseId);
    });
  }
  function lineKey(r) { return r.spec + (r.benchmark ? JSON.stringify(r.comparisonSeries) : ''); }

  function el(name, attrs, parent) {
    const node = document.createElementNS(NS, name);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(node);
    return node;
  }
  function pct(v) { return (v * 100).toFixed(1) + '%'; }
  function when(ms) {
    const d = new Date(ms);
    function two(n) { return String(n).padStart(2, '0'); }
    return two(d.getMonth() + 1) + '-' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
  }
  function niceMax(v) {
    if (!(v > 0)) return 1;
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
    return 10 * p;
  }

  // series: [{name, color, value(run), band(run)?, label?}]; y: {max, ticks, fmt}
  function draw(host, series, y, opts) {
    host.innerHTML = '';
    const W = Math.max(host.clientWidth, 240), H = opts.height;
    const endLabels = opts.endLabels && W >= 420;
    const labelWidth = Math.max(46, ...y.ticks.map(function (tick) { return y.fmt(tick).length * 6.5 + 10; }));
    const m = {l: labelWidth, r: endLabels ? 92 : 24, t: 10, b: 28};
    const pw = W - m.l - m.r, ph = H - m.t - m.b;
    const svg = el('svg', {width: W, height: H, viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': opts.title}, host);
    const times = runs.map(function (r) { return r.at; });
    const t0 = Math.min.apply(null, times), t1 = Math.max.apply(null, times);
    function x(i) {
      if (runs.length === 1) return m.l + pw / 2;
      if (xMode === 'date') return m.l + (t1 === t0 ? pw / 2 : (runs[i].at - t0) / (t1 - t0) * pw);
      return m.l + i / (runs.length - 1) * pw;
    }
    // Lines join runs left to right: by time on the date axis, where runs need not have ended in job order.
    const order = runs.map(function (r, i) { return i; });
    if (xMode === 'date') order.sort(function (a, b) { return runs[a].at - runs[b].at; });
    // An axis may start above zero ([min]), so a small chart's few problems of movement fill its height.
    const lo = y.min || 0;
    function yy(v) { return m.t + ph - (Math.max(lo, Math.min(v, y.max)) - lo) / ((y.max - lo) || 1) * ph; }
    // Dots shrink as runs crowd together, and lose their outline, which would cut the line into beads.
    const spacing = runs.length > 1 ? pw / (runs.length - 1) : pw;
    const dot = Math.max(1.5, Math.min(4, spacing / 3));
    const outline = spacing >= 10 ? 2 : 0;
    for (const t of y.ticks) {
      el('line', {x1: m.l, x2: m.l + pw, y1: yy(t), y2: yy(t), class: 'grid'}, svg);
      el('text', {x: m.l - 6, y: yy(t) + 4, 'text-anchor': 'end', class: 'tick'}, svg).textContent = y.fmt(t);
    }
    // A run of a different experiment than the one before it starts a new line, behind a dashed marker.
    for (let k = 1; k < order.length; k++) {
      if (lineKey(runs[order[k]]) === lineKey(runs[order[k - 1]])) continue;
      const bx = (x(order[k]) + x(order[k - 1])) / 2;
      el('line', {x1: bx, x2: bx, y1: m.t, y2: m.t + ph, stroke: 'var(--muted)', 'stroke-dasharray': '4 4'}, svg);
    }
    if (xMode === 'date') {
      const n = Math.max(2, Math.min(5, runs.length, Math.floor(pw / 110)));
      for (let k = 0; k < n; k++) {
        const t = n === 1 ? t0 : t0 + (t1 - t0) * k / (n - 1);
        const px = t1 === t0 ? m.l + pw / 2 : m.l + (t - t0) / (t1 - t0) * pw;
        el('text', {x: px, y: H - 8, 'text-anchor': 'middle', class: 'tick'}, svg).textContent = when(t);
      }
    } else {
      const every = Math.max(1, Math.ceil(runs.length / Math.max(1, Math.floor(pw / 64))));
      runs.forEach(function (r, i) {
        if (i % every === 0 || i === runs.length - 1) {
          el('text', {x: x(i), y: H - 8, 'text-anchor': 'middle', class: 'tick mono'}, svg).textContent = r.sha.slice(0, 7);
        }
      });
    }
    for (const s of series) {
      if (s.band) {
        const pts = order.map(function (i) { const b = s.band(runs[i]); return b ? [x(i), yy(b[0]), yy(b[1]), runs[i].spec] : null; }).filter(Boolean);
        const segments = [];
        for (const p of pts) {
          if (!segments.length || segments[segments.length - 1][0][3] !== p[3]) segments.push([]);
          segments[segments.length - 1].push(p);
        }
        for (const seg of segments.filter(function (g) { return g.length > 1; })) {
          const d = 'M' + seg.map(function (p) { return p[0] + ',' + p[2]; }).join('L') +
            'L' + seg.slice().reverse().map(function (p) { return p[0] + ',' + p[1]; }).join('L') + 'Z';
          el('path', {d: d, fill: s.color, 'fill-opacity': 0.1, stroke: 'none'}, svg);
        }
      }
      const pts = order.map(function (i, k) { const v = s.value(runs[i]); return v == null ? null : [x(i), yy(v), runs[i], k]; }).filter(Boolean);
      if (pts.length > 1) {
        const d = pts.map(function (p, k) { return (k > 0 && lineKey(pts[k - 1][2]) === lineKey(p[2]) &&
          (!benchmarkMode || pts[k - 1][3] === p[3] - 1) ? 'L' : 'M') + p[0] + ',' + p[1]; }).join('');
        el('path', {d: d, fill: 'none', stroke: s.color,
          'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'}, svg);
      }
      for (const p of pts) {
        el('circle', {cx: p[0], cy: p[1], r: dot, fill: p[2].finished ? s.color : 'var(--bg)',
          stroke: p[2].finished ? (outline ? 'var(--bg)' : 'none') : s.color, 'stroke-width': outline || 1}, svg);
      }
      // A run that contradicts the reference is ringed where it does: on the solved chart, and on the chart of the
      // suite its disagreeing problems are in, never on every chart.
      if (s === series[0] && opts.flagged) {
        for (const p of pts) {
          if (opts.flagged(p[2])) el('circle', {cx: p[0], cy: p[1], r: 8, fill: 'none', stroke: 'var(--bad)', 'stroke-width': 2}, svg);
        }
      }
      if (endLabels && pts.length) {
        const last = pts[pts.length - 1];
        el('text', {x: last[0] + 10, y: last[1] + 4, class: 'endlabel'}, svg).textContent = s.name + ' ' + y.fmt(s.value(last[2]));
      }
    }
    if (opts.failures) {
      for (const i of order) {
        const failed = opts.failures(runs[i]);
        if (!failed.length) continue;
        const value = series[0]?.value(runs[i]);
        const ring = el('circle', {cx: x(i), cy: yy(value == null ? 0 : value), r: 8, fill: 'none',
          stroke: 'var(--bad)', 'stroke-width': 2, class: 'benchmark-failure'}, svg);
        el('title', {}, ring).textContent = failed.length + ' failed: ' + failed.map(function (w) { return w.caseId; }).join(', ') +
          (value == null ? ' · timing unavailable' : '');
      }
    }
    const cross = el('line', {y1: m.t, y2: m.t + ph, class: 'cross', visibility: 'hidden'}, svg);
    const hit = el('rect', {x: m.l - 8, y: 0, width: pw + 16, height: H, fill: 'transparent'}, svg);
    function nearest(evt) {
      const box = svg.getBoundingClientRect();
      const px = evt.clientX - box.left;
      let best = 0;
      runs.forEach(function (r, i) { if (Math.abs(x(i) - px) < Math.abs(x(best) - px)) best = i; });
      return best;
    }
    hit.addEventListener('pointermove', function (evt) {
      const i = nearest(evt);
      cross.setAttribute('x1', x(i)); cross.setAttribute('x2', x(i)); cross.setAttribute('visibility', 'visible');
      show(runs[i], evt, opts);
    });
    hit.addEventListener('pointerleave', function () { cross.setAttribute('visibility', 'hidden'); tip.hidden = true; });
    hit.addEventListener('click', function (evt) { location.href = '/jobs/' + runs[nearest(evt)].job; });
    hit.style.cursor = 'pointer';
  }

  // A comparison series names hashes of 64 hex digits; their first 8 tell series apart, the label part reads as is.
  function shortSeries(series) {
    return String(series).split(':').map(function (part) { return /^[0-9a-f]{64}$/.test(part) ? part.slice(0, 8) : part; }).join(':');
  }

  function show(r, evt, opts) {
    const suite = opts.suite;
    const metric = (r.benchmark?.metrics || []).find(function (m) { return metricKey(m) === metricSelect?.value; });
    const rows = r.benchmark ? [
      ['commit', r.sha.slice(0, 9)], ['when', when(r.at)], ['job', '#' + r.job],
      ['successful', r.benchmark.succeeded], ['failed', r.benchmark.failed],
      ['skipped', r.benchmark.skipped], ['pending', r.benchmark.pending],
      ['selected metric', metric ? metric.value.toPrecision(5) + ' ' + metric.unit : 'unavailable'],
    ] : [
      ['commit', r.sha.slice(0, 9)], ['when', when(r.at) + (r.finished ? '' : ' (running)')], ['job', '#' + r.job],
      ['checked points', r.checkedPoints], ['native proof claims', r.nativeProofClaims],
      ['contradicted optima', r.contradictedOptima], ['unverified scope/check', r.unverifiedOptima],
      ['comparison series', (r.comparisonSeries || ['historical-unqualified']).map(shortSeries).join(', ')],
      ['solved', (r.solved.value * r.problems).toFixed(1) + ' of ' + r.problems + ' (' + pct(r.solved.value) + ')' +
        (r.projectedFrom != null ? ', projected from the ' + r.projectedFrom + ' done so far' : '')],
      ['proven', (r.proven * r.problems).toFixed(1) + ' (' + pct(r.proven) + ')'], ['PAR-2', r.par2.value.toFixed(2) + ' s'],
      ['unsupported', r.unsupported], ['errors', r.errors], ['disagreements', r.disagreements], ['worse incumbent', r.worse], ['problems', r.problems],
      ['vs previous', r.lost == null ? '–' : '−' + r.lost + ' +' + r.gained + (r.flipP != null ? ' (p=' + r.flipP.toFixed(3) + ')' : '')],
      ['proof vs previous', r.proofLost == null ? '–' : '−' + r.proofLost + ' +' + r.proofGained],
      ['objective vs previous', r.objectiveLost == null ? '–' : '−' + r.objectiveLost + ' +' + r.objectiveGained],
      ['confirmed proof', r.confirmedProofLost == null ? '–' : '−' + r.confirmedProofLost + ' +' + r.confirmedProofGained],
      ['confirmed objective', r.confirmedObjectiveLost == null ? '–' : '−' + r.confirmedObjectiveLost + ' +' + r.confirmedObjectiveGained],
      ['confirmed', r.confirmJob == null ? '–' : r.confirmedLost == null ? 'running' : '−' + r.confirmedLost + ' +' + r.confirmedGained],
    ];
    if (r.benchmark) {
      const failed = opts.failures ? opts.failures(r) : [];
      if (opts.benchmarkTheme) {
        const value = groupValue(r, opts.benchmarkTheme, opts.benchmarkMetric);
        rows.push(['theme', opts.benchmarkTheme], ['workloads', groupWorkloads(r, opts.benchmarkTheme).length],
          ['mean timing', value == null ? 'unavailable' : Number(value.toPrecision(5)) + ' ' + opts.benchmarkMetric.unit]);
        rows.splice(rows.findIndex(function (row) { return row[0] === 'selected metric'; }), 1);
      }
      if (failed.length) rows.push(['failed workloads', failed.map(function (w) { return w.caseId; }).join(', ')]);
    }
    const group = suite && (r.themes || {})[suite];
    if (group) {
      if (group.proven != null) rows.unshift([suite + ' proven', Math.round(group.proven * group.problems) + ' of ' + group.problems + ' (' + pct(group.proven) + ')']);
      rows.unshift([suite + ' solved', Math.round(group.solved * group.problems) + ' of ' + group.problems + ' (' + pct(group.solved) + ')']);
    }
    tip.replaceChildren();
    rows.forEach(function (kv) {
      const row = document.createElement('div'), label = document.createElement('span'), value = document.createElement('b');
      label.textContent = kv[0]; value.textContent = kv[1]; row.append(label, value); tip.appendChild(row);
    });
    tip.hidden = false;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    // Kept inside the window on both sides, so a label never ends up off screen.
    const minLeft = window.scrollX + 8, maxLeft = window.scrollX + document.documentElement.clientWidth - w - 8;
    let left = evt.pageX + 14, top = evt.pageY - h - 10;
    if (left > maxLeft) left = evt.pageX - w - 14;
    left = Math.max(minLeft, Math.min(left, maxLeft));
    if (top < window.scrollY + 8) top = evt.pageY + 16;
    tip.style.left = left + 'px'; tip.style.top = top + 'px';
  }

  function render() {
    if (benchmarkMode) {
      const selected = metricSelect.value;
      const metricOf = function (r) { return (r.benchmark?.metrics || []).find(function (m) { return metricKey(m) === selected; }); };
      const metrics = runs.map(metricOf).filter(Boolean);
      const host = document.getElementById('chart-benchmark-metric');
      if (metrics.length) {
        const top = niceMax(Math.max(...metrics.map(function (m) { return m.value; })));
        const unit = metrics[0].unit;
        draw(host, [{name: metrics[0].name, color: 'var(--series-1)', value: function (r) { return metricOf(r)?.value; }}],
          {max: top, ticks: [0, top / 2, top], fmt: function (v) { return Number(v.toPrecision(4)) + ' ' + unit; }},
          {height: 260, endLabels: false, title: metrics[0].caseId + ' ' + metrics[0].name + ' in ' + unit,
            failures: function (r) { return failures(r, null, metrics[0].caseId); }});
      } else {
        // Runs with no successful metrics must still expose their failures.
        draw(host, [], {max: 1, ticks: [], fmt: String},
          {height: 260, endLabels: false, title: 'No successful measurements yet', failures: function (r) { return failures(r); }});
      }
      const grid = document.getElementById('benchmark-themes');
      if (grid) {
        grid.replaceChildren();
        const cards = [];
        for (const [theme, metricsByType] of [...benchmarkGroups].sort(function (a, b) { return a[0].localeCompare(b[0]); })) {
          const types = metricsByType.size ? [...metricsByType.values()] : [null];
          for (const metric of types) {
            const card = document.createElement('div'), title = document.createElement('div'), plot = document.createElement('div');
            card.className = 'multiple'; title.className = 'mtitle';
            title.textContent = theme + (types.length > 1 ? ' · ' + metric.name + ' (' + metric.unit + ')' : '');
            card.append(title, plot); grid.appendChild(card); cards.push({theme, metric, plot});
          }
        }
        // Measure only once all cards are present: auto-fit columns resize as cards are added.
        for (const {theme, metric, plot} of cards) {
          const value = function (r) { return groupValue(r, theme, metric); };
          const values = runs.map(value).filter(function (v) { return v != null; });
          const top = niceMax(Math.max(0, ...values)), unit = metric?.unit || '';
          draw(plot, [{name: 'mean', color: 'var(--series-1)', value}],
            {max: top, ticks: values.length ? [0, top / 2, top] : [], fmt: function (v) { return Number(v.toPrecision(3)) + ' ' + unit; }},
            {height: 160, endLabels: false, title: theme + ' mean timing' + (unit ? ' in ' + unit : ' unavailable'),
              benchmarkTheme: theme, benchmarkMetric: metric, failures: function (r) { return failures(r, theme); }});
        }
      }
      return;
    }
    if (!runs.length) {
      for (const id of ['chart-solved', 'chart-par2', 'suites']) {
        document.getElementById(id).innerHTML = '<p class="muted small">Charts appear once a completed run provides a baseline.</p>';
      }
      return;
    }
    // Counts, not shares: a share of a handful of problems swings by a fifth on one flip, and hides how many moved.
    function countAxis(max) {
      const m = Math.max(1, max);
      const ticks = m <= 4 ? Array.from({length: m + 1}, function (_, i) { return i; })
        : [0, Math.round(m / 4), Math.round(m / 2), Math.round(3 * m / 4), m];
      return {max: m, ticks: Array.from(new Set(ticks)), fmt: function (v) { return String(Math.round(v)); }};
    }
    // A small chart's axis tops out at the theme's size, so it reads as solved out of how many, and starts a problem
    // below the fewest it ever solved, so a single flip still shows.
    function rangeAxis(values, size) {
      if (!values.length) return countAxis(size || 1);
      const max = Math.max(size, Math.ceil(Math.max.apply(null, values)));
      const min = Math.max(0, Math.min(max - 2, Math.floor(Math.min.apply(null, values)) - 1));
      const mid = Math.round((min + max) / 2);
      return {min: min, max: max, ticks: Array.from(new Set([min, mid, max])), fmt: function (v) { return String(Math.round(v)); }};
    }
    const most = Math.max.apply(null, runs.map(function (r) { return r.problems; }));
    draw(document.getElementById('chart-solved'), [
      {name: 'solved', color: 'var(--series-1)', value: function (r) { return r.solved.value * r.problems; },
        band: function (r) { return [r.solved.low * r.problems, r.solved.high * r.problems]; }},
      {name: 'proven', color: 'var(--series-2)', value: function (r) { return r.proven * r.problems; }},
    ], countAxis(most), {height: 260, endLabels: true, title: 'Problems solved and proven per run',
      flagged: function (r) { return r.disagreements > 0; }});
    const top = niceMax(Math.max.apply(null, runs.map(function (r) { return r.par2.high; })));
    draw(document.getElementById('chart-par2'), [
      {name: 'PAR-2', color: 'var(--series-1)', value: function (r) { return r.par2.value; },
        band: function (r) { return [r.par2.low, r.par2.high]; }},
    ], {max: top, ticks: [0, top / 4, top / 2, 3 * top / 4, top], fmt: function (v) { return (Math.round(v * 10) / 10) + 's'; }},
      {height: 260, endLabels: false, title: 'PAR-2 seconds per run'});
    const grid = document.getElementById('suites');
    grid.innerHTML = '';
    for (const s of themes) {
      const cell = document.createElement('div');
      cell.className = 'multiple';
      const title = document.createElement('div');
      title.className = 'mtitle';
      title.textContent = s;
      cell.appendChild(title);
      const plot = document.createElement('div');
      cell.appendChild(plot);
      grid.appendChild(cell);
    }
    // Measure only once all cards are present: auto-fit columns resize as cards are added.
    for (const [i, s] of themes.entries()) {
      const plot = grid.children[i].lastElementChild;
      const value = function (r) { const g = (r.themes || {})[s]; return g ? g.solved * g.problems : null; };
      const proven = function (r) { const g = (r.themes || {})[s]; return g && g.proven != null ? g.proven * g.problems : null; };
      const seen = runs.map(value).concat(runs.map(proven)).filter(function (v) { return v != null; });
      const size = Math.max.apply(null, runs.map(function (r) { const g = (r.themes || {})[s]; return g ? g.problems : 0; }));
      draw(plot, [{name: 'solved', color: 'var(--series-1)', value: value}, {name: 'proven', color: 'var(--series-2)', value: proven}],
        rangeAxis(seen, size),
        {height: 130, endLabels: false, title: s + ' problems solved and proven per run', suite: s,
          flagged: function (r) { return ((r.disagreementsByTheme || {})[s] || 0) > 0; }});
    }
  }

  document.querySelectorAll('[data-x]').forEach(function (b) {
    b.classList.toggle('on', b.dataset.x === xMode);
    b.setAttribute('aria-pressed', String(b.dataset.x === xMode));
    b.addEventListener('click', function () {
      xMode = b.dataset.x;
      try { localStorage.setItem('trend-x', xMode); } catch (e) {}
      document.querySelectorAll('[data-x]').forEach(function (o) {
        o.classList.toggle('on', o === b);
        o.setAttribute('aria-pressed', String(o === b));
      });
      render();
    });
  });
  let pending = null;
  window.addEventListener('resize', function () { clearTimeout(pending); pending = setTimeout(render, 120); });
  render();
})();
