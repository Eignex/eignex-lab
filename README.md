# Eignex Lab

Benchmark runner for Eignex repositories: repository-owned bench adapters and committed profiles.

[Setup](deploy/SETUP.md) · [Usage](docs/runs.md) · [Bench SDK](lab-bench/README.md)

## Local UI development

```sh
./gradlew :lab-app:devUi
```

Open [localhost:8080](http://127.0.0.1:8080). The launcher serves the real UI and API on loopback, without a
password, using fresh synthetic jobs, schedules, regression history, reference results, and an in-memory fake
Nomad. It does not start workers, execute case commands, or use the Mac, Docker, SSH, or cloud credentials.
It builds its own configuration instead of reading `LAB_*` deployment settings. The launcher and simulator live
under `lab-app/src/test` and are excluded from production distributions.

The `preview-bench` job includes a dense four-arm comparison with metrics and downloadable case artifacts.
The regression samples include 12 themes across 96 problems, with improving, regressing, and plateauing histories.
Job controls work against the local store; simulated paired rows progress every three seconds. Manual node
requests pass through the real capacity collector and create fake nodes that boot after three seconds. Releasing
a request lets idle fake nodes retire. Only the seeded fixture commands are simulated; new API submissions are
not built or executed.

Edit files under `lab-app/src/main/resources/web/` and refresh the browser to pick up CSS and JavaScript changes.
The queue's cached display can take up to five seconds to pick up those edits. Restart the command after Kotlin
changes. Each start creates a fresh directory under `lab-app/build/dev-ui/`; `Ctrl+C`
stops the preview, and `./gradlew clean` removes its data. Use `./gradlew :lab-app:devUi -PuiPort=8081` to choose another port.

## Lab access

Agents working in consuming repositories use `deploy/lab` and the authenticated HTTP API at
`https://lab.eignex.com`. Server access and administration belong to agents explicitly working on
eignex-lab operations; report operational dependencies to that owner.

**Auto placement is preferred and is the default for ordinary jobs.** Omit `tags`: the lab uses
idle local capacity and adds cloud capacity as demand grows, respecting job priority.

An A/B case row is one workload, seed and repetition with all its configuration arms. The lab runs
the complete row on the same machine in one worker attempt. Different rows, preparation and
replacement attempts can use different compatible machines; ordinary A/B jobs need no machine pin.

The build uses [KBuild 1.3.8](https://github.com/Eignex/kbuild/releases/tag/v1.3.8) from public GitHub Release assets,
including its platform and SDK release-packaging helper. Downloads require no GitHub account or token.
The service uses JDK 25; the standalone Bench SDK targets Java 17.

## Modules and build

The root project coordinates the build. Each module owns its sources, resources, tests and build output:

| Module | Responsibility | JVM target |
| --- | --- | --- |
| `lab-app` | Coordinator, workers, CLI and web application | 25 |
| `lab-bench` | Published benchmark protocol and SDK | 17 |
| `lab-model` | Portable workload model shared by the app and solvers | 25 |
| `lab-solvers` | Independent source checkers and reference solvers | 25 |
| `lab-capacity` | Capacity grants, provider APIs and cloud function handlers | 21 |
| `xcsp3-tools` | Verified upstream XCSP3 Java tools | 8 |

Shared dependency versions live in `gradle/libs.versions.toml`; repositories are configured in
`settings.gradle.kts`. The application depends on the library modules, and solver unit tests run in
`lab-solvers`; tests involving coordinator services remain in `lab-app`.

```sh
./gradlew check                         # Verify every module
./gradlew :lab-solvers:test             # Solver unit tests
./gradlew :lab-app:test                 # Application and integration tests
./gradlew :lab-app:installDist          # lab-app/build/install/eignex-lab/
./gradlew :lab-app:distZip              # lab-app/build/distributions/
```

The installed launcher remains `bin/eignex-lab`. The Bench SDK's publication coordinates and release layout
are unchanged. Capacity function packages remain under `build/capacity-functions/` for Terraform.
