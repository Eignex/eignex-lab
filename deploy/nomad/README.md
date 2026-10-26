# Nomad execution

Production uses Nomad for ordinary submissions, schedules and confirmations, with native Mac execution and Linux
Spot workers. See [the migration status](../NOMAD-MIGRATION.md) for verified canaries and outstanding removal gates.
The sandbox commands below create an isolated cluster; managed services and automatic admission are documented
later in this runbook.

The reusable Kotlin pieces are `ResourceBudget`, `NomadCpuCapacity`, and `NomadBatchJob`. The operator-only command
`eignex-lab nomad-spec <session.json>` validates a session and prints native Nomad Job JSON. It does not submit it.
Linux uses Docker with hard CPU/memory limits; Mac uses native `raw_exec`, explicitly marked as advisory scheduling.
CPU conversion uses the pinned driver's `cpu.usablecompute` denominator, not total MHz, to account for host reserves.
Live verification checks that the resulting cgroup quota never exceeds the declared vCPU budget.
Generated jobs require that exact CPU fingerprint as well as the requested node class, preventing a job converted
for one pool from being placed on a node with a different quota denominator.

## Linux verification

Requires Docker, Python 3, and JDK 25. The sandbox downloads checksum-pinned Nomad into the ignored build directory.
It runs a normal persistent single-server agent with mutual TLS and ACLs, bound to loopback ports 14646–14648.
The Linux client runs in a privileged Docker container with the local Docker socket and host cgroups mounted;
this is a local/CI prototype, not the cloud bootstrap configuration. It uses its own node class and data directory.

```sh
./gradlew test --tests com.eignex.lab.NomadBatchJobTest installDist
export JAVA_HOME="$(./gradlew -q printJavaHome)"
python3 deploy/nomad/sandbox.py install
python3 deploy/nomad/sandbox.py start
python3 deploy/nomad/sandbox.py verify
python3 deploy/nomad/sandbox.py stop
```

`verify` submits two 0.5-vCPU sessions and a 0.25-vCPU build stand-in to the same node. Each starts three CPU-bound
children in detached process sessions. It checks overlapping execution, cgroup quotas, throttling, memory ceilings,
and measured aggregate CPU use. It also submits a memory-exhaustion task and cancels a task with detached children.
These are synthetic execution probes, not real solver builds or JFR measurements. Results go to
`build/nomad-prototype/verification.json`; all probe jobs are deregistered, including on failure.

The base and probe images are pinned in `versions.json`, along with Nomad 2.0.7 and the planned Autoscaler 0.6.0.
The autoscaler is not installed or started by this slice. The agent image adds only the utilities Nomad needs for
node fingerprinting. Node CPU and memory are otherwise detected normally; the prototype reserves 500 MHz and 256 MiB
for its host. It does not request whole-node exclusivity.

Use `sandbox.py cli node status` or `sandbox.py cli job status <id>` to inspect the isolated cluster. The script
passes its own TLS configuration and ACL token rather than inheriting a production `NOMAD_*` environment. Tokens
and private keys remain under the private, ignored state directory; upload only verification reports to CI.
`stop` retains state for restart. Choose another `--state` directory and nonoverlapping `--port` range for another
prototype; pass these options before the action. Existing state cannot be transferred between ports casually.

## Native Mac check

On the Mac, use the same build/install/start steps, then:

```sh
python3 deploy/nomad/sandbox.py native-probe
python3 deploy/nomad/sandbox.py stop
```

The Mac starts the native Nomad agent as the current user, with the same loopback TLS/ACL configuration and no Linux
VM. The probe runs native Python processes and saves `native-verification.json`. Its CPU/memory settings are
scheduling budgets, not hard quotas. Native execution still needs the host's usual tooling and permissions.

Synthetic probes complement real native solver/JFR runs and live Spot acceptance. They exercise isolated service
failure and replacement without changing production jobs. Consult the migration status for production evidence.

## Render a session

