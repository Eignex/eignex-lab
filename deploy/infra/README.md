# Nomad cloud capacity

## Regional input cache

`aws/input_store.py` reconnects an existing corpus bucket to an S3 **gateway** endpoint in the workers' region and
route tables. It preserves existing bucket policy statements and data, records a rollback receipt and grants
credential-free reads only through that endpoint. Writes use short-lived signed URLs and conditional creation
of new input blobs and corpus packs. Run it with
`--bucket`, `--region`, `--vpc`, repeated `--route-table`, `--native-user` and `--receipt`. Keep S3 public-access
blocking enabled. Verify unsigned reads fail outside the VPC and work from a Spot worker. Do not route these reads
through the Mac, a NAT gateway, another region or an interface endpoint. S3 storage and requests remain billable;
direct S3-to-EC2 transfer within the same region has no data-transfer charge.

`aws/input_signer.py --config FILE` is an optional loopback-only native signing service using the existing
`aws/requirements.txt` environment. Its JSON configuration names `bucket`, `region`, `profile` and optional `port`
(8093). The signer returns five-minute URLs, never corpus bytes, and only signs input reads or conditional writes
under a SHA-256 content key or a corpus-pack key. It never distributes provider credentials. Configure native workers/coordinator with
`LAB_SHARED_INPUT_SIGNER=http://127.0.0.1:8093/`. `native_services.py` accepts an optional `inputSigner` containing
absolute `python`, `script` and `config` paths, keeping the signer independent of Nomad and lab restarts.

Cloud preparations obtain upload URLs through their authenticated, leased worker API session. An attempt-scoped
loopback relay lets consumer cache publishers request the same capabilities without receiving the worker token.
The coordinator signs metadata only; native and cloud uploads send bytes directly to the regional store.

AWS networking and signing are deployment adapters. Portable workers use an HTTPS store and paths/checksums;
another provider can supply the same HTTP operations and its own regional routing/signing adapter.

These modules are opt-in migration infrastructure. They do not alter the existing AWS workers or make Nomad the
lab default. Apply only after reviewing a real plan with the intended account, private routing, instance quota,
images and secrets. There are no provider credentials in an experiment, portable worker, manifest, or shared
Kotlin execution code. Provider SDKs run only in the Mac inventory adapters, host bootstrap/interruption adapters,
and independent cloud functions.

## AWS Spot

For a fleet of instruction-set profiles, use `deploy/nomad/capacity-architectures.example.json` and the
`aws-fleet/` module. The AWS shape choices are C5a for AVX2, M7i for AVX-512, M6g for NEON, M7g for SVE, and
M8g for SVE2. The [small-pool catalogue](aws-fleet/small-pools.json) adds separate 8-GiB pools for each profile:
2 vCPUs for AVX-512, NEON, SVE and SVE2, and 4 vCPUs for AVX2 (C5a). Keep each size in its own node class and Auto Scaling Group: mixing sizes
inside a homogeneous pool would invalidate bootstrap validation, CPU quota conversion and global vCPU accounting.
Same-size Spot alternatives are supported through the fleet module's `spot_instance_types` field.
Availability, actual host features, and Nomad usable CPU must be verified in the chosen region.
Each pool starts at zero and has its own immutable architecture-compatible AMI and worker image, purchasing
group, renewable lease, and independent expiry. Existing pools can remain in their existing state; the fleet
module manages only additional pools and adds their exact adapter/scaler policies to the existing control roles.
The shared collector's vCPU budget applies across all pools, rather than separately to each architecture.
Fleet scaler permissions combine the exact pool group ARNs in one managed policy, preserving each action and
condition while avoiding AWS's aggregate inline-policy size limit as profiles and sizes are added.

For each catalogue entry, inherit its source pool's pinned AMI, worker image, architecture and required features;
set the smaller shape, node class, CPU and memory values explicitly. Add the pool to capacity settings, adapter
inventory, Autoscaler targets and queue templates together. Use a conservative memory reserve (at least 1536 MiB
for an 8-GiB node), then verify the actual Nomad memory before admitting work. The queue reuses compatible running
capacity first and otherwise prefers larger compatible nodes so workers share host and build overhead. The capacity
policy uses smaller pools when larger pools exceed its remaining vCPU quota or pool limits. Bound allocations keep
their existing placement; every new pool starts at zero and remains Spot-only under the same global vCPU ceiling.

Use `aws/adapter-fleet.example.json` to publish all AWS pools as one inventory. A missing pool prevents publishing
a partial fresh inventory. Renewals use an independent signed grant for each pool, and one failed renewal does
not prevent attempts for other pools. Supply one Autoscaler target for every pool. ARM64 and x86_64 templates
must pin their corresponding image digest; all pools on the same OS/CPU architecture share the same worker image.

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
maximum. Its tagging permission is limited to the pinned plugin's lifecycle phase keys, so it cannot alter the
lab ownership tags that independent expiry verifies. After 30 minutes without renewal, expiry first sets **min/desired/max to zero**, then removes remaining
exactly tagged lab workers. Detection is within 35 minutes plus provider execution/termination latency. A six-hour
host watchdog is a secondary bound; it does not replace expiry because an ASG replaces independently stopped hosts.

