# Nomad execution

Production uses Nomad for ordinary submissions, schedules and confirmations, with native Mac execution and Linux
Spot workers. See [the migration status](../NOMAD-MIGRATION.md) for verified canaries and outstanding removal gates.
The sandbox commands below create an isolated cluster; managed services and automatic admission are documented
later in this runbook.

The coordinator refreshes capacity independently every five seconds, so source checks and worker reconciliation
cannot delay the 30-second freshness gate. Node details are cached by Nomad's `ModifyIndex`; node changes invalidate
the cache. The AWS adapter also publishes inventory independently of pool renewals. Renewal failures identify the
pool and error code without logging signed grants or response bodies.

Every five minutes, the coordinator asks Nomad to garbage collect this lab's native allocations that have been
`complete` or `failed` for at least one hour. This removes disposable allocation workspaces and task logs, while
accepted results, job files, shared corpus and build caches remain in their own stores. Active, unknown, recent,
unrelated and unreachable allocations are skipped. Cleanup uses Nomad's client allocation GC API, which also
refuses active allocations, and does not restart the native agent or its workers. Nomad's normal disk-pressure
cleanup remains enabled; its default thresholds alone can retain several large finished workspaces.

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
./gradlew :test --tests com.eignex.lab.NomadBatchJobTest installDist
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

The worker includes MiniZinc 2.10.1 and its bundled reference solvers from a pinned multi-architecture distribution.
MiniZinc is on `PATH` for compilation and explicit reference runs. Checkers may only validate supplied assignments or
proofs; they must never invoke a full solver, search for a missing assignment, or rerun an optimization problem.
MiniZinc claims remain `unsupported` until an independent assignment checker is available. SMT-LIB model checking
uses Dolmen; models it cannot evaluate stay undecided without a solver fallback.

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
using monotonic local time. A volume hold blocks registration, new claims and acceptance; existing workers can still
renew leases and report progress, and stop intents remain available. Pause drains at the iteration boundary.
Cancellation fences ownership before stop delivery.
The coordinator probes volume health on a dedicated five-second monitor. Control requests read its cached sample;
an unavailable or more than fifteen-second-old sample holds growth without blocking lease renewals. Artifact and job
file writes still validate the mounted volumes and free space on their I/O workers. Separate bounded I/O dispatchers
keep uploads, checksum verification and filesystem sync off Netty call threads and away from capacity and heartbeat work.
Source publication remains serial to protect Git mirrors, but admission polls it in the background. Preparation plan
publication, accepted result copies and diagnostic log writes also run on bounded workers, so one slow disk operation
does not stop other prepared experiments from being admitted or worker sessions from being reconciled.
Deletion waits until every session's durable stop has been acknowledged. An explicit `lab retry` preserves previous
loss events; infrastructure recovery requires no manual retry and has no experiment retry allowance. The coordinator's
configured namespace scopes both job submission and signed allocation identity verification. Admission enforces stored host placement
and the existing local-only exceptions, including confirmations, reference runs and local schedules.

Profiles with `runTimeoutSec` use the experiment's durable accumulated active time. Lease responses carry the
remaining whole-run allowance; workers track it monotonically across preparation, builds, execution and transfers.
Supervision reserves shutdown and cleanup time inside that allowance. Expiry permanently fails and fences the run
as a workload timeout. Accepted timeout captures still ingest after failure. Ordinary case
timeouts retain the existing 30-second shutdown grace for JVM/JFR output.

