#!/usr/bin/env bash
set -euo pipefail
exec python3 /opt/eignex-lab/bootstrap-aws.py "$@"
