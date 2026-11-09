#!/usr/bin/env bash
# Run on an otherwise prepared image builder with Docker, Python+boto3, curl, unzip and the desired private network
# tooling.
set -euo pipefail
: "${LAB_WORKER_IMAGE:?immutable OCI image digest required}"
[[ "$LAB_WORKER_IMAGE" =~ @sha256:[0-9a-f]{64}$ ]]
root=$(cd "$(dirname "$0")/../../.." && pwd)
. "$root/deploy/nomad/hashicorp.sh"
nomad="$(mktemp)"
hashicorp_fetch nomad "$(hashicorp_platform)" "$nomad"
install -m 755 "$nomad" /usr/local/bin/nomad
rm -f "$nomad"
install -d -m 755 /opt/eignex-lab
python3 -m venv /opt/eignex-lab/host-venv
/opt/eignex-lab/host-venv/bin/python3 -m pip install -r "$root/deploy/infra/aws/requirements.txt"
install -m 755 "$root"/deploy/infra/common/*.py "$root"/deploy/infra/aws/bootstrap-aws.{py,sh} "$root"/deploy/infra/gcp/bootstrap-gcp.py /opt/eignex-lab/
install -m 755 "$root/src/main/resources/node-capabilities.sh" /opt/eignex-lab/
install -m 644 "$root"/deploy/infra/common/*.service "$root"/deploy/infra/common/*.timer /etc/systemd/system/
docker pull "$LAB_WORKER_IMAGE"
timeout 30s docker run --rm --network none "$LAB_WORKER_IMAGE" --smoke-test
docker image inspect "$LAB_WORKER_IMAGE" > /opt/eignex-lab/worker-image.json
/opt/eignex-lab/host-venv/bin/python3 -m pip freeze > /opt/eignex-lab/host-python.lock
systemctl daemon-reload
# Leave all lab services disabled in the image. Boot fetches the private host identity and validates the shape first.
