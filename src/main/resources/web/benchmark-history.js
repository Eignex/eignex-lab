(function (root) {
  'use strict';
  const engine = m => m.engine || m.name;
  const key = m => JSON.stringify([m.caseId, m.name, m.unit, m.direction, engine(m)]);
  const segment = r => JSON.stringify([r.spec, r.comparisonSeries || []]);
  const workload = (r, id) => (r.benchmark?.workloads || []).find(w => w.caseId === id);
  const valid = m => Number.isFinite(m.value) && m.value > 0 && ['lower', 'higher'].includes(m.direction);
  const slowdown = (before, after) => before.direction === 'higher' ? before.value / after.value : after.value / before.value;
  const kind = m => m.engine && m.name.endsWith('/' + m.engine) ? m.name.slice(0, -m.engine.length - 1) : m.name;
  const selection = (r, m) => JSON.stringify([workload(r, m.caseId)?.workload || m.caseId, kind(m), m.unit, m.direction]);

  /** Targets use earlier comparable run medians, never the selected measurement or a future run. */
  function caseComparisons(runs, selected, method = 'p05') {
    const index = runs.findIndex(r => r.job === selected.job);
    if (index < 0) return [];
    const planned = selected.benchmark?.workloads || [], currentMetrics = selected.benchmark?.metrics || [];
    const currentWorkloads = new Map(planned.map(w => [w.caseId, w]));
    const descriptors = new Map(), samples = new Map(), current = new Map(currentMetrics.map(m => [key(m), m]));
    function remember(m) {
      if (!descriptors.has(m.caseId)) descriptors.set(m.caseId, new Map());
      descriptors.get(m.caseId).set(JSON.stringify([m.name, engine(m)]), m);
    }
    for (const r of runs.slice(0, index)) {
      if (segment(r) !== segment(selected)) continue;
      const previousWorkloads = new Map((r.benchmark?.workloads || []).map(w => [w.caseId, w]));
      for (const m of r.benchmark?.metrics || []) {
        const w = previousWorkloads.get(m.caseId), own = currentWorkloads.get(m.caseId);
        if (!own) continue;
        remember(m);
        if (!r.finished || !valid(m) || w?.status !== 'success' || w.instrumented || !w.controls || w.controls !== own.controls) continue;
        const id = key(m);
        if (!samples.has(id)) samples.set(id, []);
        samples.get(id).push(m.value);
      }
    }
    currentMetrics.forEach(remember);
    const expected = new Set(currentMetrics.map(engine));
    planned.forEach(w => Object.keys(w.engines || {}).forEach(e => expected.add(e)));
    const rows = [];
    for (const w of planned) {
      const metrics = [...(descriptors.get(w.caseId)?.values() || [])];
      for (const e of Object.keys(w.engines || {})) {
        if (!metrics.some(m => engine(m) === e)) metrics.push({caseId: w.caseId, engine: e});
      }
      if (!metrics.length) for (const e of expected.size ? expected : ['unknown']) metrics.push({caseId: w.caseId, engine: e});
      for (const m of metrics) {
        const measured = current.get(key(m)), values = [...(samples.get(key(m)) || [])].sort((a, b) => a - b);
        let target = null;
        if (values.length) {
          const q = m.direction === 'higher' ? .95 : .05, position = (values.length - 1) * q;
          target = method === 'best' ? values[m.direction === 'higher' ? values.length - 1 : 0] :
            values[Math.floor(position)] + (values[Math.ceil(position)] - values[Math.floor(position)]) * (position % 1);
        }
        const status = w.status !== 'success' ? w.status : w.engines?.[engine(m)] === 'unsupported' ? 'unsupported' :
          w.instrumented ? 'instrumented' : measured ? 'measured' : 'unavailable';
        rows.push({workload: w, metric: m, engine: engine(m), current: measured?.value ?? null, target,
          ratio: status === 'measured' && valid(measured) && target > 0 ? slowdown({...m, value: target}, measured) : null,
          samples: values.length, limited: method !== 'best' && values.length > 0 && values.length < 20, status,
          selection: m.name ? selection(selected, m) : null});
      }
    }
    return rows.sort((a, b) => (b.ratio ?? -Infinity) - (a.ratio ?? -Infinity) ||
      (a.workload.workload || a.workload.caseId).localeCompare(b.workload.workload || b.workload.caseId) || a.engine.localeCompare(b.engine));
  }

  /** Fixed baselines contain complete workload sets; missing rows never count as recoveries. */
  function history(runs) {
    const baselines = new Map(), points = new Map();
    for (const r of runs) {
      const b = r.benchmark;
      if (!b) continue;
      const id = segment(r);
      if (!baselines.has(id) && r.finished && b.workloads?.length &&
          b.workloads.every(w => w.status === 'success' && !w.instrumented && w.controls && w.threshold > 0 && w.threshold < 1) &&
          b.metrics.length && b.metrics.every(valid)) baselines.set(id, r);
      const base = baselines.get(id);
      const byEngine = new Map();
      if (base) {
        const current = new Map(b.metrics.map(m => [key(m), m]));
        for (const before of base.benchmark.metrics.filter(valid)) {
          const name = engine(before);
          if (!byEngine.has(name)) byEngine.set(name, {baseline: base, expected: new Set(), missing: new Set(), ratios: new Map(), changes: []});
          const point = byEngine.get(name), w = workload(r, before.caseId), bw = workload(base, before.caseId);
          point.expected.add(before.caseId);
          const after = current.get(key(before));
          if (!r.finished || !after || !valid(after) || w?.status !== 'success' || w.instrumented || !w.controls || w.controls !== bw.controls) {
            point.missing.add(before.caseId);
            continue;
          }
          const ratio = slowdown(before, after);
          if (!point.ratios.has(before.caseId)) point.ratios.set(before.caseId, []);
          point.ratios.get(before.caseId).push(Math.log(ratio));
          if (ratio > 1 + bw.threshold) {
            // Pair confirmations belong to their recorded before/after revisions, never to an unrelated fixed baseline.
            const event = b.baselineJob === base.job && (b.transitions || []).find(t =>
              t.caseId === after.caseId && t.metric === after.name && t.kind === 'regressed');
            point.changes.push({before, after, ratio, workload: w, confirmation: event?.confirmation || 'candidate'});
          }
        }
      }
      points.set(r.job, byEngine);
    }
    return {
      point(r, name, theme) {
        const p = points.get(r.job)?.get(name);
        if (!p) return null;
        const ids = [...p.expected].filter(id => !theme || workload(p.baseline, id)?.theme === theme);
        if (!ids.length) return null;
        const missing = ids.filter(id => p.missing.has(id));
        const changes = p.changes.filter(c => ids.includes(c.after.caseId));
        // Average within a workload first so additional metrics cannot increase its weight.
        const logs = ids.map(id => p.ratios.get(id)).filter(Boolean).map(values => values.reduce((a, b) => a + b, 0) / values.length);
        const ratio = missing.length || logs.length !== ids.length ? null : Math.exp(logs.reduce((a, b) => a + b, 0) / ids.length);
        return {ratio, baseline: p.baseline, coverage: ids.length, missing: missing.length,
          count: missing.length ? null : new Set(changes.map(c => c.after.caseId)).size, changes};
      },
      changes(r, theme) {
        return [...(points.get(r.job)?.values() || [])].flatMap(p => p.changes)
          .filter(c => !theme || c.workload.theme === theme).sort((a, b) => b.ratio - a.ratio);
      }
    };
  }
  const api = {engine, key, segment, selection, workload, slowdown, history, caseComparisons};
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.BenchmarkHistory = api;
})(typeof window === 'object' ? window : globalThis);
