#!/usr/bin/env bash
# Run on an otherwise prepared image builder with Docker, Python+boto3 and the desired private network tooling.
set -euo pipefail
: "${LAB_WORKER_IMAGE:?immutable OCI image digest required}"
[[ "$LAB_WORKER_IMAGE" =~ @sha256:[0-9a-f]{64}$ ]]
root=$(cd "$(dirname "$0")/../../.." && pwd)
python3 - "$root" <<'PY'
import hashlib, io, json, platform, sys, urllib.request, zipfile
from pathlib import Path
pin = json.loads((Path(sys.argv[1]) / 'deploy/nomad/versions.json').read_text())['nomad']
arch = {'x86_64': 'amd64', 'aarch64': 'arm64'}[platform.machine()]
with urllib.request.urlopen(f"https://releases.hashicorp.com/nomad/{pin['version']}/nomad_{pin['version']}_linux_{arch}.zip", timeout=60) as response:
    archive = response.read()
if hashlib.sha256(archive).hexdigest() != pin['sha256']['linux_' + arch]:
    raise ValueError('Nomad image checksum mismatch')
binary = Path('/usr/local/bin/nomad'); binary.write_bytes(zipfile.ZipFile(io.BytesIO(archive)).read('nomad')); binary.chmod(0o755)
PY
install -d -m 755 /opt/eignex-lab
install -m 755 "$root"/deploy/infra/common/*.py "$root"/deploy/infra/aws/bootstrap-aws.{py,sh} /opt/eignex-lab/
install -m 755 "$root/src/main/resources/node-capabilities.sh" /opt/eignex-lab/
install -m 644 "$root"/deploy/infra/common/*.service "$root"/deploy/infra/common/*.timer /etc/systemd/system/
docker pull "$LAB_WORKER_IMAGE"
docker image inspect "$LAB_WORKER_IMAGE" > /opt/eignex-lab/worker-image.json
python3 -m pip freeze > /opt/eignex-lab/host-python.lock
systemctl daemon-reload
# Leave all lab services disabled in the image. Boot fetches the private host identity and validates the shape first.
