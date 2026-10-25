# Nomad migration plan

Proposed on 2026-10-25. This is an implementation plan; no runtime or infrastructure changes have been made.

## Outcome and decisions

Run experiment execution through Nomad while retaining native macOS solves, portable cloud workers, and recovery
from Spot interruptions. Keep the lab's API, UI, experiment model, SQLite database, source trust policy, validation,
comparisons, and historical results.

Use one persistent Nomad server on the Mac initially, a native Mac client, and disposable Linux cloud clients.
The Mac remains an availability dependency, as it is today. Run the server and client as managed services, with
persistent state, TLS, ACLs, and an explicit single-server configuration; never use development mode for the lab.
Back up the Nomad server's state alongside the lab database. Additional servers, if later needed, belong together
on a low-latency network, rather than splitting a quorum between the Mac and clouds.

Use native process execution on both platforms initially: `raw_exec` on macOS and an isolated Linux process driver
where the packaged workload permits it. Do not require a container registry or a VM for native Mac solves.
Existing reference solvers may continue to use their own Docker runtime. Verify driver compatibility in step 0;
changing Linux driver or packaging must be recorded as an execution-environment change.

Nomad owns process placement and allocation lifecycle. The lab retains admission by experiment priority, placement
exceptions, pairing, and between-iteration pause/yield decisions. This preserves the lab's benchmark semantics
without maintaining SSH sessions, remote process polling, or instance adoption as execution mechanisms.

Cloud portability means changing infrastructure modules, capacity targets, identity configuration, and artifact
storage configuration when changing providers. It does not mean that provider provisioning or Spot termination
notifications have a universal API. No provider SDK, CLI command, bucket name, or instance type belongs in an
experiment definition or the shared execution protocol.

## Target architecture

```mermaid
flowchart TB
    U[Existing CLI and UI] --> L[Mac: lab API, coordinator and SQLite]
    L --> N[Mac: persistent Nomad server]
    N --> M[Native macOS client]
    N --> C[Disposable Linux clients]
    M --> W[Versioned execution protocol]
    C --> W
    W --> L
    W --> A[Artifact service and verified caches]
    L --> P[Portable capacity demand metrics]
    P --> S[Nomad Autoscaler and metrics collector]
    S --> T[Provider target: AWS initially]
    T --> C
    H[Independent provider capacity expiry] --> T
```

Worker HTTP traffic and Nomad RPC use an authenticated private network. Cloud clients need to reach the Nomad
server, worker API, and artifact endpoints; solver jobs do not require connectivity to each other. The existing
public HTTP relay is not automatically a transport for Nomad RPC. Provide a separate private route/VPN with
documented bootstrap, DNS, and credential rotation. A single Nomad server and the coordinator can be unavailable
together; recovery and cost controls must handle that explicitly.

Do not add Consul, Vault, a service mesh, or shared network filesystems as prerequisites for this migration.
Use native Nomad functionality and narrowly scoped bootstrap credentials; integrations can be added for a
demonstrated need later. Pin Nomad, Autoscaler, toolchain, and worker releases during step 0.

## Execution model

An experiment remains the user-visible job. Its plan contains stable iteration IDs: one problem, seed, and repeat,
with every configuration arm that must run on the same host. A profile entry point is an indivisible work item;
analysis and reference work keep their current local placement exceptions.

Create a real Nomad `batch` job for each bounded execution session of an experiment. A session runs on one node,
prepares its inputs once, pulls complete iterations belonging only to that experiment, and exits when its work
is exhausted, the lab requests a yield, or its node drains. Several sessions can participate in one experiment.
The session cannot choose another experiment, provision machines, or mutate the database directly.

This granularity avoids a Nomad submission and full toolchain startup for every short solve. It also preserves
the existing one-active-experiment-per-host model and the existing per-host case parallel limit. Nomad sessions
reserve the configured host benchmark budget, leaving capacity for the OS and supervision. Fixed capacity pools
initially make the Linux reservation predictable. On the Mac, reserve the lab budget and use a local execution
lock as an additional guard against a concurrent legacy runner. Validate that a second measured session cannot
start on the same host. Do not assume Nomad CPU MHz, vCPUs, physical cores, or Mac performance cores are equivalent.