Uploads resume from the confirmed byte offset and publish only after size/checksum verification and filesystem sync.
Small inline results (at most 1 MiB and 128 files per request) first sync one packed batch into
`LAB_DATA/nomad/result-journal` on the coordinator's internal SSD. Acceptance still validates ownership, checksums
and the durable database commit. Reads and result projection can use pending batches immediately. Two background
workers copy them to the external artifact store; a batch is reclaimed only after every external blob is durable.
The journal reserves rounded file blocks and metadata within a fixed 128 MiB budget, also enforces the internal
free-space guard, and returns retryable backpressure when full. It never evicts pending accepted data. Captures up to
64 MiB and 128 files use `/commit-packed`: one length-prefixed JSON manifest followed by raw checksum-addressed blobs.
The receiver reserves its complete worst-case disk charge before streaming, hashes each blob, and syncs the completed
pack before acceptance. Slow senders have separate I/O slots and never hold the small-result journal lock. Retries reuse
pending blobs; older coordinators return 404/405 and workers fall back to resumable uploads. Preparation bundles, larger
captures and requests with more files continue directly to the external store. Keep `LAB_DATA` on the internal SSD; external artifact and job paths
can remain symlinks. Startup verifies and replays pending batches under an exclusive ownership lock. Before rolling
back to a coordinator without support for its pending pack sizes, stop acceptance and let all journal `.zip` files drain; preserve the
journal on a failed rollout rather than hiding acknowledged results behind an older binary.
`lab journal`, `/health.resultJournal` and the Nodes page expose reserved bytes, the 128 MiB limit, pending batches,
oldest batch age and drain failures. Reports are written atomically every five seconds and marked stale after fifteen.
The authenticated private `/capacity/journal` endpoint and `/capacity/metrics` read in-memory journal state without
touching the external volume. Reserved bytes include incoming streams and file/metadata overhead, even before a pack
is published. Error counters cover the current coordinator process; a retained failed batch keeps its error visible.

Workers retry connection failures and transient 429/500/502/503/504 responses for up to sixty seconds, bounded by their
remaining monotonic claim lease and whole-run allowance. Fencing, cancellation and run expiry stop immediately. This
allows a short coordinator restart without discarding completed work. Private workspace sealing hashes each file once
and records its inode/ctime receipt in the same pass. Warm reuse prunes generated files during that verification walk;
changed sealed files still force a fresh private copy. Repository manifests own build outputs; workers retain the complete approved inventory for every repository. `worker staging timing` separates inputs, workspace, setup,
sealing and the executing-phase notification so cold compilation is distinguishable from filesystem overhead.
Input downloads use bounded ranges and resume without rerunning captured cases. Accepted artifacts are projected into
existing job paths before completion and source checks are recorded durably. A coordinator restart replays ingestion
and pending checks rather than resetting claims. Worker or allocation loss fences the old credentials and reruns only
unfinished iterations on another compatible node. Repeated infrastructure losses never exhaust an experiment or
discard accepted preparation/results. Recovery uses fresh sessions under the same concurrency, CPU/memory and capacity
limits, and waits when matching Spot capacity is unavailable. Loss events remain diagnostic history, not a job retry
allowance. Nomad task restarts remain disabled and its reschedule/disconnect policy is explicit.
Ordinary solver exits/timeouts remain case outcomes. Linux workers refuse missing or mismatched runtime hard limits;
native workers report advisory enforcement. Resource counters are retained under each job's `nomad/` files.

The queue distinguishes preparation, waiting for compatible capacity, execution and final result checks without
rewriting the experiment's durable state. Registered clients that are down or ineligible are unavailable, and draining
clients cannot take new placements. These instances still consume the physical vCPU quota until the provider removes
them; replacements may grow only within that quota. Provider launch failures remain visible while Spot requests retry.
Unused startup requests are withdrawn when their work disappears, unless an unexpired manual capacity request keeps
them. Already ready, empty nodes retain the configured idle grace period.

`python3 deploy/nomad/connected_probe.py` builds the worker, uses a temporary loopback OCI registry to obtain a real
image digest, and runs the end-to-end acceptance test against a separate TLS/ACL Nomad sandbox. It exercises a paired
iteration, an API/coordinator restart, forced allocation loss, generation replacement, checksum ingestion and completion.
It stops its own agent and registry and retains private diagnostic state under `build/nomad-connected`. Never upload
that directory: it contains the sandbox CA, client keys, ACL token, allocation identities and session credentials.
Only test reports and non-secret verification summaries are CI artifacts.

## Managed native services and updates

`native_services.py` manages four separate launchd daemons: the persistent Nomad server, native client, independent
process supervisor and lab coordinator. Copy the server/client/coordinator/native-services examples to private local
configuration, replacing every example path and `203.0.113.10` with the home address. The server listens for RPC on
all interfaces and advertises the home address so cloud clients reach it through the router; everything else stays on
loopback. Mutual TLS and ACLs guard the RPC port. The native daemon runs as the lab user.
Server and client state directories are distinct and persistent; both require TLS and ACLs. Keep all state and keys
private and back up the Nomad server snapshot alongside the lab database. Bootstrap its ACLs and issue the restricted
coordinator token separately; never give the management token to a worker.

