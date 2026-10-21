# klause lab setup

An experiment server for klause. An experiment solves a selection of problems under one or more configurations,
one problem per case. The server builds each commit the configurations name, runs the cases, one experiment at a
time, and compares the results. It serves the queue, live output and results over HTTP.

It runs on macOS or Linux. The lab runs work on its local machine (a Mac here) and, when the queue calls for it,
on cloud nodes rented from a cloud provider (AWS EC2 here; see Running on cloud nodes). Both services log the koblas backend at startup: the engine, and whichever host BLAS
it finds. They refuse to start (exit 78) unless koblas runs its Vector API engine, which is what klause's Level 1
calls use, so no timing comes from the scalar fallback. Host BLAS serves only Level 2 and 3, which klause does not
call, so it is reported and not required. `klause-lab check` prints the report and does nothing else.

## Experiments

**One question, one experiment.** Submit one experiment that covers all the problems a question needs; never split
it into batches. The lab bounds runtime itself (each case's `timeout`, profile time limits) and orders and places
the work (local machine first, cloud nodes by queue pressure), and a batch defeats that placement and the arms' pairing and
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
  named by the values that set it apart. `param.source-validation=true` asks the bench for independent source-witness
  validation (see [Source-witness validation](#source-witness-validation)).
- `seeds` sets the solver seed; each listed seed is its own case. Without it each case runs once on the bench's seed.
- `repeats` (default 1, at most 100) runs each (problem, configuration, seed) that many times, identically, which
  measures the machine's timing noise apart from the seed's.
  On spot nodes (AWS spot instances), which share hosts with other tenants and can be taken back mid-problem (its remaining runs
  then move to another instance), timing is noisier than locally: use `repeats` of 3 or more for an A/B whose
  answer rests on time, and more when the paired difference is small.
- `host` and `machines` are no longer followed: the lab places every job itself (see Running on cloud nodes). They are still
  accepted, so older specs and clients work, and the reply's `placement` says where the job went and that they were
  ignored.
- `profileCli: true` (with explicit `parallel: 1`) records the whole CLI JVM under JFR's `profile` settings and
  measures its physical peak RSS and user/system CPU. It is placed like any other job, local first, then a node. On a
  node (Linux) GNU time measures the CLI; on the local machine (macOS here), which has no GNU time, BSD `time -l` does, its report
  converted by the launcher, which passes SIGTERM, SIGINT and SIGHUP on to the CLI. Both write `resources.txt` with the same
  keys and units (`peakRssKiB`, `userSeconds`, `systemSeconds`, `wallSeconds`, `exit`), and `measurement.json` says
  which measured the memory and where the case ran (the node's instance, or `lab`, the local machine). Each case returns `cli.jfr`,
  `resources.txt` and `measurement.json` under `cases/<index>/profile-<uuid>/`, accessible through the job's Files page
  or `lab fetch`; locally they are written there directly, along with the case's stdout and stderr as for any case.
  Each invocation has its own directory, including retries after a runner restart. Its metadata includes the instance,
  command and exit, full CLI JVM options, and the SHA-256 of a preserved `solve-record.json` when the solve returned one.
  A profiling job runs with a parallel limit of one, however it is edited through the client or API.
  These are separate profiling runs: JFR and launcher overhead affect timing. Compare uninstrumented runs for runtime,
  at the same revisions, hardware, heap settings and solver budgets. JFR allocation weights estimate allocated bytes;
  RSS is the launch process's high-water mark, including the CLI JVM and its launcher, in KiB. Missing or interrupted
  recordings are measurement failures. A dropped artifact transfer retries that transfer without re-solving the case.
  Profiling changes generated launch scripts only in that job's worktrees (on a node after build caching); solver jars
  and the baseline revision are unchanged. On a node, killing the timer kills its CLI child through Linux's
  parent-death signal; locally, the runner's kill of the case's process group reaches it.
- `profileCompilation` (with `profileCli: true`) also records every JIT compilation of the CLI JVM: through startup,
  the solve and shutdown. `"jfr"` records `jdk.Compilation` with no threshold
  (`-XX:StartFlightRecording=…,settings=profile,jdk.Compilation#threshold=0ms,…`; the profile settings otherwise keep
  only compilations of 100 ms or more). `"jfr+log"` also passes `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation
  -XX:LogFile=<profile dir>/hotspot-compilation.log`. That is HotSpot's compilation log, with every task's calls and
  whether each was inlined, so the helpers and inlined callees inside each compiled root show up. The log runs to
  hundreds of MB per run, so ask for it only when inlining matters. From a node it comes back gzipped
  (`hotspot-compilation.log.gz`). HotSpot merges its compiler threads' logs into it when the JVM exits cleanly, so a
  case stopped at its limit still gets one, because the stop is a SIGTERM with a grace period, but a SIGKILL leaves it
  without the inlining.
  These cases are **instrumented**. `measurement.json` says so (`instrumented`, `instrumentation`, `compilation`), as
  does each case's JSON (`instrumented` in `/experiments/{id}/cases`) and the job page. The spec's fingerprint differs
  from the uninstrumented one's, so a schedule's trend keeps the two apart. `/compare` across jobs, the one place
  they could be set beside uninstrumented runs, marks their arms, and warns that a comparison mixing the two measures
  the instrumentation. Compare runtime between uninstrumented runs only.
- After each case of such a job, the lab (not klause, and the same locally and for a node's case once its captures
  are back) writes `compilation-report.json` beside the captures in `cases/<index>/profile-<uuid>/`. It reads them with
  the JDK's own JFR consumer and an XML stream reader. The report (`schemaVersion` 2, `kind: "compilation"`) is a
  summary; every compile id's own record is in `compilation-tasks.jsonl.gz` beside it (below). It holds:
  - `totals`: the compilation count (every compile id a compiler took up), compiler ms (the sum of the compilations'
    own durations, on the compiler threads beside the solve), the longest compilation, succeeded, failed (bailouts
    included), OSR, code bytes and inlined bytes, and `bailouts`, `unknown` and `queuedOnly` (ids HotSpot queued that
    no compiler took up: no compilation, so in none of the other counts).
  - `byTier` (compiler and level: C1 is tiers 1–3, C2 tier 4), `byKind` (`normal`, `osr`), `byStatus` (`succeeded`;
    `bailout`, a compilation the compiler gave up on and said why; `failed`, one that did not succeed and gave no
    reason; `unknown`, one whose end neither capture saw) and `failures` (each failure or bailout reason, with its count).
  - `methods`: per compiled root (`holder.name(descriptor)`), its count, total and longest ms, tiers, OSR count,
    failures and code size. With the log it also holds `inlined` (each callee inlined into it, at any depth, with its
    count, over all its compilations) and `inlineFailures` (why calls were not inlined, by reason). `byTier` keeps the
    root's compilations apart by compiler, tier and kind (`normal` or `osr`): count, total and longest ms, how they
    ended (counts that are zero are left out), code and inlined bytes, and their `compileIds`, which name their task
    records. `queued` counts its ids that were only queued.
  - `jvmStatistics`: the JVM's own running totals from its last `jdk.CompilerStatistics` event. `log`: the log's task,
    inline and compiler-thread counts.
  - `coverage`: the two captures joined by compile id, exactly: how many ids JFR held, how many log tasks, both
    (`matched`), JFR without a log task (`jfrOnly`), a log task without JFR (`logOnly`), and JFR with nothing at all in
    the log (`jfrWithoutLogRecord`); the log's queued tasks (`logQueued`), installed code (`logNMethods`) and native
    wrappers (`nativeWrappers`, ids that compile nothing); `queuedOnly`; the ids both captures hold but disagree on
    (`disagreements`: method, level, OSR or success); the first and last id; the ids neither holds at all; the recorded
    `jdk.Compilation` threshold; JFR events read; data lost; and log bytes.
  - `provenance`: the JVM (from `jdk.JVMInformation` and the VM's initial system properties: name, version, vendor,
    arguments, flags, pid); the source (job, case, problem, arm, ref, commit, and `from` when it was read from an
    archived report); the worker (the lab release it ran on with its sha, the host, when, and the analysis job if one
    asked); the execution (from `measurement.json` and `resources.txt`); every capture's size and SHA-256; and
    `archive`, when the captures came from a pinned archive (below).
  - `tasks`: the task records' file, `compilation-tasks.jsonl.gz`, with its record count, bytes and SHA-256.
  - `missing` and `incomplete`: each observation the captures lack, or hold only in part, with `what` and `why`.
    Examples: no recording; a recording that stops mid-chunk or has no JVM shutdown event; a `jdk.Compilation`
    threshold above zero; JFR data loss; startup compilations before the recording began (JFR alone always has these,
    and the log covers them); no or a cut-short log; a log without the compiler threads' logs; a case that exited
    non-zero, such as one stopped at its limit. With both captures whole, the join adds `jfr-without-log-task` (JFR
    compilations with no log task, so no inlining), `log-without-jfr` (compilations the log saw end after the recording
    began with no `jdk.Compilation` event, such as those while JFR starts up or after its last chunk) and
    `jfr-log-disagree`. `complete` is true only when both lists are empty.

  `compilation-tasks.jsonl.gz` holds one JSON line per compile id, in id order, never merged: a run compiles
  thousands of tasks, each with its own inlining, which as JSON runs to tens of MB, so they stay out of the report,
  which a page or `jq` then reads at once; gzipped they shrink about tenfold, and `zcat … | jq -c 'select(…)'` streams
  them. The report names the file with its SHA-256, so a reader can tell the two belong together. A record has
  `compileId`, `method`, `compiler`, `level`, `kind` (`normal`, `osr`) and `osrBci`; `status` (`succeeded`, `bailout`,
  `failed`, `unknown`, or `queued` for an id never taken up) with `failure`; `join` (`matched`, `jfr-only`,
  `log-only`, or `jfr`/`log` when the case had only that capture) and `observed` (each of `jfr`, `log-queued`,
  `log-task`, `log-done`, `log-nmethod`, `log-not-entrant`); `attempt` (the how-manyth compilation of the method at
  that compiler, tier and kind), `retryOf` (the id it compiles again, after a deoptimisation or a failure) and
  `previous` (the method's compilation before, at any tier), with `notEntrant` (why its code was made not entrant);
  `queuedAt`, `startedAt` and `endedAt` (the log's seconds since JVM start) and `jfrStartMs` (epoch ms); `ms` (JFR's
  duration, else the log's), `jfrMs` and `logMs`; `bytecodeBytes`, `codeBytes` and `inlinedBytes`; `jfrEvents`;
  `disagreements`; and `inlining`, that task's own decisions in the order the compiler made them, each with its
  `depth` (1 for a call in the root), `caller`, `callee`, `bci`, `inlined`, `reason`, the callee's `bytes`, and `late`
  for C2's late inlining. Fields the captures do not say are left out. Retries, bailouts, queued ids and ids in one
  capture only each keep a record of their own.

  A schema 1 report, from before, still reads (`CompilationReports.read`), with what schema 2 added left empty.
  The raw captures stay with the case. The job's Files page lists them with the report and its task records, and
  `lab cat` or `lab fetch` gets them.
- `{"analyze": {"job": <id>, "kind": "compilation"}}` posted to `/experiments` (`lab analyze <id>`) queues an
  analysis job. It runs locally, where the captures are. It reruns the lab's analyser over an ended `profileCli`
  job's retained captures and writes each case's `compilation-report.json` afresh, with the analysis job in its
  provenance. It also writes an `analysis.json` summary into its own directory, and its page lists the reports. Nothing
  is built and no solver or klause code runs, so it suits a report the analyser has since learned to improve, and a
  plain `profileCli` job too, whose report then says its threshold kept only the slow compilations. It is refused,
  saying why, when the job has not ended, ran without `profileCli`, or has no captures left. That includes a job
  trimmed a week after it ended (see Disk), whose raw captures are gone while its reports and manifests stay.
- `{"analyze": {"kind": "compilation", "archive": "<url or name>", "sha256": "<hex>"}}` (`lab analyze --archive
  <url|name> --sha256 <hex>`) analyses the captures in a saved-capture archive instead, once their job is cleaned up:
  a `campaign-evidence` archive (see Active campaign evidence) such as a release asset
  `https://github.com/Eignex/klause-lab/releases/download/<tag>/<campaign>-<sha256>.tar.gz`. Nothing is built, no
  solver or klause code runs, and no original job is needed. Because the lab is reachable from the internet, the
  archive is pinned and checked:
  - `sha256` is required, 64 lowercase hex digits; the archive's own SHA-256 must equal it.
  - `archive` is an HTTPS URL under an allowed prefix (`LAB_ARCHIVE_PREFIXES`, comma-separated, ending in `/`;
    `https://github.com/Eignex/` unless set), with no credentials, query, fragment, port or `..`, naming a
    `.tar.gz`; on github.com it must be a release asset. GitHub redirects a download to its asset hosts, and the
    lab follows at most five redirects, each to HTTPS on the URL's own host or one of `LAB_ARCHIVE_REDIRECT_HOSTS`
    (GitHub's asset hosts unless set). Or `archive` is a relative path under `$LAB_DATA/archives` on the lab, a
    regular file inside it (no `..`, absolute paths or links out).
  - The manifest is the `<campaign>-<sha256>.manifest.json` beside the archive unless `manifest` names another (by
    the same rules); `manifestSha256` pins it too when given. It must name the pinned archive.
  - The download stops at `LAB_ARCHIVE_MAX_MB` (2048) and the manifest's files at `LAB_ARCHIVE_MAX_UNPACKED_MB`
    (16384) unpacked; a pax or long-name tar header holds at most 1 MiB.
  - Before anything is extracted, every member is verified against the manifest as `campaign-evidence verify` does:
    each file's size and SHA-256, no missing, extra or duplicate members, no traversal path, nothing but regular
    files (links refused), and the catalog. Then only the profile directories' captures (`…/cases/<i>/profile-<id>/`
    with `cli.jfr`, `hotspot-compilation.log[.gz]`, `measurement.json`, `resources.txt`, `solve-record.json`) are
    extracted, into the analysis job's own `archive/<path in the archive>/`, each path checked again to stay inside.
    A report the archive held beside them is extracted as `archived-compilation-report.json`.

  The API refuses (409) an archive it may not fetch, saying why; the runner then fails the job, saying why, on a
  download that runs over, a hash that differs from its pin, or a member that fails verification. Otherwise it writes
  each profile directory's `compilation-report.json` and `compilation-tasks.jsonl.gz` beside its extracted captures,
  and `analysis.json` (with `archive` instead of `job`) in its own directory; its page lists them. Each report's
  `provenance.archive` names the archive URL, its SHA-256 and size, the manifest it was verified against with its
  SHA-256 and the catalog's campaign, how many files verification matched, and the profile directory within the
  archive (`member`). The case's job, problem, arm and commit come from the archived report, when one is there whose
  captures are byte for byte these (`source.from` says so); without one, `missing` says the archive does not name them.
  The downloaded `.tar.gz` is removed once extracted, and the verified manifest stays as `archive.manifest.json`.
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

The lab builds the commit as for an experiment (on a cloud node, from the build cache when it holds it) and runs the class on
the bench's classpath with each argument passed as it is, no shell between, under a JFR recording written to
`$OUT/profile.jfr`. `$OUT` is a directory whose files are kept with the job; the corpus cache is `$KLAUSE_CORPUS`, and
the built CLI and bench are `$KLAUSE_CLI` and `$KLAUSE_BENCH`, for the class to read. Its stdout, stderr, exit and
`$OUT` come back to the job directory (from a cloud node as well), read with `lab cat`, `lab files` and `lab fetch` and listed on
the job page. From a cloud node, at most 1 GiB of `$OUT` comes back, smallest files first; the rest are listed with their sizes
in `$OUT/omitted.tsv` and are lost with the instance, so a profile writes summaries rather than raw dumps. It queues, pauses, cancels and retries as an experiment does, and goes
local first like every job: the local machine when it is free, else an on-demand cloud node. Locally it runs alone, as every job does.

Every profile job is time-boxed. With no `timeout` it gets 10 minutes (`lab profile` sends none unless given its
`timeout-sec`); a `timeout` over 2 hours is cut to 2 hours, which the job log, the job page and the submit response
(`notes`) say. The case then runs for that limit plus two minutes for the JVM's start, the room every case gets. At
the limit its process group gets SIGTERM, and whatever is still running 30 seconds later gets SIGKILL: on a cloud node by
`timeout -k 30`, locally by the runner, which does the same (a case of any job stopped at its limit gets this
grace; a cancel or a runner shutdown still kills at once, since a cancelled case's results are not wanted and a case
cut off by a shutdown reruns from scratch). SIGTERM runs the JVM's shutdown hooks, so JFR writes `profile.jfr` (its
recording is started with `dumponexit=true`) and the main can flush what it has. A profile main should therefore
write its results as it goes, or flush them in a shutdown hook within those 30 seconds; whatever it has written to
`$OUT`, and its stdout and stderr, stay with the job (from a cloud node, `$OUT` is fetched back whatever the exit). The case's
exit reads `timeout`, its `.termination` file has a `timeout:` line saying SIGTERM was sent, and a second line when
SIGKILL was needed; the job page says it was stopped at its time limit and lists `$OUT` as for any other exit.

`"profile": {"main": "com.eignex.klause.bench.profile.Tool", "runTimeoutSec": 180}` keeps the profile locally
and opts into one active-time deadline across setup, build retries, the profile command,
and worktree cleanup. Queue and paused time do not count; resumed and explicitly retried work retains the active
time already recorded for that job. `base.timeout` remains the separate command allowance, including its existing
two-minute overhead. `runTimeoutSec` is capped at 7200 (2 hours) as `timeout` is. A whole-run deadline is
optional; jobs without it retain their existing timeout policy. A profile with one is placed on the local machine only,
since the local machine enforces the deadline on its own processes.
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

After a profile command reports its exit, a cloud node's output retrieval retries only the result stream when SSH drops or
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
On the cloud a profile job always runs on an on-demand node, never spot: AWS can take a spot instance back at two
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
adds its runtime options to the identity, avoiding a full runtime hash in every JVM. A cloud node's setup also
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
  hover), or the case's status when it left no record, such as `failed` for a run killed by its timeout. A Klause
  solution claim also says how far its witness was checked (below).

### Source-witness validation

Every scheduled Klause run, `status-sweep` and the four `klause-reference*` schedules, and their confirmations, asks
klause-bench to check each final witness against the original source: `param.source-validation=true` covers MiniZinc
integer, XCSP3, MPS and SMT-LIB sources (MiniZinc float runs are always checked), and
`param.source-validation-gzip=true` lets an MPS check keep up to 64 MiB of solver stdout, all its incumbents, instead of
leaving the point unchecked past 8 MiB. The committed specs say so in `base`, and the runner adds both to every Klause
schedule's base whenever it queues a run (`Validation.kt`, `SourceValidation.scheduled`), overriding a config or grid
value, so a schedule saved without them needs no edit. A schedule of reference-solver runs, a profile and an analysis
are left alone.

The bench fails a case of any other format (DIMACS, OPB, WCNF) that asks for validation, and the gzip
flag on anything but MPS, so the lab passes them only to the cases whose format takes them, in any experiment. Those
other cases run unchecked, as their records' `reported-result-v1` says.

The request is part of the experiment, so it is in the spec's fingerprint. A schedule's first checked run starts a new
experiment: the Regression trend shows the checked runs from then on, and `show them` brings back the earlier, unchecked
ones behind a dashed line, each run marked `checked` or `unchecked` in the runs table and on hover. A confirmation is
queued only against an earlier run of the same experiment, so a checked run is never confirmed against an unchecked one.
`lab reschedule` with a committed spec that differs from the saved one only by the validation request counts it as
unchanged and queues no extra run.

A case's record says how its claims were judged: `validationPolicy` names the policy, `reported-result-v1` for the
solver's own report, and under a checking policy `stats.sourceValidation` is `valid`, `invalid` or `unknown`, with
`stats.sourceValidationReason`. A check that withdrew a claim keeps the original one in `stats.reportedFeasible`,
`reportedObjective` and `reportedProven`. A verdict cell then reads:

- `checked`: the source accepted the witness, so the solution is valid. Witness checking is not proof checking: a
  star beside it is still the solver's own optimality claim, and an infeasibility claim has no witness to check.
- `unchecked`: the solver's own report, no check asked for or performed (`reported-result-v1`), such as every
  scheduled run from before validation, and the unchecked formats of a checked run.
- `check indeterminate`: the checker could not decide (unsupported syntax, residual constraints, a checker limit or
  timeout), the reason and the reported claim on hover. An MPS point that could not be checked earns no solution
  credit, so it reads `unknown` with this mark.
- `invalid solution`: the source rejected the witness; it earns no credit.

An undecided case has no mark. The arms table labels each arm `source-checked` or `unchecked`, with its counts on hover,
and warns when arms met under different validation policies, problem by problem: an unchecked report counts as a
solution or proof just as a checked witness does, and a check that withdrew a claim counts it as no solution, so their
difference may measure the checking. `/compare` across jobs gets the same warning. The scoreboard says how many of its
run's Klause solutions were checked, unchecked and indeterminate. Reference rows come only from `backend=reference`
arms, the reference solvers' own verdicts with their own `validation`, never from a Klause run, so witness validation
does not change what becomes a reference.

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
and explain coverage in the bundle. The lab's `campaign-evidence` command (`CampaignEvidence.kt`)
needs only the lab's own build. It packages regular files deterministically, rejects symlinks and output
inside the input tree, hashes every file, and verifies the completed archive. Run from this
checkout (no solver build or measurement is involved):

```sh
./gradlew -q run --args='campaign-evidence pack /absolute/path/to/publication /absolute/path/to/assets'
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
./gradlew -q run --args='campaign-evidence verify /absolute/path/to/download/<campaign>-<sha>.tar.gz
  /absolute/path/to/download/<campaign>-<sha>.manifest.json'
```

Verification streams every member without extraction and rejects altered bytes, missing
or extra files, duplicate paths, traversal paths and nonregular members; a member whose
header gives another size than the manifest's is refused before its bytes are read. Only a verified
archive should be extracted to an empty external directory for the retained analyzers.
The lab's own compilation analyser runs over such an archive's profile captures as an analysis
job, verifying it first (`lab analyze --archive <url> --sha256 <sha>`; see Experiments).
Numerical/source rechecking runs on a separate analysis host with the retained dependencies;
keep its execution identity and reports with the active evidence. Storage checks can run
locally with `./gradlew test --tests com.eignex.lab.CampaignEvidenceTest`; the build gate
also runs these tests.

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
`./gradlew -q run --args='make-sets <features-dir> <references.csv> <sets-dir> [set,…]'` (`MakeSets.kt`) draws the sets
from `select features=true` output and the reference results; give it absolute paths, since it runs in this checkout.

`deploy/specs/` holds the experiments the lab runs: `status-sweep.json` (schedule, every 120 s, the default engine
on `set=sweep` at 10 s, kept fast), and the 60 s runs over the broader selection, one per engine, all with
[source-witness validation](#source-witness-validation):
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

## Running on cloud nodes

The lab places every job itself; whoever submits one does not choose. Every job goes to the local machine first, then a
cloud spot node, then an on-demand node, with fixed exceptions:

- Scheduled runs (`<schedule>@<sha>`) and their confirmations (`~confirm`), reference runs (an arm with
  `backend=reference`), exact problem lists and profiles with a `runTimeoutSec` stay on the local machine only.
- Every job fits the local machine: a `processors` over the local machine's cores (`LAB_CORES`), in `base`, a config or the grid, and a
  `parallel` over its limit (`LAB_MAX_PARALLEL`) are cut down to them at submit. The response's `notes`, the job log
  and the job page (`capped`) say what was cut; a grid axis whose values the cap makes equal keeps one of them. So no
  job needs the cloud: nodes are overflow capacity for the local machine's queue, launched on pressure alone (below). `profileCli`
  jobs too go local first: both hosts measure them (see the spec's keys above).
- Profile jobs never run on spot: the local machine, then an on-demand node.

A job's `host` records the placement: `lab` (the local machine only), `aws` (a cloud node only) or `auto` (whichever
is free first). The stored values keep these names, so older jobs and clients need nothing; wherever a host is
given, `local` and `cloud` are accepted for `lab` and `aws`, and the submit reply's `host` and the pages use the
agnostic words (`local`, `cloud`, `auto`). The queue shows them as `local only`, `cloud` and `local or cloud`, in one queue ordered by priority, series and age. The local machine
takes the first job it may run in that order when it is free, whatever the placement: jobs placed locally and
`auto` jobs no node has claimed compete by priority alone. A job running locally, placed there or an `auto` job it
took, gives way between its cases to any such queued job of strictly higher priority; an equal priority never
preempts, and a paused, cancelled or node-claimed job does not count. So an a/b at priority 0 goes ahead of a status
sweep (-2) or a reference run (-3, -4) even when the pressure is too low to launch a node, and the run it interrupted
resumes later with its finished cases kept. An `auto` job is taken by whichever comes first, the free local machine or a free
node (a node leaves to the local machine the job it takes next, when it is free or about to give way to that job); once taken
its `host` is where it went, since a planned job's commands name paths on the host that planned it.
When the runner starts, it places every queued job that has not been planned again by these rules, cut down to fit
the local machine first, so a job queued under the old model with an explicit `aws` or `lab`, or with more `processors` than the
local machine has, becomes `auto` unless an exception applies; a planned job placed on `aws` is released as below, and other
planned ones keep their host.

A planned job a node gives back goes local first like any other: when its nodes retire or are taken back with cases
left, when it is paused, or when the lab restarts while it runs on a cloud node, it is queued again and placed by these rules,
normally `auto`. The free local machine then takes it over before a node does, and a node may still take it when the pressure
calls for one. The host that takes a planned job over writes its queued commands again for its own worktrees and
corpus, since the plan named the paths of the host that made it; finished cases and their results stay, except the
finished arms of an iteration left part done, which run again with the rest of it (below). A retried job is placed
the same way. A job whose cancel was requested is never queued again: whatever would requeue it (a pause, a lost or
retired node, a restart) ends it cancelled instead, its unfinished cases with it, and the runner ends any queued job
left with a cancel requested when it starts. `lab host <id> local|cloud|auto` (`POST /jobs/{id}/host`, `lab` and `aws` still accepted) remains as an operator override for a queued,
unplanned job; agents never need it. `lab machines` and `POST /jobs/{id}/machines` are retired and ignored.

Cloud work runs on a pool of nodes. The cloud provider is AWS: a node is an EC2 instance that, once booted, takes work until there is none for it:
first it joins a job already running on cloud nodes that still has problems no node has taken (building that job's commits
first), otherwise it claims the next queued job it may run (not local-only, its cases fit the node's cores, a profile job
only on an on-demand node, priority order). A node with nothing to take for `nodeIdleMin` minutes (5) is terminated,
and at once when a lab update waits. It stops taking problems an hour before its `maxHours` power-off. A job's
instances are the nodes working on it right now; its page lists them with their sizes and markets, and the queue
page's work summary shows the whole pool (below).

Nodes launch on pressure, with damping:

- Pressure is the estimated core-hours of the work waiting for the cloud: queued `auto` jobs at priority `nodeMinPriority`
  (0) or above and every queued `aws` job (only an operator's override places one there), less the job the local machine takes
  next when it is free or about to give way to it, plus the unclaimed problems
  of jobs running on cloud nodes. A job's core-hours are its cases left times the wall time a case is taken to need times its
  arms' `processors`. A planned job counts its queued cases; an unplanned one takes the case count of the last done run
  of the same experiment (same fingerprint, the ref, name, description and placement left out; a run of the same
  schedule first), else is guessed from its selections (`max`, else `per-family` times 10 families, else 20 problems
  per selection, the smaller when both are set) times arms, seeds and repeats; a profile job is one case. The time a
  case is the job's own mean once 10 of its cases finished, else that earlier run's mean case time (its cases' recorded
  times, or its running time over its cases at the concurrency it ran at), else each arm's whole `timeout` (a profile's
  allowance), the upper bound. The earlier runs are the newest 300 done experiments, reread at most once a minute.
  Work below `nodeMinPriority` that the local machine can run waits for the local machine: no node launches for it or takes it. Local-only
  work never counts.
- The launch threshold is set by the local machine: the work that would keep it busy for `nodeLaunchLocalHours`
  (1.0; `nodeLaunchMacHours`, its older name, is still read) hours, that is the local machine's cores times that in
  core-hours (10 core-hours on a 10-core machine). The first node launches once pressure
  has stayed at or above it for `nodeSustainMin` minutes (10). Another launches only while pressure is at least twice
  the threshold per node already up or launching, again for `nodeSustainMin`, and `nodeCooldownMin` minutes (5) after
  the last launch; at most `maxInstances` nodes, within the `vcpuQuota`. While a lab update waits, no node launches.
  A small queue never starts a node: a job alone launches one only when its own estimate is over the threshold.
- Each launch asks for spot and asks again every 30 s while AWS has no spot capacity or quota; after `spotWaitMin`
  minutes (15) it buys on demand, or at once if the account cannot launch spot at all, but only with `onDemand=true`.
  With `onDemand=false`, the default, a launch never falls back to on demand and gives up once the pressure is gone.
  A node launched while a profile job waits and no on-demand node is up is bought on demand directly. A spot node
  idle beside a profile job it may not run retires after `nodeIdleMin`, and an on-demand node then launches. A
  profile alone is far under the threshold (one of 3 minutes on all 10 of the local machine's cores is under a core-hour) and
  waits for the local machine or an on-demand node already up; profiles run locally unless a large backlog waits.
- Node size is chosen at launch and kept small: the smallest size of the family (`instanceType`, then `4xlarge`, …)
  that has the cores the largest waiting case needs (its `processors`) and covers this node's share of the pressure
  (pressure over the nodes up plus this one) within two hours. No node, automatic or started by hand, is larger than
  `nodeMaxVcpus` (16, a `c7i.4xlarge`): every job is capped to the local machine's cores, so no case needs more. More
  pressure is met by another node later, through the damping,
  rather than one large node up front. So a few core-hours get a `c7i.2xlarge`, a larger backlog a `c7i.4xlarge`, and
  a backlog beyond that a second node.
- A node runs one case per two physical cores unless the job sets `parallel`, which on cloud nodes counts per node, within
  its cores: single-core arms run 2 cases on a c7i.2xlarge and 4 on a c7i.4xlarge.

A node can also be started by hand: the Cloud gauge's **Start a node** button, `lab node start [size] [spot|on-demand]`
or `POST /cloud/nodes` (`{"size"?, "market"?}`). It skips the pressure threshold and the sustain wait, but not
`maxInstances`, the `vcpuQuota` or a waiting lab update; the lab refuses it (409) saying which, and also when a manual
request already waits or the cloud worker has not reported for two minutes (the runner is down). Its size is the one
the queue calls for, as above (at least the cores of the largest waiting case; the smallest when nothing waits),
unless one of the family's sizes within the quota is named. Its market is spot first as for any launch (on demand
after `spotWaitMin` only with `onDemand=true`, else it gives up then); `on-demand`, asked for, is always allowed,
since the operator chose to pay for it. The API leaves the request in `$LAB_DATA/aws/manual-launch.json` and the
runner's cloud worker takes it on its next poll (10 s), checking the limits again and dropping a request over 5
minutes old; it logs `aws: manual launch of a <type> <market> node` or `aws: manual launch refused: <why>`. The launch
counts as the gate's last, so the cooldown holds the next automatic one. The node is then like any other: it joins
jobs running on cloud nodes, claims queued jobs it may run (leaving the local machine its next job), and retires
after `nodeIdleMin` minutes with nothing to take, so a manual node with nothing to do costs a few minutes. The nodes
line marks it `manual`.

The queue page shows the scheduled work and the launch gauge above the Active table. Each active row has its
remaining estimate under its progress (the pool's estimate above: a running job's unfinished cases, a queued one's
queued cases), with the local machine's wall time for work it may run ("~40 min locally") and where the time a case came from: "(measured)",
"(from run 6659)", "(upper bound)", or "(guessed, upper bound)" for an unplanned job no earlier run tells about. The
local machine's wall time is the cases left times the time a case over the cases the job runs at once: its `parallel`, within
`LAB_MAX_PARALLEL`, and no more than its largest arm's `processors` fit in `LAB_CORES` (a `parallel` of 4 with
4-core cases on 8 cores runs 2 at once). The work summary totals the core-hours, split into local only, local or cloud at
`nodeMinPriority` or above (which counts toward a node), local or cloud below it (the local machine's in practice)
and cloud, with the time the local machine alone would take to drain all but the cloud work: the sum of those jobs' wall times, as the local machine runs one
job at a time. Paused and cancelling jobs are left out. With cloud settings, the Cloud gauge is a bar of the worker's pressure against the
threshold for the next node ("0.8 of 10 core-h for the first node", "26 of 40 core-h for node 3"), how long the
pressure has stood over it against `nodeSustainMin` ("over for 4 of 10 min"), the cooldown left, what holds the next
node back ("no node: below threshold", "waiting 6 min more", "cooldown 3 min", "at maxInstances", "vCPU quota full",
"lab update waiting") and the nodes up with their size, market and what each is doing. The worker writes these to
`$LAB_DATA/aws/nodes.json` on every poll; the page's totals include the job the free local machine takes next, which the
pressure leaves out.

`aws.properties` keys for the pool, all optional and reread on every poll: `maxInstances`, `vcpuQuota`, `onDemand`,
`spotWaitMin`, `nodeIdleMin`, `nodeMinPriority`, `nodeLaunchLocalHours`, `nodeSustainMin`, `nodeCooldownMin`,
`nodeMaxVcpus`. `market`, `spotFallback` and `nodeLaunchCoreHours` are no longer read.

- One-time setup: `deploy/aws-setup.sh [user@lab-host]`, from a machine with an admin session (`aws login`). It
  creates an SSH key pair, a security group admitting SSH from the local machine only, and an IAM user `klause-lab` that may only
  launch tagged instances of one type, tag them, terminate instances tagged `klause-lab` and read the Ubuntu image id.
  The admin session is the CLI's default profile unless `ADMIN_PROFILE` names another; the lab itself only ever uses
  the local machine's `klause-lab` profile. The script also creates EC2's spot service-linked role, which spot launches need and
  the `klause-lab` user may not create.
  It installs the AWS CLI on the local machine if missing, writes the user's key into its `klause-lab` profile and writes
  `$LAB_DATA/aws/aws.properties` (region, instance type `c7i.2xlarge`, `maxInstances` and `vcpuQuota`, now 8 and the account's 128 on-demand vCPUs, `cores` 4, `maxHours` 24,
  the MiniZinc version, the corpus bucket and instance profile). The lab reads that file; restart the services to
  start the cloud worker. After that the worker rereads it on every poll, so a raised `vcpuQuota` or a new
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
  The build toolchain includes C++ and the GMP and zlib development headers for the native MIPLIB checker.
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
  node that takes them; and a planned job set up afresh on another host (the local machine taking it over, or a node claiming it
  again after it went back to the queue) first queues again the finished cases of any iteration left part done. Each node keeps a job's case files in `~/jobs/<id>`, removed when it moves
  on. Cases run over SSH under `timeout`, and each record is copied back as its case ends, so the job page,
  comparisons and CSVs work as for a local job.
- A running cloud case carries its phase, so a case RUNNING past its `timeoutSec` shows whether the solver or the lab
  holds it: `EXECUTING`, `TRANSFERRING_OUTPUT` (with the record's `elapsedMs`), `RETRY_BACKOFF` after SSH dropped
  (attempt n of m and when the next starts) or `TERMINATING` after a timeout or cancel. The job page lists it under
  `running` and beside the case's status; `GET /jobs/{id}` has it as each command's `phase` (`phase`, `since`,
  `attempt`, `attempts`, `retryAt`, `elapsedMs`), null once the case ends and for local cases.
- Leave `parallel` unset on cloud nodes. The default, one case per two physical cores, is the controlled setting: each case is
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
  noisier than the local machine's, so give a timing A/B on cloud nodes more `repeats`.
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
network sits behind the ISP's carrier-grade NAT, so nothing can connect in to it, router forwarding included; the local
machine (the local machine) connects out to a relay instead:

- The relay is an EC2 instance `klause-relay` (t4g.micro, eu-north-1) with the Elastic IP `klause-relay`, a security
  group open to 22, 80 and 443, and Caddy, which serves the domain with a Let's Encrypt certificate it renews itself
  and passes every request to `127.0.0.1:8420`. Its `tunnel` user can hold that one reverse forward and nothing else:
  no shell, no command, no other port.
- The local machine holds the tunnel: the launchd daemon `com.eignex.klause-relay` runs `ssh -N -R 127.0.0.1:8420:...` to the
  relay as the lab's user with `~/.ssh/klause-lab-relay`, starts at boot and reconnects after a drop. Its errors go
  to `$LAB_DATA/logs/relay.log`.
- DNS for `eignex.com` is hosted at DigitalOcean (the registrar only points there); the `lab` A record names the
  Elastic IP.

`deploy/relay/create.sh [domain] [user@lab-host]` builds all of it from an admin AWS session, reusing what exists:
the Elastic IP carries over to a rebuilt relay, so DNS stays put, and with a DigitalOcean token in
`~/.config/digitalocean/token` it sets the A record too. It ends by printing the one step left, on the local machine:
`sudo deploy/relay/install-tunnel.sh <relay-ip>`, which installs the daemon and forgets the old relay's host key. The
relay is tagged only `Name=klause-relay`, never `klause-lab`, so the cloud worker's cleanup of its own instances leaves
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
  limit), `sibling:` (another case could not run, which failed the job) or `host-lost:` (the cloud provider took the instance back;
  the case goes back to the queue). A pause or a yield kills nothing: the job stops only between cases. A case that
  exits 137 (SIGKILL) or 143 (SIGTERM) with no such line gets an `external:` one, which says only that the lab did not
  send the signal; on a cloud node it adds the instance's kernel log lines about killed processes, or says there were none, and
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

- Below `LAB_MIN_FREE_GB` (10) free on the data directory's disk, the local machine's queue is held until there is room again;
  cloud jobs go on. `/disk` shows what takes the disk, measured every 30 minutes, with the largest job directories.
- From a cloud node, at most 1 GiB of a profile job's `$OUT` comes back (see Profile jobs).
- An ended job is trimmed a week after it ended, and sooner, largest first, while the disk has under 25 GB free: its
  `job.log`, `setup.log`, `sha`, commands' `.exit` and `.termination` files, `selection-*.jsonl` and each case's record (the `.json` in
  `cases/<i>`) stay, as do profiled cases' `compilation-report.json`, `compilation-tasks.jsonl.gz` and
  `measurement.json`, an analysis job's `analysis.json`, and an archive analysis's `archive.manifest.json` and the
  reports, task records and manifests under its `archive/`, and everything else goes: raw solver output, case stdout
  and stderr, profiles' raw captures (`cli.jfr`, the HotSpot log, extracted ones too), `$OUT`. An analysis job of a
  trimmed job is refused; one of a pinned archive of its captures (`analyze.archive`) still runs. Every
  case's record is in the database as well, so its results, comparisons and trend are unaffected. A trimmed job has a
  `trimmed` file (when, bytes freed) and a line in its log, and is not trimmed again; queued and running jobs are
  never touched. `lab rm` still deletes a job outright. Fetch what you need from a job (`lab fetch`) within a week.
- Docker's old reference images and build cache are pruned weekly, and sooner on a low disk (see Docker cleanup).

## What a case runs in

A case runs `klause-bench solve-one` in its arm's worktree, with this environment:

| variable | value |
| --- | --- |
| `KLAUSE_CLI_OPTS` | `LAB_SOLVE_JAVA_OPTS` (default `-Xmx3g -XX:+UseSerialGC`) plus `-XX:ActiveProcessorCount=<the case's cores>` |
| `JOB_DIR` | the job's output directory; the case writes its record under `cases/<n>/` |
| `OPENBLAS_NUM_THREADS`, `VECLIB_MAXIMUM_THREADS`, `MKL_NUM_THREADS`, `OMP_NUM_THREADS` | `1` |

Stdout and stderr go to `JOB_DIR/<n>.out` and `<n>.err`; the raw solver output and the record go to
`JOB_DIR/cases/<n>/`. On a cloud node the record comes back with every case, and stdout and stderr only for a case that
failed, timed out or left no record, or for a profiling job: the rest would cost two SSH round trips a case. The worktrees are deleted when the job ends. A failing case does not stop the experiment: the
rest still run, the job ends DONE, and the failures are counted on it. FAILED means the experiment itself could not
run, such as a failed checkout, build or selection, or a plan over the size limit.

Cases run in order within two limits. A case holds as many cores as its arm's `processors` (one without), and the
running cases together hold at most `LAB_CORES` (default: the machine's cores less two, for the JVMs around the
solves and the machine). Each solve sees exactly its cores (`-XX:ActiveProcessorCount`), so a portfolio sizes its
threads to them. A case too big for the cores left waits for room rather than letting later ones pass it, and an arm
asking for more processors than `LAB_CORES` is cut down to them at submit (see Running on cloud nodes). On top of that, at most `parallel` cases run at
once: the spec's, or `LAB_DEFAULT_PARALLEL` (default 4) when it sets none, and never more than `LAB_MAX_PARALLEL`
(default 6; a larger one is cut down to it at submit). The default is the local machine's performance cores (an M4 Mac's here): a solve on one of its six efficiency cores runs
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
`LAB_DOCKER=none` in `lab.env` to opt out. Build the images once on the server. SCIP and clasp build from a klause checkout:

```sh
docker build -t klause-scip klause-bench/scip
docker build -t klause-clasp klause-bench/clasp
```

The XCSP3 image builds from this repository; see [images/xcsp3-cpsat](../images/xcsp3-cpsat/README.md):

```sh
docker build -t klause-xcsp3-cpsat:source-preserving-v2 images/xcsp3-cpsat
```

### Docker cleanup

Every rebuild of an image leaves its build cache, its old layers and, when its tag changes, its old tag behind. On
macOS all of that sits in Colima's VM disk (`~/.colima/_lima/_disks`), which grows as Docker writes and does not shrink
by itself. The runner cleans up every `LAB_DOCKER_CLEANUP_DAYS` (default 7), sooner when the data directory's disk has
under 25 GB free (the signal job trimming uses, at most every six hours), and only while Docker is on (`LAB_DOCKER` is
not `none`). It runs between jobs, never during one, and skips while any container runs, a reference solve started by
hand included, trying again ten minutes later. A cleanup:

1. removes build cache older than a week: `docker builder prune -af --filter until=168h`;
2. removes the superseded tags of the reference repositories, with `docker rmi` and no force, so an image a container
   still uses stays;
3. removes dangling images: `docker image prune -f`;
4. on Colima, trims the VM's filesystems so the freed blocks can go back to the host: `colima ssh -- sudo fstrim -av`.

What it keeps: every tag in `LAB_DOCKER_KEEP`, by default
`klause-xcsp3-cpsat:source-preserving-v2,klause-scip:latest,klause-clasp:latest`, the tags klause-bench runs
(`Xcsp3CpSatReference.IMAGE`, `ScipReference.IMAGE`, `ClaspReference.IMAGE`); the `latest` tag of each of those
repositories; and every tag of them that a queued or running job's commits name in klause-bench, found with `git grep`
in the lab's mirror. When one of those commits cannot be read, the cleanup removes no tag at all that time. Images of
any other repository are never touched, except dangling ones. Change `LAB_DOCKER_KEEP` when klause-bench moves to a
new tag, so the old one goes; until then, a job on a commit that names the new tag keeps it.

The runner log has a line for each cleanup: the tags removed, the images pruned, the build cache reclaimed, the total
of `docker system df` before and after, and on Colima the allocated size of its VM disks on the host before and after
the trim. `/disk` shows what Docker holds now and the last cleanup, read from `$LAB_DATA/docker-cleanup.json`.

Whether the trim shrinks the disk file on the host depends on the VM's disk: Colima's FAQ says space freed in the VM is
released by `fstrim` (and on VM start) from v0.5.0, but that was not measured on the lab Mac, and Lima has had reports
of `fstrim` trimming in the VM while the host file kept its size. Compare the two Colima sizes in the log line: if
they stay the same, Docker's space is free inside the VM for new images but not on the host, and only
`colima delete` and a fresh VM (then rebuilding the images) gives it back.

`lab docker-cleanup` asks the runner for a cleanup at its next free moment. On the server,
`klause-lab docker-cleanup` (in `$LAB_DATA/releases/current/bin`) cleans up at once, whatever the interval; it still
skips while a container runs and exits 1 when it did. The built-in task is the default; to run it from cron or
launchd instead, set `LAB_DOCKER_CLEANUP_DAYS=0`, rerun `install.sh`, and call the subcommand weekly with what the
services get: `JAVA_HOME`, a `PATH` with `docker` and `colima`, and on Colima its `DOCKER_HOST`. For example in crontab:

```sh
0 4 * * 0  JAVA_HOME=/path/to/jdk-25 PATH=/opt/homebrew/bin:/usr/bin:/bin DOCKER_HOST=unix://$HOME/.colima/default/docker.sock $HOME/klause-lab-data/releases/current/bin/klause-lab docker-cleanup >> $HOME/klause-lab-data/logs/docker-cleanup.log 2>&1
```

A cron entry can run during a job, between its reference cases: the job's images stay, since its commits name them,
but the pruning competes with its cases for the disk. The runner's own cleanup waits for the job to end.

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
pause <id>`) or a higher-priority one it may run is waiting, whether placed locally or `auto`; if so it starts no more, lets its running cases finish, and goes
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
deploy/lab profile com.eignex.klause.bench.profile.Timing main "time X on the captured inputs"   # a profile job, local first; not for gates
deploy/lab cat 7 setup.log    # one of them; a third argument prints only its last bytes
deploy/lab analyze 7          # rerun the lab's compilation analyser over job 7's retained captures; no solver runs
deploy/lab analyze --archive https://github.com/Eignex/klause-lab/releases/download/<tag>/<campaign>-<sha>.tar.gz --sha256 <sha>
                              # the same over a pinned saved-capture archive, once job 7 is gone
deploy/lab fetch 7            # download jobs/7/ to ./lab-jobs/7 over HTTP
deploy/lab node start c7i.4xlarge   # start a cloud node now, past the pressure threshold
deploy/lab ssh 7 0 uptime     # a command on node 0 of those on a running cloud job; no command opens a shell
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
| `POST` | `/experiments` | an experiment spec (above) → `{"id", "placement", "host"}`: where the lab placed it, in words, and as `local`, `cloud` or `auto` (the job's own `experiment.host` keeps the stored `lab`, `aws` or `auto`); a branch or tag origin lacks is refused; the job it queues plans its cases when it starts. `{"analyze": {"job": <id>}}`, or `{"analyze": {"archive": <url or name>, "sha256": <hex>}}`, queues an analysis job instead (above); 409 when its captures are gone, or the archive is unpinned or not allowed |
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
| `POST` | `/cloud/nodes` | `{"size"?, "market"?}`: start a cloud node by hand (see Running on cloud nodes); 202 when requested, 409 with the reason when refused |

A browser (`Accept: text/html`) gets a page for `/jobs/{id}` and `/jobs/{id}/files`; `?json` gets the data instead.

The API has no authentication. Keep it on the LAN.
