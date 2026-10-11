# klause lab setup

An experiment server for klause. An experiment solves a selection of problems under one or more configurations,
one problem per case. The server builds each commit the configurations name, runs the cases, one experiment at a
time, and compares the results. It serves the queue, live output and results over HTTP.

It runs on macOS or Linux. Both services log the koblas backend at startup: the engine, and whichever host BLAS
it finds. They refuse to start (exit 78) unless koblas runs its Vector API engine, which is what klause's Level 1
calls use, so no timing comes from the scalar fallback. Host BLAS serves only Level 2 and 3, which klause does not
call, so it is reported and not required. `klause-lab check` prints the report and does nothing else.

## Experiments

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
- `host`: `aws` or `lab` (see Running on AWS), and for `aws`, `machines`: how many instances to split it over. Left out,
  an experiment runs on AWS when the lab has AWS settings and AWS can run it (klause arms only, selected problems,
  cores within the largest instance), and on the lab machine otherwise; a schedule's runs default to the lab machine.
- `profileCli: true` (AWS only, with explicit `parallel: 1`) records the whole CLI JVM under JFR's `profile` settings
  and measures its physical peak RSS and user/system CPU with GNU time. Each case returns `cli.jfr`, `resources.txt`
  and `measurement.json` under `cases/<index>/profile-<uuid>/`, accessible through the job's Files page or `lab fetch`.
  Each invocation has its own directory, including retries after a runner restart. Its metadata includes the instance,
  command and exit, full CLI JVM options, and the SHA-256 of a preserved `solve-record.json` when the solve returned one.
  A profiling job stays on AWS with a parallel limit of one when it is edited through the client or API.
  These are separate profiling runs: JFR and launcher overhead affect timing. Compare uninstrumented runs for runtime,
  at the same revisions, hardware, heap settings and solver budgets. JFR allocation weights estimate allocated bytes;
  RSS is the launch process's high-water mark, including the CLI JVM and its launcher, in KiB. Missing or interrupted
  recordings are measurement failures. A dropped artifact transfer retries that transfer without re-solving the case.
  Profiling changes generated launch scripts only on that job's instances, after build caching; solver jars and the
  baseline revision are unchanged. Killing the timer kills its CLI child through Linux's parent-death signal.
