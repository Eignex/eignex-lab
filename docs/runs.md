# Usage

[Setup](../deploy/SETUP.md) · [Complete command list](../deploy/lab)

## Experiments

Submit one experiment per question. Save this as `experiment.json`:

```json
{
  "name": "restarts-ab",
  "description": "Compare restart policies",
  "problems": {"suite": "xcsp3-cop", "max": "20"},
  "base": {"ref": "main", "engine": "cp", "timeout": "30000"},
  "grid": {"param.restarts": ["luby", "geometric"]}
}
```

```sh
deploy/lab run experiment.json
deploy/lab show 7
deploy/lab cases 7
deploy/lab fetch 7
```

Replace `7` with the returned ID. `base` supplies shared arm values; `configs` overrides them and `grid` crosses
combinations. Timeouts are milliseconds. The lab queues and places work automatically.

## Koblas and profiles

Only committed code in Eignex repositories runs, from `main` or trusted branches. Profiles default to ten minutes,
capped at two hours; `--timeout` uses seconds. Scripts own their instrumentation; JVM mains record JFR.

```sh
deploy/lab koblas main "Dot smoke capture" --smoke --operation dot

deploy/lab profile --repo Eignex/eignex-lab --build installDist \
  --classpath 'build/install/eignex-lab/lib/*' --timeout 600 \
  com.eignex.lab.MainKt main "Startup capture" -- check
```

## Kumulant captures

Kumulant defines its fixtures and case catalogue in code. Submit its committed
`kumulant-bench/lab/smoke.json` (or `native-smoke.json` for Linux x86_64) with
`deploy/lab run`. The profile script owns the build, correctness checks and timing;
Lab supplies the checkout, placement and retained `$OUT/report` directory. Keep
Lab recording off (`measurement: "none"`); use the capture command's `--allocation`
option for allocation measurements. Select a standard or extended preset before
collecting a performance baseline.

A job with schema-1 `manifest.json` and `results.json` under `$OUT/report` shows a
**benchmark capture** link. `/jobs/7/capture` shows status, backend, measurements,
quality metrics and links to raw evidence. Enter a baseline job ID to compare;
`/jobs/8/capture?baseline=7` returns deltas as JSON, or HTML with `Accept: text/html`.

Comparisons require complete passing captures, clean committed sources, matching
case definitions and input fingerprints, backend, per-case timing, recording
settings, runtime and hardware. Native rows also require matching compiler settings.
A subset of the baseline's cases can be compared. Smoke captures and incompatible
rows show a reason instead of a ratio. Differences retain units and available
measurement errors; ratios alone do not establish a regression. Reports remain
ordinary retained job files and follow the job's retention policy.

## Optional controls

Most jobs omit hardware and recording options:

```sh
deploy/lab run --tags 'arch:x86_64,simd:avx2,!spot' --measurement jfr experiment.json
```

`--tags` requires comma-separated labels; `!` excludes one. Detected architecture/SIMD tags and custom node labels
are supported. Capability tags do not prove which instructions a benchmark executed.

`measurement` modes are `none` (default), `resources`, `jfr`, `compilation`, and `compilation+log`.
Recording modes default to serial execution and require `parallel: 1`.

Set `param.source-validation=true` in `base` for external witness checking. A checked witness does not certify
optimality. Only an invalid verdict withdraws the solver's claim; unsupported or failed checks leave it unchecked.

Schemas and behavior: [experiments](../src/main/kotlin/com/eignex/lab/Experiments.kt),
[API](../src/main/kotlin/com/eignex/lab/Api.kt), [checkers](../src/main/kotlin/com/eignex/lab/checker/).
