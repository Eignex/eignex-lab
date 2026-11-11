const {test} = require('node:test');
const assert = require('node:assert/strict');
const H = require('../../main/resources/web/benchmark-history.js');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

// Exercise the renderer's data and chart choices without a browser or SVG layout.
function renderCharts(runs, koblas = true) {
  class Element {
    constructor(tag = 'div') {
      this.tag = tag; this.children = []; this.dataset = {}; this.style = {};
      this.classList = {toggle() {}, add() {}}; this.value = '';
    }
    set textContent(value) { this.value = String(value); this.children = []; }
    get textContent() { return this.value + this.children.map(child => child.textContent).join(''); }
    append(...children) {
      for (const child of children) this.children.push(...(child.tag === 'fragment' ? child.children : [child]));
    }
    replaceChildren(...children) { this.value = ''; this.children = []; this.append(...children); }
    setAttribute() {}
    addEventListener() {}
  }
  const elements = new Map(), plots = [];
  const get = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  get('table').dataset.koblas = String(koblas);
  const document = {
    getElementById: get, querySelector: () => get('table'), querySelectorAll: () => [],
    createElement: tag => new Element(tag), createElementNS: (_, tag) => new Element(tag),
    createDocumentFragment: () => new Element('fragment'),
    createTextNode: text => { const node = new Element('text'); node.textContent = text; return node; }
  };
  const window = {BenchmarkHistory: H};
  vm.runInNewContext(readFileSync(require.resolve('../../main/resources/web/benchmark-trend.js'), 'utf8'), {window, document});
  window.renderBenchmarkTrend({all: runs, runs, when: String, niceMax: value => value || 1,
    draw: (host, series, axis, options) => plots.push({host, series, axis, options})});
  return {get, plots};
}

function run(job, values = [[10, 1000000], [10, 1000000]], options = {}) {
  const engines = ['jvm-scalar', 'jvm-simd'];
  return {job, sha: String(job).repeat(40), spec: 'same', comparisonSeries: ['controlled'], finished: true,
    benchmark: {workloads: ['dot', 'gemm'].map(caseId => ({caseId, workload: caseId + '+64', theme: caseId,
      status: 'success', threshold: .15, controls: 'cpu-and-runtime'})),
    metrics: engines.flatMap((engine, e) => values[e].map((value, i) => ({caseId: i ? 'gemm' : 'dot',
      name: 'median_ns/' + engine, engine, direction: 'lower', unit: 'ns/op', value}))),
    transitions: [], ...options.benchmark}, ...options};
}

test('one tiny workload regression is visible despite a million nanosecond peer improving', () => {
  const before = run(1), after = run(2, [[12, 500000], [10, 1000000]]);
  const history = H.history([before, after]);
  assert.equal(history.point(after, 'jvm-scalar').count, 1);
  assert.equal(history.point(after, 'jvm-simd').count, 0);
  assert.equal(history.point(after, 'jvm-scalar', 'dot').count, 1);
  assert.equal(history.point(after, 'jvm-scalar', 'gemm').count, 0);
});

test('a fixed baseline catches gradual drift and counts each workload once', () => {
  const runs = [run(1), run(2, [[11, 1000000], [10, 1000000]]), run(3, [[12, 1000000], [10, 1000000]])];
  const history = H.history(runs);
  assert.equal(history.point(runs[2], 'jvm-scalar').count, 1);
  assert.equal(history.point(runs[2], 'jvm-scalar').baseline.job, 1);
});

test('missing failed pending and unknown controls leave gaps rather than recoveries', () => {
  const before = run(1);
  for (const change of ['missing', 'failed', 'pending', 'controls', 'running', 'instrumented']) {
    const after = run(2);
    if (change === 'missing') after.benchmark.metrics.pop();
    else if (change === 'controls') after.benchmark.workloads[1].controls = 'another-cpu';
    else if (change === 'instrumented') after.benchmark.workloads[1].instrumented = true;
    else if (change === 'running') after.finished = false;
    else after.benchmark.workloads[1].status = change;
    const history = H.history([before, after]);
    assert.equal(history.point(after, 'jvm-simd').count, null, change);
    assert.equal(history.point(after, 'jvm-simd').ratio, null, change);
    if (change === 'missing') assert.equal(history.point(after, 'jvm-scalar').count, 0);
  }
  const unqualified = run(1);
  unqualified.benchmark.workloads[0].controls = null;
  assert.equal(H.history([unqualified]).point(unqualified, 'jvm-simd'), null);
});

