#!/usr/bin/env bash
# Tests of deploy/nomad/sandbox.sh and probe.sh that need no Nomad, Docker or network. Needs jq.
# bash deploy/nomad/test_sandbox.sh [test name...]
# shellcheck disable=SC1091,SC2016,SC2034,SC2154,SC2329 # sourced functions and globals, their stubs, literal text
set -uo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/sandbox.sh"
set -u

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}
setup() {
  work="$(mktemp -d)"
  work="$(cd "$work" && pwd -P)"
  allocation=00000000-0000-4000-8000-000000000001
  sandbox_init /unused 14646
}

# A fake clock: now_ms reads it, pause advances it, and the cli stub takes no time.
clock() {
  echo 0 > "$work/clock"
  now_ms() { cat "$work/clock"; }
  pause() {
    (($1 > 0)) || { echo "  failed: paused for $1 ms" >&2; exit 1; }
    echo $(($(cat "$work/clock") + $1)) > "$work/clock"
    echo "$1" >> "$work/pauses"
  }
  : > "$work/pauses"
}
unavailable() {  # unavailable [allocation]: the client's log-readiness error
  echo "Failed to read stdout file: error reading file: Unexpected response code: 404 (state for allocation ${1:-$allocation} not found on client)"
}
# answers <status:stderr-or-stdout>...: the cli stub answers each call with the next one, repeating the last; it
# records its arguments in $work/calls.
answers() {
  printf '%s\n' "$@" > "$work/answers"
  : > "$work/calls"
  cli() {
    echo "$*" >> "$work/calls"
    local count answer status
    count="$(wc -l < "$work/calls")"
    answer="$(sed -n "${count}p" "$work/answers")"
    [[ -n "$answer" ]] || answer="$(tail -n 1 "$work/answers")"
    status="${answer%%:*}"
    if ((status == 0)); then printf '%s\n' "${answer#*:}"; else printf '%s\n' "${answer#*:}" >&2; fi
    return "$status"
  }
}

test_known_readiness_error_retries_only_the_original_allocation_and_returns_unchanged_logs() {
  setup
  clock
  local report='{"cpuMax":"50000 100000","childExits":[0,0,0]}'
  answers "1:$(unavailable)" "1:$(unavailable)" "0:$report"
  equal "$report" "$(allocation_logs "$allocation")" "logs"
  equal "$(printf -- '--timeout 5.000 alloc logs %s worker\n' "$allocation" "$allocation" "$allocation")" "$(cat "$work/calls")" "calls"
  equal "$(printf '250\n250')" "$(cat "$work/pauses")" "pauses"
}

test_permanent_readiness_failure_has_one_total_deadline_and_a_bounded_timeout_per_attempt() {
  setup
  clock
  answers "1:$(unavailable)"
  local status timeout
  allocation_logs "$allocation" > "$work/out" 2> "$work/err"
  status=$?
  equal 1 "$status" "the readiness failure's status"
  equal "$(unavailable)" "$(cat "$work/err")" "the readiness failure itself"
  equal 10000 "$(cat "$work/clock")" "deadline"
  equal 40 "$(wc -l < "$work/calls" | tr -d ' ')" "attempts"
  check "every attempt reads the same allocation" test -z "$(grep -v " alloc logs $allocation worker$" "$work/calls")"
  while read -r _ timeout _; do
    awk -v t="$timeout" 'BEGIN { exit !(t > 0 && t <= 5) }' || { echo "  failed: timeout $timeout" >&2; exit 1; }
  done < "$work/calls"
  equal "--timeout 0.250 alloc logs $allocation worker" "$(tail -n 1 "$work/calls")" "the last attempt's timeout"
}

test_other_failures_are_not_retried_or_replaced() {
  setup
  clock
  local failure status
  for failure in "1:$(unavailable another-allocation)" "2:$(unavailable)" \
    "1:Unexpected response code: 403 (Permission denied)" "1:Unexpected response code: 500 (internal error)" \
    "1:Unexpected response code: 404 (log file not found)" "124:"; do
    answers "$failure"
    allocation_logs "$allocation" > "$work/out" 2> "$work/err"
    status=$?
    equal "${failure%%:*}" "$status" "status of $failure"
    equal "${failure#*:}" "$(cat "$work/err")" "error of $failure"
    equal 1 "$(wc -l < "$work/calls" | tr -d ' ')" "attempts for $failure"
  done
  equal "" "$(cat "$work/pauses")" "pauses"
}

test_successful_malformed_output_is_returned_for_the_existing_resource_assertions_to_reject() {
  setup
  clock
  answers "0:not a probe report"
  equal "not a probe report" "$(allocation_logs "$allocation")" "logs"
  equal 1 "$(wc -l < "$work/calls" | tr -d ' ')" "attempts"
  equal "" "$(cat "$work/pauses")" "pauses"
}

test_cli_bounds_the_sandbox_nomad_and_drops_the_callers_nomad_environment() {
  setup
  sandbox_init "$work" 14646
  mkdir -p "$work/bin"
  printf 'secret-token\n' > "$work/token"
  printf '#!/bin/sh\necho "$*"; env | grep ^NOMAD_ | sort\n' > "$work/bin/nomad"
  chmod +x "$work/bin/nomad"
  bounded() { echo "bounded $1" > "$work/bounded"; shift; "$@"; }
  local output
  output="$(NOMAD_REGION=elsewhere NOMAD_ADDR=https://production cli --timeout 0.250 alloc logs "$allocation" worker)"
  equal "bounded 0.250" "$(cat "$work/bounded")" "the timeout"
  equal "alloc logs $allocation worker
NOMAD_ADDR=https://127.0.0.1:14646
NOMAD_CACERT=$work/nomad-agent-ca.pem
NOMAD_CLIENT_CERT=$work/global-cli-nomad.pem
NOMAD_CLIENT_KEY=$work/global-cli-nomad-key.pem
NOMAD_TOKEN=secret-token" "$output" "the command and its environment"
}

test_state_names_the_sandbox_and_relative_state_is_absolute() {
  setup
  sandbox_init "$work" 14646
  equal "eignex-nomad-prototype-$(printf %s "$work" | hashicorp_sha256 /dev/stdin | cut -c1-12)" "$name" "name"
  (cd "$work" && sandbox_init sub/state 14646 && echo "$state") > "$work/state"
  equal "$work/sub/state" "$(cat "$work/state")" "relative state"
}

test_probe_text_survives_nomad_interpolation_and_refuses_bad_arguments() {
  setup
  check "no dollar-brace sequence for Nomad to interpolate" test -z "$(grep -F '${' "$dir/probe.sh")"
  local arguments
  for arguments in "--seconds 0" "--seconds 121" "--children 9" "--seconds x" "--unknown"; do
    # shellcheck disable=SC2086 # the arguments are words
    bash "$dir/probe.sh" $arguments 2> /dev/null
    equal 2 "$?" "status for $arguments"
  done
}

test_probe_reports_its_detached_children() {
  setup
  check "probes" bash "$dir/probe.sh" --seconds 1 --children 2 > "$work/report"
  equal "[0,0]" "$(jq -c .childExits "$work/report")" "child exits"
  equal 2 "$(jq .children "$work/report")" "children"
  check "measured wall time" jq -e '.wallSeconds >= 1 and .cpuSeconds > 0' "$work/report" > /dev/null
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${work:-}" ]] || rm -rf "$work"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
