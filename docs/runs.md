# Runs

[Documentation home](../deploy/SETUP.md) · [Measurements](measurements.md) · [Cloud nodes](cloud.md)

## Solver experiments

Submit one experiment per question, covering its full problem selection. The lab bounds each case's runtime and
pairs its arms; splitting one question into batches scatters the comparison and can be refused with HTTP 409.
Finished cases remain readable while a job runs.

Save a specification and run `deploy/lab run experiment.json` from the repository root:

```json
{
  "name": "restarts-ab",
  "description": "Compare restart policies over a fixed sample",
  "problems": {"suite": "xcsp3-cop,mzn-bench", "kind": "cop", "per-family": "2", "max": "400", "seed": "1"},
  "base": {"ref": "main", "engine": "cp", "timeout": "30000"},
  "grid": {"param.restarts": ["luby", "geometric"]},
  "seeds": [1, 2, 3],
  "parallel": 4
}
```

| Field | Meaning |
| --- | --- |
| `name`, `description` | Required identifiers and the question being tested |
| `problems` | Selection with `suite` or `set`; a list pools several independent selections |
| `base` | Values shared by every arm |
| `configs` | Overrides merged over `base`; defaults to one empty configuration |
| `grid` | Every combination crossed with those configurations |
| `seeds` | Solver seeds; omitted, use the bench's seed |
| `repeats` | Identical runs per problem/arm/seed; default 1, maximum 100 |
| `parallel` | Cases at once, subject to host core and memory limits |
| `priority` | Higher runs first; default 0 |
| `measurement` | Optional [recording mode](measurements.md); omitted for ordinary timing runs |
| `nodeTags` | Optional [required and excluded node tags](#selecting-a-node) |

Selection filters include `kind`, `category`, `tag`, `name`, `per-family`, `max`, `seed`, and `balance`.
`reference` controls filtering against stored solver verdicts:

| Value | Keep |
| --- | --- |
| `decided` (default for suites) | Problems the reference decided within 5 s, plus problems with no reference |
| `proven` | Problems the reference proved within 5 s |
| `missing` | Problems without a current reference verdict |
| `unsettled` | Missing problems and unproven problems previously run at a smaller budget |
| `any` | All selected problems; default for named sets and reference-backend experiments |

Selection caps apply after filtering. Arm values include `ref` (default `main`), `label`, `timeout` (milliseconds,
default 60000), `backend`, `engine`, `processors`, `lp`, `presolve`, `fixed`, `exact`, `debug`, and `param.<name>`.
`debug=false` disables the CLI's lightweight runtime statistics; it does not change recording modes.
`param.source-validation=true` requests an independent [witness check](validation.md).
For the complete accepted key set, see [Experiments.kt](../src/main/kotlin/com/eignex/lab/Experiments.kt).

Jobs eligible locally are capped to `LAB_CORES` and `LAB_MAX_PARALLEL`; the response and job page explain any cap.
An arm's `processors` reserves cores and sets `-XX:ActiveProcessorCount`. Leave `parallel` unset unless memory or
the measurement requires a specific limit. Timing on spot nodes is noisier; use repeats when small differences matter.

## Profiles and Koblas captures

A profile runs a committed JVM main or executable once. Only `Eignex/<repository>` sources are accepted, and the
[trusted-commit rules](operations.md#access-and-trust) apply to every repository. Profiles are measurement runs;
use CI for tests and lint gates.

For a JVM main outside Klause, specify its preparation tasks and repository-relative runtime classpath:

```sh
deploy/lab profile --repo Eignex/eignex-lab --build installDist \
  --classpath 'build/install/eignex-lab/lib/*' --timeout 600 \
  com.eignex.lab.MainKt main "Record startup host check" -- check
```

`--build` and `--classpath` repeat. Arguments after `--` pass literally to the main. Klause JVM profiles default
to its CLI/bench build and bench classpath. JVM mains record JFR to `$OUT/profile.jfr`. The timeout defaults to
10 minutes and is capped at 2 hours; `--timeout` uses seconds, while JSON `base.timeout` uses milliseconds.

The JSON form provides script entry points and JVM options:

```json
{
  "name": "koblas-dot-smoke",
  "description": "Capture the committed dot benchmark harness",
  "repository": "Eignex/koblas",
  "base": {"ref": "main", "timeout": "1800000"},
  "profile": {
    "script": "koblas-bench/capture-report.sh",
    "outputArgument": "--output",
    "args": ["--smoke", "--operation", "dot"]
  }
}
```

Or use `deploy/lab koblas main "Dot smoke capture" --smoke --operation dot`.
The committed harness controls its builds and instrumentation; its report is retained without interpreting rows as
solver results. A script must be tracked and executable within the repository. Arbitrary shell commands and paths
outside the checkout are refused. `profile.buildTasks`, `classpath`, and `jvmArgs` configure custom JVM profiles.

Both entry points receive `$OUT`, `$LAB_REPOSITORY`, and `$LAB_WORKTREE`. `outputArgument` passes the option and
`$OUT/report` to a report-producing harness. On cloud nodes, profile output is capped at 1 GiB, smallest files first;
`omitted.tsv` lists files left behind. Keep summaries rather than raw dumps. `profile.runTimeoutSec` adds a local-only
whole-run active-time limit, including setup and retries, with queue time excluded.

## Selecting a node

**Most jobs should omit selectors.** They retain local-first placement, normal cloud launch thresholds, and the
profile preference for local or on-demand capacity. Use tags only when the question requires a particular host:

```sh
deploy/lab koblas --tags 'arch:x86_64,simd:avx2,!simd:avx512,!spot' \
  main "AVX2 capture" --smoke --operation dot
deploy/lab run --tags 'arch:arm64,simd:sme' experiment.json
```

`--tags` repeats or takes comma-separated labels; `!` excludes a label. It merges with selectors in a run file:

```json
{"nodeTags": {"require": ["arch:x86_64", "simd:avx2"], "exclude": ["spot"]}}
```

Tags are lowercase labels without spaces. Every required tag must be present and every excluded tag absent.
Built-in facts include `local`, `cloud`,
`spot`, `on-demand`, `arch:x86_64`, `arch:arm64`, `instance:<EC2-type>`, and `simd:<OS-feature>`.
Common SIMD tags include `avx2`, `avx512` (AVX-512 Foundation), `avx512f`, `neon`, `sve`, `sve2`, `sme`, and `sme2`,
each prefixed with `simd:`. AVX2 does not exclude AVX-512; SME does not imply ordinary SVE.

Hardware facts are probed after boot/adoption. Unknown hardware cannot satisfy hardware selectors, including
exclusions. Tags select a capable machine; the benchmark's own metadata must establish which instructions it used.
Operators can add arbitrary labels such as `dedicated-bench`; see [node configuration](cloud.md#node-labels-and-architectures).
Unavailable selectors are refused. Local-only workloads still require a matching local node.

## Follow, compare, and schedule

| Command | Purpose |
| --- | --- |
| `lab ls`, `lab show <id>` | Queue and job details |
| `lab placement [id]` | Why work waits and what holds cloud capacity back |
| `lab cases <id> [--json]`, `lab csv <id>` | Partial or finished results; JSON output is JSON Lines |
| `lab compare <id> <id>...` | Compare experiments over matching problems |
| `lab tail <id>`, `lab files <id>`, `lab stdout <id> <case>` | Logs and native solver output |
| `lab fetch <id>` | Download files and current case verdicts, protected from trimming during transfer |
| `lab pause <id>`, `lab resume <id>`, `lab priority <id> <n>` | Control the queue |
| `lab retry <id>`, `lab cancel <id>` | Retry unfinished cases or stop a mistaken run |
| `lab schedule <name> <ref> <file> <interval-sec>` | Run a specification when a ref changes |

Commands above use `deploy/lab`. Its [command header](../deploy/lab) lists the complete interface.
Pause and priority yields finish started work; cancel stops process trees. Queue order is priority, then the series
that finished least recently, then age. Eligible local-only work wins ties while cloud nodes are available.
Schedules remain local unless their saved specification opts into cloud with `host: "auto"` or `"cloud"`.

The results page reports solved/proven counts, PAR-2, paired scores and confidence intervals, noisy problems, and
contradictions. Check the cases' host and build identities before comparing timing across runs; shared jobs keep
an iteration's arms together, but different iterations may use different machines.