test('changed cases or measurement controls start a new fixed baseline', () => {
  const before = run(1), after = run(2, [[20, 2000000], [20, 2000000]], {spec: 'new'});
  const history = H.history([before, after]);
  assert.equal(history.point(after, 'jvm-simd').count, 0);
  assert.equal(history.point(after, 'jvm-simd').baseline.job, 2);
});

test('confirmations apply only to the matching metric and recorded baseline', () => {
  const before = run(1), after = run(2, [[13, 1000000], [13, 1000000]]);
  after.benchmark.baselineJob = 1;
  after.benchmark.transitions = [{caseId: 'dot', metric: 'median_ns/jvm-scalar', kind: 'regressed', confirmation: 'confirmed'}];
  const history = H.history([before, after]);
  assert.equal(history.point(after, 'jvm-scalar').changes[0].confirmation, 'confirmed');
  assert.equal(history.point(after, 'jvm-simd').changes[0].confirmation, 'candidate');
  const next = run(3, [[13, 1000000], [13, 1000000]]);
  next.benchmark.baselineJob = 2;
  next.benchmark.transitions = after.benchmark.transitions;
  assert.equal(H.history([before, after, next]).point(next, 'jvm-scalar').changes[0].confirmation, 'candidate');
});

test('unsupported engines are absent from coverage and absolute selections join exact engines', () => {
  const before = run(1), after = run(2);
  for (const r of [before, after]) r.benchmark.metrics = r.benchmark.metrics.filter(m => m.caseId !== 'dot' || m.engine !== 'jvm-simd');
  const history = H.history([before, after]);
  assert.equal(history.point(after, 'jvm-simd').coverage, 1);
  assert.equal(history.point(after, 'jvm-scalar').coverage, 2);
  const metrics = before.benchmark.metrics.filter(m => m.caseId === 'gemm');
  assert.equal(H.selection(before, metrics[0]), H.selection(before, metrics[1]));
  assert.notEqual(H.key(metrics[0]), H.key(metrics[1]));
});

const scalarDot = rows => rows.find(r => r.workload.caseId === 'dot' && r.engine === 'jvm-scalar');

test('case targets use the fast historical percentile without current or future measurements', () => {
  const runs = [run(1), run(2, [[20, 1000000], [40, 1000000]]),
    run(3, [[30, 1000000], [50, 1000000]]), run(4, [[1, 1000000], [1, 1000000]])];
  const rows = H.caseComparisons(runs, runs[2]), dot = scalarDot(rows);

  assert.equal(rows.length, 4);
  assert.equal(dot.current, 30);
  assert.equal(dot.target, 10.5);
  assert.equal(dot.samples, 2);
  assert.equal(dot.limited, true);
  assert.equal(dot.ratio, 30 / 10.5);
  assert.equal(rows.find(r => r.workload.caseId === 'dot' && r.engine === 'jvm-simd').target, 11.5);
  assert.equal(scalarDot(H.caseComparisons(runs, runs[2], 'best')).target, 10);
  assert.ok(H.caseComparisons(runs, runs[0]).every(r => r.target === null && r.samples === 0));
});

test('historical targets exclude changed controls units directions and invalid case results', () => {
  for (const change of ['controls', 'unit', 'direction', 'failed', 'skipped', 'pending', 'instrumented', 'running', 'zero', 'series']) {
    const before = run(1), selected = run(2);
    if (change === 'controls') before.benchmark.workloads[0].controls = 'other';
    else if (change === 'unit') before.benchmark.metrics[0].unit = 'seconds';
    else if (change === 'direction') before.benchmark.metrics[0].direction = 'higher';
    else if (change === 'instrumented') before.benchmark.workloads[0].instrumented = true;
    else if (change === 'running') before.finished = false;
    else if (change === 'zero') before.benchmark.metrics[0].value = 0;
    else if (change === 'series') before.spec = 'old';
    else before.benchmark.workloads[0].status = change;

    const rows = H.caseComparisons([before, selected], selected), dot = scalarDot(rows);

    assert.equal(rows.length, 4, change);
    assert.equal(dot.target, null, change);
    assert.equal(dot.samples, 0, change);
  }
});

