# Lab Bench SDK

`com.eignex:lab-bench` is the JVM integration module for repository-owned benchmarks. It supplies a versioned JSON
process contract, metrics, and retained artifact references. Suites own their workload selection, build layout,
runtimes, instrumentation, numerical checks, and domain-specific results. The SDK has no dependency on the lab
server, scheduler, database, solver libraries, or KBuild platform BOM. It targets Java 17 and uses Kotlin serialization.

The SDK can run inside `klause-bench`, `koblas-bench`, `kumulant-bench`, or another bench module. In a multiplatform
bench, import it from `jvmMain`; Native measurements can still be launched and collected by a repository-owned JVM
adapter or shell script. The SDK does not replace JMH or time a callback on the suite's behalf.

## Import

Releases use public GitHub Release assets; consuming builds need no GitHub login or token. This module's initial
release version is `0.1.0`; it must be published before the remote dependency below resolves.

Add this repository to the consuming bench module's `build.gradle.kts`:

```kotlin
repositories {
    exclusiveContent {
        forRepository {
            ivy {
                name = "labBenchReleases"
                url = uri("https://github.com/Eignex/eignex-lab/releases/download")
                patternLayout {
                    artifact("lab-bench-v[revision]/[artifact]-[revision](-[classifier]).[ext]")
                }
                metadataSources { gradleMetadata() }
            }
        }
        filter { includeModule("com.eignex", "lab-bench") }
    }
    mavenCentral()
}
```

For a JVM module:

```kotlin
dependencies {
    implementation("com.eignex:lab-bench:0.1.0")
}
```

For a multiplatform module:

```kotlin
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation("com.eignex:lab-bench:0.1.0")
        }
    }
}
```

Keep this dependency in the bench module. It is not part of the library being benchmarked.

To develop against a checkout before publication, add a composite build in the consumer's `settings.gradle.kts`:

```kotlin
includeBuild("../eignex-lab") {
    dependencySubstitution {
        substitute(module("com.eignex:lab-bench")).using(project(":lab-bench"))
    }
}
```

## Implement a suite

Implement `BenchSuite` in the repository and expose it through a main. This small smoke suite demonstrates the
contract; it does not establish a performance baseline. A production suite delegates `run` to its own harness.

```kotlin
package com.eignex.example.bench

import com.eignex.lab.bench.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.writeText

private object SmokeSuite : BenchSuite {
    override fun describe() = BenchDescriptor("example-smoke", "Verify benchmark integration")

    override fun plan(request: BenchRequest): List<BenchCase> {
        val size = (request.parameters["size"] ?: "65").toInt()
        require(size in 1..1000)
        return listOf(BenchCase(
            id = "smoke-$size",
            parameters = mapOf("size" to size.toString()),
            resources = BenchResources(cpus = 1, memoryMiB = 256, timeoutSeconds = 30),
        ))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val size = case.parameters.getValue("size").toInt()
        require(size in 1..1000)
        val checksum = (1..size).sum()
        context.outputDirectory.resolve("summary.txt").writeText("checksum=$checksum\n")
        return BenchResult(
            caseId = case.id,
            artifacts = listOf(context.artifact("summary.txt", "text/plain")),
            details = buildJsonObject { put("checksum", checksum) },
        )
    }
}

fun main(args: Array<String>) = BenchCli.execute(SmokeSuite, args)
```

For example, a JVM bench using Gradle's `application` plugin can configure:

```kotlin
application { mainClass.set("com.eignex.example.bench.MainKt") }
```

Build its installed distribution using the repository's own task, then invoke that distribution:

```sh
./gradlew :example-bench:installDist
BENCH=example-bench/build/install/example-bench/bin/example-bench
"$BENCH" describe
printf '%s\n' '{"protocolVersion":1,"parameters":{"size":"65"}}' > request.json
"$BENCH" plan request.json > plan.json
jq '.cases[0]' plan.json > case.json
"$BENCH" run case.json /tmp/example-bench-output
```

