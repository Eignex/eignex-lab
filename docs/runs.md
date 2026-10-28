# Usage

[Setup](../deploy/SETUP.md) · [Complete command list](../deploy/lab)

## Experiments

Submit one experiment per question. Save this as `experiment.json`:

```json
{
  "name": "policy-ab",
  "description": "Compare two repository-owned policies",
  "repository": "Eignex/example",
  "bench": {
    "manifest": "bench/lab.json",
    "request": {"parameters": {"selection": "small"}}
  },
  "configs": [{"ref":"main","label":"before"}, {"ref":"candidate","label":"after"}],
  "grid": {"param.policy": ["baseline", "new"]},
  "repeats": 3
}
```

```sh
deploy/lab run experiment.json
deploy/lab show 7
deploy/lab cases 7
deploy/lab fetch 7
```

Replace `7` with the returned ID. `base` supplies shared arm values; `configs` overrides them and `grid` crosses
combinations. `param.<key>` overrides an opaque repository request parameter. The repository manifest and
planner own runtime controls and resource ceilings. The lab queues complete paired iterations automatically.

Reference comparisons on a captured source require the same source identity and a recorded reference version.
Historical rows without either stay visible as history but cannot establish coverage, disagreement or objective
quality on known bytes.
MiniZinc reference cases retain the compiler binary hash and version, the selected solver's canonical ID,
registered version, executable hash and configuration. Missing versions fail preparation. Identity preparation
spends the case's time allowance; the remaining allowance goes to compilation and solving. Reference completion
markers are native claims, separate from independent assignment or proof validation.
For a focused reference receipt, the committed `com.eignex.lab.MainKt reference` entry point accepts `OUT` from
the lab when `out=` is omitted. `src/test/resources/reference/affine-objective.mzn` is its deterministic source
control; an instrumented capture establishes adapter consumption, not throughput or independent checking.

**Auto placement is preferred and is the default for ordinary experiments, profiles and bench-adapter runs.**
Omit `tags`, or use `"tags": []`; the lab chooses placement automatically. Idle local capacity
can contribute, and the lab adds cloud capacity as demand grows. Local and cloud workers can share the same job.
Hardware or market selectors can narrow the compatible workers when a measurement needs them.

Priority controls native admission and iteration claims; sweeps and reference runs win equal-priority ties.
The status sweep has priority 100 and its regression confirmations 101, ahead of ordinary jobs at 0.
Ordinary reference runs also use priority 0, with their confirmations at 1.
Lower-priority local workers yield between iterations when compatible higher-priority work arrives or is
reprioritized, whether either job is automatic or local-only. Running paired iterations finish and accepted results
are kept when the job resumes; cloud workers continue.
Local-only placement, including a required `local` tag or excluded `cloud` tag, is reserved for sweeps and references.

## Repository benchmarks

Submit repository-owned benchmarks with `deploy/lab run experiment.json`. The experiment selects the repository
and its committed `bench.manifest`; `bench.request.parameters` goes to that adapter. Its manifest owns build tasks
and the planner, and its plan owns the commands, runtime checks, metrics and artifacts. The lab has no per-repository
capture command or backend probe. See the [bench SDK contract](../lab-bench/README.md) and the selected repository's
bench documentation for manifests and request parameters.

## Profiles

Profiles require an explicit repository, build tasks and JVM classpath (or a committed script in JSON).
Only committed code in Eignex repositories runs, from `main` or trusted branches. Profiles default to ten minutes,
capped at two hours; `--timeout` uses seconds. Scripts own their instrumentation; JVM mains record JFR.

```sh
deploy/lab profile --repo Eignex/eignex-lab --build installDist \
  --classpath 'build/install/eignex-lab/lib/*' --timeout 600 \
  com.eignex.lab.MainKt main "Startup capture" -- check
```

## Optional controls

Most jobs omit hardware and recording options:

```sh
deploy/lab run --tags 'arch:x86_64,simd:avx2,!spot' experiment.json
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

Recording settings belong in the suite request; consult the repository bench documentation.
Top-level `measurement` is retained only for historical records and is refused on new bench submissions.

Set `bench.sourceValidation=true` for independent witness checking. A checked witness does not certify
optimality. Only an invalid verdict withdraws the solver claim; unsupported or failed checks leave it unchecked.
`bench.runTimeoutSec` optionally bounds active preparation and execution time; queue time is excluded.

Repository-specific recipes and named workload sets live with their consumers. Before removing a legacy
schedule, translate its recipe using the owning repository tools and verify a bounded capture. The generic lab
has no implicit solver executable, build layout, corpus selections or JVM profile classpath.

Schemas and behavior: [experiments](../src/main/kotlin/com/eignex/lab/Experiments.kt),
[API](../src/main/kotlin/com/eignex/lab/Api.kt), [checkers](../src/main/kotlin/com/eignex/lab/checker/).