The worker preserves intra-iteration order/concurrency, JVM flags, timeouts, and measurement wrappers. If an
iteration is interrupted, discard its partial comparison and rerun all its arms on the replacement host. Completed
iterations survive loss of the session. An ordinary solver timeout or solver error is a recorded domain outcome;
it is not automatically a failure of the Nomad task that triggers infrastructure retries.

Use deterministic job IDs such as `lab-<experiment>-<execution-class>-<session>-<generation>` and the job registration
API. Persist submission intent before calling Nomad. A reconciler can discover whether an ambiguous registration
succeeded and retry the same identity. Start with ordinary generated job specifications; parameterized dispatch
is unnecessary and introduces distinct dispatch identities and payload limits.

## Durable state and worker contract

SQLite stays on the Mac. Workers receive neither database mounts nor access to its files. Add additive tables for:

- `execution_sessions`: experiment, backend, execution class, generation, Nomad job/allocation/node identities,
  expected worker release, desired state, and latest observation.
- `iteration_attempts`: stable iteration ID, monotonically increasing generation, session and allocation owner,
  claim token, lease expiry, state, start/end times, and accepted result manifest.
- `execution_events`: termination reason, retry classification, phase changes, and reconciliation observations.
- `execution_outbox`: durable Nomad submit/stop intents with stable identities and acknowledged outcomes.

An active attempt moves through `claimed -> preparing -> executing -> uploading -> committed`; invalidated attempts
end as `lost`, `cancelled`, or `superseded`. Existing case states remain the public projection. Case outputs from a
running attempt may be visible provisionally, but comparisons use accepted iteration results only.

Add a versioned worker API for session registration, claiming an iteration, renewing its lease, publishing case
progress/log chunks, requesting artifact transfers, and committing a result manifest. Every mutation checks the
session, allocation, iteration generation, and scoped token. Tokens are bound to one session and cannot submit
experiments, launch capacity, access other jobs, or read provider control credentials. Replacements obtain a new
allocation binding; a token copied from a superseded allocation cannot revive it.

Fence replacements in a transaction: invalidate the old generation before granting another. Duplicate delivery is
allowed; at most one attempt is accepted for an iteration. An old worker may still consume CPU during a partition,
but it cannot overwrite the accepted result. A worker that cannot renew its lease stops starting cases and stops
its process tree before its locally tracked lease expires. Use monotonic local timing and an expiry margin.

After all arms and artifacts are uploaded, the coordinator verifies checksums and atomically accepts the manifest
and case projection. Retrying a lost acknowledgement returns the accepted outcome. Cancellation increments/fences
the generation before stopping allocations, so a racing upload cannot turn a cancelled experiment into success.
Run source-witness checks centrally and derive experiment completion from durable attempts and the required check
state, rather than from `Crews` membership or Nomad job success alone.

Initial timings to validate: renew worker leases every 10 seconds, a 90-second lease, and a 10-second reconciliation
loop. These are starting values, not a promise that a cloud replacement boots within 90 seconds. Block renewal and
new claims when required local volumes are unavailable. Bound all lease and transfer operations by timeouts.

## Native Mac execution and process lifetime

Extract the existing launch, measurement, and termination logic into a reusable worker executor. Preserve BSD
`time -l`, JFR/compilation capture, process-group termination, and graceful timeout handling. Keep native JDK,
architecture, SIMD, memory, and CPU provenance. Do not claim hard CPU or memory isolation on macOS.