## Process contract

| Command | Input | Output |
| --- | --- | --- |
| `describe` | None | `BenchDescriptor` JSON on stdout |
| `plan REQUEST.json` | `BenchRequest` | `BenchPlan` JSON on stdout |
| `run CASE.json [OUTPUT_DIR]` | One frozen `BenchCase` | `BenchResult` in `OUTPUT_DIR/result.json` |

The request, descriptor, plan, case, and result carry `protocolVersion: 1`. An absent version defaults to 1.
Unknown fields are ignored for additive evolution; incompatible versions fail before suite execution. Keep logs
on stderr during describe and plan so stdout remains JSON. An empty plan is valid; case IDs within a plan must
be unique. Callers extract individual cases from the plan and invoke run once for each case.

Cases carry concrete suite parameters and requested CPU count, memory in MiB, wall-time budget in seconds, and
required node tags. Seeds, repetitions, JMH forks, warmups, and backend settings belong in the suite's parameters.
The SDK itself does not enforce resources. The lab validates the plan, matches required node tags, reserves
CPU/memory for execution and applies the case timeout through its process supervisor. Linux workers use the
existing cgroup quota; macOS workers retain advisory limits. Suite code validates the parameters it accepts.

Results carry finite metrics with explicit units and optional comparison directions, artifact references, and an
optional JSON object for domain details. Preserve raw samples and richer numerical or solver records as artifacts;
the generic metrics do not replace those formats. A metric name must be unique within its result.

Artifact paths are relative to the case output directory. Referenced files must exist and resolve inside that
directory; parent traversal, absolute paths, and escaping symlinks are rejected. `result.json` is reserved.
The caller should retain the whole output directory, including partial captures left by failed work.

A successful or deliberately skipped case returns normally; skipped cases require a reason. An explicitly failed
result is retained and raises an exception. Exceptions thrown by suite execution retain a failed result and propagate
to the main, producing a failed process exit. Results are replaced atomically where the filesystem supports it, and
an earlier result is removed before each attempt. Hard kills can leave captures without a result; callers must use
the process exit and their own timeout/cancellation state rather than treating a missing result as success.

## Run through the lab

Commit a JSON `BenchEntryPoint` manifest beside the suite:

```json
{
  "main": "com.eignex.example.bench.MainKt",
  "buildTasks": [":example-bench:installDist"],
  "classpath": ["example-bench/build/install/example-bench/lib/*"],
  "jvmArgs": ["-Xmx256m"]
}
```

Bench submissions require the coordinator and workers from this change. Their work manifests explicitly use
schema version 2 so older workers reject them rather than interpreting them as solver experiments; existing
solver/profile work retains schema version 1. Deploy the matching worker release before submitting bench jobs.

The lab reads this manifest from the pinned source revision. Preparation runs its build tasks, calls `describe`
and `plan`, freezes the cases and inventories their inputs. Execution relocates the prepared output and corpus,
validates input hashes, and runs each case once per execution attempt. Infrastructure retries use the same frozen
case in a fresh output directory. `BenchContext` exposes `LAB_REPOSITORY`, `LAB_WORKTREE` and `LAB_CORPUS`.
Use `BenchInput` to name resolved source/corpus files or include directories; absolute paths never enter the plan.

Submit an experiment file with `lab run experiment.json`:

```json
{
  "name": "example-smoke",
  "repository": "Eignex/example",
  "description": "Verify the repository-owned benchmark adapter",
  "base": {"ref": "main"},
  "bench": {
    "manifest": "example-bench/lab/bench.json",
    "request": {"parameters": {"smoke": "true"}}
  }
}
```

Cases, runtime flags and instrumentation belong to the adapter; the lab does not add JFR or Vector API flags.
Top-level `configs`/`grid` pair revisions and opaque `param.<key>` request overrides; `repeats` repeats whole paired
iterations. Reference arms use declared solver-domain input files. Solver records support regression confirmations
that replay accepted prepared bundles and frozen workloads. These controls require manifest schema 3 workers.

