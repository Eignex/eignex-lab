# Cloud nodes

[Documentation home](../deploy/SETUP.md) · [Node selectors](runs.md#selecting-a-node) · [Operations](operations.md)

## Enable AWS

From a machine with an authenticated admin AWS CLI session and SSH access to the lab server:

```sh
deploy/aws-setup.sh user@lab-host
```

Setup creates the lab's IAM user/profile, SSH key, security group, tagged-instance permissions, spot service-linked
role, corpus bucket, and instance role. It writes settings and the private key under `~/eignex-lab-data/aws` on the
server. If `LAB_DATA` differs, move those files there and correct `keyFile`. Restart the runner to enable the worker;
subsequent property changes are reread on each poll.

The setup script currently targets the lab Mac and can install its AWS CLI with Homebrew. For another host, provision
the CLI and equivalent credentials/settings there. `ADMIN_PROFILE`, `AWS_REGION`, `INSTANCE_TYPE`, `INSTANCE_TYPES`,
`MAX_INSTANCES`, and `VCPU_QUOTA` customize setup. Runtime credentials use the separate `eignex-lab` profile.

Nodes boot Ubuntu with JDK 25 and the build tools, sync corpora through private S3 storage, and return each case's
results over SSH. Klause distribution caches are verified before use and separated by CPU architecture. Generic
repository profiles use their own preparation tasks. Witness checking always runs back on the lab server.

## Node labels and architectures

Use `LAB_NODE_TAGS=apple-m4,dedicated-bench` in the server's `lab.env` for local custom labels, then reinstall services.
Configure cloud labels in `$LAB_DATA/aws/aws.properties`:

```properties
instanceTypes=c7i.2xlarge,c6a.2xlarge,c7g.2xlarge
nodeTags=dedicated-bench
tags.c7i=arch:x86_64,simd:avx2,simd:avx512
tags.c6a=arch:x86_64,simd:avx2
tags.c7g=arch:arm64,simd:neon,simd:sve
tags.c7i.4xlarge=fast-ssd
tags.i-0123456789abcdef0=experiment-reserved
threadsPerCore.c7g=1
```

Labels can apply to all nodes, a family, an exact type, or a running instance. A listed baseline expands to larger
permitted sizes within the configured limits; `instanceType` is used when `instanceTypes` is absent.
To extend IAM permissions to the same families, rerun setup:

```sh
INSTANCE_TYPES=c7i.2xlarge,c6a.2xlarge,c7g.2xlarge deploy/aws-setup.sh user@lab-host
```

Setup reads EC2 architecture/topology metadata into `architecture.<family>` and `threadsPerCore.<family>`, preserving
operator labels and explicit topology overrides. ARM defaults to one thread per core, x86 to two.
Architecture selects the AMI and installers; `ami` and `arm64Ami` provide separate overrides. ARM uses Ubuntu's
MiniZinc package because the pinned upstream Linux bundle is x86-only; use a suitable AMI when an exact ARM
MiniZinc version matters.

Configured CPU labels are provisioning hints. The worker probes actual CPU facts after boot/adoption, so operator
labels cannot fabricate architecture, SIMD, or market facts. Observations appear in `/health`, `/cloud/nodes`,
node tooltips, and each job's `node-<host>.json`. [Selectors](runs.md#selecting-a-node) match those observations.

## Placement and capacity

Ordinary jobs use the local machine first and cloud nodes as overflow. Submitted `host` and `machines` values do
not choose capacity. Schedules opt in through their saved `host: "auto"` or `"cloud"`; otherwise they remain local.
Confirmations, reference-backend runs, exact problem lists, capture analysis, and whole-run-deadline profiles stay local.

A shared job's unit is one iteration: every arm of a problem/seed/repeat. One host completes that iteration.
Different iterations may use different hosts, so within-iteration pairing is preserved while aggregate timing can
mix machine speeds. A lost node returns unfinished iterations whole, including arms already completed within them.

Cloud pressure is estimated remaining core-hours from eligible queued and unclaimed work. The worker uses measured
history where available, otherwise upper-bound estimates. Work the local machine takes next is excluded.
The first launch requires pressure above `LAB_CORES × nodeLaunchLocalHours` for the sustain period; later launches
require more pressure and respect cooldown. Below-priority-floor work cannot launch nodes, but an idle node may take it.

Explicit selectors unavailable locally can request a compatible shape without the overflow threshold/sustain wait.
Existing and launching shapes count toward that request; maximum instances, quota, and cooldown still apply.
Profiles prefer on-demand nodes. Requiring `spot` explicitly allows a profile there and prevents that purchase's
on-demand fallback; excluding `spot` permits local or on-demand execution.

| Property | Reader default | Purpose |
| --- | --- | --- |
| `maxInstances` | 4 | Nodes up and launching |
| `vcpuQuota` | 32 | Total reserved vCPUs |
| `nodeMaxVcpus` | 16 | Normal node-size ceiling |
| `nodeLaunchLocalHours` | 1 | Initial pressure threshold multiplier |
| `nodeSustainMin` | 10 | Time pressure must hold |
| `nodeCooldownMin` | 5 | Minimum time between launches |
| `nodeMinPriority` | 0 | Cloud launch priority floor for locally eligible work |
| `onDemand` | false | Permit ordinary spot-first launches to fall back to on-demand |
| `spotWaitMin` | 15 | Spot wait before allowed fallback |
| `nodeIdleMin` | 5 | Retire idle nodes |
| `maxHours` | 24 | Instance lifetime limit |
| `nodeWatchdogMin` | 30 | Shut down after controller heartbeat loss; 0 disables for new nodes |

Setup writes `maxInstances=8` and `vcpuQuota=128` unless overridden; set them to the capacity your account permits.
The full property schema is [AwsConfig](../src/main/kotlin/com/eignex/lab/Aws.kt). Leave per-job `parallel` unset for
ordinary cloud runs: the default is one case per two physical cores, bounded by the case's own core requirement.

## Inspect and recover

```sh
deploy/lab placement
deploy/lab node start c7i.4xlarge on-demand
deploy/lab ssh 7 0 uptime
```

A manual launch skips pressure/sustain, but still checks pool limits and requires a fresh worker report.
Use `lab ssh` for nodes: SSH host keys are bound to instance identity, not a reusable public address.

The worker saves the pool in `$LAB_DATA/aws/pool.json`. After a controller restart it adopts healthy tagged nodes,
cleans their interrupted job processes, and retains their builds/corpora. Failed adoption or untracked lab-tagged
instances are terminated. Idle retirement, the lifetime limit, and a heartbeat watchdog bound unattended lifetime.
Updates do not drain the pool. `/cloud/nodes` and `lab placement` show phases, pressure, quota, and launch blockers.
