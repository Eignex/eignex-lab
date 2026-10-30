# Setup

This guide is for agents administering eignex-lab itself. Agents working in consuming repositories
use the [HTTP client workflow](../docs/runs.md); SSH access and host discovery stay with the lab operator.

Use macOS or Linux with Git and JDK 17+. Gradle provisions JDK 25. Benchmark runtimes and backend checks
belong to each repository adapter.

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
deploy/lab health
```

`/health` reports coordinator OS/JVM, CPU capabilities and node tags. Benchmark engine, vendor library and
thread evidence comes from the repository adapter's job artifacts; the health response no longer includes the
old `koblasEngine`, `simd`, `candidates` or vendor fields.

The client defaults to `https://lab.eignex.com`. Set `LAB_URL` for another API endpoint.

## Storage and updates

Data defaults to `~/eignex-lab-data`; logs are under `logs/`, artifacts under `jobs/` (`LAB_JOBS`).
Corpora use `~/.cache/eignex-lab/corpus` (`LAB_CORPUS`). On macOS, external volumes must mount at boot and
allow the services' Java binary Full Disk Access. The configuration example includes the mount setting.
Fetch raw artifacts promptly: ended jobs trim after seven days or sooner on low disk. Results remain stored.

Use `deploy/lab update` for code updates between cases. Check `logs/update.log` if an update fails.

On macOS, installation registers Gradle maintenance every five minutes for `$LAB_DATA/gradle` and `~/.gradle`.
Each local build cache has a 2 GiB target; eviction waits for Gradle's cache lock and removes older outputs first.
Stopped daemons' logs rotate above 20 MiB, retaining three compressed tails and expiring logs after seven days.
Active logs stay intact until the daemon exits. These are periodic limits; active logs and busy caches may temporarily
exceed them. Configure `LAB_GRADLE_CACHE_GIB` and `LAB_GRADLE_LOG_MIB` in `lab.env` and rerun installation.
The latest report is `$LAB_DATA/maintenance/gradle-maintenance.json`. Other caches, dependencies and JDKs are preserved.

## Native and cloud execution with Nomad

Use the [Nomad runbook](nomad/README.md) for the native Mac client, process supervisor, queue admission and capacity
services. Provider infrastructure is separate: [AWS and GCP modules](infra/README.md) supply disposable Spot workers,
scoped identity and independent capacity expiry through the same worker protocol.

Set `LAB_NOMAD_QUEUE_SETTINGS` and `LAB_NOMAD_CAPACITY_SETTINGS` in the service
environment after configuring the coordinator. Normal submissions, schedules and confirmations use Nomad; analysis
and maintenance remain local. The queue links to `/capacity/view` for live reservations and pool state.

Auto placement is preferred and is the default for ordinary jobs. Submissions should omit `tags`;
the lab uses idle local capacity and scales cloud capacity with demand, respecting priority. Local-only placement is
reserved for sweeps and reference runs. See [placement and priorities](../docs/runs.md#experiments).

Use shared vCPU and memory budgets, finite pool/global vCPU limits and Spot pools. Profiles and measurements share
capacity within those budgets. On-demand requires an explicit selector. Local labels use `LAB_NODE_TAGS`; target
architecture/SIMD capabilities must match worker hardware. Keep native Mac limits explicitly advisory.

The old cloud setup script is removed. Nomad ignores retired `aws/aws.properties` files and rejects legacy
`/cloud/nodes` operations; operator capacity requests use expiring `/capacity/leases`. Preserve historical results
and archive existing settings before removing old infrastructure.


## Retiring old installations

Experiment execution uses Nomad exclusively. The maintenance runner
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

The checker input cache at `$LAB_DATA/nomad/check-inputs` can also live on the results volume, with a symlink
at its original path. Saved jobs may refer to that path through `nomad/prepared-corpus`; preserve it when moving
the cache. Copy and verify the files while the coordinator runs, then stop only the coordinator for the final
consistency check and directory switch. Restart it promptly to preserve worker leases, verify existing input
reads and checking progress, and only then remove the internal copy.

Use the same canonical external corpus directory for `LAB_CORPUS` and `LAB_SHARED_INPUT_ROOT`, including the
coordinator service environment, native queue target and native-service configuration. The runner's `lab.env` needs
`LAB_SHARED_INPUT_ROOT`, `LAB_SHARED_INPUT_URL` and `LAB_SHARED_INPUT_SIGNER` as well: it runs rechecks, which read
the inputs a job's cases were prepared with. Updating `LAB_CORPUS`
alone leaves native workers and source checking on their separate shared-input root. Merge and verify existing
inputs before changing roots; drain native sessions and stop the coordinator for the switch so active input pins
are not copied across filesystems. Keep a symlink at the old shared-input path for frozen worker recipes and saved
job references, then restore scheduling and resume jobs after checking reads through both paths.