On the Mac, `python3 deploy/nomad/native_services.py --init-runtime "$LAB_DATA/nomad"` installs
the checksum-pinned native binary and initializes CA/server/client/CLI certificates. Set up the coordinator's PKCS12
stores from those CLI credentials and CA. Render without
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

The supervisor and maintenance runner share the same POSIX record lock (`LAB_NATIVE_EXECUTION_LOCK`, default
`$LAB_DATA/nomad/native.lock`). A native session reserves it across iteration boundaries and transfers. Several
experiments can share native execution within the coordinator's atomic aggregate CPU and memory budgets. The maintenance
runner still waits for the supervisor's collective reservation to end. The ownership marker survives supervisor death, so the maintenance runner cannot take the host before recovery has
cleaned recorded processes. Closing an experiment's last reservation cleans up only its processes. The collective lock is released when all experiments finish.
A missing or damaged supervisor holds native execution rather than granting unchecked ownership.

Native sessions drain for a pause or an update; priority orders admission without making an otherwise idle host exclusive. The runner takes the lock
before updating or executing local work; remote Linux allocations continue. Ordinary updates restart only the
API/runner/coordinator. They leave the Nomad agents and supervisor running. Changing those services requires the
explicit `--restart-agents` maintenance option after draining native work. In-flight workers must use immutable
release paths. Release pruning is disabled after native Nomad service installation; remove old worker releases only
after reconciliation proves no allocation or process uses them. Keep server snapshots and manifests needed for rollback.

Managed service/ownership tests complement live solver acceptance. Experiment execution now uses Nomad exclusively;
the maintenance runner retains capture analysis, reference checking, schedules and confirmations.

## Provider-neutral capacity and operations

`capacity.example.json` defines homogeneous capability pools, purchasing market, explicit host memory reserves, finite
node/vCPU limits, sustained backlog, cooldown and idle retirement. Cloud profiles use Spot unless their tags explicitly request
on-demand; measurements/builds do not reserve a whole node.
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
Nomad's MHz conversion already incorporates its host reserve. Retired `localCores`, `localPressureHours` and
`reserveCpuMillis` fields are ignored when an older settings file still has them.
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

Build staging directories hold a process lock from creation through publication. Worker startup and build-cache
maintenance reclaim abandoned staging five minutes after first observing its owner lock released, protecting live builds even across JVMs.
Unpinned staging left by older releases gets a 24-hour grace period. Cleanup first renames abandoned directories
under the maintenance lock, then deletes them outside that lock; interrupted deletions resume on the next pass.

Preparation workers report their current phase and exclusive accumulated timings once per second. The job and
execution pages show download, verification, checkout, compiler wait, compilation, workspace creation, selection,
classification, archive, local-cache publication and upload progress. Reporting runs in a background thread;
stale reports are marked rather than presented as fresh progress. Reports are fenced to the active preparation
attempt and survive coordinator restart; timings are also uploaded as the accepted `preparation/timings.json` artifact. Complete metadata uploads up to
64 KiB use the existing capped internal journal, so their acknowledgement does not wait for an external-volume
flush. Partial and larger transfers retain their resumable upload path; background drain resumes any existing
external prefix after interruption.

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

Preparation also opts into the consumer's shared corpus cache while retaining an isolated corpus output for
portable artifacts. The consumer owns fetching, locking and publishing complete collections. Native execution
keeps the user's normal cache home; Linux workers use `XDG_CACHE_HOME` under `/lab-cache/consumer` unless explicitly
configured. This reuses collections between allocations on a node; Spot termination still discards that node's
local cache. Older pinned consumers that do not recognize the shared-cache property keep their previous behavior.

For direct regional input reuse, configure `LAB_SHARED_INPUT_ROOT` and `LAB_SHARED_INPUT_URL` on preparation,
solve workers and the coordinator. The root is a persistent local input cache; the HTTPS URL is the regional
object store. Preparation publishes only selected inputs that are missing remotely and returns paths and SHA-256
checksums. Existing `corpus/` objects, including zstd objects, are reused; new bytes use immutable `input-blobs/`
content hashes. Corpus data is absent from worker uploads and coordinator artifact projection. A cold solve node
reads only its required files directly from the store, verifies them and reuses the local bytes. Immutable views
use hard links, retaining independent versions without per-job file copies. Native checking reuses matching local
files before considering a remote read. A genuinely missing native input requires one remote read.