- `parallel` (cases at once; unset, the lab's `LAB_DEFAULT_PARALLEL`), `priority` and `confirm` as below.

### Script jobs

A script job runs one bash script, once, in a build of a commit: for a measurement or check the experiments cannot
express, such as timing a component on captured inputs or inspecting a JFR recording. It is not for running the
build's tests, lint or other gates: CI runs those on every push. Instead of `problems` and arms, its spec names the
script inline (`script`) or as a path in the repository at the commit (`scriptFile`), and `base` gives the commit
(`ref`), the time it may take (`timeout` in ms, an hour by default) and its cores (`processors`):

```json
{
  "name": "factor-timing-2513",
  "description": "Time the dense factor build on the captured bases at the PR head",
  "script": "bash tools/factor-timing.sh > \"$OUT/timing.json\"",
  "base": {"ref": "codex/windowed-ger", "timeout": "1800000"}
}
```

The lab builds the commit as for an experiment (on AWS, from the build cache when it holds it) and runs the script
in the worktree with `$OUT`, a directory whose files are kept with the job, the corpus cache as `$KLAUSE_CORPUS`, and
the built CLI and bench as `$KLAUSE_CLI` and `$KLAUSE_BENCH`. Its stdout, stderr, exit and `$OUT` come back to the
job directory (on AWS as well), read with `lab cat`, `lab files` and `lab fetch` and listed on the job page. It
queues, pauses, cancels and retries as an experiment does, and runs on AWS unless it says `"host": "lab"`; on the Mac
it runs alone, as every job does, so it neither disturbs the lab's cases nor is disturbed by them.
`lab script <file> <ref> <description> [aws|lab] [timeout-sec]` queues one from a script file.

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
repeats. `reference-qfnra-extensions.json` separately runs the five extension
controls. Both use `reference=any`, so UNKNOWN, timeouts, absent references and
Klause frontend declines cannot change the planned membership. These specs require
a Klause revision containing the QF_NRA catalog; pin `base.ref` to an immutable
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
have separate authority. This reference setup does not add NRA to Klause regression
schedules or establish completion of the nonlinear implementation.

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
- A finished run that changed feasibility, proof or objective queues a confirmation job, `<schedule>~confirm@<sha>-<run>`: the deduplicated union of those problems (at
  most 40), on both commits, alternating case by case so both see the same machine, three times each. A flip counts
  only when the repeats agree with it; the trend shows `confirmed −lost +gained`, linking the job, whose page compares
  the two commits. It runs at the schedule's priority and, never having run, ahead of the schedule's next run.
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
`klause-reference-parallel.json` (the portfolio on 4 cores). `reference.json` is the one-off reference run, and `reference-<solver>.json` the one-off runs of the second reference
solvers (HiGHS, kissat, Chuffed, cvc5) over the problems each has no verdict on, and `reference-scip.json` SCIP's
over the MPS problems it has no current verdict on (its stale rows count as none). Change
a schedule by editing its file and running `lab reschedule`. A schedule's phase is its last check: the runner checks
it again one interval later, so staggered schedules stay a day apart; `lab reschedule <id> - - <hours>` moves one.

## Running on AWS

An experiment with `"host": "aws"` runs on EC2 instances the lab launches for it, on demand: nothing runs on AWS while
no such job is queued. Only klause arms go there; reference runs stay on the Mac, which has the reference solvers.

- One-time setup: `deploy/aws-setup.sh [user@lab-host]`, from a machine with an admin session (`aws login`). It
  creates an SSH key pair, a security group admitting SSH from the Mac only, and an IAM user `klause-lab` that may only
  launch tagged instances of one type, tag them, terminate instances tagged `klause-lab` and read the Ubuntu image id.
  It installs the AWS CLI on the Mac if missing, writes the user's key into the Mac's `klause-lab` profile and writes
  `$LAB_DATA/aws/aws.properties` (region, instance type `c7i.2xlarge`, `maxInstances` and `vcpuQuota`, now 8 and the account's 128 on-demand vCPUs, `cores` 4, `maxHours` 24,
  the MiniZinc version, the corpus bucket and instance profile). The lab reads that file; restart the services to
  start the AWS worker. After that the worker rereads it on every poll, so a raised `vcpuQuota` or a new
  `maxInstances` applies to the next job without a restart.
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
- The Mac runner takes only `lab` jobs and the AWS worker only `aws` ones, each queue in the usual order. The worker
  claims the next `aws` job when the vCPUs for one instance are free and gives it `machines` instances (default
  `maxInstances`), or as many as the account's `vcpuQuota` of on-demand vCPUs leaves, each job on its own thread.
  A job that started with fewer grows while it runs: whenever no `aws` job waits in the queue and the quota has room,
  it launches another instance, up to its `machines`, as long as it has problems no instance has taken. An instance
  with no problem left to take is terminated at once, so its vCPUs go to the jobs still running or waiting.
- An instance's size follows the job's arms: the smallest size of the family (`c7i.2xlarge`, `4xlarge`, `8xlarge`)
  with as many physical cores as the arm with the most `processors` needs for one case. Each instance runs one case
  per two physical cores, a case holding its `processors` of them, unless the spec sets `parallel`, which on AWS
  counts per instance. So single-core arms run 2 cases on each c7i.2xlarge, and a 4-core portfolio gets a
  c7i.2xlarge running one case, or 8 cores per case a c7i.4xlarge. The job page shows its shape, e.g.
  `4 × c7i.2xlarge, 2 cases each`.
- A job's instances boot Ubuntu 24.04 with a JDK 25 and MiniZinc, clone klause and build every commit its arms name.
  SSH remembers each host key by region and EC2 instance ID, since AWS reuses public addresses after termination.
  A new instance at a reused address may enroll its key; a changed key on the same instance is refused. So a plain
  `ssh ubuntu@<address>` with the lab's `known_hosts` finds no key for the address and refuses in batch mode; reach a
  running job's instance with `lab ssh <id> [n] [command]`, which connects through the server the way the lab does.
  It plans on the first, and the others fetch the same corpora before cases start. The instances share the job's
  problems: each takes the next one in plan order whenever it has room for a case, and a problem's arms, seeds and
  repeats all run on the instance that took it, so each comparison is made on one machine and no instance idles while
  another has a backlog. An instance added later builds and fetches the same way, then takes problems too. Each instance
  runs `parallel` cases at once (default one per two physical cores) over SSH under
  `timeout`, and each record is copied back as its case ends, so the job page, comparisons and CSVs work as for a
  local job.
- Leave `parallel` unset on AWS, and size a job with `machines`. The default, one case per two physical cores, is the
  controlled setting: each case is a bench JVM driving a solver JVM, whose JIT compiler and garbage collector threads
  need the second core, and a problem's arms run on the same instance, so a comparison never spans machines.
  `parallel=1` does not make an experiment more controlled; it only leaves half the instance idle and takes twice as
  long. Set it lower only for an arm that needs more memory per case than half the instance. Likewise, don't keep `machines` low to spare the quota: the worker shares it between jobs on its own,
  and a job waits for vCPUs rather than failing. Use as many instances as the job has problems to spread over, up to
  `maxInstances`.
- The queue and history show where each job runs (Mac or AWS, and a running AWS job's instance count), and a queued
  job's place in its own host's queue. A queued job that has not been planned can move between the Mac and AWS from
  its page, or with `lab host <id> lab|aws [machines]`; once planned, its commands name paths on its host.
  An AWS job's instance count can change until it ends, on its page or with `lab machines <id> [n]`: a queued or
  paused job launches that many next; a running one grows to a raised count as the quota allows, and keeps the
  instances it has when the count is lowered.
- The lab does not update itself while an AWS job runs: the restart would end the worker thread, and with it the job's
  instances. While an update waits, the worker starts no new AWS job and running ones launch no more instances, so the
  running jobs drain and the update lands; the queued ones start after the restart.
- The instances are terminated when the job ends, whatever way; a pause terminates them too and a resume launches new
  ones. An instance powers itself off, which terminates it, after `maxHours`. When the runner starts, it requeues any
  `aws` job left running and terminates every lab-tagged instance no running job owns.

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
asking for more processors than `LAB_CORES` is refused at submit. On top of that, at most `parallel` cases run at
once: the spec's, or `LAB_DEFAULT_PARALLEL` (default 4) when it sets none, and never more than `LAB_MAX_PARALLEL`
(default 6). The default is the lab Mac's (an M4) performance cores: a solve on one of its six efficiency cores runs
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
deploy/lab script time.sh main "time X on the captured inputs"   # a script job on AWS; not for gates
deploy/lab cat 7 setup.log    # one of them; a third argument prints only its last bytes
deploy/lab fetch 7            # download jobs/7/ to ./lab-jobs/7 over HTTP
deploy/lab ssh 7 0 uptime     # a command on instance 0 of a running AWS job; no command opens a shell
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
| `GET` | `/jobs`, `/jobs/{id}` | job and case-command states; `/jobs` takes `?limit`, `?before=<id>` and `?name` |
| `POST` | `/jobs/{id}/cancel` | a queued job is dropped; a running one has its cases' process trees killed |
| `POST` | `/jobs/{id}/retry` | a failed or cancelled job is queued again; its finished cases are kept |
| `GET` | `/jobs/{id}/files` | the files in the job directory |
| `GET` | `/jobs/{id}/files/{path}` | one file; `?tail=<bytes>` for the end of a growing log |
| `POST` | `/experiments` | an experiment spec (above) → `{"id"}`; a branch or tag origin lacks is refused; the job it queues plans its cases when it starts |
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
