#!/usr/bin/env bash
# Tests of `deploy/lab wait`: dropped connections and server errors are retried, refused requests end the wait.
# Needs jq. bash deploy/tests/test_lab_wait.sh [test name...]
# shellcheck disable=SC2016 # literal shell text for the stubs
set -uo pipefail
deploy="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null # the shared test harness, checked on its own
. "$deploy/tests/harness.sh"

setup() {
  root="$(mktemp -d)"
  mkdir "$root/bin"
  # The fake curl gives the next "<curl exit> <HTTP status> <body>" line of $root/answers, as -w '\n%{http_code}' would.
  cat > "$root/bin/curl" << 'EOF'
#!/usr/bin/env bash
echo call >> "$ROOT/calls"
read -r exit code body < <(sed -n "$(wc -l < "$ROOT/calls")p" "$ROOT/answers")
(( exit == 0 )) || { echo "curl: transport failure" >&2; exit "$exit"; }
printf '%s\n%s' "$body" "$code"
EOF
  # Retries need no real pause.
  printf '#!/bin/sh\n' > "$root/bin/sleep"
  chmod 755 "$root/bin/curl" "$root/bin/sleep"
}
# wait_for <answer lines>: run `lab wait 7`; its status, output in $root/out and errors in $root/err.
wait_for() {
  printf '%s\n' "$@" > "$root/answers"
  PATH="$root/bin:$PATH" ROOT="$root" LAB_PASSWORD=fixture LAB_URL=https://lab.invalid \
    bash "$deploy/lab" wait 7 > "$root/out" 2> "$root/err"
}
calls() { wc -l < "$root/calls" | tr -d '[:space:]'; }

test_transport_failures_server_errors_and_running_answers_are_retried() {
  setup
  wait_for '7 000' '0 503 {"error":"busy"}' '0 200 {"status":"RUNNING"}' '0 200 {"status":"DONE"}'
  equal 0 "$?" "status"
  equal $'7\tDONE' "$(cat "$root/out")" "end"
  equal 4 "$(calls)" "requests"
}

test_an_unknown_job_fails_at_once() {
  setup
  wait_for '0 404 {"error":"no such job 7"}' '0 200 {"status":"DONE"}'
  equal 1 "$?" "status"
  equal 1 "$(calls)" "requests"
  check "names the job" grep -q "no job 7" "$root/err"
}

test_another_refused_request_fails_at_once() {
  setup
  wait_for '0 401 {"error":"password required"}' '0 200 {"status":"DONE"}'
  equal 1 "$?" "status"
  equal 1 "$(calls)" "requests"
  check "gives the answer" grep -q "HTTP 401" "$root/err"
}

run_tests "$@"