Save an operator-owned JSON input with the target pool's actual fingerprinted usable MHz and total logical CPUs:

```json
{
  "id": "lab-probe-example",
  "platform": "linux",
  "nodeClass": "lab-prototype-linux",
  "cpuCapacity": {"usableMHz": 43900, "logicalCpus": 20},
  "budget": {"cpuMillis": 500, "memoryMiB": 256, "parallel": 1},
  "image": "python@sha256:d98cc29d3266e535dd9da1099688d9cb3ca4753813571e071763d17725460962",
  "command": ["python3", "-c", "print('bounded session')"]
}
```

```sh
build/install/eignex-lab/bin/eignex-lab nomad-spec session.json > job.json
python3 deploy/nomad/sandbox.py cli job run -json job.json
```

For `platform: "mac"`, omit `image`, use `nodeClass: "lab-prototype-mac"`, and provide a native executable and args.
The command is operator supplied for the prototype; it is not a public endpoint for accepting arbitrary commands
from experiment submissions. Real workers will receive trusted versioned work manifests from the coordinator.

## Portable worker and native supervisor

`WorkManifest` version 1 carries planned arms, full commits, cases, resource budgets and checksum-declared build
bundles. The executor renders the existing solve/reference/profile commands after staging on its own host.
Archives are bounded, locked, verified and atomically published; each attempt receives a private copy before any
measurement launcher is installed. Bundle OS/architecture/toolchain identities prevent accidental cache mixing.
Reference model/data/include paths in a manifest are relative to its bundle, never coordinator filesystem paths.

Run `python3 src/main/resources/nomad/process_supervisor.py --socket /private/path/process.sock` as a separate
same-user service on the Mac. The socket and ownership registry are private. It must live outside the Nomad client
and worker services. Each connection owns a process tree; connection loss, lease expiry and timeout stop it.
An inherited random marker finds children that create new sessions and are reparented; PID/start identity is
rechecked before signals. A service restart cleans recorded owners before accepting new work. This assumes trusted
lab programs preserve their inherited marker; it is not a sandbox for hostile executables. Linux uses the same
supervision protocol inside its allocation, with the Docker cgroup as the final cleanup/enforcement boundary.

The offline acceptance command is `eignex-lab worker-local manifest.json ROOT SOCKET LAB_LAUNCHER`. Put the approved
ZIP inputs at `ROOT/inputs/<sha256>.zip`. It runs complete paired iterations without opening SQLite or loading
provider configuration and writes `ROOT/results.json`. Its bounded 70-second offline lease is for acceptance probes;
connected workers use coordinator renewals. Real Mac solver/JFR acceptance remains separate from the fixture tests.

Build a portable worker image with `./gradlew installDist` and
`docker build -f deploy/nomad/worker.Dockerfile --build-arg WORKER_RELEASE=$(git rev-parse HEAD) -t REGISTRY/lab-worker:RELEASE .`.
Record the resulting registry digest in pool configuration. The pinned multi-architecture JDK base is shared across
providers; no cloud CLI, provider credential or database is included. The image entrypoint starts its allocation's
supervisor before invoking the requested worker command.

## Connected coordinator and worker protocol

Run `eignex-lab nomad-coordinator /private/path/coordinator.json` with the ordinary `LAB_DATA`, `LAB_JOBS` and
`LAB_CORPUS` configuration. Copy `coordinator.example.json` and replace its paths. Nomad control uses a narrowly
scoped ACL token and mTLS with PKCS12 client/trust stores; password and key files must be private. Apply
`coordinator-policy.hcl` in the matching namespace. Workers receive neither these files nor database access.
The worker listener defaults to loopback, separately from the operator API. A non-loopback listener requires
`privateNetwork=true` and an authenticated encrypted private route, or an HTTPS proxy terminating on loopback.
HTTP workers outside loopback must explicitly set `LAB_WORKER_PRIVATE_TRANSPORT=true` for that private route.

