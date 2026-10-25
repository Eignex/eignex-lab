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
