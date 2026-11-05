# Eignex Lab

Benchmark runner for Eignex repositories: repository-owned bench adapters and committed profiles.

[Setup](deploy/SETUP.md) · [Usage](docs/runs.md) · [Bench SDK](lab-bench/README.md)

## Local UI development

```sh
./gradlew devUi
```

Open [localhost:8080](http://127.0.0.1:8080). The launcher serves the real UI and API on loopback, without a
password, using fresh synthetic jobs, schedules, regression history, reference results, and an in-memory fake
Nomad. It does not start workers, execute case commands, or use the Mac, Docker, SSH, or cloud credentials.
It builds its own configuration instead of reading `LAB_*` deployment settings. The launcher and simulator live
under `src/test` and are excluded from production distributions.

The `preview-bench` job includes a dense four-arm comparison with metrics and downloadable case artifacts.
The regression samples include 12 themes across 96 problems, with improving, regressing, and plateauing histories.
Job controls work against the local store; simulated paired rows progress every three seconds. Manual node
requests pass through the real capacity collector and create fake nodes that boot after three seconds. Releasing
a request lets idle fake nodes retire. Only the seeded fixture commands are simulated; new API submissions are
not built or executed.

Edit files under `src/main/resources/web/` and refresh the browser to pick up CSS and JavaScript changes.
The queue's cached display can take up to five seconds to pick up those edits. Restart the command after Kotlin
changes. Each start creates a fresh directory under `build/dev-ui/`; `Ctrl+C`
stops the preview, and `./gradlew clean` removes its data. Use `./gradlew devUi -PuiPort=8081` to choose another port.

## Lab access

Agents working in consuming repositories use `deploy/lab` and the authenticated HTTP API at
`https://lab.eignex.com`. Server access and administration belong to agents explicitly working on
eignex-lab operations; report operational dependencies to that owner.

`bench/mps-replay.json` runs retained MPS point checks through the Bench SDK. Its
`checks` parameter is a JSON array with `model`, `record`, `expected`, `origin` and
`control` for each check. Optional `root=CORPUS` resolves the model under the frozen
corpus; the default `SOURCE` uses the repository. Source hashes are required, and
`control` permits only witness or exact-objective changes for explicit negative
checks. Receipts validate points, without proving optimality or performance. Optional
`numericalSemantics` with an explicit `semanticsOrigin` supplies a separately
identified conditional replay policy; it cannot replace a retained declaration.
`expected` checks the exact verdict and optional `expectedConfigured` checks the
configured verdict. Historical records without declarations remain unqualified.

MPS checks use rational arithmetic for every witness spelling. `labCheck.mpsChecks`
retains zero-tolerance and declared-policy verdicts independently. A supported
`numericalSemantics` declaration identifies exact decimal source authority,
absolute/relative feasibility slack and effective default bounds; integrality and
objective equality remain exact. Comparison identity binds that effective policy,
independent checker rules, source digest, original objective sense/units and whole
model scope without assumptions. A point verdict supplies no optimum authority.

**Auto placement is preferred and is the default for ordinary jobs.** Omit `tags`: the lab uses
idle local capacity and adds cloud capacity as demand grows, respecting job priority.

An A/B case row is one workload, seed and repetition with all its configuration arms. The lab runs
the complete row on the same machine in one worker attempt. Different rows, preparation and
replacement attempts can use different compatible machines; ordinary A/B jobs need no machine pin.

The build uses [KBuild 1.3.6](https://github.com/Eignex/kbuild/releases/tag/v1.3.6) from public GitHub Release assets,
including its platform and SDK release-packaging helper. Downloads require no GitHub account or token.
The service uses JDK 25; the standalone Bench SDK targets Java 17.