Nomad's `raw_exec` documentation specifically notes the risk of leaking processes that create new process groups.
This matters because the current runner deliberately creates process sessions. Implement a host-local supervisor
that records process identity/start time, forwards signals to the complete owned group, and cleans up after abrupt
worker or client death. Do not kill by an unchecked reused PID. The supervisor must remain outside the worker's
failure boundary. On Linux, verify cgroup cleanup as well as application signal forwarding. See the
[raw execution driver documentation](https://developer.hashicorp.com/nomad/docs/deploy/task-driver/raw_exec).

Keep the current one-local-experiment policy, `LAB_CORES`, `LAB_DEFAULT_PARALLEL`, `LAB_MAX_PARALLEL`, and reserves
for surrounding JVMs. Before a local priority yield or update, stop claiming and finish started iterations. A pause
has the same boundary; a cancel retains the current immediate-stop behavior. Nomad priority alone must not kill
running local comparisons. Reference jobs, confirmations, whole-run profiles, and analysis stay local wherever
`Placement.localOnlyReason` currently requires it.

## Builds, inputs, and artifacts

Keep repository allowlisting, trusted-ref checks, immutable commit resolution, and provenance in the coordinator.
Generate a portable work manifest identifying source, commits, cases, toolchain, resource budget, and artifact hashes.
Do not send a shell command string with embedded host paths as the cross-host contract.

Workers stage verified source/toolchain/build artifacts into their own cache and render paths locally. Reuse node
caches across sessions; stage and validate extraction before making a cache entry visible. Deduplicate builds with
locks. Preserve the current cache corruption checks and retry transfers without rerunning an already captured solve.
Key caches by repository, full SHA, OS, architecture, toolchain/runtime identity, and build options. Source-only or
portable JVM artifacts may be shared only when their manifest explicitly establishes that portability. JFR/profile
launcher changes remain per attempt and cannot contaminate reusable baseline builds.

Begin with the lab's HTTP artifact service and local filesystem implementation, with ranged/resumable transfers,
per-job authorization, checksums, disk guards, and a configurable external base URL. This runs without AWS storage.
Add an optional object-storage adapter for large captures and remote caching; provider-specific signing stays in
that adapter. S3 compatibility is one implementation, not the universal storage contract. Preserve current retained
file paths and `lab fetch`/Files behavior through an ingestion projection.

Schedule preparation outside measured intervals. On a node, do not compile another session's commits while a solve
is measured. Bound build concurrency, cache disk usage, upload size, and retries. Report preparation, execution,
uploading, and transfer-backoff as separate phases.

## Cloud capacity, Spot, and portability

Extract the pure pressure, admission, quota, and retirement policy from `Nodes.kt`, `Estimates.kt`, and `AwsWorker`
into a provider-independent policy. Preserve local-first behavior, launch priority floor, sustained pressure,
cooldown, compatible hardware selection, and existing warm workers taking eligible lower-priority work.

Do not let every pending allocation start a cloud machine immediately. Export desired worker counts by capability
pool, using the current estimates and policy. A small metrics collector on the Mac makes this demand available to
Nomad Autoscaler. Feed a queue-aware/pass-through policy that can grow from zero; CPU utilization of existing nodes
cannot bootstrap a pool with no nodes. Account for booting workers and existing allocations to avoid repeated
launches for the same backlog. Expired coordinator metrics must not authorize growth.

Start with fixed, homogeneous pool shapes and separate architecture/market classes. Record physical cores, hardware
threads, SIMD capabilities, memory, and effective benchmark budget. Pools may contain equivalent instance types only
when their resource accounting is valid for every member. Job requirements use normalized capabilities, while
provider/model/instance identifiers remain provenance fields. Detect CPU capabilities with `node-capabilities.sh`
before enabling a client for scheduling; reject unknown or mismatched hardware as today.

Default the general cloud pool to Spot. Retain an explicitly configured on-demand pool for existing profile policy
and operator requests; unavailable Spot capacity must not silently enable it. A provider that cannot supply Spot
cannot satisfy a Spot-only requirement. Report capacity unavailable separately from an invalid job or solver failure.

Implement AWS first with a Spot Auto Scaling Group, minimum zero, finite maximum, a pinned bootstrap image, and
the Nomad Autoscaler AWS target. The image contains JDK/toolchain, worker, Nomad client, native dependencies, and
capability probing. Use an AWS-specific interruption adapter to disable new placement and tell the session to stop
claiming iterations. Allow a current iteration to finish only while time permits. Recovery must also pass when the
machine disappears without a warning. Configure task restart, allocation failure rescheduling, node disconnect, and
reconnect behavior explicitly, and test each path; they are distinct mechanisms in Nomad. Application generations
remain authoritative even if Nomad temporarily runs an original and replacement allocation.

Initially disable in-place task restarts (`attempts = 0`, `mode = "fail"`) so worker failures are visible as allocation
failures. Give infrastructure rescheduling a finite retry budget with backoff; node-loss replacement is tested
separately. A replacement always claims fresh attempt generations. The coordinator reports exhausted retries and
keeps capacity shortages distinguishable from a permanent source/build failure. Do not use Nomad retry defaults
as the experiment's solver retry policy.

Scale in only empty workers after the configured idle period; use `empty` or `empty_ignore_system` as appropriate.
Do not put a measured batch session on a node selected for voluntary termination. Spot revocation remains an
independent involuntary termination and must always be recoverable. Autoscaler target adapters exist for
[AWS, GCP, and Azure](https://developer.hashicorp.com/nomad/tools/autoscaling/plugins/target), and its
[empty-node strategies](https://developer.hashicorp.com/nomad/tools/autoscaling/concepts/policy-eval/node-selector-strategy)
support this batch scale-in policy.

Cost expiry must be independent of the Mac. Give each provider pool a renewable capacity lease. For AWS, run a
small provider-side expiry function every five minutes. It checks a timestamp written by the healthy coordinator,
sets the group's minimum, desired, and maximum capacity to zero after 30 minutes without renewal, and removes the
lab's remaining expired workers. This also prevents a stale autoscaler action from reopening capacity. Require a
fresh reconciliation to restore the configured maximum and reopen the pool. Target detection within 35 minutes of
the last renewal, plus provider termination latency. Keep an emergency per-instance lifetime/watchdog as a second
bound. Define equivalent obligations in every provider module.

Resetting desired capacity is essential: an ASG
[replaces terminated instances to maintain its desired capacity](https://docs.aws.amazon.com/autoscaling/ec2/userguide/asg-capacity-limits.html).
Keeping only the current self-power-off watchdog would cause replacement workers to keep appearing. Test expiry
with both the Mac and its autoscaler offline, and verify that no replacement instances appear. Treat provider-side
expiry as required production infrastructure, not a best-effort worker shutdown hook.

Choose GCP Spot as the second reference provider for the portability gate. The same worker protocol, execution
manifests, and experiment specifications must run unchanged; only infrastructure, autoscaler target, bootstrap
identity, interruption adapter, and artifact endpoint configuration may differ. A plain non-AWS Linux VM proves
execution portability but does not by itself prove portable Spot provisioning and recovery.

## Implementation sequence

Each step is a reviewable change. Runtime switches remain off until that step's acceptance checks pass.

| Step | Deliverable and main code boundaries | Acceptance / exit condition |
| --- | --- | --- |
| 0 | Add `deploy/nomad/` sandbox configuration and pin versions. Prototype native Mac and Linux execution, private networking, cleanup, resource reservations, and session replacement. | Real Mac solve and JFR capture run without a VM. Worker/client SIGKILL leaves no solver JVM. Two measured sessions cannot overlap on one host. Allocation replacement and batch completion semantics are understood. |
| 1 | Add execution session/attempt/outbox tables and transactional transitions in `Store.kt`; add an `ExecutionBackend` interface and explicit per-experiment backend choice. | Existing execution remains the default. Ambiguous submission, repeated commit, generation replacement, and cancel/upload races have deterministic outcomes. Legacy and Nomad cannot own an experiment simultaneously. |
| 2 | Extract `WorkManifest`, `IterationExecutor`, `ProcessSupervisor`, and verified cache staging from `Runner.kt`, `ExecutionHost.kt`, `CliMeasurements.kt`, `Termination.kt`, and `AwsBuildCache.kt`. Add a `worker` entry point in `Main.kt`. | The standalone worker runs a complete paired iteration and profiles from a manifest with no database or provider access. Existing termination, measurements, provenance, and cache-corruption tests still apply. |
| 3 | Add `WorkerApi.kt`, `ArtifactStore.kt`, `NomadClient.kt`, and `NomadBackend.kt`. Split planning/admission/reconciliation into a coordinator; replace `Crews` as the completion authority for Nomad work. | A real local Nomad batch session reports cases through the normal UI/API. Restarting the coordinator reattaches to its allocation and does not wipe completed work or duplicate submissions. Cancel, pause, resume, retries, volume holds, and transfer retries pass. |
| 4 | Extend `deploy/install.sh`, `deploy/update.sh`, and `SelfUpdater.kt` for the Mac services and execution lock. Switch an explicit canary set to native Mac Nomad sessions. | Existing workload kinds pass, including ordinary runs, committed profiles, reference runs, confirmations, scheduled runs, and capture analysis. Local concurrency and priority/yield behavior match the current policy. |
| 5 | Add provider-independent capacity configuration/metrics and `deploy/infra/aws/` plus image/bootstrap and interruption handling. Introduce Nomad Autoscaler and independent capacity expiry. | A tagged cloud-only job starts a Spot worker from zero, completes, and scales down. A forced termination halfway through a comparison reruns the entire unfinished iteration. Capacity shortages and quota failures are visible. Expiry passes while the Mac is offline. |
| 6 | Add `deploy/infra/gcp/` as a second reference adapter and a provider-neutral Linux integration environment. | The same experiment and worker run on GCP Spot, recover from forced loss, and scale down without edits to shared Kotlin execution code. Both providers enforce the same purchasing and expiry rules. |
| 7 | Migrate cloud reporting and manual operations in `Pages.kt`, `Waiting.kt`, `Api.kt`, `ManualLaunch.kt`, and `deploy/lab`. Roll out Nomad by workload class and execution pool. | Placement, waiting reasons, host contributions, logs, captures, and `lab wait`/fetch/retry/cancel remain usable. Nomad/allocation identities are available for diagnosis. Operator capacity requests respect finite limits and expire. |
| 8 | Make Nomad the default, then remove legacy AWS execution after a stable observation period. Update `deploy/SETUP.md` and deployment examples. | Remove `AwsWorker`, `AwsHost`, SSH execution/transfer logic, pool adoption state, and provider shell commands from shared execution code. Retain domain pressure/placement policy, central checks, and necessary native process supervision. |

Proposed new Kotlin files live under `src/main/kotlin/com/eignex/lab/`; names above describe boundaries and are not
a requirement to introduce one class per file. Keep the existing source trusted/restricted behavior throughout.
Extend the existing `SharedJobsTest`, `CancelTest`, `PlacementTest`, `NodeTagsTest`, `CliMeasurementsTest`,
`TerminationTest`, `VolumesTest`, and `AwsBuildCacheTest` contracts rather than replacing them with mocks of Nomad.
Add focused backend/API tests plus real scheduler and OS integration runs for behavior that mocks cannot establish.

## Cutover and rollback

Use `legacy` and `nomad` as explicit execution backend values. Store the selected backend on an experiment before
its first session starts; an environment default affects only new/unstarted work. Keep the old runner available
during the rollout, but its claim path must refuse work owned by Nomad. Local process and database ownership guards
must cover both backends, including after a service restart.

Deploy additive database migrations first. Preserve old case paths and historical `lab`/`aws` placement aliases on
read; new internal placement and provider identity are separate fields. Keep CLI/API compatibility adapters while
moving operator requests to pool IDs and normalized capabilities. Existing explicitly AWS-shaped operator commands
may retain a documented alias during the transition; they cannot become the new shared protocol.

Canary native Mac ordinary jobs first, then profiles/reference/local exceptions, then AWS Spot cloud-only jobs,
then shared local/cloud experiments. Run semantic comparisons on fixed manifests and compare timing distributions
on the same hardware; exact elapsed-time equality is not the migration criterion. Record runner release, OS, driver,
toolchain, node and CPU identity so old and new execution environments remain distinguishable in results.

To roll back: stop new Nomad admissions, request sessions to finish started iterations, reconcile accepted results,
fence any remaining attempts, stop their Nomad jobs, and verify their process trees are gone. Only then release
unfinished iterations to legacy execution. Retain accepted completed iterations and their original provenance.
Do not simply flip an environment variable while old allocations are alive. Keep a database/state backup and
document which deployed application versions can read the additive schema.

Update workers using pinned releases and protocol compatibility, not an unversioned `current` path. An in-flight
worker finishes with its release; new sessions use the new release. Restarting the coordinator should not require
killing healthy remote allocations. During a longer outage, workers stop at lease expiry and uncommitted iterations
run again after reconciliation. A Mac server outage prevents scheduling; provider capacity expiry bounds billing.

## Required acceptance scenarios

1. Native Mac A/B, resources, JFR, compilation captures, whole-run profile timeout, and committed profile entry points.
2. One shared experiment on Mac and Linux, with all arms of every accepted iteration on the same host and attempt.
3. Loss after arm A, during arm B, during upload, and after result acceptance but before acknowledgement.
4. Worker, Nomad client, Nomad server, coordinator, and API restart independently; a lab update during remote work.
5. Worker partition followed by replacement and late reconnect; only the current generation can publish results.
6. Cancel racing with finish or upload, pause between iterations, priority yield, and resume after restart.
7. Host process-tree cleanup after worker/client SIGKILL, including subprocesses that create their own sessions.
8. Missing external drive, full cache, corrupt build/capture, slow transfer, and expired credentials, all with bounded retries.
9. Spot from zero, no Spot capacity, repeated revocations, optional on-demand isolation, finite quotas, and idle scale-in.
10. Mac offline past the capacity lease: provider desired capacity reaches zero and replacement billing does not continue.
11. AWS and GCP Spot execute the same manifest without provider changes to the worker or experiment spec.
12. Rollback with completed and in-flight work; no simultaneous legacy/Nomad ownership and no mixed-attempt comparisons.

Live Mac and cloud checks are explicit migration gates, not work claimed complete by writing this plan. Run targeted
local tests for each implementation change and use GitHub CI for the full repository gate. This planning-only
change requires no solver execution, infrastructure provisioning, or full Gradle check.

## Documentation checked for this plan

- [Nomad raw process driver and cleanup caveats](https://developer.hashicorp.com/nomad/docs/deploy/task-driver/raw_exec).
- [Nomad network and deployment requirements](https://developer.hashicorp.com/nomad/docs/deploy/production/requirements).
- [Task restarts](https://developer.hashicorp.com/nomad/docs/job-specification/restart),
  [allocation rescheduling](https://developer.hashicorp.com/nomad/docs/job-specification/reschedule), and
  [disconnect/reconnect policy](https://developer.hashicorp.com/nomad/docs/job-specification/disconnect).
- [Nomad resource requests](https://developer.hashicorp.com/nomad/docs/job-specification/resources).
- [Nomad on-demand batch autoscaling example](https://developer.hashicorp.com/nomad/tutorials/autoscaler/horizontal-cluster-scaling-on-demand-batch).
- [Autoscaler target adapters](https://developer.hashicorp.com/nomad/tools/autoscaling/plugins/target).
- [AWS Auto Scaling desired capacity and replacement](https://docs.aws.amazon.com/autoscaling/ec2/userguide/asg-capacity-limits.html).
- [Nomad release support and Community Edition licensing](https://developer.hashicorp.com/nomad/docs/ce-license-support).
  The distribution is source-available under BSL/BUSL; cloud portability does not imply an Apache/MIT license.

The native Mac cleanup, resource reservation, session granularity, and actual second-provider Spot tests in step 0
and later gates are deliberately explicit: documentation support is not evidence that the lab's complete workload
already works through Nomad.