test('the case table retains failed pending and unsupported cases without invented timings', () => {
  const before = run(1), selected = run(2);
  selected.benchmark.workloads[0].status = 'failed';
  selected.benchmark.workloads[1].engines = {'jvm-simd': 'unsupported'};
  selected.benchmark.metrics = selected.benchmark.metrics.filter(m => m.caseId !== 'dot' && m.engine !== 'jvm-simd');

  const rows = H.caseComparisons([before, selected], selected);

  assert.equal(rows.length, 4);
  assert.equal(scalarDot(rows).status, 'failed');
  assert.equal(scalarDot(rows).current, null);
  assert.equal(scalarDot(rows).target, 10);
  assert.equal(scalarDot(rows).ratio, null);
  assert.equal(rows.find(r => r.workload.caseId === 'gemm' && r.engine === 'jvm-simd').status, 'unsupported');
  selected.benchmark.workloads[0].status = 'pending';
  assert.equal(scalarDot(H.caseComparisons([before, selected], selected)).status, 'pending');
  assert.equal(H.caseComparisons([selected], selected).filter(r => r.workload.caseId === 'dot').length, 2);
});

test('fast throughput targets use the upper percentile and the largest observed value', () => {
  const runs = [run(1), run(2, [[20, 1000000], [20, 1000000]]), run(3)];
  for (const r of runs) for (const m of r.benchmark.metrics) {
    m.direction = 'higher'; m.unit = 'op/s'; m.name = 'throughput/' + m.engine;
  }

  const dot = scalarDot(H.caseComparisons(runs, runs[2]));

  assert.equal(dot.target, 19.5);
  assert.equal(dot.ratio, 1.95);
  assert.equal(scalarDot(H.caseComparisons(runs, runs[2], 'best')).target, 20);
});

test('twenty historical run medians remove the limited history label', () => {
  const runs = Array.from({length: 21}, (_, i) => run(i + 1, [[i + 1, 1000000], [10, 1000000]]));

  const dot = scalarDot(H.caseComparisons(runs, runs[20]));

  assert.equal(dot.samples, 20);
  assert.equal(dot.limited, false);
  assert.ok(Math.abs(dot.target - 1.95) < 1e-12);
});


test('relative performance weights workloads equally regardless of timing unit or number of metrics', () => {
  const before = run(1), after = run(2, [[20, 500000], [10, 1000000]]);
  // One workload doubles and one halves; the geometric mean is unchanged.
  // A second metric for dot must not give it twice the weight of gemm.
  for (const r of [before, after]) {
    r.benchmark.metrics.push({...r.benchmark.metrics[0], name: 'extra/jvm-scalar'});
  }
  const history = H.history([before, after]);

  assert.equal(history.point(after, 'jvm-scalar').ratio, 1);
  assert.equal(history.point(after, 'jvm-scalar', 'dot').ratio, 2);
  assert.equal(history.point(after, 'jvm-scalar', 'gemm').ratio, .5);
  assert.equal(history.point(after, 'jvm-scalar').count, 1);
});

test('relative performance reverses throughput and resets at a new baseline', () => {
  const before = run(1), after = run(2, [[20, 2000000], [10, 1000000]]);
  for (const r of [before, after]) for (const m of r.benchmark.metrics) m.direction = 'higher';
  const next = run(3, [[100, 2000000], [10, 1000000]], {spec: 'new'});
  const history = H.history([before, after, next]);

  assert.equal(history.point(after, 'jvm-scalar').ratio, .5);
  assert.equal(history.point(next, 'jvm-scalar').ratio, 1);
});


