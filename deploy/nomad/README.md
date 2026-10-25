# Nomad execution prototype

This is the first implementation slice of [the migration plan](../NOMAD-MIGRATION.md). It renders real Nomad batch
sessions and checks Linux CPU/memory enforcement and sharing in a separate single-node cluster. It does not start
the lab API/runner, open `lab.db`, change lab placement, or provision cloud infrastructure.

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

Step 0 remains open until real native solver/JFR runs, the host-local process supervisor, abrupt worker/client-death
cleanup, and session replacement/reconciliation have been verified. A successful synthetic probe does not satisfy
those gates. Durable attempts and the production worker/backend integration follow this prototype; legacy lab
execution remains the only active backend.

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

Session admission is currently an internal/operator operation, not a normal experiment submission selector.
`NomadBackend.enqueue` validates the manifest against the stored experiment, arm commits and case plan, then
persists the session, credential hash, manifest and Nomad submission intent together. Native/cloud preparation,
normal queue admission and the canary operator workflow follow in the stack. Do not manually edit a running job's
backend column. Existing production submissions still select legacy execution.

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

The supervisor and legacy runner share the same POSIX record lock (`LAB_NATIVE_EXECUTION_LOCK`, default
`$LAB_DATA/nomad/native.lock`). A native session reserves it across iteration boundaries and transfers. Several
sessions of the same experiment can share the supervisor's reservation; another experiment and the legacy runner
wait. The ownership marker survives supervisor death, so the legacy runner cannot take the host before recovery has
cleaned recorded processes. Closing the last reservation stops any still-owned process before releasing the lock.
A missing or damaged supervisor holds native execution rather than granting unchecked ownership.

Native sessions drain for a pause, a higher-priority compatible local job, or an update. The runner takes the lock
before updating or executing local work; remote Linux allocations continue. Ordinary updates restart only the
API/runner/coordinator. They leave the Nomad agents and supervisor running. Changing those services requires the
explicit `--restart-agents` maintenance option after draining native work. In-flight workers must use immutable
release paths. Release pruning is disabled after native Nomad service installation; remove old worker releases only
after reconciliation proves no allocation or process uses them. Keep server snapshots and manifests needed for rollback.

The managed service/ownership tests do not establish real solver, reference, confirmation, whole-run profile and
capture-analysis canary acceptance. Queue/source-preparation admission remains the next rollout boundary; these
gates must pass before changing the production backend default or removing legacy execution.

## Provider-neutral capacity and operations

`capacity.example.json` defines homogeneous capability pools, purchasing market, explicit host reserves, finite
node/vCPU limits, sustained pressure, cooldown and idle retirement. Cloud profiles preserve the existing explicit
on-demand purchasing policy unless their tags request Spot; measurements/builds do not reserve a whole node.
Every session budget includes its helpers. The policy packs free CPU and memory on ready nodes, accounts for booting
nodes, preserves per-experiment/per-host parallel caps, and reuses warm capacity for lower-priority eligible work.
Only old empty nodes contribute to voluntary scale-in. Terminating machines still consume quota until gone, without
restoring an obsolete provider target count. CPU budgets are logical vCPUs, separately from Nomad's MHz conversion.

Set the coordinator's optional `capacity` object to `{"settingsFile":"/private/capacity.json",
"inventoryFiles":["/private/aws-inventory.json"],"healthTokenFile":"/private/capacity.token"}`.
Provider adapters write versioned `CapacityInventory` JSON atomically: observation time, pool IDs, instance IDs,
draining/target-count state and classified capacity failures. They contain no control credentials. The collector
joins this with live Nomad allocations; an unknown allocation conservatively consumes a whole node. It reuses
`Estimates` and `NodePool` pressure calculations and persists cooldown/idle history across restarts.
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
