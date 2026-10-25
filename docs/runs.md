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
