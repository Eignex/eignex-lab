# klause lab setup

An experiment server for klause. An experiment solves a selection of problems under one or more configurations,
one problem per case. The server builds each commit the configurations name, runs the cases, one experiment at a
time, and compares the results. It serves the queue, live output and results over HTTP.

It runs on macOS or Linux. Both services log the koblas backend at startup: the engine, and whichever host BLAS
it finds. They refuse to start (exit 78) unless koblas runs its Vector API engine, which is what klause's Level 1
calls use, so no timing comes from the scalar fallback. Host BLAS serves only Level 2 and 3, which klause does not
call, so it is reported and not required. `klause-lab check` prints the report and does nothing else.

## Experiments

**One question, one experiment.** Submit one experiment that covers all the problems a question needs; never split
it into batches. The lab bounds runtime itself (each case's `timeout`, profile time limits) and orders and places
the work (Mac first, AWS nodes by queue pressure), and a batch defeats that placement and the arms' pairing and
scatters the results. Don't impose your own wall-clock caps or cancel a job to keep it short. Follow a long job
with `lab wait <id>` run in the background, or poll `lab show <id>` / `lab cases <id>`: finished cases are readable
while it runs. A submission whose arms, seeds, repeats and profiling equal those of a queued or running experiment,
so that only its name, description or problems differ, is refused (409) and names that job; scheduled runs,
confirmations, profile jobs and `lab retry` are exempt, and a cancelled job no longer counts.

An experiment runs one selection of problems under every configuration it names, one problem per case. Each case
is one job command the lab writes itself; there is no other kind of job. `lab run <experiment.json>` queues one:

```json
{
  "name": "restarts-ab",
  "problems": { "suite": "xcsp3-cop,mzn-bench", "kind": "cop", "per-family": "2", "max": "400", "seed": "1" },
  "base": { "ref": "main", "engine": "cp", "timeout": "30000" },
  "grid": { "ref": ["main", "fix/x"], "param.restarts": ["luby", "geometric"] },
  "seeds": [1, 2, 3],
  "parallel": 4
}
```

- `description` (required): a sentence or two on what the experiment tests and why. The queue, the history and the
  job page show it, and the history's filter searches it; `lab describe <id> <text>` changes it later. Scheduled runs
  carry their schedule's, and a confirmation job describes itself.
- `problems` are `klause-bench select` filters: `suite` or `set` (one is required), `kind`, `category`, `tag`, `name`,
  `per-family`, `max`, `seed`, `balance`. A list of such selections pools their problems, each selection capped on
  its own, so `[{"suite": "hakank", "max": "60"}, {"suite": "xcsp3-cop", "max": "60"}]` takes 60 of each.
- A selection's `reference` decides which problems it keeps by their verdict in the lab's reference results:
  `decided` (the default) leaves out those the reference left undecided or took over 5 s to decide, since a problem
  no strong solver settles in seconds says little about klause; `proven` keeps only those it proved within 5 s; `missing` keeps only those with no reference verdict, which a reference run
  uses to backfill; `unsettled` keeps those plus the ones left unproven under a smaller budget than the run's, which a
  scheduled reference run uses; `any` keeps all. A
  problem with no reference verdict is kept by `decided`. Caps (`per-family`, `max`) count the problems the filter
  keeps, so a capped selection is filled from the decided ones. An experiment with a `backend=reference` arm defaults
  to `any`. Planning logs how many problems the filter left out.
- Each entry of `configs` (default: one empty config) is merged over `base`, then crossed with every combination of
  `grid`. A configuration takes `ref` (default `main`), `label`, `timeout` (ms, default 60000), `backend`, `engine`,
  `processors`, `lp`, `presolve`, `fixed`, `exact`, and `param.<name>` for `--param <name>=<value>`. `exact=true` forwards
  klause-cli's `--exact` flag; omit it or use `false` for the default policy. An arm without a `label` is
  named by the values that set it apart.
- `seeds` sets the solver seed; each listed seed is its own case. Without it each case runs once on the bench's seed.
- `repeats` (default 1, at most 100) runs each (problem, configuration, seed) that many times, identically, which
  measures the machine's timing noise apart from the seed's.
  On AWS spot instances, which share hosts with other tenants and can be taken back mid-problem (its remaining runs
  then move to another instance), timing is noisier than on the Mac: use `repeats` of 3 or more for an A/B whose
  answer rests on time, and more when the paired difference is small.
- `host` and `machines` are no longer followed: the lab places every job itself (see Running on AWS). They are still
  accepted, so older specs and clients work, and the reply's `placement` says where the job went and that they were
  ignored.
- `profileCli: true` (with explicit `parallel: 1`) records the whole CLI JVM under JFR's `profile` settings and
  measures its physical peak RSS and user/system CPU. It is placed like any other job, Mac first, then a node. On a
  node (Linux) GNU time measures the CLI; on the Mac (macOS), which has no GNU time, a Python wrapper reads the CLI's
  resource usage from `wait4` and passes SIGTERM, SIGINT and SIGHUP on to it. Both write `resources.txt` with the same
  keys and units (`peakRssKiB`, `userSeconds`, `systemSeconds`, `wallSeconds`, `exit`), and `measurement.json` says
  which measured the memory and where the case ran (the node's instance, or `lab`). Each case returns `cli.jfr`,
  `resources.txt` and `measurement.json` under `cases/<index>/profile-<uuid>/`, accessible through the job's Files page
  or `lab fetch`; on the Mac they are written there directly, along with the case's stdout and stderr as for any case.
  Each invocation has its own directory, including retries after a runner restart. Its metadata includes the instance,
  command and exit, full CLI JVM options, and the SHA-256 of a preserved `solve-record.json` when the solve returned one.
  A profiling job runs with a parallel limit of one, however it is edited through the client or API.
  These are separate profiling runs: JFR and launcher overhead affect timing. Compare uninstrumented runs for runtime,
  at the same revisions, hardware, heap settings and solver budgets. JFR allocation weights estimate allocated bytes;
  RSS is the launch process's high-water mark, including the CLI JVM and its launcher, in KiB. Missing or interrupted
  recordings are measurement failures. A dropped artifact transfer retries that transfer without re-solving the case.
  Profiling changes generated launch scripts only in that job's worktrees (on a node after build caching); solver jars
  and the baseline revision are unchanged. On a node, killing the timer kills its CLI child through Linux's
  parent-death signal; on the Mac, the runner's kill of the case's process group reaches it.
- `parallel` (cases at once; unset, the lab's `LAB_DEFAULT_PARALLEL`), `priority` and `confirm` as below.

### Profile jobs

A profile job runs one `main` of klause's build, once, at a commit: for a measurement the experiments cannot express,
such as timing a component on captured inputs. It is not for running the build's tests, lint or other gates: CI runs
those on every push. The lab runs no shell scripts or commands a job supplies; the code a profile job runs is the
class it names, from the commit it names, which must pass the trust check below. Instead of `problems` and arms, its
spec names a class in `com.eignex.klause.bench.profile` (klause-bench's package for them) and its arguments (`profile`), and `base` gives the commit (`ref`), the
time it may take (`timeout` in ms: **10 minutes by default, at most 2 hours**) and its cores (`processors`):

```json
{
  "name": "factor-timing-2513",
  "description": "Time the dense factor build on the captured bases at the PR head",
  "profile": {"main": "com.eignex.klause.bench.profile.FactorTiming", "args": ["bases=captured", "repeats=5"]},
  "base": {"ref": "codex/windowed-ger", "timeout": "1800000"}
}
```

The lab builds the commit as for an experiment (on AWS, from the build cache when it holds it) and runs the class on
the bench's classpath with each argument passed as it is, no shell between, under a JFR recording written to
`$OUT/profile.jfr`. `$OUT` is a directory whose files are kept with the job; the corpus cache is `$KLAUSE_CORPUS`, and
the built CLI and bench are `$KLAUSE_CLI` and `$KLAUSE_BENCH`, for the class to read. Its stdout, stderr, exit and
`$OUT` come back to the job directory (on AWS as well), read with `lab cat`, `lab files` and `lab fetch` and listed on
the job page. From AWS, at most 1 GiB of `$OUT` comes back, smallest files first; the rest are listed with their sizes
in `$OUT/omitted.tsv` and are lost with the instance, so a profile writes summaries rather than raw dumps. It queues, pauses, cancels and retries as an experiment does, and goes
Mac first like every job: the Mac when it is free, else an on-demand AWS node. On the Mac it runs alone, as every job does.

Every profile job is time-boxed. With no `timeout` it gets 10 minutes (`lab profile` sends none unless given its
`timeout-sec`); a `timeout` over 2 hours is cut to 2 hours, which the job log, the job page and the submit response
(`notes`) say. The case then runs for that limit plus two minutes for the JVM's start, the room every case gets. At
the limit its process group gets SIGTERM, and whatever is still running 30 seconds later gets SIGKILL: on AWS by
`timeout -k 30`, on the Mac by the runner, which does the same (a case of any job stopped at its limit gets this
grace; a cancel or a runner shutdown still kills at once, since a cancelled case's results are not wanted and a case
cut off by a shutdown reruns from scratch). SIGTERM runs the JVM's shutdown hooks, so JFR writes `profile.jfr` (its
recording is started with `dumponexit=true`) and the main can flush what it has. A profile main should therefore
write its results as it goes, or flush them in a shutdown hook within those 30 seconds; whatever it has written to
`$OUT`, and its stdout and stderr, stay with the job (from AWS, `$OUT` is fetched back whatever the exit). The case's
exit reads `timeout`, its `.termination` file has a `timeout:` line saying SIGTERM was sent, and a second line when
SIGKILL was needed; the job page says it was stopped at its time limit and lists `$OUT` as for any other exit.

`"profile": {"main": "com.eignex.klause.bench.profile.Tool", "runTimeoutSec": 180}` keeps the profile on the Mac
and opts into one active-time deadline across setup, build retries, the profile command,
and worktree cleanup. Queue and paused time do not count; resumed and explicitly retried work retains the active
time already recorded for that job. `base.timeout` remains the separate command allowance, including its existing
two-minute overhead. `runTimeoutSec` is capped at 7200 (2 hours) as `timeout` is. A whole-run deadline is
optional; jobs without it retain their existing timeout policy. A profile with one is placed on the Mac only,
since the Mac enforces the deadline on its own processes.
Reclaiming an interrupted bounded profile conservatively includes the interval until the runner reclaims it,
including runner downtime, so a restart cannot replenish its allowance.

Up to thirty seconds, or ten percent of a shorter allowance, is reserved for cleanup. Setup uses an owned
single-use Gradle process with Kotlin compilation in that process, so expiry can stop it without stopping a shared
daemon. Expiry interrupts the current stage and stops its owned process groups and descendants, SIGTERM first and
SIGKILL after a grace of 30 seconds (or a tenth of a shorter allowance, reserved from the work's share so cleanup
keeps its own), so a profile cut off mid-command still dumps its JFR and flushes; the case's `.termination` says
`deadline:`. It fails the job;
an explicit cancel records cancellation. The job keeps output and timeout/error diagnostics. Cleanup uses only
the remainder of the same deadline; incomplete cleanup is logged and the job is not reported successful. These
profiles do not repair shared Gradle transform caches. The deadline manages OS processes, not independently
created container/service resources, whose cleanup remains the profile's responsibility.

After a profile command reports its exit, AWS output retrieval retries only the result stream when SSH drops or
its transfer times out. It uses the file-transfer policy: at most eight 120-second attempts, with cancellable
backoff from 15 seconds to 5 minutes. It does not restart the completed profile command. Transfer failures remain
in `cases/<n>/out.transfer-attempts.tsv`; cleanup or failure-reporting errors prevent another attempt.
`out.transfer-status` distinguishes complete and incomplete retrieval,
and terminal failure also leaves `out.transfer-error`. Both terminal receipt writes are attempted independently.
When both writes succeed the original command exit is preserved; if either receipt cannot be written, the original
transfer error propagates with reporting failures suppressed, without retrying transfer or restarting the command.
A command exit or a DONE job does not establish output
custody. The instance is retained through these attempts and is still terminated after terminal transfer failure;
files not returned are unavailable, and the file API serves only files already retained on the lab machine.
Local archive unpacking runs once, with the same 120-second timeout and cancellation handling, and retains
`out.unpack.log` when successful. A terminal status identifies whether transfer or unpacking was incomplete.
`lab profile <main> <ref> <description> [host] [timeout-sec] [arg...]` queues one; `[host]` is ignored.
On AWS a profile job always runs on an on-demand node, never spot: AWS can take a spot instance back at two
minutes' notice, and a profile cannot pick up where it stopped, so it would start over. Experiments and reference A/Bs
resume after a reclaim and run on spot. The script jobs that came before (`script`, `scriptFile`) are retired: a spec
naming one is refused, and one still queued fails when it is planned.

### Password

Every page and API call, `/health` included, needs the lab's password, sent as HTTP Basic with any user name or none:
a browser asks for it once (leave the user name empty), `curl -u :<password>` sends it, and `lab` sends it from
`LAB_PASSWORD` or `~/.config/klause-lab/password`. The lab keeps only a salted SHA-256 hash of it, committed in
`src/main/resources/lab-password` and safe to publish, since the password is 32 random bytes no one can guess from it;
a slow hash would add nothing for such a secret, and every wrong password a stranger sends would cost the lab
machine CPU its cases' timings would feel. `LAB_PASSWORD_HASH` overrides it. To change it, run `klause-lab password`
(the installed binary, or `./gradlew run --args=password`), commit the hash it prints, and hand the password it
prints to the clients; the lab takes the new one when it updates. A header that passed is remembered by its digest;
failed passwords are limited to 10 a minute from one client and 60 a minute in all, past which a request gets 429
without being checked. A request from the public address is counted by the address the relay forwards.

A request that changes something (any method but GET, HEAD and OPTIONS) is refused when the browser says another
site made it (`Sec-Fetch-Site`, else `Origin` against `Host`): once a browser holds the password it would send it
with a form any other page submitted. Clients that send neither header, such as `curl` and `lab`, are unaffected.

### Which commits run

Every commit a job builds, in any arm, a schedule's run and a confirmation included, must be on `main` or on a branch
that only trusted GitHub users pushed to: `LAB_TRUSTED_USERS`, `rasros` unless set. Who pushed is read from GitHub's
repository activity, which records the actor of every branch creation, push, force push and merge; a commit's author
and committer are whatever its maker typed, so they prove nothing. The runner checks each commit when it plans the job,
after resolving its ref on the mirror, so a branch moved after the job was queued is checked as it is then; a commit
on several branches passes when one of them passes. Submitting an experiment or schedule on a branch checks that
branch at once, so a refused one never queues. The check fails closed: a branch GitHub has no activity for, or
GitHub not answering through the retries, fails the job. GitHub allows 60 unauthenticated calls an hour; a token in
`$LAB_DATA/github-token` (read-only, public repository access) lifts that, read on each use.

The runner sets an experiment up when it first takes it. It builds every commit the arms name once, each in its own
worktree. It then runs `klause-bench select` at the first arm's commit, which also fetches the corpus, and writes one
command per case: a `klause-bench solve-one` of one problem in one arm's worktree. Every arm of a problem runs back to
back, in an order rotated per problem, so drift and pauses spread evenly over the arms; with seeds or repeats the arms
alternate, every arm running once per seed and repeat before the next (A, B, A, B, not A, A, B, B). A case always solves (the
bench result cache is off) and its record lands in the store. `lab cases <id>` lists them, as does `GET
/experiments/<id>/cases`.

After building each commit, setup captures the installed CLI and Java runtime hashes in a provenance
manifest when that bench revision supports it. Each case validates the installed files' metadata and
adds its runtime options to the identity, avoiding a full runtime hash in every JVM. AWS setup also
captures a fresh manifest when it restores a cached distribution. Older bench revisions run without
one. The raw records and case CSV retain `buildFingerprint` and `validationPolicy`; arm summaries and
experiment pages list them and mark mixed identities or records missing provenance. These labels
disclose what contributed to the aggregate; they do not change its scoring.

An experiment's page (its job id) compares the arms:

- each arm's commit, cases done, solved, proven (optima and infeasibility), unsupported (models klause declines),
  errors (solver crashes and problems that did not compile or parse) and mean PAR-2 time (an unsolved run charged
  twice its budget);
- 95% intervals on solved, PAR-2 and the score, bootstrapped over problems, a problem's seeds and repeats averaged
  first so each problem counts once;
- each arm against the first: the geometric mean of its PAR-2 time over the first arm's on the problems either solved,
  with its interval and a Wilcoxon signed-rank p, and the problems it scored better and worse on, with a sign-test p;
- noisy problems: an arm's runs of one problem (seeds, repeats) that disagree on the verdict, or whose time to best
  spreads over 25% of its mean;
- a pairwise score by the MiniZinc Challenge rule `output/compare.sh` uses: per problem and seed, solved beats
  unsolved, proven beats unproven, then the better objective, and equal outcomes split the point by time;
- how each arm did against the first, problem by problem;
- disagreements first: one arm proving infeasibility where another solves, different proven optima, or a solution
  better than a proven optimum;
- every problem across the arms, the best cell marked, optionally only the rows where arms differ. A cell reads
  the objective (starred when proven), `sat`, `infeasible`, `unknown`, `unsupported` or `load error` (the reason on
  hover), or the case's status when it left no record, such as `failed` for a run killed by its timeout.

`lab compare <id> <id>...` gives the address of the same view over several experiments, each arm named by its job,
problems paired by suite and name. A scheduled run's page links to the comparison with the run before it.

`lab csv <id>` prints every case as CSV; `lab csv <id> <arm> [seed]` prints one arm's results as the bench writes
`output/<config>.csv`, for `bench credit` and the `output/` scripts.

## Active campaign evidence

Active campaigns publish reviewed snapshots as assets on a prerelease in this public
repository, tagged `active-evidence-<campaign>`. The release page is the stable reviewer
link; its download URLs require no lab access or signed URL. Assets remain available until
explicitly retired, independently of CI artifact expiry and lab job/instance deletion.
These are campaign data releases, not server distributions. Keep one release per active
campaign and use `--latest=false` so it does not replace the latest server release.

Stage only needed evidence under the shared external
`klause-evidence/campaigns/<campaign>/publication/`, outside every repository. Review
redistribution permissions and exclude credentials before publishing. Include experiment
specifications and actual selection, raw records/stdout/profiles, executed source and
installed-build/runtime hashes, validation provenance, and separately generated summaries
with their analyzer and execution identity. Preserve original bytes and retained hash
manifests. A requested revision alone does not identify the executed binary. Record missing
captures and unsupported checks explicitly; packaging does not strengthen solver claims.

Add `catalog.json` to the staged tree. It describes the current reason for retaining the
bundle, links its owning issues/PRs, and names evidence files relative to that tree:

```json
{
  "campaign": "lp-ceiling",
  "purpose": "Retain the evidence needed to review the active LP ceiling change.",
  "references": ["https://github.com/Eignex/klause/issues/123"],
  "limitations": ["Paired recorded outcomes do not independently certify proofs."],
  "roles": {
    "specifications": ["experiment.json", "selection.jsonl"],
    "raw": ["cases.json", "cases/0/record.json"],
    "profiles": [],
    "provenance": ["result-provenance.json"],
    "summaries": ["result-summary.json"],
    "validation": ["source-checks.json", "analysis-execution.json"]
  },
  "omissions": {"profiles": "This uninstrumented experiment collected no profiles."}
}
```

Role lists identify entry points, not every file; all staged files are included. Empty
roles require an omission reason. For multiple experiments, list each one's entry points
and explain coverage in the bundle. `deploy/campaign-evidence.py` uses Python 3.11+ standard
library only. It packages regular files deterministically, rejects symlinks and output
inside the input tree, hashes every file, and verifies the completed archive. Run from this
checkout (no solver build or measurement is involved):

```sh
python3 deploy/campaign-evidence.py pack /absolute/path/to/publication /absolute/path/to/assets
```

The command prints two paths: `<campaign>-<archive-sha256>.tar.gz` and its
`.manifest.json`. The manifest includes the catalog, archive identity and every member's
size/SHA-256. Snapshot names cannot be overwritten. Hash verification establishes byte
identity and completeness against the reviewed manifest, not authenticity or numerical
correctness; pin the manifest's own SHA-256 in the review alongside its download link.

Publish the two generated paths with `gh`, targeting the reviewed lab tooling commit.
Write release notes outside the repository describing scope, analyzer/source-check
provenance and limitations, with the manifest SHA-256 and owning review links:

```sh
gh release create active-evidence-lp-ceiling /absolute/path/to/assets/lp-ceiling-<sha>.tar.gz \
  /absolute/path/to/assets/lp-ceiling-<sha>.manifest.json --repo Eignex/klause-lab \
  --target <lab-commit> --prerelease --latest=false --title 'Active evidence: LP ceiling' \
  --notes-file /absolute/path/to/release-notes.md
```

Before claiming acceptance, download both assets from the release's public
`https://github.com/Eignex/klause-lab/releases/download/<tag>/<asset>` URLs with `curl -fL`
without credentials into a fresh external directory. Compare the downloaded manifest's
SHA-256 with the review's pinned value, then verify **before extracting**:

```sh
python3 deploy/campaign-evidence.py verify /absolute/path/to/download/<campaign>-<sha>.tar.gz \
  /absolute/path/to/download/<campaign>-<sha>.manifest.json
```

Verification streams every member without extraction and rejects altered bytes, missing
or extra files, duplicate paths, traversal paths and nonregular members. Only a verified
archive should be extracted to an empty external directory for the retained analyzers.
Numerical/source rechecking runs on a separate analysis host with the retained dependencies;
keep its execution identity and reports with the active evidence. Storage checks can run
locally with `python3 -m unittest discover -s deploy/tests -p test_campaign_evidence.py`;
the existing build gate also runs these tests.

For an updated snapshot, upload new content-addressed assets with `gh release upload`
without `--clobber`, verify their anonymous downloads, and update the release notes and
review links. Remove superseded assets only after confirming no active review still needs
them. Keep the release mutable: immutable releases would prevent required retirement.
When all owning work has promoted its useful conclusions, reusable tools or regression
fixtures, update issue/PR links to those durable results and delete the obsolete release
and tag with `gh release delete <tag> --repo Eignex/klause-lab --cleanup-tag --yes`.
Confirm the release and tag are absent through the GitHub API. Cached redirects and
already-issued downloads can remain accessible after deletion; a fresh asset request
can be checked with a unique query string. Deletion does not promise immediate download
revocation. Delete its external staging/download copies and ended lab jobs with `deploy/lab rm <id>`
after checking their remaining consumers. Do not delete a shared job another active
campaign needs. Do not move retired bundles into a historical archive. No raw/generated
campaign data or measurement workflows belong in either repository.

## Reference results

The lab keeps reference solvers' verdicts on problems in its own database, one row per (collection, problem,
solver), and an experiment's page compares each arm against them: problems both solved, solved by only one side,
proven optima reached, objectives that beat the reference's best, and the mean gap where the arm is worse. The
reference ran under its own budget, so this compares verdicts, not speed. Contradictions are listed as
disagreements, which makes the reference a soundness check: an arm proving infeasible what the reference solved, or
beating a proven reference optimum.
It also lists the worse incumbents: each problem an arm solved to a worse objective than the reference, with its gap,
the largest first; a schedule's trend counts them per run.

An arm with `"backend": "reference"` runs each problem's reference solver instead of klause: clasp for DIMACS, OPB and
WCNF, the cp-sat image for XCSP3, z3 for SMT-LIB, SCIP for MPS, cp-sat for MiniZinc. Each such case adds its verdict
to the reference results as it finishes, keyed by collection and problem; a stronger result already there stays. So
`{"problems": {"suite": "satlib"}, "base": {"backend": "reference", "timeout": "60000"}}` fills in clasp's verdicts
on all of SATLIB.

`deploy/specs/reference-qfnra.json` runs all frozen `qfnra-core-v1` and
`qfnra-holdout-v1` polynomial-real inputs with Z3 at ten seconds/P1 and three
repeats. `reference-qfnra-source-canonical.json` separately runs both canonical
negative-constant variants with the same policy. Their versioned identities do not
replace frozen originals or suppress original dedicated-source load declines.
`reference-qfnra-extensions.json` separately runs the five extension
controls. All use `reference=any`, so UNKNOWN, timeouts, absent references and
Klause frontend declines cannot change the planned membership. These specs require
a Klause revision containing the selected QF_NRA catalog (canonical variants
require bf1ecb126b43ff37f4c9a885186771cf68fc5b1f or its merged successor); pin `base.ref` to an immutable
commit in a saved execution spec. The generic reference and cvc5 declarations
also include the fetched, unclassified `smtlib-qfnra` archive; its logic tag alone
does not prove polynomial membership.

The ordinary reference arm stores QF_NRA rows and binary/options identity just as
other SMT inputs. Frozen QF_NRA benchmark collections appear in Reference coverage
and collection selection despite living beside fixtures. Exact `root-obj` models,
source hashes, raw stdout and stderr/exit/timeout diagnostics remain in the bench
case artifacts. Solver parse/exit errors count as errors and do not become trusted
reference rows; UNKNOWN and timeout remain undecided references. Z3 agreement is
reference evidence; independently checked source witnesses and proof certificates
have separate authority. `reference-qfnra-sweep.json` adds Z3 and cvc5 at 60 s, the budget
of the klause-reference schedules, over the frozen suites and up to 100 per family of the
archive. Those schedules include the archive (3 per family, at most 60) and the frozen
core, and the status sweep reaches QF_NRA through the `nonlinear-real` set, drawn like the
other theme sets from problems the classifier tags `nonlinear-real`.

An arm can name which reference solver runs with `solver`: `kissat` beside clasp for CNF, `cvc5` beside z3 for
SMT-LIB, `highs` beside SCIP for MPS, or any MiniZinc solver such as `chuffed` beside cp-sat. When every arm names the
same solver, a selection's `reference` filter reads only that solver's results, so `"reference": "missing"` with
`"solver": "highs"` solves the problems HiGHS has no verdict on, whatever SCIP has. Problems where two reference
solvers contradict each other (one proving infeasible what another solved, different proven optima, or a solution
beyond another's proven optimum or dual bound) are listed on the Reference tab: one of them is wrong. Both rows stay
as evidence, but a proof another solver's solution contradicts is set aside, whichever solver made it: comparisons
and the `reference` filters see an infeasibility proof against a solution as undecided, and an optimum a better
solution beats, or whose dual bound one lies beyond (by more than a relative 1e-6), as an unproven solution.

Results are kept per (collection, problem, solver); a new one replaces a stored one unless the stored one is stronger
(decided over undecided, proven over unproven, then the better objective, then the bigger budget), so no proof is lost
and an equally strong rerun refreshes the row. A verdict is timed by the solver's solve time, else by the solve's
wall-clock time (a model refuted while flattening reports no solve time), and only an undecided one by its budget. A row
produced another way (its `version`: an MPS reference's solver build, options and how its solution was checked, see
klause-bench's README) replaces the stored one whatever its strength, so a proof judged wrongly never outlives its
correction. SCIP and HiGHS rows from before their solutions were checked are stale: kept on the problem pages as
evidence, never used by comparisons or filters, and replaced by the next checked run of each solver, which a
`"reference": "missing"` run of that solver backfills. A bench too old to check sends rows that arrive stale and
never replace a checked one. `lab references <text> [solver] [verdict]` searches them. The Reference tab shows what they cover, filters them, and links each problem to a page
with every solver's verdict and every lab run of it.

A problem's name can come to mean other source files (MiniZinc Challenge `2011/bacp` was once `bacp-9.mzn`, later
`bacp-24.mzn`), so each row keeps the identity of the files it was solved on: a digest of the case record's
`sourceHashes` (the model's and data's SHA-256), shown on the problem page. A verdict only counts against runs on the
same identity: comparisons, shortfalls, disagreements and the scoreboard take each problem's identity from the run's
cases, and two solvers' rows only contradict each other or set each other's proofs aside on the same identity. A row
bound to new files replaces that solver's row on others whatever its strength. Rows from before identities were kept
are unbound. A row a lab run made (`source` `lab:<job>`) is bound when the runner starts, to the identity that run's
cases recorded. One that stays unbound, imported or from a bench too old to record sources, counts against any run
unless a row bound to other files shows the name has meant something else, when it could be from either and is left
out; a run that recorded no source reads the unbound rows, else the bound ones. Selecting problems, before anything
ran, reads the rows for the identity of the newest bound row, else the unbound ones.

XCSP3 cp-sat rows require an image SHA-256 and a registered importer policy in
`References.XCSP_POLICY`; the active policy is `xcsp3-source-preserving-v2`.
Unversioned or unregistered XCSP rows remain visible as stale evidence and cannot
influence comparisons, selection or replacement of current rows. Startup retires
stored legacy policies without changing their verdict, objective, version or
source; it updates the trust flag and reference timestamp so cached comparisons
refresh. Imports apply the same rule. MiniZinc cp-sat identities retain their own
admission policy. Register an importer policy with its source-preserving adapter
change before refreshing reference rows through ordinary reference experiments.


The lab has three tabs: Queue (experiments, their pages and comparisons), Regression (a schedule's trend) and
Reference.

An experiment whose cases could take more than `LAB_MAX_EXPERIMENT_HOURS` (default 24), each using its whole budget,
fails at planning with the count and the estimate; resubmit with `"confirm": true` to run it. Priorities, pause,
`parallel` and cancel act between cases (see Client).

`lab schedule <name> <ref> <experiment.json> <interval-sec>` reruns an experiment on every new commit of `<ref>`. The
runner checks it every interval, also while a job runs: when `<ref>` resolves to a commit other than the last one it
queued, and that run has ended, it queues the experiment with every arm at the new commit, named `<name>@<sha>`, which
waits its turn by priority like any other. A scheduled experiment's configs name no `ref`. An unchanged ref queues
nothing. A check asks origin for a branch or tag's commit with `git ls-remote`, one small request, and fetches only when
it moved, so a short interval such as 120 is cheap; the minimum is 60. `lab check <id>` makes a schedule due at once,
and the runner checks it within 10 s, which is also what a push hook would call. `lab launch <id>`, or the schedule's
"launch now" button, queues a run at the ref's commit within 10 s even when that commit already ran, to repeat one; a
run still queued or going is not doubled, the launch waiting for it. `lab schedules` lists them with the
last commit and job; `lab unschedule <id>` removes one. `lab reschedule <id> <experiment.json|-> [interval-sec|-]
[next-in-hours]` changes a schedule in place; `-` keeps a field. A new experiment forgets the last commit, so the
schedule queues it at the ref's commit on its next check, once a run still going has ended; `lab check` makes that
now. The schedule keeps its phase unless `next-in-hours` sets when it is next checked, which is how schedules are
staggered.

The Regression trend shows the runs of the schedule's current experiment: a run of an edited one measured something
else. Runs match when their experiments agree on selections, arms, seeds and repeats (name, priority and `parallel` do
not count) and they planned the same problems, so a named set redrawn in place, or a selection the reference filter
shifts, starts a new line just as an edited spec does. `show them` brings back the earlier runs, each change of experiment marked by a dashed line.

Planning logs each selection: its arguments, how many problems the bench selected, how many the reference filter
left out and how many were planned. A selection without `per-family` whose every family gave one problem is flagged:
most suites take one per family unless asked for more.

A wall-clock budget makes any problem near it flip between runs on machine noise alone, so a schedule's trend reads
runs against each other problem by problem, not just by its totals:

- Each run shows, against the run before it of the same experiment, the problems it lost and gained, with a two-sided
  sign test on them. Fifteen lost and none gained is p < 0.001 even when the solved shares' bands overlap.
- Proof-strength changes and worse/better optimization incumbents also select problems for confirmation even when
  both runs remain solved. Proof means optimality or infeasibility; a satisfaction witness stays in the feasibility
  count. These counts are separate from feasibility flips and their sign test. Objective comparisons
  honor minimize/maximize and compare exact integers/rationals before the legacy numeric channel; malformed exact
  objectives receive no quality credit. Rejected source witnesses and approximate-model proof claims receive no
  proof credit. Contradictory infeasibility or optimum claims select no proof/quality transition and remain disagreements.
  Proof and quality confirmations require a strict majority of matched seed/repeat blocks; missing, duplicate or
  invalid blocks cannot vote, and missing blocks stay in the denominator. Timing differences never select a transition.
  These are confirmations of recorded solver claims, not independent proof certification.
- A finished run that gained or lost a solution or a proof queues a confirmation job, `<schedule>~confirm@<sha>-<run>`;
  an unproven objective that moved is shown but not rerun, since at a sweep's budget it moves with the machine's timing,
  both ways about equally, dozens of problems each run. The job reruns the deduplicated union of those problems (at
  most 40), on both commits, alternating case by case so both see the same machine, three times each. A flip counts
  only when the repeats agree with it; the trend shows `confirmed −lost +gained`, linking the job, whose page compares
  the two commits. It runs one priority above its schedule, so it goes ahead of the schedule's next run.
- Experiments run 4 cases at a time by default, one per performance core (see What a case runs in), and the lab never updates
  itself while a case runs (see Updating).
- The lab's own work beside the cases runs in the background, which on macOS confines it to the efficiency cores:
  the API service, the runner's git checks during a job and update builds. Setup steps run alone, at full speed.

Schedules are for klause. The reference solvers do not change, so a reference run is a one-off experiment; rerunning
one with `"reference": "unsettled"` on its selections solves only the problems added since, plus those a smaller budget
left unproven. A schedule of reference runs would have no Regression trend.

A `set` names a fixed problem set in klause-bench's `sets/` (see its README), selected whole and, by default, not
reference-filtered: it was drawn from the reference and is meant to stay put. The status sweep runs `set=sweep`.
`deploy/make-sets.py` draws the sets from `select features=true` output and the reference results.

`deploy/specs/` holds the experiments the lab runs: `status-sweep.json` (schedule, every 120 s, the default engine
on `set=sweep` at 10 s, kept fast), and the 60 s runs over the broader selection, one per engine:
`klause-reference.json` (the default sequential portfolio, daily), and every 3 days, staggered a day apart so one
runs per day: `klause-reference-ls.json` (local search only), `klause-reference-bt.json` (backtracking only) and
`klause-reference-parallel.json` (the portfolio on 4 cores). Their MiniZinc Challenge selection is
`mzn-bench-compatible-v1`, the suite whose models the installed MiniZinc compiler accepts, so a model it cannot
compile is left out by the bench before the lab's reference filter and caps rather than failing as a case; the daily
run also takes every problem of `smtlib-qfnra-source-canonical-v1`, the canonical-syntax variants of the frozen QF_NRA
cores, whatever their reference. `reference.json` is the one-off reference run, and `reference-<solver>.json` the one-off runs of the second reference
solvers (HiGHS, kissat, Chuffed, cvc5) over the problems each has no verdict on, and `reference-scip.json` SCIP's
over the MPS problems it has no current verdict on (its stale rows count as none). Change
a schedule by editing its file and running `lab reschedule`. A schedule's phase is its last check: the runner checks
it again one interval later, so staggered schedules stay a day apart; `lab reschedule <id> - - <hours>` moves one.

## Running on AWS

The lab places every job itself; whoever submits one does not choose. Every job goes Mac first, then an AWS spot
node, then an on-demand node, with fixed exceptions:

- Scheduled runs (`<schedule>@<sha>`) and their confirmations (`~confirm`), reference runs (an arm with
  `backend=reference`), exact problem lists and profiles with a `runTimeoutSec` stay on the Mac only.
- Every job fits the Mac: a `processors` over the Mac's cores (`LAB_CORES`), in `base`, a config or the grid, and a
  `parallel` over its limit (`LAB_MAX_PARALLEL`) are cut down to them at submit. The response's `notes`, the job log
  and the job page (`capped`) say what was cut; a grid axis whose values the cap makes equal keeps one of them. So no
  job needs AWS: nodes are overflow capacity for the Mac's queue, launched on pressure alone (below). `profileCli`
  jobs too go Mac first: both hosts measure them (see the spec's keys above).
- Profile jobs never run on spot: Mac, then an on-demand node.

A job's `host` records the placement: `lab` (the Mac only), `aws` (a node only) or `auto` (whichever is free first).
The queue shows them as `Mac only`, `AWS` and `Mac or AWS`, in one queue ordered by priority, series and age. The Mac
takes the next job it may run when it is free: jobs placed on the Mac first, then `auto` jobs in queue order. A job
placed on the Mac always goes before an `auto` job there, whatever their priorities, and an `auto` job the Mac took
gives way to one between its cases, never the other way round, so sweeps and reference runs are never held up. An
`auto` job is taken by whichever comes first, the free Mac or a free node (a node leaves the free Mac's next job to
it); once taken its `host` is where it went, since a planned job's commands name paths on the host that planned it.
When the runner starts, it places every queued job that has not been planned again by these rules, cut down to fit
the Mac first, so a job queued under the old model with an explicit `aws` or `lab`, or with more `processors` than the
Mac has, becomes `auto` unless an exception applies; a planned job placed on `aws` is released as below, and other
planned ones keep their host.

A planned job a node gives back goes Mac first like any other: when its nodes retire or are taken back with cases
left, when it is paused, or when the lab restarts while it runs on AWS, it is queued again and placed by these rules,
normally `auto`. The free Mac then takes it over before a node does, and a node may still take it when the pressure
calls for one. The host that takes a planned job over writes its queued commands again for its own worktrees and
corpus, since the plan named the paths of the host that made it; finished cases and their results stay, except the
finished arms of an iteration left part done, which run again with the rest of it (below). A retried job is placed
the same way. A job whose cancel was requested is never queued again: whatever would requeue it (a pause, a lost or
retired node, a restart) ends it cancelled instead, its unfinished cases with it, and the runner ends any queued job
left with a cancel requested when it starts. `lab host <id> auto|lab|aws` (`POST /jobs/{id}/host`) remains as an operator override for a queued,
unplanned job; agents never need it. `lab machines` and `POST /jobs/{id}/machines` are retired and ignored.

AWS work runs on a pool of nodes. A node is an EC2 instance that, once booted, takes work until there is none for it:
first it joins a job already running on AWS that still has problems no node has taken (building that job's commits
first), otherwise it claims the next queued job it may run (not Mac-only, its cases fit the node's cores, a profile job
only on an on-demand node, priority order). A node with nothing to take for `nodeIdleMin` minutes (5) is terminated,
and at once when a lab update waits. It stops taking problems an hour before its `maxHours` power-off. A job's
instances are the nodes working on it right now; its page lists them with their sizes and markets, and the queue's
summary has a line on the whole pool: each node's size, market and what it is doing, and the pressure.

Nodes launch on pressure, with damping:

- Pressure is the estimated core-hours of the work waiting for AWS: queued `auto` jobs at priority `nodeMinPriority`
  (0) or above and every queued `aws` job (only an operator's override places one there), less the job the Mac takes
  next when it is free, plus the unclaimed problems
  of jobs running on AWS. A job's core-hours are its cases times each arm's `timeout` times its `processors`: a planned
  job counts its queued cases; an unplanned one is estimated from its selections (`max`, else `per-family` times 10
  families, else 20 problems per selection, the smaller when both are set) times arms, seeds and repeats; a profile
  job is one case of its allowance. Work below `nodeMinPriority` that the Mac can run waits for the Mac: no node
  launches for it or takes it. Mac-only work never counts.
- The launch threshold is set by the Mac: the work that would keep it busy for `nodeLaunchMacHours` (1.0) hours, that
  is the Mac's cores times that in core-hours (10 core-hours on a 10-core Mac). The first node launches once pressure
  has stayed at or above it for `nodeSustainMin` minutes (10). Another launches only while pressure is at least twice
  the threshold per node already up or launching, again for `nodeSustainMin`, and `nodeCooldownMin` minutes (5) after
  the last launch; at most `maxInstances` nodes, within the `vcpuQuota`. While a lab update waits, no node launches.
  A small queue never starts a node: a job alone launches one only when its own estimate is over the threshold.
- Each launch asks for spot and asks again every 30 s while AWS has no spot capacity or quota; after `spotWaitMin`
  minutes (15) it buys on demand, or at once if the account cannot launch spot at all, but only with `onDemand=true`.
  With `onDemand=false`, the default, a launch never falls back to on demand and gives up once the pressure is gone.
  A node launched while a profile job waits and no on-demand node is up is bought on demand directly. A spot node
  idle beside a profile job it may not run retires after `nodeIdleMin`, and an on-demand node then launches. A
  profile alone is far under the threshold (one of 3 minutes on all 10 of the Mac's cores is under a core-hour) and
  waits for the Mac or an on-demand node already up; profiles run on the Mac unless a large backlog waits.
- Node size is chosen at launch and kept small: the smallest size of the family (`instanceType`, then `4xlarge`,
  `8xlarge`, …) that has the cores the largest waiting case needs (its `processors`) and covers this node's share of
  the pressure (pressure over the nodes up plus this one) within two hours, at most `nodeMaxVcpus` (16, a
  `c7i.4xlarge`) unless a case itself needs more. More pressure is met by another node later, through the damping,
  rather than one large node up front. So a few core-hours get a `c7i.2xlarge`, a larger backlog a `c7i.4xlarge`, and
  a backlog beyond that a second node.
- A node runs one case per two physical cores unless the job sets `parallel`, which on AWS counts per node, within
  its cores: single-core arms run 2 cases on a c7i.2xlarge and 4 on a c7i.4xlarge.

`aws.properties` keys for the pool, all optional and reread on every poll: `maxInstances`, `vcpuQuota`, `onDemand`,
`spotWaitMin`, `nodeIdleMin`, `nodeMinPriority`, `nodeLaunchMacHours`, `nodeSustainMin`, `nodeCooldownMin`,
`nodeMaxVcpus`. `market`, `spotFallback` and `nodeLaunchCoreHours` are no longer read.

- One-time setup: `deploy/aws-setup.sh [user@lab-host]`, from a machine with an admin session (`aws login`). It
  creates an SSH key pair, a security group admitting SSH from the Mac only, and an IAM user `klause-lab` that may only
  launch tagged instances of one type, tag them, terminate instances tagged `klause-lab` and read the Ubuntu image id.
  The admin session is the CLI's default profile unless `ADMIN_PROFILE` names another; the lab itself only ever uses
  the Mac's `klause-lab` profile. The script also creates EC2's spot service-linked role, which spot launches need and
  the `klause-lab` user may not create.
  It installs the AWS CLI on the Mac if missing, writes the user's key into the Mac's `klause-lab` profile and writes
  `$LAB_DATA/aws/aws.properties` (region, instance type `c7i.2xlarge`, `maxInstances` and `vcpuQuota`, now 8 and the account's 128 on-demand vCPUs, `cores` 4, `maxHours` 24,
  the MiniZinc version, the corpus bucket and instance profile). The lab reads that file; restart the services to
  start the AWS worker. After that the worker rereads it on every poll, so a raised `vcpuQuota` or a new
  `maxInstances` applies to the next launch without a restart.
- The corpus cache lives in a private S3 bucket in the same region, `klause-lab-corpus-<account>-<region>`. An instance
  syncs it down while it builds (fast and free within the region) and, after planning, pushes back any collection it
  had to fetch from its source, so each collection is downloaded from the internet once. The instances reach it through
  the role `klause-lab-instance`, which may read and write only that bucket.
- The bucket also holds Gradle's dependency cache and wrapper distribution as one archive, restored before every build
  and refreshed after one, and each commit's built distributions (`builds/verified-v1/<sha>.tar.zst`): an instance that finds its
  commit there unpacks it instead of building, so a commit is built once across every job and instance.
- Build and Gradle archives use a verified cache namespace; older archives are left intact and are not reused.
  Downloads and extraction are staged. Failed transfers, truncated archives, missing bootstrap classes, missing
  launchers/main classes, corrupt JARs or incomplete launcher classpaths reject the cache without contaminating
  the target. A failed build stops setup and records its output in `setup.log`; no cases start and no build cache
  is published. Supported provenance capture must succeed and write a nonempty manifest. Publication packs a
  complete local archive before uploading, so a failed tar cannot stream an incomplete installation into S3.
- A node boots Ubuntu 24.04 with a JDK 25 and MiniZinc, clones klause and builds every commit its job's arms name.
  SSH remembers each host key by region and EC2 instance ID, since AWS reuses public addresses after termination.
  A new instance at a reused address may enroll its key; a changed key on the same instance is refused. So a plain
  `ssh ubuntu@<address>` with the lab's `known_hosts` finds no key for the address and refuses in batch mode; reach a
  node working on a running job with `lab ssh <id> [n] [command]`, which connects through the server the way the lab
  does. The node that claims a job plans it, and nodes that join fetch the same corpora before their cases start. The
  nodes share the job's problems: each takes the next one in plan order whenever it has room for a case, and a
  problem's arms, seeds and repeats all run on the node that took it, so each comparison is made on one machine and no
  node idles while another has a backlog. An iteration, the arms of one problem, seed and repeat (an A/B's A and B),
  is never split across machines: when AWS takes a spot node back, the cases it finished of a problem it had not
  finished lose their results and go back to the queue with the rest of their iteration, to run again together on the
  node that takes them; and a planned job set up afresh on another host (the Mac taking it over, or a node claiming it
  again after it went back to the queue) first queues again the finished cases of any iteration left part done. Each node keeps a job's case files in `~/jobs/<id>`, removed when it moves
  on. Cases run over SSH under `timeout`, and each record is copied back as its case ends, so the job page,
  comparisons and CSVs work as for a local job.
- A running AWS case carries its phase, so a case RUNNING past its `timeoutSec` shows whether the solver or the lab
  holds it: `EXECUTING`, `TRANSFERRING_OUTPUT` (with the record's `elapsedMs`), `RETRY_BACKOFF` after SSH dropped
  (attempt n of m and when the next starts) or `TERMINATING` after a timeout or cancel. The job page lists it under
  `running` and beside the case's status; `GET /jobs/{id}` has it as each command's `phase` (`phase`, `since`,
  `attempt`, `attempts`, `retryAt`, `elapsedMs`), null once the case ends and for cases on the Mac.
- Leave `parallel` unset on AWS. The default, one case per two physical cores, is the controlled setting: each case is
  a bench JVM driving a solver JVM, whose JIT compiler and garbage collector threads need the second core, and a
  problem's arms run on the same node, so a comparison never spans machines. `parallel=1` does not make an experiment
  more controlled; it only leaves half the node idle and takes twice as long. Set it lower only for an arm that needs
  more memory per case than half the node.
- The lab does not update itself while any node is up: the restart would end the threads that drive them. While an
  update waits, no node launches or claims a new job, nodes only join jobs already running, and an idle node retires
  at once, so the pool drains and the update lands; queued jobs start after the restart.
- Spot nodes are the same hardware at a fraction of the on-demand price, and AWS can take one back at two minutes'
  notice. When it does, the lab finds the node gone at its next dropped connection and puts the cases it was running,
  and the problems it had taken, back in the job's queue; the job's other nodes take them, or a free node joins. A job
  left with work and no node is queued again rather than failed, and the next free node takes it up. Cases that
  finished keep their records, so a reclaim costs only the cases in flight. The account's spot quota is a quota of its
  own, apart from `vcpuQuota`, and spot launches need the service-linked role `aws-setup.sh` creates. Spot timing is
  noisier than the Mac's, so give a timing A/B on AWS more `repeats`.
- The corpus is kept in the bucket as one tar per collection, `corpus-packs/<collection>.tar`, with an index of each
  pack's signature (a digest of its files' names and sizes) in `corpus-packs/index.tsv`. A node pulls the index
  and the packs that differ from what it holds while it builds, and the node that plans a job pushes, after
  planning, a pack of each collection it changed. That is a few dozen requests an instance; the files one by one,
  as they were kept in `corpus/` before, were over fifty thousand, which cost more in S3 requests than the instances
  did. Until a push has written the first packs, an instance still pulls `corpus/` file by file.
- A paused job goes back to the queue once its nodes finish their cases in flight, and a node takes it up again on
  resume. A node powers itself off, which terminates it, after `maxHours`. When the runner starts, it requeues any
  `aws` job left running and terminates every lab-tagged instance, none of which is a node of the new runner; the
  sweep every five minutes terminates any lab instance that is not a node.

## Public access

The lab answers at `https://lab.eignex.com` from anywhere, and only with its password (see Password). The home
network sits behind the ISP's carrier-grade NAT, so nothing can connect in to it, router forwarding included; the Mac
connects out to a relay instead:

- The relay is an EC2 instance `klause-relay` (t4g.micro, eu-north-1) with the Elastic IP `klause-relay`, a security
  group open to 22, 80 and 443, and Caddy, which serves the domain with a Let's Encrypt certificate it renews itself
  and passes every request to `127.0.0.1:8420`. Its `tunnel` user can hold that one reverse forward and nothing else:
  no shell, no command, no other port.
- The Mac holds the tunnel: the launchd daemon `com.eignex.klause-relay` runs `ssh -N -R 127.0.0.1:8420:...` to the
  relay as the lab's user with `~/.ssh/klause-lab-relay`, starts at boot and reconnects after a drop. Its errors go
  to `$LAB_DATA/logs/relay.log`.
- DNS for `eignex.com` is hosted at DigitalOcean (the registrar only points there); the `lab` A record names the
  Elastic IP.

`deploy/relay/create.sh [domain] [user@lab-host]` builds all of it from an admin AWS session, reusing what exists:
the Elastic IP carries over to a rebuilt relay, so DNS stays put, and with a DigitalOcean token in
`~/.config/digitalocean/token` it sets the A record too. It ends by printing the one step left, on the Mac:
`sudo deploy/relay/install-tunnel.sh <relay-ip>`, which installs the daemon and forgets the old relay's host key. The
relay is tagged only `Name=klause-relay`, never `klause-lab`, so the AWS worker's cleanup of its own instances leaves
it alone. Expose the lab only while its password check is live: a request without it must get 401.

## Crash safety

- `api` and `runner` are separate processes that share one SQLite database (WAL, `synchronous=FULL`). A solve
  that takes the runner down leaves the queue readable.
- Every state change is committed before the next step starts. A restarted runner resumes the job it was on:
  - finished cases keep their results;
  - an interrupted case is rerun from the start;
  - the interrupted case's processes are killed first, so the rerun never runs alongside them. Each case leads its
    own process group, and the group is killed whole, including children whose shell already died. A recorded
    start time keeps a reused pid from being taken for the case.
- A case's `<n>.exit` file is written atomically once it ends: an exit code, or `timeout`.
- A service shutdown, such as a reboot, kills the running cases but does not record them, so they rerun after boot.
- Whenever the lab kills a case's processes it first appends why to `<n>.termination`, one line each, the time in
  epoch milliseconds and then the reason: `shutdown:` (the runner stopped; the case reruns), `orphan:` (a stopped
  runner left it running; killed before the rerun), `cancel:`, `timeout:`, `deadline:` (a profile job's whole-run
  limit), `sibling:` (another case could not run, which failed the job) or `host-lost:` (AWS took the instance back;
  the case goes back to the queue). A pause or a yield kills nothing: the job stops only between cases. A case that
  exits 137 (SIGKILL) or 143 (SIGTERM) with no such line gets an `external:` one, which says only that the lab did not
  send the signal; on AWS it adds the instance's kernel log lines about killed processes, or says there were none, and
  a kill once the case's limit has passed is `timeout`'s own. The job page shows the lines under each case's exit, and
  `GET /jobs/{id}` as each command's `terminations`.
- Setup steps that can fail for a passing reason are retried with exponential backoff before a job fails:
  - fetching the mirror and adding a worktree: up to `LAB_RETRY_ATTEMPTS` tries (default 6), waiting
    `LAB_RETRY_BASE_SEC` (15) after the first failure and doubling to at most `LAB_RETRY_MAX_SEC` (300), so a network
    outage of about 8 minutes is ridden out;
  - building and selecting problems, which can also fail on a download but more often for good: up to
    `LAB_BUILD_RETRY_ATTEMPTS` tries (default 3) on the same waits.

  Each retry is logged to the job. A cancel ends the wait, and the job is then cancelled, not failed. A schedule
  whose fetch still fails stays due, so the next poll tries again rather than a whole interval later. A case that
  runs and fails is a result and is not retried.
- `lab retry <id>`, or the job page's retry button, queues a failed or cancelled job again: its finished cases are
  kept, its unfinished ones rerun, and it is set up afresh, planned only if it never was.
- The service manager restarts either process after a crash and starts both at boot without a login: launchd
  daemons on macOS, systemd user units with lingering on Linux. On macOS the runner also runs under
  `caffeinate -i`, which keeps the machine awake while it lives.

## Gradle

The lab builds klause with its own Gradle home, `$LAB_DATA/gradle`, not `~/.gradle`: that one is shared with whatever
else builds on the machine, and other daemons' cache cleanups left its transform records naming files they had
deleted, which failed every build until the cache was cleared. A build that still fails on a missing transform file
stops the lab's Gradle daemons and clears its transform caches before its next try. The first build after the switch
fetches the dependencies into the new home once.

## Disk

- Below `LAB_MIN_FREE_GB` (10) free on the data directory's disk, the Mac's queue is held until there is room again;
  AWS jobs go on. `/disk` shows what takes the disk, measured every 30 minutes, with the largest job directories.
- From AWS, at most 1 GiB of a profile job's `$OUT` comes back (see Profile jobs).
- An ended job is trimmed a week after it ended, and sooner, largest first, while the disk has under 25 GB free: its
  `job.log`, `setup.log`, `sha`, commands' `.exit` and `.termination` files, `selection-*.jsonl` and each case's record (the `.json` in
  `cases/<i>`) stay, and everything else goes: raw solver output, case stdout and stderr, profiles, `$OUT`. Every
  case's record is in the database as well, so its results, comparisons and trend are unaffected. A trimmed job has a
  `trimmed` file (when, bytes freed) and a line in its log, and is not trimmed again; queued and running jobs are
  never touched. `lab rm` still deletes a job outright. Fetch what you need from a job (`lab fetch`) within a week.

## What a case runs in

A case runs `klause-bench solve-one` in its arm's worktree, with this environment:

| variable | value |
| --- | --- |
| `KLAUSE_CLI_OPTS` | `LAB_SOLVE_JAVA_OPTS` (default `-Xmx3g -XX:+UseSerialGC`) plus `-XX:ActiveProcessorCount=<the case's cores>` |
| `JOB_DIR` | the job's output directory; the case writes its record under `cases/<n>/` |
| `OPENBLAS_NUM_THREADS`, `VECLIB_MAXIMUM_THREADS`, `MKL_NUM_THREADS`, `OMP_NUM_THREADS` | `1` |

Stdout and stderr go to `JOB_DIR/<n>.out` and `<n>.err`; the raw solver output and the record go to
`JOB_DIR/cases/<n>/`. On AWS the record comes back with every case, and stdout and stderr only for a case that
failed, timed out or left no record, or for a profiling job: the rest would cost two SSH round trips a case. The worktrees are deleted when the job ends. A failing case does not stop the experiment: the
rest still run, the job ends DONE, and the failures are counted on it. FAILED means the experiment itself could not
run, such as a failed checkout, build or selection, or a plan over the size limit.

Cases run in order within two limits. A case holds as many cores as its arm's `processors` (one without), and the
running cases together hold at most `LAB_CORES` (default: the machine's cores less two, for the JVMs around the
solves and the machine). Each solve sees exactly its cores (`-XX:ActiveProcessorCount`), so a portfolio sizes its
threads to them. A case too big for the cores left waits for room rather than letting later ones pass it, and an arm
asking for more processors than `LAB_CORES` is cut down to them at submit (see Running on AWS). On top of that, at most `parallel` cases run at
once: the spec's, or `LAB_DEFAULT_PARALLEL` (default 4) when it sets none, and never more than `LAB_MAX_PARALLEL`
(default 6; a larger one is cut down to it at submit). The default is the lab Mac's (an M4) performance cores: a solve on one of its six efficiency cores runs
at a fraction of the speed, and which solves land there changes from run to run, which is timing noise. The cap is
bounded by memory: each solve holds its own heap (`-Xmx3g` by default), so keep `LAB_MAX_PARALLEL` times that within
the machine's memory. `deploy/lab parallel <id> <n>` changes an experiment's limit while it runs: raising it starts
more cases at once, lowering it starts no more until fewer than `n` run, and never stops a running one. Setup always
runs alone.

`LAB_SHARED_PATHS` names worktree directories every job shares, by default `klause-bench/build/bench-cache`. Cases
run with the bench result cache off, so it serves only the bench's own tooling.

## Docker

The containerised reference solvers (SCIP, clasp, the XCSP3 cp-sat image) need Docker. `install.sh` picks it up
by itself: on macOS it installs a boot-time colima daemon when colima is installed (`brew install colima docker`), on
Linux it uses the system Docker. The runner then waits for `docker info` to answer before it takes a job. Set
`LAB_DOCKER=none` in `lab.env` to opt out. Build the images once on the server, from a klause checkout:

```sh
docker build -t klause-scip klause-bench/scip
docker build -t klause-clasp klause-bench/clasp
docker build -t klause-xcsp3-cpsat klause-bench/xcsp3-cpsat
```

## Setup

1. Install git and any JDK 17 or newer on the server; Gradle needs one to launch. The Gradle toolchain downloads
   JDK 25, and the services and the klause builds run on that.
2. For SSH access from the dev PC, enable Remote Login on macOS and install the PC's key with `ssh-copy-id`. The
   lab itself clones klause over HTTPS and needs no key.
3. Clone this repository on the server and run `deploy/install.sh`. It builds the lab, runs the host check, and
   registers the services. Settings live in `~/.config/klause-lab/lab.env`, which the first run creates with every
   setting commented out at its default; rerun the script after changing it. The services get a fixed PATH
   (Homebrew's and the system directories on macOS, `~/.local/bin` and the system ones on Linux); `LAB_PATH` puts
   more in front. `LAB_INSTALL_DRY_RUN=1 deploy/install.sh` writes the service files to a temporary directory and
   registers nothing; it reuses the existing build, since rebuilding under running services breaks them until
   they restart. On macOS it asks for sudo, to install the launchd daemons and to set
   `pmset -a sleep 0 disksleep 0`.
4. Bench downloads each corpus collection the first time an experiment selects from it. To copy the dev PC's instead (51 GB on the
   first run, incremental after that), run `deploy/lab corpus` there; it needs the SSH access from step 2.
5. Prefer Ethernet to Wi-Fi: a Wi-Fi link that drops leaves the server unreachable while it keeps working.

Logs go to `$LAB_DATA/logs/{api,runner}.log`.

## Updating

`deploy/update.sh` on the server pulls this repository, builds it, and restarts the services on the new build. It
needs no sudo. Each build is copied into its own `$LAB_DATA/releases/<sha>-<time>/`, the services run
`releases/current`, and the three newest releases are kept. A rebuild never rewrites jars a running service still
loads classes from, which would break it until it restarts. The cases running when the services restart are rerun. Rerun `install.sh` instead when `lab.env` or the service setup itself changed.

The runner does this on its own, and never alongside a solve: at most every `LAB_UPDATE_CHECK_SEC` (default 300) it
fetches the checkout `install.sh` ran from, and when its branch's upstream is ahead, the running job yields between
its cases, as it does to a higher-priority job; `update.sh` then runs with nothing running and the job resumes on the
new release. A build competing with the solves for the CPU pushes problems near their budget over it, which reads as
a regression. `lab update` asks for the check at once. Deploy by pushing and running `lab update` rather than running
`update.sh` by hand, which builds (niced) and restarts while cases run. A failed build or host check leaves the
running release in place, with the output in `$LAB_DATA/logs/update.log`, and is tried again at the next check.
`LAB_UPDATE_CHECK_SEC=0` turns it off.

## Client

`deploy/lab` wraps the API with curl, jq and rsync. Set `LAB_HOST` to override the default server, `192.168.50.104`.

Experiments run highest priority first (`"priority"` in the spec, default 0; `lab priority <id> <n>` changes it
later). Within one priority, the job whose series last finished a run longest ago goes first (a schedule's runs
share its name, an experiment's reruns their name; one that never ran goes before all), then the oldest, so a
schedule that just ran does not keep going ahead of one that has waited. A running experiment checks before each case whether it was paused (`lab
pause <id>`) or a higher-priority one is waiting; if so it starts no more, lets its running cases finish, and goes
back to the queue with its worktrees and finished cases kept, resuming at its next case when it is taken again. `lab
resume <id>` releases a paused one.

`lab wait <id>...` returns once every listed job has ended, printing each one's final status. The server holds each
request open until its job ends (`GET /jobs/<id>/wait`), so a waiting client makes no repeated requests; run it in the
background to be told when a job is done.

```sh
deploy/lab run restarts-ab.json
deploy/lab ls
deploy/lab cases 7            # each case: status, arm, problem, result
deploy/lab tail 7             # the last 8 kB of the job's log: planning, commands, exits
deploy/lab tail 7 0           # the last 8 kB of case 0's stdout; `err` for stderr
deploy/lab csv 7 > 7.csv
deploy/lab files 7            # every file of job 7: job.log, setup.log, cases/<n>/record.json, case output
deploy/lab profile com.eignex.klause.bench.profile.Timing main "time X on the captured inputs"   # a profile job, Mac first; not for gates
deploy/lab cat 7 setup.log    # one of them; a third argument prints only its last bytes
deploy/lab fetch 7            # download jobs/7/ to ./lab-jobs/7 over HTTP
deploy/lab ssh 7 0 uptime     # a command on node 0 of those on a running AWS job; no command opens a shell
deploy/lab cancel 7
```

The browser page at `http://<server>:8420/` shows the running and queued experiments, with each queued one's place
in line and the cases running now, then the schedules and the history. The history filters by text and by state, a
job's name links to all its runs (a schedule's runs included), and `older` pages back past the newest 200. An
id opens its page: settings, the setup and job logs, the comparison above, every case's command with its exit code
and output, and, while it is unfinished, pause/resume, priority, parallel and cancel. One that ran every case but
saw some fail reads `DONE · N failed`, which links to just the failed cases. The live parts refresh in place every 10 s, while a job is unfinished.

## API

| method | path | |
| --- | --- | --- |
| `GET` | `/jobs`, `/jobs/{id}` | job and case-command states; `/jobs` lists 50 by default, each with its command `counts` instead of its commands (`?commands=true` for them), and takes `?limit`, `?before=<id>` and `?name` |
| `POST` | `/jobs/{id}/cancel` | a queued job is dropped; a running one has its cases' process trees killed |
| `POST` | `/jobs/{id}/retry` | a failed or cancelled job is queued again; its finished cases are kept |
| `GET` | `/jobs/{id}/files` | the files in the job directory |
| `GET` | `/jobs/{id}/files/{path}` | one file; `?tail=<bytes>` for the end of a growing log |
| `POST` | `/experiments` | an experiment spec (above) → `{"id", "placement"}`, where the lab placed it; a branch or tag origin lacks is refused; the job it queues plans its cases when it starts |
| `GET` | `/experiments/{id}/arms` | each arm's configuration and the commit it built |
| `GET` | `/experiments/{id}/cases` | each case's problem, arm, seed, status and result record |
| `GET` | `/experiments/{id}/cases.csv` | the same as CSV |
| `GET` | `/experiments/{id}/stats` | the intervals, paired tests and noisy problems as JSON |
| `GET` | `/experiments/{id}/bench.csv?arm=<label>[&seed=<n>]` | one arm's results in the bench's result-table format |
| `GET` | `/compare?jobs=<id>,<id>…` | the comparison page over several experiments |
| `POST` | `/schedules` | `{"name", "ref", "intervalSec", "experiment": <spec>}` → `{"id"}` |
| `GET` | `/schedules`; `POST` `/schedules/{id}/delete` | list or remove schedules |
| `POST` | `/schedules/{id}` | `{"experiment"?, "intervalSec"?, "nextCheckInSec"?}`: change a schedule; it keeps its phase unless told |
| `POST` | `/schedules/{id}/check` | check the schedule's ref now instead of at its next interval |
| `GET` | `/trend[?name=<schedule>][&all=1]` | a schedule's runs of its current experiment, or all of them; the first klause schedule without a name |
| `GET` | `/references[?q=&solver=&collection=&verdict=]` | reference coverage, or the results the filters keep |
| `GET` | `/problem?collection=<c>&problem=<p>` | one problem: every reference solver's verdict and every lab run |
| `GET` | `/experiments/{id}/reference` | each arm against the reference, with disagreements |
| `GET` | `/health` | queue counts and the koblas report |

A browser (`Accept: text/html`) gets a page for `/jobs/{id}` and `/jobs/{id}/files`; `?json` gets the data instead.

The API has no authentication. Keep it on the LAN.
