#!/usr/bin/env bash
# Run on an otherwise prepared image builder with Docker, iptables, curl 8 or newer, jq, unzip and the desired private
# network tooling. The host scripts it installs need no Python or cloud SDK.
set -euo pipefail
: "${LAB_WORKER_IMAGE:?immutable OCI image digest required}"
[[ "$LAB_WORKER_IMAGE" =~ @sha256:[0-9a-f]{64}$ ]]
command -v jq > /dev/null || { echo "the host scripts need jq" >&2; exit 1; }
# bootstrap-aws.sh signs its Secrets Manager request with curl's --aws-sigv4, signing the session token header too.
curl_version="$(curl --version | awk 'NR == 1 { print $2 }')"
[[ "${curl_version%%.*}" -ge 8 ]] || { echo "the host scripts need curl 8 or newer, not $curl_version" >&2; exit 1; }
root=$(cd "$(dirname "$0")/../../.." && pwd)
. "$root/deploy/nomad/hashicorp.sh"
nomad="$(mktemp)"
hashicorp_fetch nomad "$(hashicorp_platform)" "$nomad"
install -m 755 "$nomad" /usr/local/bin/nomad
rm -f "$nomad"
install -d -m 755 /opt/eignex-lab
# Images baked before the bash host scripts carried a Python environment and these scripts.
rm -rf /opt/eignex-lab/host-venv /opt/eignex-lab/host-python.lock /opt/eignex-lab/*.py
install -m 755 "$root"/deploy/infra/common/{bootstrap,interruption}.sh "$root/deploy/infra/aws/bootstrap-aws.sh" \
  "$root/deploy/infra/gcp/bootstrap-gcp.sh" /opt/eignex-lab/
install -m 755 "$root/src/main/resources/node-capabilities.sh" /opt/eignex-lab/
install -m 644 "$root"/deploy/infra/common/*.service "$root"/deploy/infra/common/*.timer /etc/systemd/system/
docker pull "$LAB_WORKER_IMAGE"
timeout 30s docker run --rm --network none "$LAB_WORKER_IMAGE" --smoke-test
docker image inspect "$LAB_WORKER_IMAGE" > /opt/eignex-lab/worker-image.json
{ curl --version | head -n 1; jq --version; } > /opt/eignex-lab/host-tools.lock
systemctl daemon-reload
# Leave all lab services disabled in the image. Boot fetches the private host identity and validates the shape first.
