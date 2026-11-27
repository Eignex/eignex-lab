# Setup

This guide is for agents administering eignex-lab itself. Agents working in consuming repositories
use the [HTTP client workflow](../docs/runs.md); SSH access and host discovery stay with the lab operator.

Use macOS or Linux with Git and any JDK 17+ to launch Gradle; Gradle provisions the JDK 25 the services run on.
Benchmark runtimes and backend checks belong to each repository adapter.

```sh
git clone https://github.com/Eignex/eignex-lab.git
cd eignex-lab
deploy/install.sh
```

The installer builds a release under `$LAB_DATA/releases` and registers the API and runner services (launchd
daemons on macOS, systemd user units on Linux). Edit `~/.config/eignex-lab/lab.env` using the
[configuration example](lab.env.example), then rerun installation after setting changes.
`LAB_INSTALL_DRY_RUN=1 deploy/install.sh` writes the service files to a temporary directory without registering them.

## Client

Requires Bash, curl, and jq. Obtain the lab password from the operator and save it in
`~/.config/eignex-lab/password` or `LAB_PASSWORD`. Every API call requires it.

```sh
deploy/lab health
```

`/health` reports coordinator OS/JVM, CPU capabilities and node tags. Benchmark engine, vendor library and
thread evidence comes from the repository adapter's job artifacts.

The client defaults to `https://lab.eignex.com`. Set `LAB_URL` for another API endpoint.

## Public access

The lab Mac serves `lab.eignex.com` directly from the public home IPv4 address, which may be dynamic. The home
router forwards TCP 80 and 443 to Caddy on the Mac and TCP 4647 to its Nomad server, and the domain's A record
points at the home address. `sudo deploy/proxy/install-proxy.sh` installs Caddy as a boot service: it renews its
own certificate and forwards `/worker/v1/*` to the coordinator's worker API and everything else to the lab.
The Nomad server advertises its RPC at the home address so cloud clients reach it directly; mutual TLS and ACLs
guard it. A server first set up on the earlier private network keeps that address in its Raft configuration
and still binds it; keep it as a loopback alias with a boot service that runs `ifconfig lo0 alias ADDRESS/32`.

When the home address changes, the A record and the server's advertised RPC address both go stale, and the public
API, cloud workers and this machine's own Nomad client all stop. The periodic maintenance job follows it with
`deploy/public-address.sh` (which needs dig, curl and jq) when `~/.config/eignex-lab/dynamic-dns.json` (mode 600)
holds `{"token": "...", "domain": "eignex.com", "name": "lab"}` with a DigitalOcean token limited to the domain's
records: every five minutes it reads the address from OpenDNS's resolver, updates the A record when it differs,
rewrites `advertise.rpc` in the Nomad server configuration, and restarts the server and client agents. Without the
file it does nothing. An address change can interrupt access until the next maintenance run, DNS cache expiry and
worker reconnection, so keep the record's TTL short (currently 300 seconds). Keep DNS credentials and live
configuration outside source control; the current home address does not belong in the repository.

If the ISP puts the connection behind carrier-grade NAT, use the optional [public relay fallback](relay/README.md).

## Storage and updates

Data defaults to `~/eignex-lab-data` (`LAB_DATA`); logs are under `logs/`, artifacts under `jobs/` (`LAB_JOBS`).
Corpora use `~/.cache/eignex-lab/corpus` (`LAB_CORPUS`). Keep `LAB_DATA`, `LAB_JOBS`, the corpus,
`$LAB_DATA/nomad/artifacts` and the checker input cache `$LAB_DATA/nomad/check-inputs` on the internal SSD: they
are hot data that running jobs write and read file by file. A spinning external volume is an archive of ended jobs'
data only, never a path running jobs write:

- `LAB_JOBS_ARCHIVE` moves ended job directories there. Set it in the coordinator environment and native worker
  templates too, so native snapshots, logs and results follow archived jobs, including confirmations that reuse an
  ended job and retries of an archived one.
- `LAB_ARTIFACT_ARCHIVE` makes `ArtifactArchiver` move each ended job's bundle directory from
  `$LAB_DATA/nomad/artifacts` there `LAB_ARTIFACT_ARCHIVE_GRACE_MIN` (default 60) minutes after the job ends,
  leaving a symlink. Never move the whole artifacts directory behind a symlink instead. Keep all historical bundles.

An archive volume must pass the same mount, disk access and free-space checks as `LAB_JOBS`; while it fails,
admission holds pause new work but existing worker lease and progress updates continue. On macOS it must mount at
boot, and the programs launchd starts need Full Disk Access to read it: grant it to `/bin/sh` and
`/usr/bin/caffeinate`, through which every service starts. The configuration example includes the mount setting.
A launchd plist edited by hand takes effect only after `launchctl bootout` and `bootstrap`, not `kickstart`.

