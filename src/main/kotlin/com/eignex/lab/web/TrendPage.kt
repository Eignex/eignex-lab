package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val trendJson = Json { encodeDefaults = true }

/**
 * How a schedule's runs moved over its commits: solved and proven shares with the solved interval, PAR-2 time, and
 * solved share per suite, against commit or date. The runs ride in the page as JSON; a small script draws the charts.
 */
internal fun trendPage(
    name: String?,
    runs: List<TrendRun>,
    repoUrl: String,
    schedules: List<String> = emptyList(),
    /** Runs left out because they ran an earlier experiment than the schedule's current one. */
    earlier: Int = 0,
    all: Boolean = false,
    /** Finished runs whose points are still being computed in the background, left out until they are. */
    computing: Int = 0,
): Html = buildHtml {
    // Not live for the page-wide refresh, which refetches the whole page: the charts have no live parts, and a page whose
    // points are computing polls a small progress call instead ([trendPoll]).
    append(head("${name ?: "regression"} · eignex lab", live = false, tab = Tab.REGRESSION))
    if (name == null) {
        append(Html("<header><h1>Regression</h1></header><p class=\"muted\">No schedule yet: <code>lab schedule</code> sets one up, "))
        append(Html("and its runs chart here, one point per commit.</p></body></html>"))
        return@buildHtml
    }
    append(Html("<header><h1>Regression: ") + esc(name) + Html("</h1>"))
    if (schedules.size > 1) {
        append(Html("<div class=\"tools\">"))
        for (other in schedules) {
            append(Html("<a class=\"chip") + (if (other == name) " on" else "") + Html("\" href=\"/trend") + query("name" to other) + Html("\">") + esc(other) + Html("</a>"))
        }
        append(Html("</div>"))
    }
    append(Html("<p class=\"muted\">") + runs.size + Html(" runs · <a href=\"/") + query("name" to name) + Html("\">its jobs</a>"))
    if (computing > 0) append(Html(" · ") + computing + Html(" more being computed, shown as they are ready"))
    if (earlier > 0) {
        append(Html(" · ") + earlier + Html(" earlier runs of a different experiment left out: <a href=\"/trend") + query("name" to name, "all" to "1") + Html("\">show them</a>"))
    }
    val specs = runs.map { it.spec }.distinct().size
    if (runs.any { it.sourceChecked } && runs.any { !it.sourceChecked }) {
        append(Html(" · runs marked unchecked report the solver's own claims; checked ones asked for source-witness validation"))
    }
    if (all && specs > 1) {
        append(Html(" · ") + specs + Html(" experiments, each change marked by a dashed line: <a href=\"/trend") + query("name" to name) + Html("\">current only</a>"))
    }
    append(Html("</p></header>"))
    if (runs.isEmpty()) {
        append(Html("<p class=\"muted\">"))
        append(esc(when {
            computing > 0 -> "Computing ${if (all) "its runs" else "this experiment's runs"}; they appear here as they are ready."
            all -> "No run of this schedule has results yet."
            else -> "No run of this schedule's current experiment has results yet."
        }))
        append(Html("</p>") + trendPoll(runs.size, computing) + Html("</body></html>"))
        return@buildHtml
    }
    append(Html("<div class=\"tools\" role=\"group\" aria-label=\"x-axis\"><span class=\"muted\">x-axis</span>"))
    append(Html("<button class=\"chip on\" data-x=\"commit\">commit</button><button class=\"chip\" data-x=\"date\">date</button></div>"))
    append(Html("<h2>Solved and proven <small>problems out of those each run ran (a problem's seeds and repeats averaged); band: 95% interval of solved</small></h2>"))
    append(Html("<div class=\"legend\"><span><i style=\"background:var(--series-1)\"></i>solved</span>"))
    append(Html("<span><i style=\"background:var(--series-2)\"></i>proven</span>"))
    append(Html("<span><i class=\"hollow\"></i>run still going</span>"))
    append(Html("<span><i class=\"flag\"></i>disagrees with the reference</span></div>"))
    append(Html("<div class=\"chart\" id=\"chart-solved\"></div>"))
    append(Html("<h2>PAR-2 time <small>mean seconds per problem, an unsolved one charged twice its budget; lower is better</small></h2>"))
    append(Html("<div class=\"chart\" id=\"chart-par2\"></div>"))
    append(Html("<h2>Solved by theme <small>problems solved in each theme the sweep set was drawn by (a problem of several themes counts in each); each axis tops out at the theme's size</small></h2><div class=\"multiples\" id=\"suites\"></div>"))
    append(Html("<h2>Runs</h2><div class=\"scroll\"><table><tr><th>job</th><th>commit</th><th>when</th>"))
    append(Html("<th class=\"num\">problems</th><th class=\"num\">solved</th><th class=\"num\">proven</th>"))
    append(Html("<th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">disagreements</th>"))
    append(Html("<th class=\"num\" title=\"problems solved to a worse objective than the reference\">worse incumbent</th>"))
    append(Html("<th class=\"num\" title=\"against the run before: problems lost and gained, the sign test, and what reruns confirmed\">"))
    append(Html("vs previous</th>"))
    append(Html("<th class=\"num\">PAR-2 s</th></tr>"))
    for (run in runs.asReversed()) {
        val commit = commitLink(repoUrl, run.sha)
        append(Html("<tr><td><a href=\"/jobs/") + run.job + Html("\">") + run.job + Html("</a>") + (if (run.finished) "" else Html(" <small class=\"muted\">running</small>")))
        append(Html("") + trendWitness(run) + Html("</td>"))
        append(Html("<td><code>") + commit + Html("</code></td><td>") + ago(run.at) + Html("</td><td class=\"num\">") + run.problems + Html("</td>"))
        append(Html("<td class=\"num\">") + percent(run.solved.value) + Html(" <small class=\"muted\">") + percent(run.solved.low) + Html("–") + percent(run.solved.high) + Html("</small></td>"))
        append(Html("<td class=\"num\">") + percent(run.proven) + Html("</td><td class=\"num\">") + run.unsupported + Html("</td><td class=\"num\">") + run.errors + Html("</td>"))
        append(Html("<td class=\"num\">"))
        append(if (run.disagreements > 0) (Html("<a class=\"FAILED\" href=\"/jobs/") + run.job + Html("\">") + run.disagreements + Html("</a>")) else Html("0"))
        append(Html("</td>"))
        append(Html("<td class=\"num\"><a class=\"plain\" href=\"/jobs/") + run.job + Html("\">") + run.worse + Html("</a></td>"))
        append(Html("<td class=\"num\">") + flipCell(run) + Html("</td>"))
        append(Html("<td class=\"num\">") + ("%.2f".format(run.par2.value)) + Html("</td></tr>"))
    }
    append(Html("</table></div><div id=\"tip\" class=\"tip\" hidden></div>"))
    append(Html("<script type=\"application/json\" id=\"trend-data\">"))
    // Every field, defaults included: the script reads each run's fields without guarding for absent ones.
    append(Html(trendJson.encodeToString(runs).replace("</", "<\\/")))
    append(Html("</script>"))
    append(trendPoll(runs.size, computing))
    append(Html(SCRIPT))
    append(Html(TREND_SCRIPT))
    append(Html("</body></html>"))
}

/** Whether a trend run's solution claims were checked against their sources, as a small word with its meaning on hover. */
private fun trendWitness(run: TrendRun): Html =
    (if (run.sourceChecked) {
        Html(" <small class=\"DONE\" title=\"asked for source-witness validation: its solutions in checked formats are source-checked\">checked</small>")
    } else {
        Html(" <small class=\"muted\" title=\"no source-witness validation asked for: its solutions are the solver's own reports\">unchecked</small>")
    }) + (Html(" <small class=\"muted\" title=\"") + (esc(run.comparisonSeries.joinToString(", "))) + Html("\">")) +
        (if (run.comparisonSeries.isEmpty() || run.comparisonSeries == listOf("historical-unqualified"))
            "historical controls unqualified" else "controlled series") + Html("</small>")

/** A run against the one before it: lost and gained, the sign test when it is telling, and what reruns confirmed. */
private fun flipCell(run: TrendRun): Html = buildHtml {
    val lost = run.lost ?: return Html("–")
    val gained = run.gained ?: 0
    append(Html("feasibility −") + lost + Html(" +") + gained)
    append(Html("; proof −") + (run.proofLost ?: 0) + Html(" +") + (run.proofGained ?: 0))
    append(Html("; objective −") + (run.objectiveLost ?: 0) + Html(" +") + (run.objectiveGained ?: 0))
    run.flipP?.takeIf { it < FLIP_SIGNIFICANCE }?.let { append(Html(" <small>p=%.3f</small>".format(it))) }
    val job = run.confirmJob ?: return@buildHtml
    val confirmedLost = run.confirmedLost
    append(Html(" · <a href=\"/jobs/") + job + Html("\">"))
    append(
        if (confirmedLost == null) {
            Html("confirming")
        } else {
            val cls = if (confirmedLost > 0 || (run.confirmedProofLost ?: 0) > 0 || (run.confirmedObjectiveLost ?: 0) > 0) Html(" class=\"FAILED\"") else ""
            (Html("<span") + cls + Html(">confirmed feasibility −") + confirmedLost + Html(" +") + (run.confirmedGained ?: 0) + Html("; ")) +
                "proof −${run.confirmedProofLost ?: 0} +${run.confirmedProofGained ?: 0}; " +
                (Html("objective −") + (run.confirmedObjectiveLost ?: 0) + Html(" +") + (run.confirmedObjectiveGained ?: 0) + Html("</span>"))
        },
    )
    append(Html("</a>"))
}

/** Below this, a run's flips against the one before lean one way more than chance would. */
private const val FLIP_SIGNIFICANCE = 0.05

private fun percent(share: Double) = "%.1f%%".format(share * 100)

/** Draws the trend charts from the embedded runs, and redraws them when the x-axis or the width changes. */
private const val TREND_SCRIPT = """<script>
(function () {
  // A run still going is charted as projected: its shares so far, of the problems the last finished run of the same
  // problems ran, overall and per theme, so its counts sit beside the others' instead of dropping every line. Without
  // such a run to scale by, it is charted only once it ends. The table below lists it as it goes.
  const all = JSON.parse(document.getElementById('trend-data').textContent);
  const runs = all.map(function (r, i) {
    if (r.finished) return r;
    const base = all.slice(0, i).reverse().find(function (p) { return p.finished && p.spec === r.spec; });
    if (!base) return null;
    const themes = {};
    for (const t of Object.keys(base.themes || {})) {
      const own = (r.themes || {})[t];
      if (own) themes[t] = {problems: base.themes[t].problems, solved: own.solved};
    }
    return Object.assign({}, r, {problems: base.problems, themes: themes, projectedFrom: r.problems});
  }).filter(Boolean);
  const tip = document.getElementById('tip');
  const NS = 'http://www.w3.org/2000/svg';
  let xMode = 'commit';
  try { xMode = localStorage.getItem('trend-x') || 'commit'; } catch (e) {}
  // The small multiples are by theme, the groups the sweep set was drawn by.
  const themes = Array.from(new Set(runs.flatMap(function (r) { return Object.keys(r.themes || {}); }))).sort();

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
    const m = {l: 46, r: opts.endLabels ? 92 : 36, t: 10, b: 28};
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
      if (runs[order[k]].spec === runs[order[k - 1]].spec) continue;
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
      const pts = order.map(function (i) { const v = s.value(runs[i]); return v == null ? null : [x(i), yy(v), runs[i]]; }).filter(Boolean);
      if (pts.length > 1) {
        const d = pts.map(function (p, k) { return (k > 0 && pts[k - 1][2].spec === p[2].spec ? 'L' : 'M') + p[0] + ',' + p[1]; }).join('');
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
      if (opts.endLabels && pts.length) {
        const last = pts[pts.length - 1];
        el('text', {x: last[0] + 10, y: last[1] + 4, class: 'endlabel'}, svg).textContent = s.name + ' ' + y.fmt(s.value(last[2]));
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
      show(runs[i], evt, opts.suite);
    });
    hit.addEventListener('pointerleave', function () { cross.setAttribute('visibility', 'hidden'); tip.hidden = true; });
    hit.addEventListener('click', function (evt) { location.href = '/jobs/' + runs[nearest(evt)].job; });
    hit.style.cursor = 'pointer';
  }

  function show(r, evt, suite) {
    const rows = [
      ['commit', r.sha.slice(0, 9)], ['when', when(r.at) + (r.finished ? '' : ' (running)')], ['job', '#' + r.job],
      ['witnesses', r.sourceChecked ? 'source-checked' : 'unchecked solver reports'],
      ['comparison series', (r.comparisonSeries || ['historical-unqualified']).join(', ')],
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
    const group = suite && (r.themes || {})[suite];
    if (group) rows.unshift([suite, Math.round(group.solved * group.problems) + ' of ' + group.problems + ' (' + pct(group.solved) + ')']);
    tip.innerHTML = rows.map(function (kv) { return '<div><span>' + kv[0] + '</span><b>' + kv[1] + '</b></div>'; }).join('');
    tip.hidden = false;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    let left = evt.pageX + 14, top = evt.pageY - h - 10;
    if (left + w > window.scrollX + document.documentElement.clientWidth - 8) left = evt.pageX - w - 14;
    if (top < window.scrollY + 8) top = evt.pageY + 16;
    tip.style.left = left + 'px'; tip.style.top = top + 'px';
  }

  function render() {
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
      {height: 220, endLabels: false, title: 'PAR-2 seconds per run'});
    const grid = document.getElementById('suites');
    grid.innerHTML = '';
    for (const s of themes) {
      const cell = document.createElement('div');
      cell.className = 'multiple';
      cell.innerHTML = '<div class="mtitle">' + s + '</div>';
      const plot = document.createElement('div');
      cell.appendChild(plot);
      grid.appendChild(cell);
      const value = function (r) { const g = (r.themes || {})[s]; return g ? g.solved * g.problems : null; };
      const seen = runs.map(value).filter(function (v) { return v != null; });
      const size = Math.max.apply(null, runs.map(function (r) { const g = (r.themes || {})[s]; return g ? g.problems : 0; }));
      draw(plot, [{name: s, color: 'var(--series-1)', value: value}], rangeAxis(seen, size),
        {height: 130, endLabels: false, title: s + ' problems solved per run', suite: s,
          flagged: function (r) { return ((r.disagreementsByTheme || {})[s] || 0) > 0; }});
    }
  }

  document.querySelectorAll('[data-x]').forEach(function (b) {
    b.classList.toggle('on', b.dataset.x === xMode);
    b.addEventListener('click', function () {
      xMode = b.dataset.x;
      try { localStorage.setItem('trend-x', xMode); } catch (e) {}
      document.querySelectorAll('[data-x]').forEach(function (o) { o.classList.toggle('on', o === b); });
      render();
    });
  });
  let pending = null;
  window.addEventListener('resize', function () { clearTimeout(pending); pending = setTimeout(render, 120); });
  render();
})();
</script>"""

/**
 * While a trend page's points are still being computed ([trendPage]'s `computing`), it polls `/trend/progress` with its
 * own query and reloads once the server would show other than its [runs], so the points appear without the reader asking
 * again; it stops once nothing is computing. A page with nothing computing gets no script.
 */
private fun trendPoll(runs: Int, computing: Int): Html = if (computing == 0) Html("") else Html("""<script>
(function () {
  const poll = setInterval(async function () {
    if (document.hidden) return;
    try {
      const response = await fetch('/trend/progress' + location.search, {headers: {Accept: 'application/json'}});
      if (!response.ok) return;
      const progress = await response.json();
      if (progress.runs !== $runs) location.reload();
      else if (progress.computing === 0) clearInterval(poll);
    } catch (e) {}
  }, 5000);
})();
</script>""")
