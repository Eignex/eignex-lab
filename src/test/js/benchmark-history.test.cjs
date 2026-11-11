const {test} = require('node:test');
const assert = require('node:assert/strict');
const H = require('../../main/resources/web/benchmark-history.js');

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
