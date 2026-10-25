# Nomad cloud capacity

These modules are opt-in migration infrastructure. They do not alter the existing AWS workers or make Nomad the
lab default. Apply only after reviewing a real plan with the intended account, private routing, instance quota,
images and secrets. There are no provider credentials in an experiment, portable worker, manifest, or shared
Kotlin execution code. Provider SDKs run only in the Mac inventory adapters, host bootstrap/interruption adapters,
and independent cloud functions.

## AWS Spot

`aws/` creates one homogeneous zero-sized Auto Scaling Group, a pinned launch template, separate worker/scaler/
adapter identities, capacity lease storage, a renewal Lambda, and an independent expiry Lambda scheduled every
five minutes. `market` defaults to `spot`; failure to buy Spot never falls back to on-demand. Configure a distinct
explicit on-demand pool for policy that requires it. The module's finite pool size must fit its vCPU budget.
Account Spot/on-demand vCPU quotas remain the final provider backstop, including booting/terminating overlap;
set the shared collector's overall `maxVcpus` and pool limits consistently with them.

1. Bake a Linux image with Docker, iptables, Python 3.13, host dependencies from `aws/requirements.txt`, and any
   private network client. From this checkout, run `LAB_WORKER_IMAGE=REGISTRY/WORKER@sha256:DIGEST sudo -E bash
   deploy/infra/common/bake.sh` on the builder. Build the portable worker with `deploy/nomad/worker.Dockerfile`
   first. Record the base image ID, package lock, OCI digest, architecture, Nomad version and toolchain, then
   publish a new immutable AMI. Do not bake credentials or running lab state into it. This recipe installs
   checksum-pinned Nomad and pre-pulls the immutable OCI image; it does not create billable image builders.
2. Put host-only client identity in an existing Secrets Manager secret: JSON keys `ca`, `certificate`,
   `privateKey`, optional `bindAddress`, and optional `networkCommand` (an absolute executable argv for private
   network join). The client cert must belong to the same Nomad CA. The role receives access only to this secret.
   No server CA signing key, management ACL, provider control key or capacity signing key belongs here.
3. Put the exact trimmed contents of the coordinator's private capacity token file into a **different** existing
   secret, accessible only to the cloud renewal function. The module references secret ARNs and never reads
   their values into Terraform state or user data. Use provider-managed encryption, or add narrowly scoped KMS
   decrypt permission if your secrets use a customer-managed key.
4. Copy `aws/terraform.tfvars.example` outside version control; supply pinned IDs and a private subnet/security
   group. Require only the Mac/private-network Nomad HTTP/RPC/Serf routes, outbound artifact/registry access,
   DNS, Secrets Manager and time synchronization. No public Nomad endpoint, inbound SSH execution or public
   worker address is required. Run `tofu init`, `tofu plan`, review, then `tofu apply` in `aws/`.
5. Copy `aws/adapter.example.json` into the lab's private configuration. Configure its AWS profile to assume
   `adapter_role_arn` with normal short-lived SDK credentials. Run the adapter as a supervised independent Mac
   service every ten seconds. Point the coordinator capacity settings at the resulting inventory file.
6. Use `deploy/nomad/autoscaler.py SETTINGS CAPACITY_SETTINGS PRIVATE_OUTPUT --install-runtime` to render private
   policies and install checksum-pinned Autoscaler 0.6.0. Run `nomad-autoscaler-0.6.0 agent -config=PRIVATE_OUTPUT/agent.json`
   as a separate supervised Mac service with `AWS_PROFILE=lab-nomad-scaler`, assuming `scaler_role_arn`. The profile
   uses the SDK's standard credential chain; no long-lived keys belong in generated policy or worker environment.
   Give it a dedicated ACL using `deploy/nomad/autoscaler-policy.hcl`, mTLS, and the private collector endpoint.
   Policies use fresh instantaneous demand, fail on query errors, grow from zero, and select only empty nodes for
   voluntary removal. Configure one independent target per capacity pool; GCP uses `gce-mig` instead of `aws-asg`.
7. Cloud job templates must use `statusHostVolume: "lab-status"`. This mounts only the read-only interruption
   marker directory and sets `LAB_WORKER_DRAIN_FILE`. The host adapter detects Spot interruption, writes the marker,
   then stops its Nomad client with a 60-second drain deadline. Abrupt disappearance still uses durable fencing
   and whole-iteration replacement. Workloads cannot access host cloud credentials, the Docker socket or host TLS;
   IMDSv2 hop limit one and host `DOCKER-USER` rules block container metadata HTTP access. Keep host networking off.

The adapter publishes inventory before requesting a signed grant from `/capacity/grant/POOL`. The coordinator
requires healthy volumes and provider/Nomad observations no older than 30 seconds. The cloud function verifies
HMAC, pool, finite maximum and timestamp using its own clock; stale metrics cannot renew capacity. Only the cloud
function can reopen the group's maximum. The autoscaler can change desired capacity but cannot change minimum or
maximum. After 30 minutes without renewal, expiry first sets **min/desired/max to zero**, then removes remaining
exactly tagged lab workers. Detection is within 35 minutes plus provider execution/termination latency. A six-hour
host watchdog is a secondary bound; it does not replace expiry because an ASG replaces independently stopped hosts.

The image bootstrap checks the actual vCPU count, available memory, architecture and common SIMD flags before
enabling the client. Calibrate `attr.cpu.usablecompute` for each homogeneous pool and use that exact value in
session templates; conversion uses it to preserve Docker's actual hard vCPU ceiling. The default host CPU reserve
is 2000 MHz and memory reserve is 1024 MiB; keep the policy's additional resource reserve consistent. Changing image,
instance type, reserve, architecture or toolchain creates a new pool calibration and execution provenance.

Generated autoscaler files contain private ACL/capacity credentials (directory 0700, files 0600). Keep them outside
Git and exported artifacts. Normal lab updates must not restart Nomad agents or active worker allocations. A control
host restart should independently restart the inventory adapter, collector and autoscaler. Expiry requires none of
them. Configure the expiry error alarm's SNS topic and verify notification delivery before production use.

## Validation and live gates

`python3 -m unittest discover -s deploy/infra/tests -p 'test_*.py'` tests grant freshness, ownership, boot accounting,
expiry ordering and bootstrap rejection. `python3 deploy/infra/validate.py` installs a checksum-pinned OpenTofu on
Linux amd64 and checks formatting/schema without account credentials or applying changes. Provider schemas and
lock files are pinned. These checks cannot establish actual IAM authorization, Spot availability or time-to-delete.

Before rollout, run a real cloud-only canary from zero, record CPU/memory enforcement, co-locate multiple fitting
experiments, revoke a worker after the first arm, and verify all unfinished arms rerun together. Observe no-capacity
and quota failures in the collector report. Let an empty worker retire without affecting busy neighbors. Finally
stop the Mac and autoscaler for longer than the lease: verify min/desired/max reach zero, all tagged workers and
auto-delete disks disappear, and **no replacement appears**. Attempt stale autoscaler growth and renewal with an
old grant; both must fail. Restart control services and verify only fresh reconciliation reopens capacity. Retain
account/pool IDs, timestamps, grants with signatures redacted, cloud audit events and allocation provenance as gate
evidence. Production cutover remains blocked on these real gates and the native Mac solver/JFR gates.