Optional `bench.runTimeoutSec` limits the job's total active preparation/execution time to 1–7200 seconds;
queue time is excluded. The allowance persists across worker replacements. Workers reserve shutdown/transfer
time, retain partial files and report a permanent deadline failure. Without it, the adapter's per-case resource
ceilings apply without a separate whole-job limit. Worker and coordinator releases must support these fields.

Set `bench.sourceValidation` to `true` to request the lab's independent source-witness checks for solver-domain
records. The coordinator uses the preserved solve record, final witness and source hashes; the SDK request and
runtime parameters do not receive this lab policy. An unsupported checker or missing input leaves the claim
unchecked and records why; only an invalid witness withdraws the claim. The SDK envelope remains intact alongside
the lab verdict. This does not add proof checking for infeasibility or optimality.

Bench schedules preserve these explicit controls without injecting legacy solver options. For a checked solver
schedule, include `bench.sourceValidation: true` in its experiment. Timing suites can leave it false.

The coordinator retains the SDK envelope as the case record, displays metrics/artifacts on the job page and exports
`/experiments/<id>/metrics.csv`. `/experiments/<id>/cases` exposes full records. An adapter marking its case
`recordSchema=solver-v1` supplies its complete solve record in `details`; the coordinator preserves those fields
at the record root and includes the SDK envelope as `labBench`, retaining the existing solver checks and exports.

`context.reportPath()` returns an absent child destination for harnesses that reject precreated output directories.
`context.artifacts()` registers its report tree. `context.process(argv, log)` preserves literal arguments, writes
combined output under the case output directory and clears inherited JVM instrumentation variables. Failures,
invalid/missing SDK results and supervisor timeouts still retain all files under the case output directory.

## Verify the consumer adapters before publication

Stage the unpublished SDK using the same metadata and URL layout as a public release:

```sh
./gradlew :lab-bench:stageAdapterRepository -PbenchVersion=0.1.0
```

The consumer bench modules accept
`-PlabBenchRepository=file:///absolute/eignex-lab/lab-bench/build/adapter-repository`
and `-PlabBenchVersion=0.1.0`. Build the tasks declared in each consumer's committed manifest with those properties.
Their default repository URL resolves public GitHub Release assets anonymously after publication. When restaging
the same unpublished version, refresh consumer dependencies with `--refresh-dependencies` or select a fresh
development version so Gradle cannot reuse an earlier SDK artifact.

Provide a JSON scenario matrix for the opt-in local worker/coordinator gate. Each entry names
`name`, `repository`, `source` (an absolute checkout), `manifest`, prepared `outputs`, opaque
`parameters`, and optional `configs` and `repeats`. For example:

```json
[{"name":"tools","repository":"Eignex/tools","source":"/work/tools",
  "manifest":"bench/lab.json","outputs":["bench/build/install/bench"],
  "parameters":{"mode":"small"},"configs":[{"label":"before"},{"label":"after","param.policy":"new"}]}]
```

```sh
LAB_BENCH_ADAPTER_MATRIX=/absolute/scenarios.json ./gradlew verifyBenchAdapters
```

The test stages the declared prepared outputs, calls each manifest planner, relocates frozen
inputs, executes complete paired iterations under the process supervisor, transfers artifacts
and ingests records through `NomadBackend`. Solver-v1 scenarios retain source hashes and build
provenance. The lab contains no consumer-specific selections, build paths or report expectations.
Consumer repositories own those scenarios and their workload assertions. Ordinary unit tests
exclude this gate. Local captures establish wiring, not performance or live scheduling evidence.

## Paired runs and runtime declarations

Top-level `configs` and `grid` expand paired arms. Each arm selects a `ref` and can override
opaque request values with `param.<key>`. Top-level `repeats` repeats each paired block; the
suite owns seed expansion. Plans are resolved at each pinned commit and retained separately.
`attributes.comparisonKey` optionally names the same workload across runtime variants; it defaults
to the case id. Keys must be unique and all arms must select the same workload identities and
input bytes. Directory contents are pinned too. Missing workloads and changed inputs fail preparation.

