# Usage

[Setup](../deploy/SETUP.md) · [Complete command list](../deploy/lab)

Use `deploy/lab` and the authenticated HTTP API at `https://lab.eignex.com` from consuming repositories.
SSH access, host discovery and server administration belong to agents explicitly working on eignex-lab
operations. Report a concrete operational dependency to that owner instead of opening a server session.

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

Each case row groups one workload, seed and repetition across all configuration arms. Every arm in that
row runs on the same machine within one worker attempt, under the row's resource budget. The scheduler
may place other rows on different compatible machines; preparation can also run on a different machine
from solving. A job's node changing between rows is normal and does not invalidate its paired comparison.
Keep ordinary A/B placement automatic rather than pinning the entire job to a machine.

If a worker is lost before accepting a complete row, the lab replaces that unfinished row as a whole.
It does not combine arm A from one machine or attempt with arm B from another. Accepted rows remain
accepted. Retain failed-attempt and preparation costs, and investigate repeated losses as an operational
issue separately from the validity of completed same-machine pairs.

Reference comparisons on a captured source require the same source identity and a recorded reference version.
Historical rows without either stay visible as history but cannot establish coverage, disagreement or objective
quality on known bytes.
MiniZinc reference cases retain the compiler binary hash and version, the selected solver's canonical ID,
registered version, executable hash and configuration. Missing versions fail preparation. Identity preparation
spends the case's time allowance; the remaining allowance goes to compilation and solving. Reference completion
markers are native claims, separate from independent assignment or proof validation.
For a focused reference receipt, the committed `com.eignex.lab.cli.MainKt reference` entry point accepts `OUT` from
the lab when `out=` is omitted. `src/test/resources/reference/affine-objective.mzn` is its deterministic source
control; an instrumented capture establishes adapter consumption, not throughput or independent checking.

**Auto placement is preferred and is the default for ordinary experiments, profiles and bench-adapter runs.**
Omit `tags`, or use `"tags": []`; the lab chooses placement automatically. Idle local capacity
can contribute, and the lab adds cloud capacity as demand grows. Local and cloud workers can share the same job.
Hardware or market selectors can narrow the compatible workers when a measurement needs them.

Priority controls native admission and iteration claims; sweeps and reference runs win equal-priority ties.
Locally eligible jobs below a cloud pool's growth priority may reuse compatible existing cloud capacity;
they do not reserve workers that require new capacity. An unallocated cloud worker yields its slot when
compatible native resources become available. A bound attempt keeps its machine through the complete row.
`lab placement <id>` and the job page distinguish requested slots, executing claim concurrency and slots
awaiting allocation. Preparation, transfers and idle reservations do not count as executing solver slots.
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
  com.eignex.lab.cli.MainKt main "Startup capture" -- check
