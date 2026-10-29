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
A repository recipe can set priority 100 and its regression confirmations then use 101, ahead of ordinary jobs at 0.
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

Schedules use auto by default. Repository recipes can request `"tags": ["local"]`; reference runs and
legacy confirmations retain their local-only execution policy. Repository bench confirmations preserve the original
run's placement and pinned build platform instead of being forced onto a native worker. Schedule responses expose the same editable tags
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

MiniZinc integer/Boolean points can be replayed through `checker/minizinc.json` using the bench protocol.
The request contains `record`, `model`, `fzn`, `ozn` and `stdout` as frozen UTF-8 text from one retained solve.
The source must match the record's digest. Native stdout and the model witness must retain the same complete
`flatzinc-integer-v1` comment frame. The adapter adds equality constraints to the original FlatZinc declarations,
including their aliases and domains, and checks the claimed objective in the original solve variable's units.
MiniZinc runs with `--compile` only. Its residual fixed constraints go through Choco's official FlatZinc parser and
`Constraint.isSatisfied()` API. The wrapper disables SAT/LCG, rejects every unfixed or non-integer variable, and
invokes no search or propagation API.
Official `--ozn-file` rendering must then reproduce the recorded model witness. Unsupported checks stay unknown.
The policy validates the retained compiled frame under MiniZinc lowering authority, not optimality or an
independently certified compiler. Each request has a 24 MiB text limit, one 15-second tool allowance and retained
inputs, binary identities and bounded process receipts. The SDK result's success means capture completed;
`details.state` is the validation verdict. Missing original compiler outputs cannot be replaced with another run's.
Automatic checks use the three files from a single accepted iteration's artifact inventory and verify their hashes
before passing them to the checker. Historical records without the complete point and compiler outputs stay unsupported.
For shell-backed transports, pass the JSON parameter map compressed with Zstandard and Base64-encoded as the single
`capture-zstd-base64` parameter. The frozen case keeps this packed representation until execution. Its 120 KiB limit
keeps the command below the per-argument limit; decoded input is capped at 24 MiB. A replay may select the last complete
native solution and omit unrelated record statistics when it retains the original record/stdout hashes and selection
description in `captureOrigin`. Source, compiler outputs, witness coordinates and objective remain exact.
The retained FZN declarations determine the checked coordinates. Every original scalar variable must be present;
extra parser-generated constant entries are retained as diagnostics and counted separately in the verdict.
The Choco parser and core jars are pinned to 5.0.1 and resolved as ordinary build dependencies. Their Maven version
metadata and actual jar hashes identify the API; the existing XCSP checker dependency is preserved separately.
The wrapper runs in a bounded child JVM, with its executable and complete classpath jar hashes in the receipt.
Its Kotlin source delegates all constraint evaluation to the provider's API.

Schemas and behavior: [experiments](../src/main/kotlin/com/eignex/lab/Experiments.kt),
[API](../src/main/kotlin/com/eignex/lab/Api.kt), [checkers](../src/main/kotlin/com/eignex/lab/checker/).

OPB (including WBO sources), WBO and WCNF retained points use the independent lab evaluator
`eignex-lab-weighted-point-1`. It reads the original source, checks every original Boolean coordinate and hard
constraint, and compares the exact integer objective or sum of violated soft weights. WBO additionally enforces
cost strictly below `soft:` top; weights at or above top act as hard constraints. Legacy WCNF numeric top and
headerless `h` clauses are supported. WCNF witnesses may be the retained `v` bit vector or signed DIMACS literals;
PB witnesses use signed `xN` literals. Missing, duplicate and extra coordinates reject; no omitted variable is
completed by a solver. Nonlinear PB products are evaluated directly as Boolean conjunctions.

The optional `alteredControl=true` replay adds a second case that flips exactly the first retained coordinate,
retaining both original and altered records. Its verdict is observed rather than assumed to be a rejection.

The authority is [PB Competition 2010 section 3](https://www.cril.univ-artois.fr/PB10/format.pdf) and the
[MaxSAT Evaluation input/output specification](https://maxsat-evaluations.github.io/2022/rules.html).
The evaluator uses Java `BigInteger`, with no Klause parser, acceptance code, presolve or reference solver.
This avoids finite-width provider costs and validates a point and its cost only, never a lower bound or optimum.
The receipt binds original plain source bytes, complete witness, claimed objective, policy version, installed lab
JAR, executable/classpath hashes and the existing process resource envelope. A source hash is required.

Parsing and arithmetic run in a child with a 256 MiB heap, 10 CPU seconds, 15-second wall deadline and existing
4 GiB address-space limit on Linux; incomplete execution is `not-run`. Source snapshots are capped at 32 MiB
(including decompression); individual numeric tokens at 4096 characters and variables at one million. Exceeding
input coverage is `unsupported`; malformed source stays unsupported, while a malformed point, hard violation or
wrong exact cost is `invalid`. Missing source/witness/objective and process failures are never valid checks.

`checker/weighted.json` replays a retained weighted point through the same production dispatcher/adapters.
Supply frozen UTF-8 `format`, `model` and `record` parameters, optionally packed as `capture-zstd-base64` using the
same bounded transport described above. It retains the complete source, record and lab receipt as artifacts.
`details.state` is the verdict; successful SDK capture alone is not acceptance. Capture preparation, checker
execution and retention belong to the containing job's budget. Repository tests require no external provisioner.