Fetch raw artifacts promptly: ended jobs trim after `LAB_TRIM_AGE_DAYS` (default 7) days, or sooner on low disk.
Results remain stored. Set `LAB_JOB_RETENTION_DAYS` to also forget ended jobs' records and directories that many
days after they end. Saved jobs may refer to the checker input cache through `nomad/prepared-corpus`; preserve it.

Use the same canonical corpus directory, on the internal SSD, for `LAB_CORPUS` and `LAB_SHARED_INPUT_ROOT`, in the
coordinator service environment, native queue target and native-service configuration. The runner's `lab.env` needs
`LAB_SHARED_INPUT_ROOT`, `LAB_SHARED_INPUT_URL` and `LAB_SHARED_INPUT_SIGNER` as well, since rechecks read the inputs
a job's cases were prepared with. Updating `LAB_CORPUS` alone leaves native workers and source checking on their
separate shared-input root. To change roots, merge and verify existing inputs, drain native sessions and stop the
coordinator so active input pins are not copied across filesystems, keep a symlink at the old shared-input path for
frozen worker recipes and saved job references, then restore scheduling and check reads through both paths.

The runner keeps the lab on the newest commit of the checkout it was installed from (`SelfUpdater`); this is the
only update channel. Every `LAB_UPDATE_CHECK_SEC` seconds (default 300; 0 turns the periodic check off) it fetches
that checkout and, when its upstream is ahead, runs `deploy/update.sh` between cases, never during one: native workers
yield after their current iteration first. The update pulls, builds a new release, checks the host and restarts the
lab services (API, runner, coordinator) onto it; Nomad agents and accepted remote work keep running. `deploy/lab update`
asks for the same update at once. Check `logs/update.log` if an update fails.

Worker images and native launchers follow separately: every five minutes the maintenance job runs
`deploy/worker-rollout.sh`, which, once the release's commit has its worker images on public ECR (`<commit>-amd64`,
`<commit>-arm64`, published by the `worker-image` workflow), pins them by digest in `$LAB_DATA/nomad/queue.json`,
points the native launcher at the new release, keeps the old release in `workerReplaces` so its sessions finish,
and restarts the coordinator. Its result is in `$LAB_DATA/maintenance/gradle-maintenance.json` (`workerRollout`);
each rollout keeps the old settings and a receipt in `$LAB_DATA/operations/worker-<sha8>-<time>`.

On macOS, installation registers that maintenance job (`deploy/host-maintenance.sh`, run every five minutes from a
copy in `$LAB_DATA/maintenance` that updates refresh). Besides the public address and worker rollout, it bounds
`$LAB_DATA/gradle` and `~/.gradle`: each local build cache has a `LAB_GRADLE_CACHE_GIB` (default 2) GiB target,
evicting older outputs once Gradle's cache lock is free, and stopped daemons' logs rotate above `LAB_GRADLE_LOG_MIB`
(default 20) MiB, keeping three compressed tails and expiring after seven days. The lab services' own logs under
`$LAB_DATA/logs` are bounded the same way in place, and logs of removed services go after seven days without writes.
These are periodic limits; active logs and busy caches may temporarily exceed them. Other caches, dependencies and
JDKs are preserved. The latest report is `$LAB_DATA/maintenance/gradle-maintenance.json`.

## Native and cloud execution with Nomad

Experiment execution uses Nomad exclusively; the local maintenance runner keeps schedules, confirmations, capture
analysis and reference checking. Use the [Nomad runbook](nomad/README.md) for the native Mac client, process
supervisor, queue admission and capacity services. Provider infrastructure is separate: [AWS and GCP
modules](infra/README.md) supply disposable Spot workers, scoped identity and independent capacity expiry through the
same worker protocol.

Set `LAB_NOMAD_QUEUE_SETTINGS` and `LAB_NOMAD_CAPACITY_SETTINGS` in the service environment after configuring the
coordinator. The queue links to `/capacity/view` for live reservations and pool state. Placement and priorities for
submissions are described in [usage](../docs/runs.md#experiments).

Use shared vCPU and memory budgets, finite pool/global vCPU limits and Spot pools. Profiles and measurements share
capacity within those budgets. On-demand requires an explicit selector. Local labels use `LAB_NODE_TAGS`; target
architecture/SIMD capabilities must match worker hardware. Keep native Mac limits explicitly advisory.
macOS installation needs no Docker VM. Grant disk access to the configured Java and Nomad executables; the process
supervisor runs on the same Java.

## Runtime pruning

Review `bash deploy/nomad/prune-runtime.sh --data "$LAB_DATA"` (needs jq and sqlite3), then add `--apply` to remove
unused release directories and named deployment leftovers. The pruner preserves releases referenced by running
processes, installed services, pending session intents, active jobs and retryable frozen recipes. It never deletes
the database, results, corpus, artifacts or Nomad allocation state; small retired-release provenance stays in
`deployments/retired-runtime`.
