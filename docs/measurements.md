# Measurements and evidence

[Documentation home](../deploy/SETUP.md) · [Run specifications](runs.md) · [Storage and retention](operations.md#storage-and-retention)

## Choose a recording mode

Use one `measurement` field in a solver experiment, or set it at submission:

```sh
deploy/lab run --measurement compilation experiment.json
```

| Mode | Captures |
| --- | --- |
| `none` (default) | Ordinary run |
| `resources` | Peak RSS, user/system CPU, and wall time through GNU/BSD time |
| `jfr` | Resources plus JFR with its `profile` settings |
| `compilation` | Resources and JFR including every `jdk.Compilation` event |
| `compilation+log` | The above plus HotSpot's compilation/inlining log |

Recording modes default to `parallel: 1`; an explicit different limit is rejected, including later edits.
The same modes work in schedule specifications. `resources` is for Klause CLI arms, not reference-backend arms.
Committed [profile entry points](runs.md#profiles-and-koblas-captures) use their own recording behavior instead of this field.

Each invocation gets its own `cases/<index>/profile-<uuid>/` directory, including retries:

| Artifact | Contents |
| --- | --- |
| `resources.txt` | `peakRssKiB`, `userSeconds`, `systemSeconds`, `wallSeconds`, and exit |
| `measurement.json` | Host, command, JVM options, instrumentation, artifact list, and record hash |
| `solve-record.json` | Preserved solver record bound to the measurement |
| `cli.jfr` | JVM recording when requested |
| `hotspot-compilation.log[.gz]` | Compilation and inlining details; gzipped after cloud transfer |
| `compilation-report.json` | Summarized analysis produced after a compilation-recorded case |
| `compilation-tasks.jsonl.gz` | One task record per compile ID, linked by hash from the report |

JFR allocation weights are statistical estimates. RSS is the measured CLI process's high-water mark, not an
enforced memory cap or a count of allocated bytes. GNU time measures Linux cases; BSD `time -l` measures macOS
cases. Manifests identify the platform and method. `resources` collects no JFR allocation samples.

Use ordinary runs for runtime comparisons: JFR and compilation logging add overhead. Compilation-recorded runs
are marked `instrumented` on pages and exports, and have distinct experiment fingerprints. A hardware tag alone
does not establish which SIMD route the benchmark executed.

## Read compilation reports

The report summarizes compilation counts and time, success/bailout/failure states, OSR, code sizes, tiers, methods,
and inlining. It joins JFR and HotSpot records by compile ID, retaining disagreements and records present in only
one capture. Provenance identifies the source commit, runtime, worker, execution, and input hashes.

Read `missing` and `incomplete` before interpreting totals. Missing shutdown events, JFR data loss, a nonzero
threshold, truncated logs, startup compilations, and interrupted cases can leave gaps. `complete` requires both
lists to be empty. Plain JFR uses compilation thresholds and does not capture every short compilation; the
`compilation` modes remove that threshold, while the HotSpot log adds startup and inlining evidence.

Stream individual tasks rather than loading the full file into memory:

```sh
zcat compilation-tasks.jsonl.gz | jq -c 'select(.status == "bailout")'
```

The authoritative report and task schemas live in
[CompilationReports.kt](../src/main/kotlin/com/eignex/lab/CompilationReports.kt).

## Fetch and re-analyze

```sh
deploy/lab files 7
deploy/lab fetch 7
deploy/lab analyze 7
```

Analysis runs the lab's analyser locally over an ended job's retained JFR captures, without rebuilding or solving.
It writes fresh per-case reports and an `analysis.json` summary with its own execution identity. Missing, trimmed,
or still-running captures are refused. Fetch raw evidence before the default seven-day trim.

`lab fetch` holds the job against trimming, downloads a single file listing, and adds `lab-cases.jsonl` with current
lab verdicts. Missing files produce `lab-fetch-missing.json` and a nonzero exit; a successful retry removes that
receipt. The export retains the solver witness, build provenance, native-output hashes, and `record.labCheck`.
The database's embedded producer-record summary is reduced to `timeToBestMs`; preserve the raw case record when
its original producer bytes or hash matter.

## Retain a campaign

Keep generated evidence outside repositories. Stage specifications, actual selection, raw outputs/captures,
executed build/runtime identities, validation provenance, and separately generated summaries. Name omissions
explicitly. A requested revision does not identify the executed binary, and packaging does not validate a claim.

Add `catalog.json` to the staged directory. Each role needs entry paths or an omission reason:

```json
{
  "campaign": "restart-comparison",
  "purpose": "Retain the paired restart comparison",
  "references": ["https://github.com/Eignex/klause/issues/123"],
  "limitations": ["Witness checking does not certify optimality"],
  "roles": {
    "specifications": ["experiment.json", "selection.jsonl"],
    "raw": ["cases.json"],
    "profiles": [],
    "provenance": ["result-provenance.json"],
    "summaries": ["result-summary.json"],
    "validation": ["source-checks.json"]
  },
  "omissions": {"profiles": "This ordinary run collected no profiles"}
}
```

From this checkout, pack and verify the staged tree:

```sh
./gradlew -q run --args='campaign-evidence pack /absolute/path/to/staged /absolute/path/to/assets'
./gradlew -q run --args='campaign-evidence verify /absolute/path/to/archive.tar.gz /absolute/path/to/archive.manifest.json'
```

Packing produces `<campaign>-<sha256>.tar.gz` and a manifest of every file's size/hash. Pin the manifest's own hash
in the review as well. Verification rejects altered, missing, extra, duplicate, traversal, and nonregular members
without extracting them. It establishes byte identity against the manifest, not authenticity or numerical correctness.

Copy both files under `$LAB_DATA/archives` to analyze retained captures after their original job was trimmed:

```sh
deploy/lab analyze --archive archive.tar.gz --sha256 <archive-sha256> \
  --manifest archive.manifest.json --manifest-sha256 <manifest-sha256>
```

Archive inputs must be local relative paths under that directory, never URLs. Limits default to 2 GiB compressed
and 16 GiB unpacked (`LAB_ARCHIVE_MAX_MB`, `LAB_ARCHIVE_MAX_UNPACKED_MB`). Retire evidence only after its consumers
have durable results or fixtures; do not remove jobs still needed by another campaign.