const columns = [{engine: 'jvm-simd'}, {engine: 'accelerate-jvm'}, {engine: 'jvm-scalar'}];
function threeEngines(job, simd, accelerate, portable) {
  const r = run(job, [[portable, 1000000], [simd, 1000000]]);
  r.benchmark.metrics.push({caseId: 'dot', name: 'median_ns/accelerate-jvm', engine: 'accelerate-jvm',
    unit: 'ns/op', direction: 'lower', value: accelerate});
  return r;
}

test('Koblas overview and themes use only JVM SIMD for baselines scales summaries and tooltips', () => {
  const before = threeEngines(1, 10, 100, 1000), after = threeEngines(2, 12, 10000, 100000);
  for (const r of [before, after]) {
    // Neither an invalid metric nor a failed workload from another engine may block the SIMD baseline.
    r.benchmark.metrics.push({caseId: 'dot', name: 'extra/jvm-scalar', engine: 'jvm-scalar',
      value: 0, unit: 'ns/op', direction: 'lower'});
    r.benchmark.workloads.push({caseId: 'portable-only', workload: 'portable-only', theme: 'dot',
      status: 'failed', engines: {'jvm-scalar': 'failed'}});
    r.benchmark.failed = 1;
  }
  after.benchmark.transitions = ['jvm-simd', 'jvm-scalar', 'accelerate-jvm'].map(engine =>
    ({caseId: 'dot', metric: 'median_ns/' + engine, confirmation: 'confirmed'}));

  const {get, plots} = renderCharts([before, after]);

  assert.equal(get('benchmark-engines').textContent, 'JVM SIMD');
  assert.equal(plots.length, 4);
  for (const plot of plots) {
    assert.deepEqual(Array.from(plot.series, series => series.name), ['JVM SIMD']);
    assert.ok(plot.axis.max < 2);
    assert.equal(plot.options.failures(after).length, 0);
    const rows = plot.options.rows(after);
    assert.ok(rows.some(([label]) => label === 'JVM SIMD'));
    assert.ok(!rows.some(([label]) => ['Portable JVM', 'Accelerate', 'failed captures'].includes(label)));
    assert.equal(rows.find(([label]) => label === 'paired vs previous')[1], '1 confirmed changes');
  }
  assert.equal(plots[0].series[0].value(after), 1);
  assert.ok(Math.abs(plots[1].series[0].value(after) - Math.sqrt(1.2)) < 1e-12);
  for (const id of ['benchmark-regression-summary', 'benchmark-performance-summary']) {
    assert.equal(get(id).children.length, 1);
    assert.match(get(id).textContent, /^JVM SIMD/);
  }
  assert.match(get('benchmark-case-head').textContent, /JVM SIMD.*Accelerate.*Portable/);
  assert.match(get('benchmark-case-rows').textContent, /10,000 ns\/op/);
});

test('default-only captures leave SIMD charts unavailable while preserving their table timings', () => {
  const selected = run(1);
  selected.benchmark.metrics = selected.benchmark.metrics.filter(m => m.engine === 'jvm-scalar')
    .map(m => ({...m, engine: 'jvm-default', name: 'median_ns'}));

  const {get, plots} = renderCharts([selected]);

  assert.ok(plots.every(plot => plot.series.length === 1 && plot.series[0].value(selected) == null));
  assert.equal(get('benchmark-engines').textContent, 'JVM SIMD');
  assert.match(get('benchmark-performance-summary').textContent, /No comparable baseline/);
  for (const theme of get('benchmark-themes').children) assert.match(theme.textContent, /JVM SIMDNo comparable baseline/);
  assert.match(get('benchmark-case-head').textContent, /JVM default/);
  assert.match(get('benchmark-case-rows').textContent, /10 ns\/op/);
});

test('other repositories keep their engine chart series', () => {
  const {get, plots} = renderCharts([threeEngines(1, 10, 100, 1000)], false);

  assert.equal(plots[0].series.length, 3);
  assert.match(get('benchmark-engines').textContent, /Portable JVM.*JVM SIMD.*accelerate-jvm/);
});