The image bootstrap checks the actual vCPU count, available memory, architecture and common SIMD flags before
enabling the client. Templates provide an initial `attr.cpu.usablecompute` calibration. The coordinator reads
ready, eligible clients and recalibrates the Nomad CPU request and exact frequency constraint together for each
compatible shape. Architecture, vCPU count and memory remain pool requirements; GHz can vary among Spot choices.
Blocked, unallocated sessions can be replaced with another shape's calibration without changing frozen inputs,
worker releases or vCPU budgets. Bound allocations remain untouched. Conversion preserves Docker's actual hard
vCPU ceiling. The default host CPU reserve
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

## GCP Spot reference

`gcp/` implements the same inventory, fresh signed grant, 30-minute renewable lease, five-minute cloud expiry,
private host bootstrap and interruption obligations using a zonal Spot managed instance group. The worker image,
manifest, resource budgets, artifact protocol and shared execution code do not change. Copy the GCP variables and
adapter examples and the GCP autoscaler example; supply the actual module outputs. The group starts at zero with
opportunistic updates and no surge. A Spot worker is deleted on preemption, its boot disk auto-deletes, and there
is no on-demand fallback. Private VMs have no external address: provide Cloud NAT/private API access and the private
route to the Mac before launching them. Set firewall rules for the same restricted Nomad/artifact paths as AWS.

Use a **dedicated GCP project**, with finite Spot/preemptible CPU and regional machine-family quotas matching the
intended aggregate `maxVcpus`. The module validates each pool's `maxNodes * vcpus` against its budget; MIG itself has
no ASG-style hard maximum. Verify the actual project quotas and denial of an oversized resize before rollout.
A dedicated project also bounds the project-level Compute/IAM permissions needed by this reference adapter;
managed groups do not support the resource-name IAM restriction that would scope these operations like AWS ASGs.

The scaler's custom resize role is conditional on `request.time`. Its initial deadline is 1970 and only the
cloud renewal function advances it to cloud time plus 30 minutes after verifying a fresh signed coordinator grant.
The function reads IAM policy version 3, changes only the exact dedicated scaler binding, and preserves its etag
and all unrelated bindings. The Mac adapter can invoke the function and read inventory; it cannot edit IAM, read
the signing key, or renew a lease directly. The scaler cannot alter its own binding. The independent expiry
function resizes the managed group to zero after the lease expires; natural IAM deadline expiry also prevents a
stale scaler reopening it. Detection is within 35 minutes plus IAM propagation, scheduler and termination latency.

The cloud renewal identity necessarily has project `getIamPolicy`/`setIamPolicy`: this is administrative code in
the dedicated lab project. Review it and restrict deployment access. Never give that role to the Mac, worker or
scaler. Do not grant these service accounts additional inherited resize permissions that bypass the lease. The
module creates separate worker, adapter, scaler, renewal, expiry, scheduler and build service accounts and creates
no private service-account keys. Only host bootstrap reads the bootstrap secret; only renewal reads the separate
capacity signing secret. Cloud Run functions require dedicated authenticated invokers, with no `allUsers` grant.

Use ADC/federated identity and service-account impersonation for Mac control. Install `gcp/requirements.txt` in an
adapter venv. Configure the adapter's `serviceAccount` from the output. For Autoscaler, generate an impersonated
ADC configuration for the scaler service account (for example `gcloud auth application-default login
--impersonate-service-account=SCALER_EMAIL`) and reference that trusted private credential configuration in its
`credentials` option; do not download a service-account key. Keep adapter and scaler identities distinct. The
module grants the configured `control_member` token creation only on those two accounts. Native Mac services
need no GCP credentials. Build the same base Linux image with `common/bake.sh` and publish a named immutable GCE
image before applying the module. Function image builds use a separate build account/repository, unrelated to
the experiment worker registry. Enable Cloud Monitoring alerting for function/scheduler failures and verify its
notification route before production use.

Repeat the AWS live gates on GCP Spot using the **identical experiment and immutable worker digest**. Record the
preemption/loss recovery, pairing, idle retirement, and Mac-offline zero target/no replacements. Attempt a resize
with the stale scaler identity after its IAM deadline and verify denial, even with an otherwise valid OAuth token.
Verify source/build artifacts, CPU/memory observations and output projection are unchanged apart from execution
provenance. A validated module or a generic Linux Docker smoke run does not satisfy this second-provider gate.

### Regional input cost audit

`aws/audit_inputs.py` reads bucket region, gateway endpoint state, every observed worker's subnet routing,
plus daily account-level S3 and Data Transfer billing. It does not read or copy object bodies, enable paid
request metrics, or modify infrastructure. Use an operations identity with EC2/S3 describe and Cost Explorer
read access; workers and the input signer do not need billing permissions. For example:

```sh
python3 deploy/infra/aws/audit_inputs.py --cli-credentials \
  --bucket BUCKET --region REGION --endpoint vpce-ID \
  --start YYYY-MM-DD --end YYYY-MM-DD --output input-cost-audit.json
```

The end date is exclusive. `--cli-credentials` uses the AWS CLI credential provider, including `aws login`,
in memory. Billing is account-wide and delayed; empty recent days or a window with no workers cannot prove
steady-state cache savings. Inspect usage types and estimated-day flags before attributing costs to the lab.

Small-node bootstrap checks use a separate OS-visible memory baseline (`bootstrap_memory_mib`), because baked
kernels can reserve a significant fixed amount of an 8-GiB machine’s RAM. Nominal `memory_mib` still describes
the homogeneous cloud shape. Never assume that all of it is schedulable: measure Nomad’s node memory minus its
host reserve and set the capacity pool reserve and queue memory ceiling to fit that measured value.
