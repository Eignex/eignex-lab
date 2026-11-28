# Usage

[Setup](../deploy/SETUP.md) · [Complete command list](../deploy/lab)

Use `deploy/lab` and the authenticated HTTP API at `https://lab.eignex.com` from consuming repositories.
SSH access, host discovery and server administration belong to agents explicitly working on eignex-lab
operations. Report a concrete operational dependency to that owner instead of opening a server session.

## Corpus catalog metadata

Fetch an existing catalog index over authenticated HTTP before selecting inputs:

```sh
deploy/lab corpus-index pb-comp-2024 > pb24-index.json
deploy/lab corpus-index pb-comp-2025 > pb25-index.json
jq -r '.files[] | [.path, .sha256, .bytes] | @tsv' pb24-index.json
```

The general endpoint is `GET /corpus/indexes/{collection}`; it uses the same HTTP Basic
password as every lab call. Collection IDs contain letters, digits, `.`, `_` and `-`, start
with a letter or digit, and have at most 200 characters. There is no PB-specific allowlist.
An HTTP 200 response is versioned JSON:

```json
{
  "version": 1,
  "collection": "pb-comp-2024",
  "files": [
    {"path": "PB24/family/example.opb.xz", "sha256": "<64 lowercase hex digits>", "bytes": 123}
  ]
}
```

This example illustrates the schema, not an actual catalog member. `collection` and the
collection-relative `path` identify a stored source; `sha256` and `bytes` bind its exact
stored bytes, including compression. A `.xz` or `.zst` member's checksum describes the
packed file, not its decompressed contents. PB24 and PB25 use collection IDs `pb-comp-2024`
and `pb-comp-2025`; their source paths retain the `PB24/` and `PB25/` prefixes. Adapters own
upstream provenance, normalization and any separate identity for materialized plain sources.

Select members from this metadata, then declare only the selected corpus paths and hashes
with the existing Bench SDK `BenchInput`/declared-input protocol. A corpus path includes
its collection prefix, for example `pb-comp-2024/PB24/family/example.opb.xz`. The lab resolves
declared bytes through its existing shared input store and verifies them at execution.
An index alone does not guarantee that every member has been published as an individual
input blob; ordinary preparation must materialize/publish a selected input that is missing
there. The index request never falls back to a complete pack download or generates metadata.
Capture and commit metadata before running a planner: `plan` and `describe` remain offline.