Session admission is automatic when the Nomad backend and queue settings are enabled.
`NomadBackend.enqueue` validates the manifest against the stored experiment, arm commits and case plan, then
persists the session, credential hash, manifest and Nomad submission intent together. Native/cloud preparation and
normal queue admission use this protocol. Do not manually edit a running job's
backend column. Production submissions select Nomad through the explicit backend setting.

Every `/worker/v1/sessions/<id>/` request carries its scoped session token, allocation ID and a signed Nomad workload
identity. Named identities have audience `eignex-lab-worker` and a ten-minute lifetime. Their renewable file lives at
`local/lab-worker.jwt` in the private allocation; restrict Nomad filesystem/job read permissions as credential access.
The worker reloads it on every request. Only the configured Nomad cluster supplies verification keys. The server
checks signature, expiry, audience, namespace, job, task and allocation, then checks durable generation/lease ownership.
A copied old allocation token cannot authenticate as its replacement.

The protocol registers a release, claims complete iterations, renews leases, publishes preparation/execution/upload
phases and case starts, uploads checksum-addressed outputs, and commits the full iteration manifest. CPU permits
bound aggregate case threads; durable claim grants preserve the experiment's per-node parallel cap across sessions.
Workers renew every ten seconds and stop owned processes ten seconds before their last confirmed lease would expire,
using monotonic local time. A volume hold blocks registration, new claims, renewals and acceptance; stop intents remain
available. Pause drains at the iteration boundary. Cancellation fences ownership before stop delivery.
Deletion waits until every session's durable stop has been acknowledged. An explicit `lab retry` starts a fresh
infrastructure retry allowance while preserving the previous loss events. The coordinator's configured namespace
scopes both job submission and signed allocation identity verification. Admission enforces stored host placement
and the existing local-only exceptions, including confirmations, reference runs and local schedules.

Profiles with `runTimeoutSec` use the experiment's durable accumulated active time. Lease responses carry the
remaining whole-run allowance; workers track it monotonically across preparation, builds, execution and transfers.
Supervision reserves shutdown and cleanup time inside that allowance. Expiry permanently fails and fences the run
rather than spending infrastructure retries. Accepted timeout captures still ingest after failure. Ordinary case
timeouts retain the existing 30-second shutdown grace for JVM/JFR output.

Uploads resume from the confirmed byte offset and publish only after size/checksum verification and filesystem sync.
Input downloads use bounded ranges and resume without rerunning captured cases. Accepted artifacts are projected into
existing job paths before completion and source checks are recorded durably. A coordinator restart replays ingestion
and pending checks rather than resetting claims. Worker or allocation loss reruns only unfinished iterations, with a
finite infrastructure retry budget. Nomad task restarts remain disabled and its reschedule/disconnect policy is explicit.
Ordinary solver exits/timeouts remain case outcomes. Linux workers refuse missing or mismatched runtime hard limits;
native workers report advisory enforcement. Resource counters are retained under each job's `nomad/` files.

`python3 deploy/nomad/connected_probe.py` builds the worker, uses a temporary loopback OCI registry to obtain a real
image digest, and runs the end-to-end acceptance test against a separate TLS/ACL Nomad sandbox. It exercises a paired
iteration, an API/coordinator restart, forced allocation loss, generation replacement, checksum ingestion and completion.
It stops its own agent and registry and retains private diagnostic state under `build/nomad-connected`. Never upload
that directory: it contains the sandbox CA, client keys, ACL token, allocation identities and session credentials.
Only test reports and non-secret verification summaries are CI artifacts.

## Managed native services and updates

`native_services.py` manages four separate launchd daemons: the persistent Nomad server, native client, independent
process supervisor and lab coordinator. Copy the server/client/coordinator/native-services examples to private local
configuration, replacing every example path and private-network address. The native daemon runs as the lab user.
Server and client state directories are distinct and persistent; both require TLS and ACLs. Keep all state and keys
private and back up the Nomad server snapshot alongside the lab database. Bootstrap its ACLs and issue the restricted
coordinator token separately; never give the management token to a worker.