test('a single run shows current timings without historical targets or slowdowns', () => {
  for (const finished of [true, false]) {
    const selected = threeEngines(1, 10, 100, 1000);
    selected.finished = finished;

    const dot = H.caseTable([selected], selected, columns).find(row => row.workload.caseId === 'dot');

    assert.deepEqual(dot.cells.map(cell => cell.current), [10, 100, 1000]);
    assert.ok(dot.cells.every(cell => cell.status === 'measured' && cell.target === null &&
      cell.ratio === null && cell.samples === 0));
  }
});

test('a single default engine capture is visible in its own column', () => {
  const selected = run(1);
  selected.benchmark.metrics = selected.benchmark.metrics.filter(m => m.engine === 'jvm-scalar')
    .map(m => ({...m, name: 'median_ns', engine: 'jvm-default'}));

  const rows = H.caseTable([selected], selected, [...columns, {engine: 'jvm-default'}]);

  assert.deepEqual(rows.map(row => row.cells[3].current), [10, 1000000]);
  assert.ok(rows.every(row => row.cells[3].status === 'measured' && row.cells[3].target === null &&
    row.cells[3].ratio === null && row.cells[3].samples === 0));
  assert.ok(rows.every(row => row.cells.slice(0, 3).every(cell => cell.current === null)));
});

test('three engine columns each use their own earlier history and preserve distinct slowdowns', () => {
  const runs = [threeEngines(1, 10, 100, 1000), threeEngines(2, 20, 200, 2000),
    threeEngines(3, 30, 50, 1300), threeEngines(4, 1, 1, 1)];

  const rows = H.caseTable(runs, runs[2], columns), dot = rows.find(r => r.workload.caseId === 'dot');

  assert.equal(rows.length, 2);
  assert.deepEqual(dot.cells.map(c => c.engine), ['jvm-simd', 'accelerate-jvm', 'jvm-scalar']);
  assert.deepEqual(dot.cells.map(c => c.target), [10.5, 105, 1050]);
  assert.deepEqual(dot.cells.map(c => c.current), [30, 50, 1300]);
  assert.deepEqual(dot.cells.map(c => c.samples), [2, 2, 2]);
  assert.deepEqual(dot.cells.map(c => c.ratio), [30 / 10.5, 50 / 105, 1300 / 1050]);
  const best = H.caseTable(runs, runs[2], columns, 'best').find(r => r.workload.caseId === 'dot');
  assert.deepEqual(best.cells.map(c => c.target), [10, 100, 1000]);
});

test('default engine provider attribution never fills an exact engine column', () => {
  const r = run(1);
  r.benchmark.metrics = r.benchmark.metrics.map(m => ({...m, name: 'median_ns', engine: 'jvm-default', provider: m.engine}));

  const rows = H.caseTable([r], r, columns);

  assert.equal(rows.length, 2);
  assert.ok(rows.every(row => row.cells.length === 3 && row.cells.every(c =>
    c.current === null && c.target === null && c.ratio === null && c.samples === 0 && c.status === 'unavailable')));
});

test('an unavailable engine keeps its own historical target without borrowing a current timing', () => {
  const before = threeEngines(1, 10, 100, 1000), selected = threeEngines(2, 20, 200, 2000);
  selected.benchmark.metrics = selected.benchmark.metrics.filter(m => m.engine !== 'accelerate-jvm');
  selected.benchmark.workloads[0].engines = {'accelerate-jvm': 'unsupported'};

  const dot = H.caseTable([before, selected], selected, columns).find(r => r.workload.caseId === 'dot');

  assert.equal(dot.cells[1].current, null);
  assert.equal(dot.cells[1].target, 100);
  assert.equal(dot.cells[1].ratio, null);
  assert.equal(dot.cells[1].status, 'unsupported');
  assert.deepEqual(dot.cells.map(c => c.samples), [1, 1, 1]);
  assert.equal(dot.cells[0].current, 20);
  assert.equal(dot.cells[2].current, 2000);
});

test('different metric units remain separate rows within the engine comparison table', () => {
  const r = threeEngines(1, 10, 100, 1000);
  r.benchmark.metrics.find(m => m.engine === 'accelerate-jvm').unit = 'seconds';

  const rows = H.caseTable([r], r, columns).filter(r => r.workload.caseId === 'dot');

  assert.equal(rows.length, 2);
  assert.ok(rows.every(row => new Set(row.cells.filter(c => c.current != null).map(c => c.metric.unit)).size === 1));
});


