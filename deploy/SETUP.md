# Install Eignex Lab

Eignex Lab runs Klause experiments, Koblas captures, and committed profile entry points from **Eignex repositories only**.
It serves the queue and results over HTTP, with a local runner and optional AWS workers.

## Server

Use macOS or Linux with Git and a JDK 17+ to launch Gradle. The build provisions JDK 25 for the services.
The startup check requires Koblas's Vector API engine; scalar fallback stops installation. Host BLAS is reported
but is optional. Docker and external solver tools are needed only for the [reference workloads](../docs/validation.md#reference-solvers) that use them.

```sh
git clone https://github.com/Eignex/eignex-lab.git
cd eignex-lab
deploy/install.sh
```

The installer builds the distribution, checks the host, and registers the API and runner with launchd on macOS or
systemd user units on Linux. Services start without a login. On macOS, installation needs sudo and disables sleep.
`LAB_INSTALL_DRY_RUN=1 deploy/install.sh` generates service files without registering them.

Settings live in `~/.config/eignex-lab/lab.env`. Edit them and rerun `deploy/install.sh` to apply service changes.
Start with the commented [environment example](lab.env.example); [Config.kt](../src/main/kotlin/com/eignex/lab/Config.kt)
defines all defaults. Before placing data on a removable disk, follow [external storage](../docs/operations.md#external-storage).

| Location | Contents |
| --- | --- |
| `~/eignex-lab-data` (`LAB_DATA`) | Database, mirrors, worktrees, releases, isolated Gradle home |
| `$LAB_DATA/jobs` (`LAB_JOBS`) | Per-job logs, records, and captures |
| `~/.cache/klause-bench/corpus` (`LAB_CORPUS`) | Downloaded benchmark sources |
| `$LAB_DATA/logs/{api,runner}.log` | Service logs |

Inspect a host independently with `$LAB_DATA/releases/current/bin/eignex-lab check`.

## Client and password

The [client](lab) needs Bash, curl, and jq. Copying corpora also needs rsync and SSH access to the server.
Set `LAB_HOST` (default `192.168.50.104`) or `LAB_URL` for another endpoint:

```sh
export LAB_URL=http://lab-host:8420
deploy/lab health
```

Every page and API call requires the lab password, including `/health`. Put the password in
`~/.config/eignex-lab/password`, or set `LAB_PASSWORD`; `LAB_PASSWORD_FILE` selects another file.
Browsers use HTTP Basic with an empty username. Obtain the password from the operator.
Password rotation and commit permissions are covered in [operations](../docs/operations.md#access-and-trust).

## First run

Prepare a [run specification](../docs/runs.md), then submit and inspect it:

```sh
deploy/lab run experiment.json
deploy/lab show 7
deploy/lab cases 7
deploy/lab wait 7
deploy/lab fetch 7
```

Use the returned job ID in place of `7`. The browser at `LAB_URL` shows the same queue, live output, and results.
The runner downloads required corpora on first use. To seed its cache from another machine, enable SSH access and
run `deploy/lab corpus` there.

## Updates

Push changes to the server checkout's upstream and run `deploy/lab update`. The runner checks automatically every
300 seconds (`LAB_UPDATE_CHECK_SEC`; `0` disables it) and updates between local cases. A failed build or host check
leaves the current release running; inspect `$LAB_DATA/logs/update.log`.

Builds are copied into versioned releases, with the three newest retained. Avoid building over a running
installation. Rerun `deploy/install.sh` when changing service configuration; normal code updates use the runner's
update flow. Cloud nodes are adopted after restart, and interrupted iterations rerun.

## Guides

| Task | Guide |
| --- | --- |
| Submit experiments, profiles, or Koblas captures; select hardware | [Runs](../docs/runs.md) |
| Collect resources, JFR, compilation reports, or saved evidence | [Measurements](../docs/measurements.md) |
| Understand witness checks and reference comparisons | [Validation](../docs/validation.md) |
| Configure AWS capacity and node labels | [Cloud nodes](../docs/cloud.md) |
| Manage access, storage, Docker, and recovery | [Operations](../docs/operations.md) |
| Integrate another client | [HTTP API](../docs/api.md) |