On the Mac, `python3 deploy/nomad/native_services.py --init-runtime "$LAB_DATA/nomad" --server-ip PRIVATE_IP` installs
the checksum-pinned native binary and initializes CA/server/client/CLI certificates. Set up the coordinator's PKCS12
stores from those CLI credentials and CA. Provide the private route before starting the client. Render without
registering anything using `--config native-services.json --output /temporary/review-directory`; review the plists,
then run `--config native-services.json --install`. Setting `LAB_NOMAD_NATIVE_CONFIG` in `lab.env` integrates this
with `deploy/install.sh`, including its dry run. This does not opt normal submissions in to Nomad.

When job files or the shared worker cache live on an external disk, grant macOS Full Disk Access to both the lab's
Java executable and the supervisor's Python runtime. For Xcode's bundled Python, macOS can attribute disk access to
the enclosing Xcode app even when Python itself is enabled; inspect the `tccd` attribution before choosing the entry.
Restart the drained supervisor after changing its runtime, and verify it can create an output file on the external
disk before enabling native scheduling. An HTTP 200 health response alone is insufficient: `held` must also be null.

A loopback client needs an explicit HTTP advertise address, as supplied in `client.example.json`; Nomad refuses
an implicit localhost advertisement at startup. Verify the client's actual CPU attributes before creating queue
templates. On the production Apple M4, Nomad 2.0.7 reported frequencies of 4 and 2 MHz and total compute of 28,
which underflowed `cpu.usablecompute` after the host reserve. Configure the documented
[`client.cpu_total_compute` override](https://developer.hashicorp.com/nomad/docs/configuration/client#cpu_total_compute)
with the host's reviewed scheduling calibration, then read the exported `cpu.usablecompute` and `cpu.numcores`
again. Pin those exact attributes in queue templates; native resource enforcement remains advisory.

The supervisor and legacy runner share the same POSIX record lock (`LAB_NATIVE_EXECUTION_LOCK`, default
`$LAB_DATA/nomad/native.lock`). A native session reserves it across iteration boundaries and transfers. Several
experiments can share native execution within the coordinator's atomic aggregate CPU and memory budgets. The legacy
runner still waits for the supervisor's collective reservation to end. The ownership marker survives supervisor death, so the legacy runner cannot take the host before recovery has
cleaned recorded processes. Closing an experiment's last reservation cleans up only its processes. The collective lock is released when all experiments finish.
A missing or damaged supervisor holds native execution rather than granting unchecked ownership.

Native sessions drain for a pause or an update; priority orders admission without making an otherwise idle host exclusive. The runner takes the lock
before updating or executing local work; remote Linux allocations continue. Ordinary updates restart only the
API/runner/coordinator. They leave the Nomad agents and supervisor running. Changing those services requires the
explicit `--restart-agents` maintenance option after draining native work. In-flight workers must use immutable
release paths. Release pruning is disabled after native Nomad service installation; remove old worker releases only
after reconciliation proves no allocation or process uses them. Keep server snapshots and manifests needed for rollback.

Managed service/ownership tests complement live solver acceptance. Reference, confirmation, whole-run profile,
capture-analysis and interruption canaries must pass before final legacy execution removal.

## Provider-neutral capacity and operations

`capacity.example.json` defines homogeneous capability pools, purchasing market, explicit host memory reserves, finite
node/vCPU limits, sustained backlog, cooldown and idle retirement. Cloud profiles preserve the existing explicit
on-demand purchasing policy unless their tags request Spot; measurements/builds do not reserve a whole node.
Every session budget includes its helpers. The policy packs free CPU and memory on ready nodes, accounts for booting
nodes, preserves per-experiment/per-host parallel caps, and reuses warm capacity for lower-priority eligible work.
Only old empty nodes contribute to voluntary scale-in. Terminating machines still consume quota until gone, without
restoring an obsolete provider target count. CPU budgets are logical vCPUs, separately from Nomad's MHz conversion.

Set the coordinator's optional `capacity` object to `{"settingsFile":"/private/capacity.json",
"inventoryFiles":["/private/aws-inventory.json"],"healthTokenFile":"/private/capacity.token"}`.
Provider adapters write versioned `CapacityInventory` JSON atomically: observation time, pool IDs, instance IDs,
draining/target-count state and classified capacity failures. They contain no control credentials. The collector
joins this with live Nomad allocations; an unknown allocation conservatively consumes a whole node. It starts a growth
timer only for demand that cannot fit warm or booting workers, and persists backlog/cooldown/idle history across restarts.
A small sustained backlog can grow capacity without a core-hour estimate. CPU packing uses all logical vCPU quota;
Nomad's MHz conversion already incorporates its host reserve. Legacy `localPressureHours` and `reserveCpuMillis` fields
remain readable but no longer impose another growth threshold or subtract a second CPU reserve.
Inventory and Nomad observations must remain within 30 seconds, including collection time. Missing/stale inventory,
unavailable local volumes or an expired monotonic sample return no growth authorization. Capacity collectors never
provision instances. Control credentials and purchasing-specific operations stay in provider adapters.

The private worker listener exposes read-only `/capacity/snapshot`, `/capacity/metrics` and a narrow instant query
endpoint at `/capacity/api/v1/query`. They require the separate capacity token, not a worker credential. The instant
endpoint implements only `lab_capacity_desired_nodes{pool="POOL"}` for the Autoscaler's Prometheus APM plugin and
`pass-through` strategy (`query_window="instant"`). It requires no Prometheus server and does not implement PromQL.
Use the text endpoint if an existing Prometheus deployment should collect it. Stale data produces an error, never
an old count that can authorize growth.

On the operator API, `/capacity/view` shows every allocation/experiment, its budgets, pool purchasing, desired counts,
unreserved CPU/memory and capacity failures. `lab capacity` shows its JSON; `lab execution ID` shows session/allocation
identities, phases and generations. Set `LAB_NOMAD_CAPACITY_SETTINGS` on the ordinary API to the same settings file,
then use `lab capacity request POOL NODES [TTL_SECONDS]`, `lab capacity leases`, or `lab capacity release LEASE_ID`.
Requests persist, remain within pool/vCPU limits and expire within one hour; they cannot change a pool's market.
Worker credentials cannot create these requests. Existing wait/fetch/retry/cancel and accepted output paths remain.

Provider-side capacity expiry, private-network bootstrap and real AWS/GCP Spot canaries follow in the infrastructure
stack. The collector/packing tests alone do not establish live provisioning, quota handling or billing expiry.


## Source builds inside worker budgets

A `WorkerBundle` may include a `build` recipe with explicit Gradle task paths and a bounded timeout. Builds run
through the independent supervisor before execution (an empty task list only stages a committed script), inside the same session CPU/memory ceiling as the worker
and its helper JVMs. They do not claim an extra node or a second reservation. Gradle uses a repository/toolchain/platform-scoped dependency home, no
persistent daemon, bounded worker count and JVM heap, and in-process Kotlin compilation. A thirty-second disk check
holds preparation when a staging build exceeds its budget or the volume has less than 1 GiB free. This is a runtime
guard, not a filesystem quota; configure host/volume quotas where a hard disk ceiling is required.

`SourceInputs` exports an approved Git tip and its reachable history only after its mandatory source-trust
callback succeeds. The archive contains Git objects, never the mirror's remotes, hooks, credentials or configuration.
Source export must itself run in the budgeted preparation/helper allocation so Git packing and compression are
charged to that allocation. The publication utility does not start an unbudgeted coordinator-side build.
Additional data inputs live under `lab-inputs/` and cannot replace committed code. Workers fetch the exact approved
ref from that local bundle and check out the full SHA. Git state is retained for committed profile entry points
that use `git ls-files`; no external Git source or provider SDK runs in this build path. Git symlinks are checked out
as ordinary blobs, preserving their committed content without following host filesystem links. Source inputs are
supplied through the private artifact service.

Prepared builds are atomically published with a checksum inventory. Cache identity includes the approved full SHA and additional input checksums,
OS/architecture, declared toolchain/build recipe, actual JDK identity and effective build budget. Git pack transport
bytes do not invalidate a build of the same approved commit. A lock
prevents duplicate builds within the cache; corrupted or incomplete output rebuilds before execution. Every
iteration gets a private copy, keeping measurements and profiles from changing the baseline. Caches default
to the allocation unless shared storage is configured. Set native `LAB_WORKER_CACHE_ROOT` to a cache volume with sufficient free space.
Linux templates set `cacheHostVolume: "lab-cache"`; the coordinator creates a dynamic `mkdir` host volume on each
managed ready node, without restarting the client. The job requests multi-writer access, and checksum-verified
builds are single-flight across allocations on that node. Cache eviction is limited to unused outputs/dependency
homes; a pin protects each build until copying into its attempt finishes. Maintenance scans run at most once per
node per minute, with a 32 GiB default cache target plus bounded active staging. Spot loss only loses a disposable cache. Normal queue
preparation/admission runs through the coordinator.

A failed source build reports its bounded diagnostic tail through its authenticated attempt. The coordinator
fences all sibling sessions and records a permanent `source-build` failure, rather than repeatedly treating a
compiler error as Spot loss. A stale generation cannot fail replacement work. Failure acknowledgements are
idempotent. Transport loss and actual allocation revocation continue to replace whole unfinished iterations.

Targeted tests exercise real Git bundle export/checkout, trust rejection, build reuse/corruption recovery, helper
budgets and fenced failures. The connected Nomad test now builds its fixture from source in the Docker allocation,
then exercises coordinator restart and forced allocation loss after arm A. Native macOS CI runs the same source
build and independent-supervisor contracts. Real solver/JFR canaries and provider Spot gates remain outstanding.

## Logs and execution reporting

`lab tail ID` reads a bounded projection of durable execution events in the usual `job.log`, including final
status and preparation failures. Restarting the coordinator reconstructs it from SQLite. Accepted iterations still ingest their files and central
checks if cancellation or permanent failure wins before the next reconciliation; the ended status is preserved. Ended jobs are not
continually rewritten, so trimming their files remains effective. `/jobs/ID/execution` renders sessions,
allocation/node identities, phase/generation, accepted iterations and budgets in the browser; JSON clients retain
the existing structured response.

Workers stream provisional stdout/stderr once per second in chunks of at most 64 KiB, with idempotent byte
offsets and an 8 MiB ceiling per stream. Output belongs to a session/iteration/generation directory and requires
the current signed allocation and attempt lease. `/jobs/ID/files/N.out` and `lab cat ID N.out` fall back to this
active attempt only while no accepted file exists. Revocation removes that fallback immediately; a late write
can reach only the old private attempt path. Accepted files and captures still come from checksum-verified whole
iteration commit and are never overwritten by streaming output. After the acceptance acknowledgement, the worker removes the private iteration worktree and captures so long
sessions do not accumulate one source/build copy per iteration. Interrupted unacknowledged attempts retain their
workspace until allocation GC; reusable verified caches remain. Full output becomes available after acceptance;
the execution page explains the provisional limit. Transport retry and log streaming never hold up a solver or its
lease heartbeat. Provisional logs remain visible in Files for diagnosis until normal job-file retention removes them.

Tests cover duplicate/mismatched chunks, size limits, authentication, stale ownership, accepted-file separation,
and durable event reconstruction without recreating trimmed jobs. The real connected test additionally requires
output to be visible before acceptance and the final log to report `DONE`, across coordinator restart and forced
allocation loss. These are canary reporting checks; automatic queue planning/admission and live rollout gates remain
open as documented in the migration plan.

### Allocation-scoped preparation

A preparation manifest sets `preparationTimeoutSec` and has no cases. The worker uses its existing allocation
identity, renewable attempt lease, process supervisor and CPU/memory reservation to build approved source inputs,
resolve selections, and publish checksummed source and corpus bundles. The result records portable `lab-source/`
and `lab-corpus/` paths rather than allocation filesystem paths. Solve manifests can carry the prepared corpus in
`inputCorpus`; workers verify and stage it before rendering commands.

Preparation acceptance is durable before planning. Lost allocations replace the same pinned preparation inputs;
accepted preparation survives coordinator restarts without completing an experiment or running preparation twice.
A source/build error remains a permanent failure, and cancellation fences preparation uploads and acceptance.
Capacity collection accounts for queued preparation as build demand under the same provider-neutral vCPU limit.

### Automatic preparation and admission

Set `LAB_NOMAD_QUEUE_SETTINGS` to an absolute JSON settings file based on [queue.example.json](queue.example.json)
in the API, runner and coordinator environments. Start the coordinator before setting `LAB_EXECUTION_BACKEND=nomad`
in the API/runner environment. The default remains `legacy` until this explicit cutover. Analysis/recheck jobs still
run through the local coordinator; ordinary submissions, scheduled experiments and confirmations use the selected backend.

Replace the example's release, immutable launcher/image, node classes, datacenter, private API address and fingerprinted
`cpu.usablecompute`/logical CPU values. Declare the native target's usable CPU/memory ceiling and cloud targets within
the configured capacity pool's reserves. Each target's tags must describe its actual hardware. Worker images for the
same OS/architecture must use the same digest so prepared binaries can be shared across providers. Raw HTTP requires
an authenticated private network and `LAB_WORKER_PRIVATE_TRANSPORT=true`; the public operator listener is never a
worker endpoint.

The coordinator verifies repository ownership and trusted commit ancestry before exporting local Git bundles. Builds,
selection, corpus retrieval and archives run in budgeted preparation allocations. Accepted preparation publishes arms,
problems, cases, commands and its setup marker in one SQLite transaction. Reference filters and per-selection refill/caps are approved durably before the worker resolves files or packages
its corpus. Identical selection retries keep that approval even if reference rows change, and attempts cannot change
the approved problem identities. Interleaving, duration confirmation and source checking retain their domain behavior. Reference rows and scheduled
confirmation effects are reconciled after accepted results, with a durable completion marker.

Admission fills native capacity first and spills compatible work into provider-neutral Spot pools. On-demand requires
an explicit node selector; profiles do not imply it. Nomad also permits exact problem inputs and whole-run profile
limits on cloud workers. Reference solvers and schedules that remain local still respect those placement rules.
`caseMemoryMiB` includes each solve's heap/native allowance and `sessionMemoryMiB` covers worker/helpers. An explicit
solve heap must fit rather than being silently changed. Reservations are bounded by the cases a paired iteration can
actually run, the job's per-host parallel cap, and each target's CPU/memory ceiling. A template `parallel` of one gives
small reservations that pack between experiments; larger values let one paired iteration run its arms concurrently.
The existing global provider vCPU limit bounds capacity growth, independent of provider and Spot pool count.

Queue settings are snapshotted per admitted experiment, and source SHAs, inputs, release and templates remain pinned
across coordinator restarts and configuration changes. Lost workers are replenished while others run, within the
infrastructure retry limit. Pending solve sessions do not request capacity once all work is claimed. Source checks run
on a bounded background queue so slow checker installation/execution does not stall capacity health or reconciliation.

Keep the legacy executor and provider infrastructure until native and Spot production canaries, interruption recovery,
source checks, schedule/confirmation behavior and a stable rollout have passed. Switching the default is not proof that
old allocations have stopped; use the durable stop/reconciliation gates before deleting their setup.