function tableRow(id, ratios) {
  return {workload: {caseId: id, workload: id}, identity: null,
    cells: columns.map((column, i) => ({engine: column.engine, ratio: ratios[i] ?? null}))};
}
const caseNames = rows => rows.map(r => r.workload.caseId);

test('default sort finds the worst own history slowdown in any engine and sorts numeric changes', () => {
  const rows = [tableRow('dot+64', [1.9, 1, 1]), tableRow('dot+128', [1, 1, 1.25]),
    tableRow('dot+32', [1, 11, 1]), tableRow('dot+8', [.8, .7, .9]), tableRow('dot+16', [null, null, null])];

  assert.deepEqual(caseNames(H.sortCases(rows)), ['dot+32', 'dot+64', 'dot+128', 'dot+8', 'dot+16']);
  assert.deepEqual(caseNames(rows), ['dot+64', 'dot+128', 'dot+32', 'dot+8', 'dot+16']);
});

test('each engine header sorts only its own historical changes in either direction with missing last', () => {
  const rows = [tableRow('dot+64', [3, 1, .8]), tableRow('dot+128', [1, 3, 1]), tableRow('dot+32', [.8, .8, 3]),
    tableRow('dot+8', [null, null, null]), tableRow('dot+16', [2, null, 1.5])];
  const descending = [
    ['dot+64', 'dot+16', 'dot+128', 'dot+32', 'dot+8'],
    ['dot+128', 'dot+64', 'dot+32', 'dot+8', 'dot+16'],
    ['dot+32', 'dot+16', 'dot+128', 'dot+64', 'dot+8']];
  const ascending = [
    ['dot+32', 'dot+128', 'dot+16', 'dot+64', 'dot+8'],
    ['dot+32', 'dot+64', 'dot+128', 'dot+8', 'dot+16'],
    ['dot+64', 'dot+128', 'dot+16', 'dot+32', 'dot+8']];

  columns.forEach((column, i) => {
    assert.deepEqual(caseNames(H.sortCases(rows, {key: 'engine:' + column.engine, direction: 'desc'})), descending[i]);
    assert.deepEqual(caseNames(H.sortCases(rows, {key: 'engine:' + column.engine, direction: 'asc'})), ascending[i]);
  });
});

test('equal and unavailable comparisons use stable natural case ordering', () => {
  const rows = ['dot+4096', 'dot+128', 'dot+8', 'dot+64', 'dot+16'].map(id => tableRow(id, [1, 1, 1]));
  const expected = ['dot+8', 'dot+16', 'dot+64', 'dot+128', 'dot+4096'];

  assert.deepEqual(caseNames(H.sortCases(rows)), expected);
  assert.deepEqual(caseNames(H.sortCases([...rows].reverse())), expected);
  assert.deepEqual(caseNames(H.sortCases(rows, {key: 'case', direction: 'asc'})), expected);
  assert.deepEqual(caseNames(H.sortCases(rows, {key: 'case', direction: 'desc'})), [...expected].reverse());
  for (const row of rows) row.cells.forEach(c => {c.ratio = null;});
  assert.deepEqual(caseNames(H.sortCases(rows)), expected);
});

test('throughput sorting ranks a decrease as a slowdown against that engines own history', () => {
  const before = threeEngines(1, 10, 100, 1000), selected = threeEngines(2, 5, 200, 2000);
  for (const r of [before, selected]) for (const m of r.benchmark.metrics) m.direction = 'higher';

  const descending = H.caseTable([before, selected], selected, columns, 'p05', {key: 'engine:jvm-simd', direction: 'desc'});
  const ascending = H.caseTable([before, selected], selected, columns, 'p05', {key: 'engine:jvm-simd', direction: 'asc'});

  assert.deepEqual(caseNames(descending), ['dot', 'gemm']);
  assert.deepEqual(caseNames(ascending), ['gemm', 'dot']);
  assert.equal(descending[0].cells[0].ratio, 2);
});