HTTP 404 with `{"error":"no published corpus index for <collection>"}` means the configured
backend has no index. Invalid collection IDs return 400. Storage, signing, authorization,
transport, malformed-index and unsupported-version failures return 503, rather than an empty
catalog or 404. Lab password failures retain 401/429. The client exits 0 on success, 3 only
for that explicit missing-index response, 2 for bad arguments, and 1 for HTTP/transport
failures (including an older server's bare route-not-found 404). A non-JSON success also
fails. Inspect stderr; do not interpret every failure as an empty collection.

The API service reads `<collection>.index.tar` (one `index.json`, at most 256 MiB) from
`LAB_CORPUS_MIRROR`, or from `LAB_SHARED_INPUT_URL/corpus-packs` when no mirror is set.
With `LAB_SHARED_INPUT_SIGNER`, the service obtains a short-lived GET capability for
`corpus-packs/<collection>.index.tar`; without it the mirror must permit direct HTTPS reads.
Only metadata crosses this route, and credentials and signed URLs stay on the service.
When neither remote setting is present, the service reads the existing
`$LAB_SHARED_INPUT_ROOT/.index/<collection>.json`, falling back to
`$LAB_CORPUS/.index/<collection>.json`. A missing or unreadable local root is 503.
Operators must configure these settings on the API service as well as the workers.
This route reads existing sidecars; publication remains the adapter's responsibility.

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

`deploy/lab files 7 --json` includes the accepted preparation archives' stored `sha256` alongside their sizes.
Regional archives are listed without downloading them. Reading their usual `preparation/<commit>.zip` file URL
fetches and verifies the accepted bytes on demand; the response ETag carries that same SHA-256. A trimmed job
does not regain its archives through this route. An unavailable accepted archive fails explicitly rather than
silently disappearing from a supposedly complete listing.
`lab fetch` verifies listed SHA-256 hashes before publishing or resuming a file, then checks the retained
files again before reporting completion. Files without a listed hash retain the transport and size checks.
A failed download leaves `lab-fetch-missing.json` with its reason. Accepted names that differ only by ASCII
case require a case-sensitive destination; the client refuses an incompatible destination before overwriting files.

The HTTP service needs the same `LAB_SHARED_INPUT_URL` and loopback `LAB_SHARED_INPUT_SIGNER` configuration
as the coordinator to retrieve regional archives. Already projected archives remain readable without that store.

Replace `7` with the returned ID. `base` supplies shared arm values; `configs` overrides them and `grid` crosses
combinations. `param.<key>` overrides an opaque repository request parameter. The repository manifest and
planner own runtime controls and resource ceilings. The lab queues complete paired iterations automatically.

Each case row groups one workload, seed and repetition across all configuration arms. Every arm in that
row runs on the same machine within one worker attempt, under the row's resource budget. The scheduler
may place other rows on different compatible machines; preparation can also run on a different machine
from solving. A job's node changing between rows is normal and does not invalidate its paired comparison.
Keep ordinary A/B placement automatic rather than pinning the entire job to a machine.

`vcpus` is the width of each of the job's worker sessions. Each session reserves that many and runs
`max(1, vcpus - 1)` cases at once, divided by a case's CPUs when it needs more than one (at least one case), leaving
one vCPU for the worker JVM and its helpers. A session claims whole rows until they fill those case slots.
While rows wait, the lab adds sessions of that width, on other nodes when one is full, until the job's sessions
together reach the queue's `maxVcpusPerJob`; a session wider than a worker can hold is narrowed to fit. On the local
machine a job's sessions together hold at most its `vcpus`.
The job's preparation gets the same vCPUs, at least the queue's `preparationBudget`, with memory scaled in proportion,
both within what the chosen worker offers, so a larger `vcpus` also builds faster. A cloud preparation also takes
what a running node leaves idle, up to that node's share per compiler slot, so a small job's build need not crawl on
two vCPUs of an otherwise idle node; solves placed there, and work still waiting for a node, keep theirs. Jobs the local machine may run
have `vcpus` capped at its cores. Without `vcpus` the lab's default (`LAB_DEFAULT_PARALLEL`, 4) applies; recording
modes default to 1.
`parallel` is its retired name and still means the same; a spec that gives both must give them equal.
`lab vcpus <id> <n>` changes it for a running job.

A session narrowed by remaining capacity can hold fewer slots than a row has arms; the row then still runs whole
in one attempt, but its later arms start as earlier ones finish, so arms see different contention by launch
position. For paired timing comparisons, set `"simultaneousArms": true` together with a `vcpus` that leaves one
slot per arm: `arms × case CPUs + 1`, so `"vcpus": 5` for four CPU1 arms. Automatic placement then admits a solve
session only when its granted CPU and memory hold a whole row, and otherwise waits for such a grant instead of
starting narrower. A worker never starts a row its free slots cannot hold, so every arm of a row starts at once.
A job whose `vcpus` or the lab's limits can never grant that many slots is refused with the `vcpus` it needs;
lowering `vcpus` below that on a running job refuses it the same way. With `comparison-series`, the setting is part
of the measurement fingerprint.

A worker keeps its case slots full: each row runs as its own attempt, all arms together, and as soon as one
row ends the worker claims the next row whose arms all fit the slots it has free, while its other rows keep
running. If a worker is lost before accepting a complete row, the lab replaces that unfinished row as a whole;
it never combines arm A from one machine or attempt with arm B from another. Accepted rows remain accepted.
Retain failed-attempt and preparation costs, and investigate repeated losses as an operational issue separately
from the validity of completed same-machine pairs.

Reference comparisons on a captured source require the same source identity and a recorded reference version.
Historical rows without either stay visible as history but cannot establish coverage, disagreement or objective
quality on known bytes.
Use `deploy/lab references --json <query> [solver] [verdict]` to retain complete reference metadata, including
source bindings, versions, validation and stale status. The default output is a compact table.
MiniZinc reference cases retain the compiler binary hash and version, the selected solver's canonical ID,
registered version, executable hash and configuration. Missing versions fail preparation. Identity preparation
spends the case's time allowance; the remaining allowance goes to compilation and solving. Reference completion
markers are native claims, separate from independent assignment or proof validation.
Kissat and cvc5 reference records retain the reported solver version and executable SHA-256;
Clasp records retain its immutable Docker image ID and execute that image rather than its mutable tag.
These identities include the adapter policy and fixed solver options. Identity lookup must succeed and
spends the case allowance before solving. Historical rows without an identity remain unqualified for
comparisons on captured sources; rerun them with updated workers to obtain qualified reference rows.
For a focused reference receipt, the committed `com.eignex.lab.cli.MainKt reference` entry point accepts `OUT` from
the lab when `out=` is omitted. `lab-solvers/src/test/resources/reference/affine-objective.mzn` is its deterministic source
control; an instrumented capture establishes adapter consumption, not throughput or independent checking.

**Auto placement is preferred and is the default for ordinary experiments, profiles and bench-adapter runs.**
Omit `tags`, or use `"tags": []`; the lab chooses placement automatically. Idle local capacity
can contribute, and the lab adds cloud capacity as demand grows. Local and cloud workers can share the same job.
Hardware or market selectors can narrow the compatible workers when a measurement needs them. Automatic cloud placement
prefers Spot; a configured equivalent on-demand pool handles `UnfulfillableCapacity` shortages. Explicit `spot` or
`!on-demand` tags disable that fallback, while `on-demand` or `!spot` requests on-demand directly.

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
are kept when the job resumes; cloud workers continue. Higher-priority work that is preparing on a cloud node, or whose
rows cloud workers are running, does not hold the local machine idle: lower-priority local work keeps it until that
job waits for a worker again.
Local-only placement, including a required `local` tag or excluded `cloud` tag, is reserved for sweeps and references.

## Repository benchmarks

Bench adapters can declare `themes`, `logic` and `structure` in case attributes. Preparation publishes
these source labels for theme and class views when the source has no stored classification. Approval
matches their source collection and problem identity independently of runtime variants, seeds and repetitions. Labels do
not require fetching source bytes during planning and do not certify a source's decidable fragment.
Classification updates invalidate stored trend points that depend on the source, so their theme views are recomputed.

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
  --classpath 'lab-app/build/install/eignex-lab/lib/*' --timeout 600 \
  com.eignex.lab.cli.MainKt main "Startup capture" -- check
```

## Optional controls

Repository bench jobs may set top-level `"measurement": "native-startup"` for worker-owned
MiniZinc discovery sampling. Submit the existing experiment JSON with `deploy/lab run`; omit
placement tags as usual. This mode defaults to one vCPU and marks every case instrumented,
including cases where sampling is unavailable. Its times are not ordinary runtime measurements.
It is the only `measurement` mode a bench job accepts: the CLI-recording modes (`resources`, `jfr`, `compilation`,
`compilation+log`, or `lab run --measurement`) are refused on bench submissions, whose recording settings belong
in the suite request.

On Mac, a private wrapper identifies the case's supervised process tree. The worker considers
only an executable named `minizinc` within that case's private worktree, with exactly one argument:
`--config-dirs` or `--version`. Process start identities, ancestry and executable metadata are
checked before and after sampling. After a query has lived for 500 ms, the worker may run
`/usr/bin/sample` for one second at 10 ms intervals. Each sampler has a two-second ceiling,
zero stop grace and its own file-size limit; at most two captures are attempted per case.
Capture setup spends the original case allowance; the remaining case limit is rounded down to
whole seconds before launch. Capture also spends the remaining whole-run and lease allowances. Cancellation closes
the sampler's supervisor connection; it does not signal or wait for the query being observed.
The helper's two-second execution limit is separate from the remaining containing allowance.
Symbol lookup follows sampling; a verified report requires the target to remain alive and retain its identity through both.

Accepted artifacts are under `cases/N/native-startup/`, accessible through authenticated
`deploy/lab files ID --json` and `deploy/lab cat ID PATH`. Receipts bind session, case, attempt
generation, worker, source commit, query, executable metadata, PID/start identity, CPU observations,
sampler exit/signal, elapsed time and cancellation. Only samples whose target still matches after
capture are marked `captured` and `sampleVerified=true`; bounded partial samples remain explicitly
unverified. Temporary output is outside the accepted case directory. Non-Mac workers,
missing tools, denied permissions, exited targets and incomplete captures retain explicit
unavailability; absent CPU observations remain null. No matching query observed is not proof
that startup was fast: discovery may finish between polls or lie beyond the 128-descendant scan.

Sampling uses the existing worker account without sudo. Native call stacks can identify blocked
calls, but do not supply a filesystem or scheduler timeline. Those traces remain explicitly
unavailable; a captured stack does not by itself certify a filesystem-versus-descheduling cause.
Automatic placement, containing deadlines, accepted paired rows and failed-attempt costs remain
authoritative. Sampling success does not qualify a compiler repair or a performance benefit.

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

`tags` is the only placement input for experiments and schedules; the retired `nodeTags`, `host`, `auto` and
`machines` submission fields are rejected.

To replace a job's tags before admission, use `deploy/lab tags 7 'arch:x86_64,!spot'`, or POST
`{"tags": ["arch:x86_64", "!spot"]}` to `/jobs/7/tags`. `deploy/lab tags 7` clears the selector and restores auto
placement. Once source preparation or session admission starts, the recipe is frozen and tag edits are refused.

Schedules use auto by default. Repository recipes can request `"tags": ["local"]`; reference runs and
legacy confirmations keep their local-only execution policy. Repository bench confirmations preserve the original
run's placement and pinned build platform. Schedule responses expose the same editable tags format.

Spot interruption is normal worker replacement. A provider warning stops new claims immediately and gives
the current row at most 45 seconds to finish inside the host's 60-second drain. Accepted rows remain accepted;
an unfinished row is replaced in full, preserving its spent allowance and all failed-attempt costs.
Workers send bounded resource checkpoints every five seconds and on retirement, independently of result
publication. `/jobs/ID/execution` exposes each session's latest `resources` with the explicit
`worker-cumulative-lower-bound` scope. These counters include child processes, are cumulative within one worker,
and must not be summed across its snapshots or treated as a final total. Later cleanup/delivery CPU remains
unknown. Authenticated checkpoints may arrive briefly after fencing; they cannot renew work or accept a case.
Nomad-assigned pending preparations reserve their node's compiler slots before image pull and registration,
allowing other preparations and replacements to be placed without granting an early worker claim.

A declared input checksum mismatch is a permanent preparation failure: the job stops with the path,
declared SHA-256, local SHA-256 and local byte size. Correct the consuming repository's corpus declaration
or materialized bytes before submitting again. Worker replacement cannot repair this binding, and the lab
never substitutes different bytes for a declared hash.

With a shared input store, preparation also sets `LAB_DECLARED_TREES=true` for `describe` and `plan`. A bench may
then declare a directory, such as a MiniZinc include directory, by its files instead of materializing it: the
directory input carries the tree checksum of `BenchInputTrees` (SHA-256 over each file's relative path and SHA-256
in path order) and every file below it is a declared input of the same case and root. Preparation pins such a
corpus directory from the declarations as the directory holding exactly those files, publishes and checks the files
one by one, and needs no local bytes when the store already holds them; solve workers fetch the directory as those
files. A tree checksum that does not match its declared files is a permanent preparation failure like a file
checksum mismatch, and a directory that is read (in a replay, or from the source tree) must hold exactly them.
A directory some case names without a checksum is still published from local bytes.

The **Nodes** tab shows the local machine, cloud pools, active nodes, and their allocations. To start a specific
kind of cloud node, select its configured pool by CPU, memory, architecture/SIMD tags, and Spot or on-demand market.
Choose the minimum total nodes to keep in that pool and a duration of 15–60 minutes. Existing nodes count toward
the minimum; requests respect the pool and global vCPU limits. Release a request to return that capacity to
automatic scaling, which retires idle nodes after its normal delay. Additional node shapes need a configured pool.
Each node shows one CPU architecture and primary SIMD profile, such as `x86_64 / AVX-512` or `ARM64 / SME2`.
Detected lower instruction sets remain compatibility capabilities. For AVX2-only placement, require `simd:avx2`
and exclude `simd:avx512`; for NEON-only placement, exclude `simd:sve` and `simd:sme` as well.

`bench.adapterResources=true` requests external GNU time recording of the runtime preflight and SDK `run`
invocation, including CPU of child processes waited for through that invocation. It requires an explicit
`platform:linux` tag.
Verify the effective stored option and accepted receipt before using a measurement. Reference arms use their own recordings.
The case keeps `adapter-resources.txt` and `adapter-resources.json` beside its output directory. The coordinator
projects them as `labAdapterResources` only after checking their accepted artifact hashes and command identity.
Interrupted, missing, malformed or mismatched receipts stay unavailable, with raw partial captures retained.
Optional adapter measurements remain separate from physical comparison frames. An unavailable receipt does not
invalidate an otherwise valid accepted paired row. Adapter CPU claims require complete verified receipts with
matching timer identity and measurement scope. The opaque suite request and case allowances stay fixed.

The receipt records user/system CPU and elapsed seconds at GNU time's 0.01-second resolution. Its maximum
process RSS is the largest individual process value, not simultaneous tree memory. It does not measure detached
or unwaited descendants, the timer observer, planning, builds, worker staging, upload, coordinator work or post-commit checking.
CPU from waited CLI children is included and must not be added again. Post-commit source-checker receipts retain
their separate scopes; their CPU ceilings are limits, not consumed CPU. Whole-job lifecycle costs remain separate.
These child and RSS semantics follow the
[Linux resource-usage contract](https://man7.org/linux/man-pages/man2/getrusage.2.html).

Set `bench.sourceValidation=true` for independent witness checking. A checked witness does not certify
optimality. Only an invalid verdict withdraws the solver claim; unsupported or failed checks leave it unchecked.
`bench.runTimeoutSec` optionally bounds active preparation and execution time; queue time is excluded.
After whole-run expiry, a worker makes one best-effort diagnostic upload of available case results, witnesses,
termination records and resource captures. These files live under
`nomad/diagnostics/<session>/<iteration>-<generation>/`, with a `receipt.json` explicitly marking them unaccepted.
They never populate accepted case records or combine with replacement attempts. Collection is limited to five
seconds, 128 files, 1 MiB per file and 16 MiB total, followed by one five-second request. The receipt marks truncated
files and collection limits. The authenticated failed attempt can publish only within 120 seconds of its deadline
fence; ordinary execution, lease renewal and result acceptance remain fenced. Read retained diagnostics through
`lab files` and `lab fetch`. Missing evidence after abrupt worker loss or a failed upload remains unknown.

## Plan and build reuse

Set `bench.replayJobs` to a map from each new arm label to a completed or failed source job to reuse its
durably accepted preparation and resolved arm plan. `bench.frozenCases` pins the requested workload subset. Each arm must match
an original arm's revision and exact request, including selections and runtime settings, in the same
repository and manifest. The retained input hashes must match; a failed job without accepted preparation
cannot supply a replay.
Replay reuses prepared builds and corpus inputs while executing the requested cases anew.
Independent source checks run when requested.
It does not copy earlier solver outcomes or reset the source job's spent allowance. Retain the source job's
full preparation, failed work and retirement costs alongside the replay's own bounded costs.

A bench manifest may opt into `"sourceHistory": "snapshot"` when its build and tools need only the selected
revision. The coordinator exports its exact files and a shallow Git repository at the original commit ID;
`git rev-parse HEAD`, `git ls-files` and `git show HEAD:path` still work. Ancestors, tags, other branches,
repository hooks and credentials are not exported. With `packedSourceSnapshots` enabled in queue settings,
the verified ZIP contains only shallow Git metadata and objects; a protocol-7 worker checks out the files once
in its private build directory. Enable this setting only after every pinned native launcher and cloud worker
image supports protocol 7. Its default is false; older frozen queue recipes retain the file-based snapshot ZIP.
Omitting the field, or setting `"sourceHistory": "full"`, retains the original history bundle; use that mode
for tools such as `git describe` or builds that inspect ancestors. Profiles retain full history. The source
hint cache separates history bundles, file snapshots and packed snapshots; exports normalize their Git metadata
for stable reuse. Additional declared inputs remain bound to their own checksums and are not source-only hints.

A bench manifest may declare top-level `workspaceInputs`, a nonempty list of portable relative file or directory
paths to copy from the verified build. The manifest itself is included automatically. This asserts that `describe`,
`plan` and `run` need only those paths, generated files and the declared corpus: include installed classpaths,
compiler/runtime assets, source inputs and any scripts or Git metadata they read. Listed paths must exist in the
build. Without the declaration, preparation retains the full built tree. Cold declared builds retain and checksum
the manifest, workspace inputs and all `planInputs`; unrelated checkout and compiler files are detached by rename
and removed in the background. Planner cache identities still cover every `planInputs` path before copying;
limiting the workspace never narrows that identity. A consumer requiring the full checkout rebuilds a retained
cache entry. Existing full cache entries remain reusable, and cache disk accounting covers all retained files.
Solve attempts receive private copies of the resulting snapshot, and source input verification remains enforced.
`LAB_WORKSPACE_CACHE` names a worker-owned sibling directory for bounded adapter metadata shared between fresh
case JVMs. It is outside sealed inputs and removed with the worker session; adapters must still verify cached identities.

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
That content includes files the build generates below a plan input, so a fresh build of an unchanged planner
usually misses. A manifest that also declares `"planIdentity": "committed"` asserts that the plan is a function of
the *committed* content of its `planInputs` (which must then name every source the planner is built from, and no
generated path): the coordinator reads the Git modes and object ids of the manifest and each plan input from its
mirror at admission (a local `git ls-tree`, no build and no network) and sends that identity in the prepare manifest,
so the key is known at submission and every commit that leaves those paths unchanged shares the plan. A plan input the
commit lacks falls back to the built-tree identity. The queue setting `committedSelectionKeys` enables this once every
pinned worker supports protocol 10; the coordinator then logs `nomad selection at admission`, saying whether the
accepted plan is already known and how many cases it holds.
A hit skips `describe`, `plan` and input pinning for that arm. It is used only when its source-root input bytes
still match and its corpus inputs are held by the same shared input store, whose checksums solve workers verify.
When every arm hits, the accepted shared inputs replace corpus publication; otherwise the planning arm's fresh
digests must equal the reused ones. A plan is offered for reuse only when its job's accepted shared inputs hold all its
corpus inputs, so a selection filter that dropped cases, or a worker without a shared input store, leaves nothing to
reuse. The preparation's cache report shows `Bench selection` hits and misses. On a miss, shared-input publication
checks up to eight distinct content hashes concurrently. Presence checks and conditional uploads retry transient
transport failures at most three times per request, checking ownership before each retry; signing and object-store
failures carry separate diagnostics. Metadata requests have a 15-second timeout. Local hashing and uploads keep their checksum checks;
ownership cancellation interrupts pending checks before publication continues.

Cloud bench preparations also reuse accepted builds across jobs and nodes when `reuseAcceptedBuilds` is enabled in
queue settings. The coordinator remembers each accepted cloud preparation's build archive that is held in the regional
store, by build recipe: repository, commit, platform, toolchain, build options and tasks, bench manifest and source
inputs. A later preparation of the same recipe is offered the most recent such archive accepted within the last week.
The worker uses it only when its own build identity (the recipe plus its JDK, platform and Gradle init script) is
equal and the archive is in the regional store, stages it with every byte checked against the accepted bundle, and
reports it as its prepared archive without compiling, archiving or uploading. Its cache report shows a `Build` hit
with reason `reused-accepted`. Plans on a reused workspace are keyed by the plan source the accepting preparation
computed on its complete build, so excluded `planInputs` keep their identity. Any other outcome is an ordinary build.
When an identical build is being prepared for another job on the same platform, a preparation waits for its archive
up to ten minutes before building itself. Enable this setting only after every pinned cloud worker image supports
protocol 9; its default is false and frozen queue recipes keep theirs. Native preparations are unaffected.

Build staging is pinned before background cleanup begins, and cleanup filters unrelated cache entries by
name before reading their metadata. Input/archive quota eviction renames unused entries to tombstones;
background workers delete those trees under one process-owned garbage lock per cache root. Publication
and claims do not wait for that deletion. macOS deletion threads use background disk priority,
restoring the caller policy afterward. Worker startup resumes interrupted tombstones. Pinned inputs
and pending garbage can temporarily exceed the cache target; free-disk admission checks still apply.

Cold JVM builds can stage checksum-pinned official Gradle distributions from the public
Gradle release-asset API into the private dependency home. The configured URL, version,
checksum and standard wrapper installer stay authoritative. Unpinned and custom URLs
keep their wrapper download path; unsupported releases also fall back to it. Downloads
check the preparation's remaining budget, publish only verified bytes by atomic rename,
and retain their size, digest and cache reuse in the build output. This needs no worker
shell downloader or credentials. Malformed or unavailable release metadata also falls
back to the wrapper path; a digest or byte mismatch fails the build. Tests use fake
transfers only, so CI makes no Gradle release requests.

Cold preparation placement respects the node's configured compiler-slot count as well as CPU and memory.
The coordinator places one pending preparation per cloud class at a time and excludes nodes whose preparation
slots are occupied. Other jobs wait before source publication and before starting a charged worker attempt;
their existing spent allowance is preserved. Capacity requests count compiler slots within the existing pool
and physical-vCPU limits. Warm verified builds bypass the compiler gate. Cold compilers use arrival-ordered,
process-owned tickets; cancelled or crashed waiters release their place without leaving a blocking lock file.
Queue and placement time remains part of wall-clock turnaround, observable from job creation through completion.
The whole-run allowance charges active worker attempts, including compiler waits after a claim; time before
a claim is admission time. Moving a wait before admission does not reduce solver work or erase spent attempt time.

## Preparation and worker diagnostics

Preparation diagnostics are provisional observations retained on the attempt before completion. The execution
report and preparation details expose the last snapshot: bounded build stdout/stderr tails, observed child exit
code, the last 16 upload responses (including offsets and retries), resource counters and remaining lease/run time.
Workers report every five seconds and make one best-effort final report before staging cleanup or failure exits.
The final evidence request is bounded to five seconds and the existing lease; it grants no additional work time.
Abrupt node loss can prevent the final snapshot. Missing exit/signal/OOM evidence stays unknown. The coordinator
also retains bounded Nomad task termination evidence in execution events, including for older worker releases.
These receipts do not accept a build, reset failed-attempt costs, or change owner/generation/lease fencing.

`lab execution ID` (`GET /jobs/ID/execution`, authenticated) exposes retained allocation receipts as
`termination`: allocation and node identity, controller `observedAt` (epoch milliseconds), and `task` evidence.
The coordinator fetches the full allocation before replacing a bound solve worker, including when the allocation
list has lost it. Receipts survive controller restarts and Nomad garbage collection. Task evidence contains the
last 32 allowlisted event types, client/desired/task states, exit code, signal, OOM flag and kill/driver/setup error
indicators. Free-form Nomad messages, task configuration and credentials are excluded. `task: null` means no task
evidence was available; null exit/signal/OOM fields remain unknown. Historical sessions without a receipt have
no termination evidence. Diagnose these infrastructure losses separately from solver performance; the whole unfinished
paired row is still replaced together, with failed-attempt costs retained.

Workers also write two bounded `worker-lifecycle-v1` JSON receipts to their uploaded console at
`nomad/workers/<session>.log`: registration and retirement. They require no attempt owner, so a preparation
that exits before its first claim still records whether the coordinator returned no work, an admission wait
expired, the worker was fenced, or a provider interruption was observed. Available resource counters retain
their cumulative lower-bound scope; missing counters remain unknown. The receipts contain fixed reason codes,
not exception messages, commands or credentials. Read them with `lab files` and `lab fetch`. Abrupt process or
node loss can prevent delivery of the final receipt.

Repository-specific recipes and named workload sets live with their consumers. Before removing a legacy
schedule, translate its recipe using the owning repository tools and verify a bounded capture. The generic lab
has no implicit solver executable, build layout, corpus selections or JVM profile classpath.

## Source checking

Source validation uses the official XCSP3 checker, Dolmen for SMT-LIB models, VeriPB for OPB and WCNF points and the
lab's complete DIMACS SAT evaluator. MiniZinc, MPS and WBO retain native results without a lab point check. Native
optimization completion counts without an independent assignment receipt; checker rejection and checked
counterexamples still withdraw claims. Assignment validity and native completion are separate evidence.
See [Independent source checking](source-checking.md) for qualification and supported checker boundaries.
When source validation was requested for a format without a registered checker, the job page says which formats
are unavailable. This is a normal result; requesting validation does not require a checker for every format.

`lab recheck <job>` accounts for every completed saved case. Its `recheck.json` and job page list each outcome,
including unsupported formats and failures to read or check a retained record. `checked` counts replacement
receipts, while `states` counts all outcomes; unsupported cases keep any historical receipt. No solver runs.

DIMACS SAT points use the independent complete original-coordinate policy documented in
[Independent source checking](source-checking.md#original-dimacs-sat-points). Ordinary requested checks and
ended-case rechecks share the production registry.

## Retained artifacts

File listings and downloads use accepted artifact names and content hashes. Names differing only in case remain
separate on a coordinator with a case-insensitive filesystem; its physical job directory is a projection rather
than the artifact inventory. Full accepted downloads include their SHA-256 as an ETag. A blob still draining from
the result journal may have unknown listed size; a matching local projection can serve it, while missing or corrupt
bytes return an unavailable response. Existing authentication, job scoping, safe paths and trim policy still apply.
Trimmed jobs retain their designated logs and records without making raw artifacts downloadable again.

Complete inventories are prepared and validated in the background. The default file-list request waits at most
one second, returning `503` with `Retry-After` while work is pending. Explicit fetches can use
`GET /jobs/<id>/files?waitSec=30` to suspend for up to 30 seconds on that request's fresh validation. The maintained
client opts into this wait with a longer transport timeout and bounded retries, so validation that finishes between
short polls remains deliverable. Timeouts and disconnected callers do not cancel the shared inventory work.
Reads that remain blocked beyond the selected wait still return unavailable; this does not serve an older snapshot
without checking accepted version, status, trim, terminal logs and storage availability.

An inventory `503` includes bounded cached progress in the existing JSON `detail` string: waiting/busy/failed state,
current phase and elapsed time, last safe failure category and phase, retry delay, and retained-snapshot age/staleness.
The detail contains no file names, paths or exception text. `currentValidated=false` means a retained snapshot is not
proof that this unavailable request passed validation. Reading these diagnostics does not probe storage or start
another refresh; a new successful request still returns the complete ordinary inventory array.

## Benchmark trends and confirmations

Opt-in `metric-v1` benchmark sweeps show regression counts by exact engine and absolute timings
for one selected workload, size and storage. Every workload uses its own positive timing and slowdown
threshold in the first complete finished run of the same case/control series as its fixed baseline.
Counts are candidates, not statistical confirmations; missing, failed or pending measurements leave gaps.
Unsupported arms do not enter an engine's baseline coverage. Each workload retains its own runtime/hardware
control digest; changing it withholds that workload's comparison even if the run's set of hosts is unchanged.
Changed cases or controls start a separate series. The absolute graph keeps the original metric unit,
shows every measured engine together, and exposes provider/kernel attribution and speed relative to the
fastest measured engine. It never averages unlike workloads. Historical default-only Koblas runs remain
labeled `jvm-default`; they do not imply timings for unmeasured engines.

The case table lists every planned case and engine at the selected commit, with current absolute timing,
historical target, relative slowdown, prior run count and measurement status. Its default target is the
linearly interpolated 5th percentile of earlier comparable run medians (95th for higher-is-better metrics).
An alternative selects the best observed earlier value. Samples require the same metric, unit, direction,
case/control series and per-case runtime/hardware controls; failed, skipped, pending, nonpositive and
instrumented measurements do not set targets. Neither the selected run nor later runs contribute.
Percentile estimates based on fewer than 20 prior runs are marked as limited history, not presented as
confidence bounds. Targets cover only the shown history, not an all-time record. Failed and unsupported
cases stay visible with unavailable current values; cases link to the absolute timing graph. The table's
target selector does not change the fixed-baseline graphs or paired confirmation policy.

Clicking a commit or selecting it exposes affected engine/workload buttons; a button selects that
workload's absolute graph. Koblas has the same regression-count graphs for dense and sparse BLAS Levels
1, 2 and 3. Rings mark failed captures; a ring on the baseline means timing is unavailable, not zero.
A failed capture does not identify an individual failing engine unless its retained record does so.
The run table retains case outcomes, changes and paired confirmations. Paired confirmations compare their
recorded neighboring revisions and are labeled separately from the fixed-baseline candidates; an engine
confirmation is attached to a baseline candidate only when workload, metric and baseline revision match.
Skipped and pending cases are never labeled incorrect. `lab trend <schedule-name>` reads the plotted
points as JSON. Renaming a schedule preserves its job history.

For local graph-model coverage, run `node --test lab-app/src/test/js/benchmark-history.test.cjs`.

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
An undecided record has no claim to check, so its absent label pairs with a checked claim.
Retained-case rechecks invalidate finished trend points and confirmation counts without changing the original job's finish time.

The coordinator attaches `labExecution` from the accepted iteration's checksum-verified
worker resource artifact. Its frame retains the lab release, CPU model/features,
JDK/platform, literal cgroup CPU/memory ceilings, session budget, iteration concurrency
and paired case count. Resource counters stay in the linked receipt and do not split
a series. Different frames split controlled series; missing or advisory observations
withhold comparisons across runs. Repository output cannot supply this authority.
Flips between successive scheduled runs are candidates: they need equal logical controls and source hashes but no
execution frame, and only queue the paired confirmation that carries the attribution.
Preparation resources are separate from these solve observations.

Paired confirmations take their pairing from the coordinator's accepted worker attempt for each row (see
[Experiments](#experiments)), not from repository records; missing CPU identity metadata does not invalidate it.

Every feasibility/proof/objective vote uses a complete matching seed/repeat block;
failed, duplicate or absent blocks cannot vote and remain in the denominator.
Confirmations preserve each affected source's solver seeds and repeat the paired
blocks three times. A persistent unproved objective loss is one worse than the same
retained baseline in two successive runs; the bounded confirmation compares that
baseline with the latest run, even when the worse objective plateaus. At most 40
sources are selected; other losses remain unconfirmed. Occasional fixed multi-seed
baselines are repository-owned recipes, separate from per-commit trend lines.
Requests with `comparison-series` include `vcpus` in their measurement fingerprint; `vcpus=1` keeps the
fingerprint of earlier `parallel=1` runs, while larger values start a new series.
Canonical CPU1 controls use `vcpus=1`; preparation and solve grants
are separate, and the actual iteration cgroup receipt controls physical accounting.
Parallel CLI/JIT/harness processes share the aggregate group grant. Cgroup CPU
counters can accumulate across iterations; sampled current memory is not a peak.
Independent witness checks retain their policy and unsupported states. Native
optimality/UNSAT flags remain distinct from independently checked source proofs.

## Schedules

Schedules are listed by priority, highest first, with creation order breaking ties. Rename a schedule with
`deploy/lab rename-schedule <id> <name>`. Its ID, last revision, check cadence and all scheduled run and confirmation
history are retained; the renamed series continues comparing with its existing baseline.

### Klause sweep placement

The committed recipe in `deploy/schedules/klause-sweep.json` runs the repository's sweep on the Mac, and its
confirmations follow: a confirmation copies its run's node tags, and `platform:mac` matches only the native Mac
worker, so no run or confirmation of this schedule goes to a cloud node. It uses `klause-bench/lab/bench.json`,
which declares no platform, and requests `vcpus=1`, so the Mac runs one case at a time. The Mac's CPU limits are
advisory (`advisory-native` frames), so adapter resources, which need Linux, stay off.

The recipe writes the `klause-sweep-mac-cpu1-v1` comparison series. Keep the 10-second solver limit, seed,
source validation and repository-owned sweep selection. The `klause-sweep-linux-cpu1-v1` history remains
available, but its wall times are not a baseline for the Mac; paired confirmations still decide regression
attribution.

Apply a changed recipe to the existing schedule, preserving its ID and history:

```sh
deploy/lab reschedule 2 deploy/schedules/klause-sweep.json
```

After applying it, check that the next run and its confirmation report the Mac as their host in `lab cases`.
