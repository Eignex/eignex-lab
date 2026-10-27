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
Resource declarations describe requirements; they do not enforce process limits or configure existing lab jobs.
The caller must validate and enforce them before execution. Suite code validates the parameters it accepts.

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

## Run through the existing lab

The current integration uses committed profile entry points. `BenchCli` uses the runner's `OUT` directory when no
explicit output directory is supplied and exposes `LAB_REPOSITORY` and `LAB_WORKTREE` through `BenchContext`.
The profile runner already retains files under OUT on both successful and failed executions.

For example, after adding an SDK main and a committed `example-bench/lab/smoke-case.json` in a consumer repo:

```json
{
  "name": "example-smoke",
  "repository": "Eignex/example",
  "description": "Verify the repo-owned benchmark entry point",
  "base": {"ref": "main"},
  "profile": {
    "main": "com.eignex.example.bench.MainKt",
    "buildTasks": [":example-bench:installDist"],
    "classpath": ["example-bench/build/install/example-bench/lib/*"],
    "args": ["run", "example-bench/lab/smoke-case.json"]
  }
}
```

A committed script can instead own the build and launch an installed SDK main. The current JVM profile path adds
JFR recording and Vector API flags; use a script when the repository needs control of instrumentation and runtimes.

Automatic suite discovery, scheduling an SDK plan, enforcing its resources, and ingesting SDK metrics into the UI
are separate coordinator integration work. This module supplies the contract and is usable through profiles; it
does not change Klause experiments, reference-solver ownership, or the lab's existing result schema.

## Build and release

```sh
./gradlew :lab-bench:check :lab-bench:verifyRelease -PbenchVersion=0.1.0
```

`releaseAssets` stages the JAR, sources JAR, POM, and Gradle Module Metadata under `lab-bench/build/release-assets/`.
The SDK version is independent of the service version; local builds default to `0.1.0-SNAPSHOT`.
`verifyRelease` serves these files using the exact release URL layout and compiles/runs an independent consumer
with a fresh Gradle user home and no credential environment variables. It also checks the dependency boundary
and Java 17 bytecode. The consumer downloads ordinary Kotlin dependencies anonymously from Maven Central.

The `release-bench` workflow validates and publishes assets for tags named `lab-bench-v<version>`, such as
`lab-bench-v0.1.0`. Only publication uses GitHub credentials. Published versions must keep their asset names and
contents unchanged; consumers pin an exact SDK version. This change does not publish a release by itself.
