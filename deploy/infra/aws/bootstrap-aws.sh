#!/usr/bin/env bash
set -euo pipefail
exec /opt/eignex-lab/host-venv/bin/python3 /opt/eignex-lab/bootstrap-aws.py "$@"
