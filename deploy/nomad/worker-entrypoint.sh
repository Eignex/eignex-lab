#!/usr/bin/env bash
set -euo pipefail
mkdir -p /local
# The process supervisor is a small JVM of its own in the same container, outside the worker's failure boundary. Its
# heap and compiler stay small: the container's memory limit covers it, the worker and the measured processes.
EIGNEX_LAB_OPTS="${LAB_SUPERVISOR_JVM_OPTS:--Xms4m -Xmx48m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1}" \
  /opt/lab/bin/eignex-lab process-supervisor --socket "$LAB_SUPERVISOR_SOCKET" &
supervisor=$!
trap 'kill -TERM "$supervisor" 2>/dev/null || true' EXIT
# The JVM listens within about a second; allow 30 s for a cold, small cloud node.
for _ in {1..600}; do
  [[ -S "$LAB_SUPERVISOR_SOCKET" ]] && break
  # A supervisor that ended first passes on its status: 126 for a launcher that lost its exec bit, as a worker would.
  if ! kill -0 "$supervisor" 2>/dev/null; then
    status=0
    wait "$supervisor" || status=$?
    exit $((status ? status : 1))
  fi
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