Derived local blobs and views under `.lab-inputs` have an 8 GiB soft limit, configurable with
`LAB_SHARED_INPUT_MAX_BYTES`, and a seven-day idle expiry. Maintenance runs at most once per minute on cache use,
counts hard-linked bytes once, and evicts idle views before unreferenced blobs. Each concurrent solve and source
check holds a separate filesystem lock pin for its full input use. Process death releases its pin automatically;
active inputs can temporarily exceed the quota. Old interrupted transfers are removed only when their blob lock
is free. Consumer-owned collections, remote input objects and historical job artifacts are outside this cleanup.
Evicted views can be reconstructed from the job's saved input inventory.

Consumer adapters own their corpus formats, collection downloads and cache configuration.
The lab supplies `LAB_WORKTREE`, `LAB_CORPUS`, `LAB_REPOSITORY` and the output directory.
Optional generic corpus transport settings are `LAB_CORPUS_MIRROR`, `LAB_CORPUS_MIRROR_WRITE`,
`LAB_CORPUS_KEEP_PACKED` and the preparation-only `LAB_INPUT_SIGNER`;
repository manifests can declare runtime tools, tags and consumer-specific environment settings.
The optional loopback `LAB_SHARED_INPUT_SIGNER` supplies short-lived HTTP capabilities to native workers;
no provider credentials enter manifests or consumer processes.
The portable input transport only uses HTTPS, metadata probes and conditional PUT. See the provider setup in
`deploy/infra/README.md`. Saved recipes and accepted preparations retain their original inputs and worker release.

A failed source build reports its bounded diagnostic tail through its authenticated attempt. The coordinator
fences all sibling sessions and records a permanent `source-build` failure, rather than repeatedly treating a
compiler error as Spot loss. A stale generation cannot fail replacement work. Failure acknowledgements are
idempotent. Transport loss and actual allocation revocation continue to replace whole unfinished iterations.
Preparation/build commands can explicitly report temporary dependency failures with exit `75` (`EX_TEMPFAIL`).
These exit through infrastructure recovery without calling the permanent build-failure endpoint or spending a
code attempt. Solver exit codes remain solver outcomes. Consumers must reserve this signal for infrastructure
failures; ordinary compiler errors and permanent invalid selections remain code failures.

Saved recipes are rechecked before source publication or allocation, and invalid saved schedules show a
`blockedReason` in `/schedules` and the queue page until edited. A malformed preparation selection fails once
with its diagnostic instead of returning a retryable service error. Unexpected coordinator selection errors
are fingerprinted by worker release, request and failure site; three matching errors fence the job, including
across coordinator restart and replacement workers. IO failures, admission holds and cancellation do not count.

Updated workers use process exit `70` (`EX_SOFTWARE`) for unhandled software failures and `75` for transport,
explicit temporary dependency failure or cancellation; a fenced worker exits successfully. After three positively
observed unsignalled software exits at the same preparation stage and worker release, without accepted preparation,
the job fails with the exit, stage and release recorded. Nomad kill/eviction, lost nodes, lease expiry, signals,
OOM, missing task evidence and legacy ambiguous exit `1` remain infrastructure recovery. Manual job retry resets
both counters and retains accepted results. Worker images/native launchers must be updated for generic crash
protection; recipe and selection protection also applies to older workers through the coordinator alone.

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
in the API, runner and coordinator environments. Nomad is the only experiment executor. Analysis/recheck jobs
run through the local maintenance runner; ordinary submissions, scheduled experiments and confirmations use Nomad.

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
solve heap must fit rather than being silently changed. Reservations are bounded by the job's vCPUs, the job's
per-host parallel cap, and each target's CPU/memory ceiling; a session claims whole paired rows to fill its case slots.
The existing global provider vCPU limit bounds capacity growth, independent of provider and Spot pool count.

Queue settings are snapshotted per admitted experiment, and source SHAs, inputs, release and templates remain pinned
across coordinator restarts and configuration changes. Lost workers are replenished while others run, within the
infrastructure retry limit. Pending solve sessions do not request capacity once all work is claimed. Source checks run
on a bounded background queue so slow checker installation/execution does not stall capacity health or reconciliation.