```

## Optional controls

Most jobs omit hardware and recording options:

```sh
deploy/lab run --tags 'arch:x86_64,simd:avx2,!spot' experiment.json
```

`--tags` requires comma-separated labels; `!` excludes one. Detected architecture/SIMD tags and custom node labels
are supported. Capability tags do not prove which instructions a benchmark executed.

To hold the CPU fixed across a comparison, require a `cpu:` tag. Each names one microarchitecture and is carried
only by pools of a single instance type: `cpu:apple-m4` (the local Mac), `cpu:zen2` (AVX2 pools), `cpu:graviton2`
(NEON), `cpu:graviton3` (SVE) and `cpu:graviton4` (SVE2). The AVX-512 pools mix Ice Lake and Sapphire Rapids
instances and carry no `cpu:` tag, so `simd:avx512` alone can place one job's rows on either; read each case's CPU
model from its metadata there.

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

Set `bench.replayJobs` to a map from each new arm label to a completed or failed source job to reuse its
durably accepted preparation and resolved arm plan. `bench.frozenCases` pins the requested workload subset. Each arm must match
an original arm's revision and exact request, including selections and runtime settings, in the same
repository and manifest. The retained input hashes must match; a failed job without accepted preparation
cannot supply a replay.
Replay reuses prepared builds and corpus inputs while executing the requested cases anew.
Independent source checks run when requested.
It does not copy earlier solver outcomes or reset the source job's spent allowance. Retain the source job's
full preparation, failed work and retirement costs alongside the replay's own bounded costs.

During one preparation, repeated compiler/include files reuse checksums only while inode, size, mtime and
ctime remain unchanged on a supported filesystem. Directory membership is walked again for every input;
changed files and unsupported filesystems take the checksum path. Reuse ends with that preparation.
The worker logs `worker selection verification` counters for hashed/reused files and hashed bytes.

Preparation also reuses accepted planner output across jobs. Before running `describe` and `plan`, the worker computes
one selection key per repository arm: the SHA-256 of the worker release, repository, manifest path, the arm's request
(sorted parameters), the corpus transport (shared input store URL, or `local`) and a source identity. The source
identity is the arm's commit unless the manifest declares top-level `planInputs` (relative paths): then it is the
content of the manifest and of every file under those paths, and different commits with the same plan inputs share a
plan. Declaring `planInputs` asserts that the plan is a function of those files, the request and the corpus; include
the planner's own sources. Coordinator selection filters and `bench.frozenCases` apply afterwards and are not keyed.
A hit skips `describe`, `plan` and input pinning for that arm. It is used only when its source-root input bytes
still match and its corpus inputs are held by the same shared input store, whose checksums solve workers verify.
When every arm hits, the accepted shared inputs replace corpus publication; otherwise the planning arm's fresh
digests must equal the reused ones. A plan is offered for reuse only when its job's accepted shared inputs hold all its
corpus inputs, so a selection filter that dropped cases, or a worker without a shared input store, leaves nothing to
reuse. The preparation's cache report shows `Bench selection` hits and misses.
Build staging is pinned before background cleanup begins, and cleanup filters unrelated cache entries by
name before reading their metadata. Input/archive quota eviction renames unused entries to tombstones;
background workers delete those trees under one process-owned garbage lock per cache root. Publication
and claims do not wait for that deletion. macOS deletion threads use background disk priority,
restoring the caller policy afterward. Worker startup resumes interrupted tombstones. Pinned inputs
and pending garbage can temporarily exceed the cache target; free-disk admission checks still apply.

Cold preparation placement respects the node's configured compiler-slot count as well as CPU and memory.
The coordinator places one pending preparation per cloud class at a time and excludes nodes whose preparation
slots are occupied. Other jobs wait before source publication and before starting a charged worker attempt;
their existing spent allowance is preserved. Capacity requests count compiler slots within the existing pool
and physical-vCPU limits. Warm verified builds bypass the compiler gate. Cold compilers use arrival-ordered,
process-owned tickets; cancelled or crashed waiters release their place without leaving a blocking lock file.
Queue and placement time remains part of wall-clock turnaround, observable from job creation through completion.
The whole-run allowance charges active worker attempts, including compiler waits after a claim; time before
a claim is admission time. Moving a wait before admission does not reduce solver work or erase spent attempt time.

Preparation diagnostics are provisional observations retained on the attempt before completion. The execution
report and preparation details expose the last snapshot: bounded build stdout/stderr tails, observed child exit
code, the last 16 upload responses (including offsets and retries), resource counters and remaining lease/run time.
Workers report every five seconds and make one best-effort final report before staging cleanup or failure exits.
The final evidence request is bounded to five seconds and the existing lease; it grants no additional work time.
Abrupt node loss can prevent the final snapshot. Missing exit/signal/OOM evidence stays unknown. The coordinator
also retains bounded Nomad task termination evidence in execution events, including for older worker releases.
These receipts do not accept a build, reset failed-attempt costs, or change owner/generation/lease fencing.

Repository-specific recipes and named workload sets live with their consumers. Before removing a legacy
schedule, translate its recipe using the owning repository tools and verify a bounded capture. The generic lab
has no implicit solver executable, build layout, corpus selections or JVM profile classpath.

MiniZinc integer/Boolean points can be replayed through `checker/minizinc.json` using the bench protocol.
The request contains `record`, `model`, optional `data`, `fzn`, `ozn` and `stdout` as frozen UTF-8 text from one retained solve.
A record naming a data digest requires the matching original model/data pair.
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
Policy v7 binds annotated satisfaction goals with the same complete-coordinate obligations as optimization goals.
It declares the no-argument `klause_symmetry` and `klause_redundant` annotations when the captured frame
uses either without a declaration.
Tagged constraints remain asserted exactly as captured. Other solver annotations and opaque predicates retain their
ordinary compiler/provider handling; an unsupported predicate cannot be accepted by discarding it.
For `klause_subcircuit`, only literal integer arrays indexed from one are translated for the official circuit API.
Self loops are inactive; the remaining indices are densely relabeled with their original successor edges.
The resulting fixed `fzn_circuit(0, successors)` is equivalent to one cycle over all active indices. An empty active
set is true; out-of-range successors or edges into inactive indices are false. Choco checks every remaining cycle
and uniqueness obligation. The original `checked.fzn` and the derived `provider.fzn` are separately retained and
hashed, and the process receipt identifies the actual provider input. Unfixed, non-one-based and unknown forms
remain unsupported. No variable is completed, and neither search nor propagation is invoked.
The contracts come from [MiniZinc subcircuit](https://github.com/MiniZinc/libminizinc/blob/2.10.1/share/minizinc/std/subcircuit.mzn)
and [Choco's pinned FlatZinc circuit binding](https://github.com/chocoteam/choco-solver/blob/v5.0.1/parsers/src/main/java/org/chocosolver/parser/flatzinc/ast/FConstraint.java).
Fixed literal `klause_all_different_int`, `klause_circuit`, `klause_lex_less_int` and `klause_inverse_offsets`
calls use the corresponding pinned Choco assignment APIs. Named arrays must have explicit literal values;
metadata and all other constraints stay present. Circuit values are one-based. Inverse offsets name the original
index sets: forward values subtract the inverse index offset, and inverse values subtract the forward index offset
before calling the zero-based provider. Asymmetric-offset negative cases check this distinction. Lexicographic
checking is strict and requires equal nonempty lengths; other shapes stay unsupported. Every translation keeps
the original and actual derived input, with the same complete-variable guard and no solver completion.
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

Decompression, parsing and arithmetic run in a child with a 256 MiB heap, 10 CPU seconds, 15-second wall deadline and existing
4 GiB address-space limit on Linux; incomplete execution is `not-run`. Source snapshots and compressed transport are capped at 32 MiB
(including decompression, with a 64 MiB zstd window); individual numeric tokens at 4096 characters and variables at one million. Exceeding
input coverage is `unsupported`; malformed source stays unsupported, while a malformed point, hard violation or
wrong exact cost is `invalid`. Missing source/witness/objective and process failures are never valid checks.

`checker/weighted.json` replays a retained weighted point through the same production dispatcher/adapters.
Supply frozen UTF-8 `format`, `model` and `record` parameters, optionally packed as `capture-zstd-base64` using the
same bounded transport described above. It retains the complete source, record and lab receipt as artifacts.
`details.state` is the verdict; successful SDK capture alone is not acceptance. Capture preparation, checker
execution and retention belong to the containing job's budget. Repository tests require no external provisioner.

Exact rational MPS point checks and the remote replay contract are specified in
[Independent source checking](01-source-checking.md#exact-rational-mps). A valid point is feasibility evidence only.

DIMACS SAT points use the independent complete original-coordinate policy documented in
[Independent source checking](01-source-checking.md#original-dimacs-sat-points). Ordinary requested checks and
ended-case rechecks share the production registry. `checker/dimacs.json` accepts explicit frozen captures and
retains both paths' receipts; SDK success alone is insufficient. SAT point acceptance supplies no independent
UNSAT or optimum proof. Resource and unavailable checks remain unchecked.

## Controlled sweep migrations

Klause's `matched-source-v1` records retain effective solver/compiler seeds,
processor/memory/deadline controls and normalized runtime/compiler identities.
Separate harness and library fingerprints retain bench class bytes and runtime dependencies;
changing only the solver implementation does not substitute for either identity.
Revision confirmations require equal complete controls, original-source hashes and,
for MiniZinc, compiled-content/output-mapping hashes. FlatZinc raw hashes remain
retained separately: content policy v1 elides only the two generated path-comment
lines immediately after MiniZinc's version header, preserving every remaining byte.
Missing historical controls remain
unqualified. Rates and historical fingerprints stay available; `comparisonSeries`
labels controlled runtime/compiler series and historical records explicitly.
Migrations start separate trend segments and need a retained bounded old/current
bridge before a solver-only attribution. A seed change cannot count as recovery.
Different independent-checking policy labels also split controlled series and withhold revision attribution.
Retained-case rechecks invalidate finished trend points and confirmation counts without changing the original job's finish time.

The coordinator attaches `labExecution` from the accepted iteration's checksum-verified
worker resource artifact. Its frame retains the lab release, CPU model/features,
JDK/platform, literal cgroup CPU/memory ceilings, session budget, iteration concurrency
and paired case count. Resource counters stay in the linked receipt and do not split
a series. Different frames split controlled series; missing or advisory observations
withhold revision attribution. Repository output cannot supply this authority.
Preparation resources are separate from these solve observations.

Every feasibility/proof/objective vote uses a complete matching seed/repeat block;
failed, duplicate or absent blocks cannot vote and remain in the denominator.
Confirmations preserve each affected source's solver seeds and repeat the paired
blocks three times. A persistent unproved objective loss is one worse than the same
retained baseline in two successive runs; the bounded confirmation compares that
baseline with the latest run, even when the worse objective plateaus. At most40
sources are selected; other losses remain unconfirmed. Occasional fixed multi-seed
baselines are repository-owned recipes, separate from per-commit trend lines.
Requests with `comparison-series` retain explicit parallelism in their measurement
fingerprint. Canonical CPU1 controls use `parallel=1`; preparation and solve grants
are separate, and the actual iteration cgroup receipt controls physical accounting.
Parallel CLI/JIT/harness processes share the aggregate group grant. Cgroup CPU
counters can accumulate across iterations; sampled current memory is not a peak.
Independent witness checks retain their policy and unsupported states. Native
optimality/UNSAT flags remain distinct from independently checked source proofs.
