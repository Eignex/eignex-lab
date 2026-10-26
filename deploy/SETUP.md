# Setup

Use macOS or Linux with Git and JDK 17+. Gradle provisions JDK 25; startup requires Koblas's Vector API engine.

```sh
git clone https://github.com/Eignex/eignex-lab.git
cd eignex-lab
deploy/install.sh
```

The installer registers API/runner services. Edit `~/.config/eignex-lab/lab.env` using the
[configuration example](lab.env.example), then rerun installation after setting changes.

## Client

Requires Bash, curl, and jq. Obtain the lab password from the operator and save it in
`~/.config/eignex-lab/password` or `LAB_PASSWORD`. Every API call requires it.

```sh
export LAB_URL=http://lab-host:8420
deploy/lab health
```

## Storage and updates

Data defaults to `~/eignex-lab-data`; logs are under `logs/`, artifacts under `jobs/` (`LAB_JOBS`).
Corpora use `~/.cache/klause-bench/corpus` (`LAB_CORPUS`). On macOS, external volumes must mount at boot and
allow the services' Java binary Full Disk Access. The configuration example includes the mount setting.
Fetch raw artifacts promptly: ended jobs trim after seven days or sooner on low disk. Results remain stored.

Use `deploy/lab update` for code updates between cases. Check `logs/update.log` if an update fails.

## Native and cloud execution with Nomad

Use the [Nomad runbook](nomad/README.md) for the native Mac client, process supervisor, queue admission and capacity
services. Provider infrastructure is separate: [AWS and GCP modules](infra/README.md) supply disposable Spot workers,
scoped identity and independent capacity expiry through the same worker protocol.

Set `LAB_NOMAD_QUEUE_SETTINGS` and `LAB_NOMAD_CAPACITY_SETTINGS` in the service
environment after configuring the coordinator. Normal submissions, schedules and confirmations use Nomad; analysis
and maintenance remain local. The queue links to `/capacity/view` for live reservations and pool state.

Use shared vCPU and memory budgets, finite pool/global vCPU limits and Spot pools. Profiles and measurements share
capacity within those budgets. On-demand requires an explicit selector. Local labels use `LAB_NODE_TAGS`; target
architecture/SIMD capabilities must match worker hardware. Keep native Mac limits explicitly advisory.

The old cloud setup script is removed. Nomad ignores retired `aws/aws.properties` files and rejects legacy
`/cloud/nodes` operations; operator capacity requests use expiring `/capacity/leases`. Preserve historical results
and archive existing settings before removing old infrastructure.


## Retiring old installations

Experiment execution uses Nomad exclusively; there is no backend switch or AWS/SSH executor. The maintenance runner
keeps schedules, confirmations, capture analysis and reference checking. Old job backend labels are retained as
historical provenance, and those historical jobs remain readable without enabling an old executor.

macOS installation defaults to no Docker VM and never starts Colima. Grant disk access to the configured Java,
Python and Nomad executables; Xcode-bundled Python may require the enclosing Xcode app's permission too.

Review `python3 deploy/nomad/prune_runtime.py --data "$LAB_DATA"`, then add `--apply` to remove unused release
directories and named deployment leftovers. The pruner preserves releases referenced by running processes, installed
services, pending session intents, active jobs and retryable frozen recipes. It never deletes the database, results,
corpus, artifacts or Nomad allocation state; small retired-release provenance stays in `deployments/retired-runtime`.

The coordinator stores immutable, job-scoped bundles under `$LAB_DATA/nomad/artifacts`. On a small internal disk,
place that directory on the mounted results volume and retain a symlink at the original path. Copy while the
coordinator runs, stop only the coordinator for a final synchronization and directory switch, then restart it and
verify existing artifact reads before deleting the original copy. Keep all historical bundles; the runtime pruner
does not remove them. The results volume must pass the same mount, disk access and free-space checks as `LAB_JOBS`.
Admission holds pause new work; existing worker lease and progress updates remain available.