Runtime pruning is documented in [setup](../SETUP.md#retiring-old-installations). Keep active worker releases
immutable and retain frozen retry recipes. Reconciliation delivers cancellation and stop intents before allocation
state is considered disposable; historical job results and provenance are never runtime-pruning candidates.

Job `vcpus` (formerly `parallel`) determines its total solve CPU request across the cluster. Each worker session
reserves the job's vCPUs, or the most its target offers, and runs `max(1, vcpus - 1)` cases at once divided by the
largest case's CPUs, claiming whole paired rows to fill them; a target template's `parallel` no longer limits that.
The native worker runs at most `LAB_MAX_PARALLEL` cases a session. Preparation reserves the job's vCPUs too, at least
`preparationBudget`, with memory scaled from it, within the target's budget and the local cores. Sessions from the
same experiment share a node whenever those resources fit. Operator queue settings
`maxVcpusPerJob` (default 16) and `maxSessionsPerJob` bound job expansion;
`perHostParallel` optionally caps same-job cases on each node and is unset by
default. These live limits apply independently of frozen experiment sources and
prepared inputs. A worker larger than the job ceiling is admitted once so existing
large requests remain runnable. Existing workers affected by a sharing-policy
change finish their current iteration before retiring.

Cold compilation uses the worker's CPU/memory reservation, Gradle worker and JVM
processor limits, and at most `maxConcurrentBuildsPerNode` compilers per node
(default 2). Shared cache locks deduplicate builds and warm hits bypass compiler
slots. Preparation placement reserves the same slot count across experiments, excluding full nodes until
a preparation ends. One unbound preparation per cloud class prevents concurrent placement from overbooking
those slots. Capacity packing respects that limit within the existing pool and global vCPU ceilings.
Waiting jobs preserve spent costs and do not start their active clock before preparation is claimed.
Process-owned tickets order cold compilers; a cancelled or dead waiter cannot retain its place indefinitely.
Oversized literal commands remain intact in `cases/<index>/command.sh`
artifacts without overflowing result metadata.

Warm build and bundle caches walk their inventory and reuse checksums only while file identity, size,
modification/change timestamps and executable state match a local verification receipt. Changed files are
hashed again; missing receipts are rebuilt by checksum verification. Filesystems without reliable fine-grained
change timestamps use full verification. Each solve resolves only its case's shared model, data and include
directories, rather than rechecking the experiment's entire shared-input inventory.

`workerReplaces` explicitly approves older worker release IDs for a compatible
infrastructure patch. New solve admissions and infrastructure replacements then
use the current pinned worker image/launcher and record its new release ID. The
consumer experiment, resolved commits, arguments, budgets, corpus bundles and
accepted results remain unchanged; original manifests and submissions remain
immutable. Releases not listed retain their existing worker runtime.

### Warm worker I/O

Preparation runs only the requested runtime build tasks. The worker init script excludes every Gradle
`AbstractTestTask` before dependency graph resolution, including custom and subproject test tasks, so their
test compilation and fixture dependencies are omitted too. JVM CLI preparations also disable native release packaging.
Build and Gradle dependency cache identities use the actual toolchain, recipe, platform and JVM rather than the worker
release. Historical `jdk25:<release SHA>` bundles normalize to `jdk25`; explicit custom toolchains remain distinct.
This switches historical cache keys once; later worker patches preserve the new warm entries.

Workers reuse verified input distributions across allocations under `LAB_WORKER_CACHE_ROOT/inputs`. The node-local
cache keeps an 8 GiB soft limit and seven-day expiry; process-owned pins protect readers during staging and compilation.
Cold preparation seeds its published distribution from the verified private source tree, using a filesystem clone when
available, instead of decompressing and rewriting the ZIP it just created. The source inventory is checked against the
published bundle before promotion; readers still receive private copies and the existing cache size limit applies.
Prepared ZIPs use buffered output and fast compression for source text, while already compressed JARs, Git packs and
other compressed formats use uncompressed DEFLATE framing to avoid repeated compression. The archive-v2 identity
makes this a one-time ZIP cache miss without invalidating the build cache. Discarded compiler directories are pruned
before traversal. Cold publication logs inventory time, ZIP writing time, input bytes and compressed bytes separately;
cache observations include ZIP verification and lock wait time, so slow cold reads can be separated from reuse.
A separate node cache under `LAB_WORKER_CACHE_ROOT/archives` reuses the complete ZIP when verified file checksums,
executable state and directory layout match. Its 2 GiB compressed-size soft limit and seven-day expiry protect disk;
active publishers hold process pins, and crashed staging follows the same five-minute orphan grace as build staging.
Metadata receipts avoid rehashing unchanged private files, while changed inputs, executable bits, empty directories or
corrupt ZIPs invalidate reuse. Custom recipes retain their complete inventory. Uploads opt into cross-job reuse: the coordinator checks an
existing archive's SHA-256, then durably links the immutable artifact into the requesting job without transferring it again.
A disposable index has a 4096-hint soft limit with seven-day expiry (pruned on publication, at most once per minute);
it retains no archive data itself. Missing, expired, deleted or corrupt sources fall back to resumable uploads. Old
coordinators ignore this optional request flag and retain the original upload protocol.
Each worker session receives a private copy, using filesystem clones where supported, and cannot modify the cached distribution.
Within one serial worker session, unchanged private workspaces are reused between iterations. Each reuse verifies a sealed
inventory including installed measurement launchers and provenance captured for that stable private path. Generated outputs
are removed; changed or missing sealed files discard the workspace and repeat trusted setup. No mutable workspace is shared between sessions. This avoids
copying thousands of files and starting provenance JVMs for every case. Session workspaces live under
`LAB_WORKER_CACHE_ROOT/workspaces` on the cache volume, enabling private filesystem clones without cross-volume byte copies. On APFS, an atomic directory clone avoids
a separate copy transaction for every source file; unsupported filesystems retain the portable copy path.
Normal worker exit removes its workspace; process-owned locks protect active sessions while background cleanup reaps
abandoned sessions after crashes. Cached distributions remain immutable and bounded separately.

Cold input downloads stream bounded HTTP ranges directly into extraction. The archive and each declared file are hashed
as bytes pass through; publication checks the complete archive (including its trailer), file sizes and metadata before
atomic promotion. No temporary compressed archive or duplicate file checksum pass is needed. Before downloading,
a default runtime cache miss searches the node's pinned build cache for an identical approved file inventory. A verified matching
build can be cloned directly, avoiding archive download and extraction entirely; changed or unmatched builds fall back
to the verified stream. The cache limit still accounts for every materialized distribution. Trusted private copies bind
verified source inode/ctime before and after copying and record the new private inode metadata. Unchanged copied files
need no checksum reread; trusted setup changes are hashed during sealing. Filesystems without reliable identity/ctime
retain full checksum verification. Runtime bundles retain the complete approved inventory. Cache layouts have separate identities,
and existing full cache hits remain reusable.

Solve results totaling at most 1 MiB across 128 files use `/commit-inline`: one authenticated request publishes verified,
filesystem-synced blobs and accepts the iteration durably. Empty and duplicate blobs are supported. Larger captures retain
resumable uploads; successful chunk acknowledgements supply the next offset, while ambiguous responses re-query progress.
Workers fall back to the original protocol when a coordinator does not support inline acceptance. Storage is freshly
validated before each bounded batch, and accepted-manifest retries retain the same fencing and idempotency rules.
Small commits use a separate bounded I/O dispatcher so bulk input/output streams cannot occupy all their execution slots.

Signing keys are cached for at most thirty seconds; token signatures, expiry and allocation ownership are checked on every
request. Unknown key ids trigger a bounded refresh for rotation, and expired key sets cannot authenticate during a failed
refresh. Background completion wakes admission and reconciliation immediately instead of waiting for the polling interval.
Worker output records `worker timing:` lines with staging, execution, output and byte counts for measuring warm overhead.

A native target may set `LAB_WORKER_API` in its trusted template environment to the coordinator's loopback URL. The same
setting follows explicitly approved worker-runtime replacements. An optional coordinator `privateBindAddress` adds a
second listener while keeping loopback control access; it requires `privateNetwork: true` and an authenticated private
interface. This allows a private network proxy to reach workers without multiplexing bulk traffic through an SSH tunnel.
