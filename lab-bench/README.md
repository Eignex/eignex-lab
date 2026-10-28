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

A bench request names one source revision. Cases, backends, seeds, repetitions, runtime flags and instrumentation
belong to the adapter; the lab does not add JFR or Vector API flags. Use legacy Klause experiments for reference arms
and paired multi-revision comparisons. Generic bench jobs do not trigger solver regression confirmations.

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

The three consumer bench modules accept
`-PlabBenchRepository=file:///absolute/eignex-lab/lab-bench/build/adapter-repository`
and `-PlabBenchVersion=0.1.0`. Build the tasks declared in each consumer's committed manifest with those properties.
Their default repository URL resolves public GitHub Release assets anonymously after publication. When restaging
the same unpublished version, refresh consumer dependencies with `--refresh-dependencies` or select a fresh
development version so Gradle cannot reuse an earlier SDK artifact.

Arrange the consumer checkouts as `<root>/klause`, `<root>/koblas` and `<root>/kumulant`, then run:

```sh
LAB_BENCH_ADAPTER_ROOT=/absolute/consumer-checkouts \
LAB_BENCH_ADAPTER_NATIVE=true LAB_BENCH_ADAPTER_MINIZINC=true ./gradlew verifyBenchAdapters
```

This opt-in test stages the real prepared outputs, calls each manifest's planner through `PreparationExecutor`,
relocates bundles, executes frozen cases under the process supervisor, transfers artifacts and ingests accepted
records through `NomadBackend`. It checks Klause solve provenance, Koblas CSV/report retention and Kumulant JMH/
Native reports. The Native option requires Linux x86_64 binaries built by the Native manifests. Without that
option it covers the JVM adapters. The ordinary test task excludes this integration gate, so unit tests do not
build consumer repos or run performance captures. The smoke timings establish wiring, not performance evidence.
The MiniZinc option also exercises a file-backed Klause MiniZinc case with a locally installed compiler.
A local run does not validate live Nomad scheduling, remote transport or other hardware platforms.

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
