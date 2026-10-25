#!/usr/bin/env bash
set -euo pipefail
mkdir -p /local
python3 /opt/lab/process_supervisor.py --socket "$LAB_SUPERVISOR_SOCKET" &
supervisor=$!
trap 'kill -TERM "$supervisor" 2>/dev/null || true' EXIT
for _ in {1..100}; do
  [[ -S "$LAB_SUPERVISOR_SOCKET" ]] && break
  kill -0 "$supervisor"
  sleep .05
done
[[ -S "$LAB_SUPERVISOR_SOCKET" ]]
/opt/lab/bin/eignex-lab "$@"
