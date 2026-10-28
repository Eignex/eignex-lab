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

**Auto placement is preferred and is the default for ordinary experiments, profiles and bench-adapter runs.**
Omit `tags`, or use `"tags": []`; the lab chooses placement automatically. Idle local capacity
can contribute, and the lab adds cloud capacity as demand grows. Local and cloud workers can share the same job.
Hardware or market selectors can narrow the compatible workers when a measurement needs them.

Priority controls native admission and iteration claims; sweeps and reference runs win equal-priority ties.
The status sweep has priority 100 and its regression confirmations 101, ahead of ordinary jobs at 0.
Ordinary reference runs also use priority 0, with their confirmations at 1.
Lower-priority local workers yield between iterations when higher-priority work arrives; cloud workers continue.
Local-only placement, including a required `local` tag or excluded `cloud` tag, is reserved for sweeps and references.

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

The API and experiment JSON files use the same flat array:

```json
{"tags": ["arch:x86_64", "simd:avx2", "!spot"]}
```

`tags` is the only placement input for experiments and schedules. The old `nodeTags`, `host`, `auto` and `machines`
submission fields are rejected. Historical jobs and execution recipes remain readable in their stored format;
recorded host information describes where a job may run or did run.

To replace a job's tags before admission, use `deploy/lab tags 7 'arch:x86_64,!spot'`, or POST
`{"tags": ["arch:x86_64", "!spot"]}` to `/jobs/7/tags`. `deploy/lab tags 7` clears the selector and restores auto
placement. Once source preparation or session admission starts, the recipe is frozen and tag edits are refused.
The old `lab host` and `lab machines` commands and their API overrides are retired.

Schedules use auto by default. The status sweep's committed schedule uses `"tags": ["local"]`; reference runs and
regression confirmations retain their local-only execution policy. Schedule responses expose the same editable tags
format, including the local tag for historical local schedules.

The **Nodes** tab shows the local machine, cloud pools, active nodes, and their allocations. To start a specific
kind of cloud node, select its configured pool by CPU, memory, architecture/SIMD tags, and Spot or on-demand market.
Choose the minimum total nodes to keep in that pool and a duration of 15–60 minutes. Existing nodes count toward
the minimum; requests respect the pool and global vCPU limits. Release a request to return that capacity to
automatic scaling, which retires idle nodes after its normal delay. Additional node shapes need a configured pool.
Each node shows one CPU architecture and primary SIMD profile, such as `x86_64 / AVX-512` or `ARM64 / SME2`.
Detected lower instruction sets remain compatibility capabilities. For AVX2-only placement, require `simd:avx2`
and exclude `simd:avx512`; for NEON-only placement, exclude `simd:sve` and `simd:sme` as well.

`measurement` modes are `none` (default), `resources`, `jfr`, `compilation`, and `compilation+log`.
Recording modes default to serial execution. Set `parallel` to run measured cases concurrently within the normal
CPU and memory budgets; shared-host contention can affect timings.

Set `param.source-validation=true` in `base` for external witness checking. A checked witness does not certify
optimality. Only an invalid verdict withdraws the solver's claim; unsupported or failed checks leave it unchecked.

Schemas and behavior: [experiments](../src/main/kotlin/com/eignex/lab/Experiments.kt),
[API](../src/main/kotlin/com/eignex/lab/Api.kt), [checkers](../src/main/kotlin/com/eignex/lab/checker/).