The optional manifest `runtime` object declares `java` (the exact Java feature version),
`requiredTags`, `tools` (executable names) and `environment`. Tags constrain preparation and
execution; Java and tools are checked before entry-point invocation. Environment assignments
cannot override lab custody variables, PATH, HOME or the Java executable/options. This is lab
manifest metadata; it does not change the SDK 0.1.0 entry-point or result protocol.

For solver-v1 workloads, reference arms use `backend=reference`, `solver`, `timeout` in ms,
`processors` and optional `fixed`. The repository planner still resolves the frozen files, including
in a reference-only run. Checkers and independent reference executables remain lab-owned.
`bench.referenceFilter` and per-suite `bench.referenceFilters` filter frozen plans by stored reference
verdicts. Source matching uses `attributes.problem` within its collection; case/seed/runtime identities
remain separate. Selection caps remain repository-owned and are not refilled after this filter.

Bench schedules preserve their explicit checking policy. A solver regression queues a confirmation
with the same repository, request, placement, selected workload bytes and both pinned source commits.
It reuses the original accepted prepared bundles for both revisions instead of resolving build dependencies again.
Changed requests or missing retained builds are refused.
It repeats paired blocks three times. Invalid witnesses and exact objective/proof semantics keep the
same solver-v1 interpretation. Generic metric-only suites do not claim solver regressions.

New paired worker manifests use schema 3 so older workers cannot silently execute a canonical case
for every arm. Deploy matching workers and coordinator before migrating schedules. Legacy solver
recipes are refused at admission; their owning repository supplies the translation and profile wrapper.
Saved records and captures remain readable. The only retained consumer implementation dependency
is the MPS parser within the independent checker boundary.

## Build and release

```sh
./gradlew :lab-bench:check :lab-bench:verifyRelease -PbenchVersion=0.1.0
```

KBuild 1.3.6's `com.eignex.publish.github` helper stages the JAR, sources JAR, POM, Gradle Module Metadata and
`SHA256SUMS` under `lab-bench/build/release-assets/`. `releaseAssets` is an alias for `githubReleaseAssets`.
The helper is applied independently of KBuild's library conventions so the SDK has no KBuild platform dependency.
The SDK version is independent of the service version; local builds default to `0.1.0-SNAPSHOT`.
`verifyRelease` serves these files using the exact release URL layout and compiles/runs an independent consumer
with a fresh Gradle user home and no credential environment variables. It also checks the dependency boundary
and Java 17 bytecode, and verifies every staged checksum. The consumer downloads ordinary Kotlin dependencies anonymously from Maven Central.

The `release-bench` workflow validates and publishes assets for tags named `lab-bench-v<version>`, such as
`lab-bench-v0.1.0`. The workflow uploads KBuild's staged assets with `gh release create --latest=false` so an SDK
release does not replace the service's latest-release channel; KBuild's upload task does not expose that flag.
Only publication uses GitHub credentials. Published versions must keep their asset names and
contents unchanged; consumers pin an exact SDK version. This change does not publish a release by itself.

Consumer corpus transport settings use `LAB_CORPUS_MIRROR`, `LAB_CORPUS_MIRROR_WRITE`,
`LAB_CORPUS_KEEP_PACKED` and the preparation-only `LAB_INPUT_SIGNER`. These are generic
input capabilities; adapters own their pack formats and downloads. Replace older consumer-specific
worker variables when deploying the matching coordinator/worker release. Provider credentials
and worker authentication tokens are not forwarded to consumer processes.

Metrics CSV identifies each arm, seed and repeat. Solver-v1 records retain the normal
solver comparison and source-check views alongside SDK artifacts. Per-result
`stats.labMeasurement` marks compilation-instrumented captures, and `stats.labDebug`
reports the suite-owned debug setting; the coordinator does not inject either setting.
