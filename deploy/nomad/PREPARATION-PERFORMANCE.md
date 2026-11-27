# Preparation cache observations and performance regression

Job and execution pages show the latest outcome/reason per input, prepared-input, build, archive and upload cache. Workspace creation is a separately timed phase: preparation creates a fresh private workspace rather than reporting a workspace cache hit. Verification and waiting time, files checked/rehashed, bytes rehashed and access counts accumulate across all arms and calls in one preparation, so a later cheap hit cannot hide earlier expensive verification or a miss. Counters saturate rather than failing large preparations. The accepted `preparation/cache.json` artifact holds the observations beside the `preparation/timings.json` phase durations; both contain fixed labels and counts, never paths or worker text. The coordinator stores them in the `preparation_caches` column of `iteration_attempts`, separate from `preparation_progress`.

`absent` means only that the requested entry was unavailable on this node: a new input identity, another node and an earlier eviction all look the same. `verification-failed` means an existing entry failed validation. `verified-local` is a validated local hit; `build-hit` marks source-input acquisition bypassed because a verified build already exists; `coordinator-present` means a reusable upload already exists. A hit can still be slow: verification and waiting are reported separately, and nonzero rehash counters reveal a missing/stale proof or changed metadata.

## Run the full preparation regression

```sh
LAB_PREPARATION_PERF_ROOT=/path/on/measured/disk ./gradlew :lab-app:verifyPreparationPerformance
```

Use a short scratch path on the filesystem being measured (Unix socket paths have a length limit). The test needs 4 GiB free, removes its fixture and cache directories at completion and leaves `preparation-performance.json` there as the report. Ordinary `test` excludes it, and the task skips unless `LAB_PREPARATION_PERF_ROOT` is set; Linux and macOS CI set it in an explicit step.

The deterministic fixture holds 4,096 incompressible files of about 256 MiB in total: many small source files, four 32 MiB JAR-shaped blobs and a 96 MiB Git-pack-shaped blob, with a supervised build script that records invocations. The test runs one cold `PreparationExecutor` pass and two warm passes with fresh worker cache helpers and private workspaces, through a real HTTP upload endpoint, a local artifact store and a 128 MiB result journal in `java.io.tmpdir`. Each pass uses a different artifact job ID, so reuse goes through cross-job archive linking as in production.

Warm passes must report the source-input bypass plus prepared-input/build/archive/upload hits, perform no source download, build, checkout or archive replacement, rehash no cached contents, and make no large upload. These are structural assertions, not a wall-clock threshold: compare `elapsedMs`, `timings`, verification and waiting time and the work counters across filesystems and revisions. The fixture does not cover consumer selection, real compilation, cloud networking or disk contention.
