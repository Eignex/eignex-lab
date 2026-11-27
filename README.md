# Eignex Lab

Benchmark runner for Eignex repositories: repository-owned bench adapters and committed profiles.

[Setup](deploy/SETUP.md) · [Usage](docs/runs.md) · [Bench SDK](lab-bench/README.md)

Agents in consuming repositories use `deploy/lab` and the HTTP API at `https://lab.eignex.com`; see
[Usage](docs/runs.md) for access, placement and paired-row semantics.

## How the parts fit

One Mac runs the controller and a native worker; Linux Spot instances on AWS (or GCP) add workers on demand.

- **API** (`eignex-lab api`) serves the web UI and the authenticated HTTP API that `deploy/lab` calls, behind Caddy
  (`deploy/proxy`). State is one SQLite database under `LAB_DATA`.
- **Runner** (`eignex-lab runner`) is the local maintenance loop: schedules, confirmations, capture analysis,
  rechecks, retention and self-update from this checkout.
- **Coordinator** (`eignex-lab nomad-coordinator`) prepares submitted experiments, admits Nomad batch jobs, serves the
  worker protocol (`/worker/v1`), accepts results and computes desired cloud capacity.
- **Workers** (`eignex-lab worker`) run in Nomad allocations: natively on the Mac through `raw_exec`, in the worker
  Docker image on Linux. Each builds the repository, runs its bench adapter's cases in paired rows under a separate
  **process supervisor** (`eignex-lab process-supervisor`), and uploads checksummed results.
- **Capacity**: the Mac-side `eignex-lab capacity-adapter` publishes provider inventory and renews signed leases,
  the Nomad Autoscaler resizes Spot groups, and the `lab-capacity` cloud functions renew or expire them
  independently of the Mac.
- **Bench adapters** live in consuming repositories and implement the [Bench SDK](lab-bench/README.md) process
  contract (`describe`, `plan`, `run`); the lab owns placement, pairing, checking and storage.

## Repository layout

| Path | Contents |
| --- | --- |
| `lab-app` | Coordinator, runner, API/web UI, worker and CLI (JVM 25). Packages under `com.eignex.lab`: `cli` entry points, `domain` models and settings, `execution` runner jobs and source/input handling, `nomad` queue, admission and reconciliation, `worker` worker session and preparation, `capacity` capacity policy and provider adapters, `store` SQLite repositories and artifact store, `web` routes and pages (assets in `src/main/resources/web`), `analysis` results, trends and confirmations, `util` I/O and process helpers |
| `lab-bench` | Published Bench SDK and protocol (JVM 17) |
| `lab-model` | Portable workload model shared by the app and solvers (JVM 25) |
| `lab-solvers` | Independent source checkers and reference solvers (JVM 25) |
| `lab-capacity` | Capacity grants, provider HTTP clients and cloud function handlers (JVM 21) |
| `xcsp3-tools` | Verified upstream XCSP3 Java tools (JVM 8) |
| `deploy/` | Host install/update scripts, `deploy/lab` client and `lab.env.example`; `nomad/` runbook, settings examples and worker image; `infra/` OpenTofu provider modules; `proxy/` Caddy; `relay/` CGNAT fallback; `schedules/` committed schedule recipes; `tests/` bash tests |
| `images/` | Reference solver Docker images (SCIP, clasp, XCSP3 cp-sat) |
| `checker/`, `bench/` | Bench manifests the lab runs on itself: DIMACS checker controls (`checker/verify-dimacs.sh` in CI) and optimum-claim replay |
| `docs/` | [Usage](docs/runs.md) and [independent source checking](docs/source-checking.md) |

## Build

Shared dependency versions live in `gradle/libs.versions.toml`; repositories are configured in
`settings.gradle.kts`. The build uses [KBuild 1.3.8](https://github.com/Eignex/kbuild/releases/tag/v1.3.8) from public
GitHub Release assets and needs no GitHub token. Gradle provisions JDK 25 for the service. Gradle uses build and
configuration caching, a 4 GiB heap and at most four workers; CI uses `.github/actions/setup-build` for the same
Temurin 25 setup. Test results are not cached because tests exercise host processes and external tools.

```sh
./gradlew check                         # Verify every module
./gradlew :lab-solvers:test             # Solver unit tests
./gradlew :lab-app:test                 # Application and integration tests
./gradlew :lab-app:installDist          # lab-app/build/install/eignex-lab/ (launcher bin/eignex-lab)
./gradlew :lab-app:distZip              # lab-app/build/distributions/
./gradlew :lab-capacity:capacityFunctions  # build/capacity-functions/ for OpenTofu
```

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
