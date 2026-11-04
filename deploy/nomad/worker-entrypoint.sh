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
if [[ "${1:-}" == --smoke-test ]]; then
  # Exercise the real launcher and JVM without credentials, a database or a network connection.
  printf '{"maxVcpus":1,"pools":[{"id":"smoke","nodeClass":"smoke","vcpus":1,"memoryMiB":2048,"maxNodes":1,"tags":["arch:x86_64"]}]}' > /local/smoke-capacity.json
  /opt/lab/bin/eignex-lab capacity-export /local/smoke-capacity.json
  echo 'worker entrypoint smoke test passed'
  exit 0
fi
/opt/lab/bin/eignex-lab "$@"
