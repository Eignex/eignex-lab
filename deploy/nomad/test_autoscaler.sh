#!/usr/bin/env bash
# Tests of deploy/nomad/autoscaler.sh. Needs jq.
# bash deploy/nomad/test_autoscaler.sh [test name...]
set -uo pipefail
nomad="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$nomad/autoscaler.sh"

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}
setup() {
  root="$(mktemp -d)"
  root="$(cd "$root" && pwd -P)"
  printf 'private-token-private-token-private-token-private-token-' > "$root/key"
  jq -n --arg key "$root/key" '{capacityTokenFile: $key, nomadTokenFile: $key, coordinator: "http://127.0.0.1:8091",
    nomad: {address: "https://nomad:4646"}, targets: [{pool: "general", driver: "aws-asg",
    options: {aws_asg_name: "group", node_selector_strategy: "least_busy"}}]}' > "$root/settings.json"
  printf '{"pools": [{"id": "general", "nodeClass": "general", "maxNodes": 4}]}' > "$root/capacity.json"
}

test_empty_only_scale_in_and_fail_closed_metrics_are_forced() {
  setup
  check "renders" bash "$script" "$root/settings.json" "$root/capacity.json" "$root/out"
  local policy
  policy="$(jq -c .scaling.general "$root/out/policies/general.json")"
  equal 0 "$(jq .min <<< "$policy")" "min"
  equal 4 "$(jq .max <<< "$policy")" "max"
  equal fail "$(jq -r .policy.on_check_error <<< "$policy")" "check errors"
  equal empty "$(jq -r '.policy.target["pool-general"].node_selector_strategy' <<< "$policy")" "node selection"
  equal instant "$(jq -r '.policy.check["fresh-demand"].query_window' <<< "$policy")" "query window"
  check "the policy holds no token" test -z "$(grep private-token "$root/out/policies/general.json")"
  equal "Bearer $(cat "$root/key")" "$(jq -r '.apm["lab-demand"].config.header_Authorization' "$root/out/agent.json")" \
    "the agent holds the capacity key"
  local file
  for file in "$root/out" "$root/out/policies" "$root/out/agent.json" "$root/out/policies/general.json"; do
    # shellcheck disable=SC2012 # only the mode column
    equal "" "$(ls -ld "$file" | cut -c5-10 | tr -d -)" "private mode of $file"
  done
  jq '.targets = []' "$root/settings.json" > "$root/none.json"
  bash "$script" "$root/none.json" "$root/capacity.json" "$root/refused" 2> "$root/reason" &&
    { echo "  failed: rendered without a target for a pool" >&2; exit 1; }
  check "explains" grep -q "exactly one target for every capacity pool" "$root/reason"
  check "writes nothing" test ! -e "$root/refused"
}

test_refuses_relative_output_unverified_nomad_and_short_keys() {
  setup
  bash "$script" "$root/settings.json" "$root/capacity.json" relative 2> /dev/null &&
    { echo "  failed: accepted a relative output directory" >&2; exit 1; }
  jq '.nomad.address = "http://nomad:4646"' "$root/settings.json" > "$root/plain.json"
  bash "$script" "$root/plain.json" "$root/capacity.json" "$root/out" 2> /dev/null &&
    { echo "  failed: accepted plain HTTP to Nomad" >&2; exit 1; }
  printf short > "$root/key"
  bash "$script" "$root/settings.json" "$root/capacity.json" "$root/out" 2> /dev/null &&
    { echo "  failed: accepted a short capacity key" >&2; exit 1; }
  check "writes nothing" test ! -e "$root/out"
}

test_queries_the_desired_metric_of_the_shared_wire_format() {
  setup
  check "renders" bash "$script" "$root/settings.json" "$root/capacity.json" "$root/out"
  equal "$(jq -r .desiredMetric "$nomad/../capacity-wire.json")"'{pool="general"}' \
    "$(jq -r '.scaling.general.policy.check["fresh-demand"].query' "$root/out/policies/general.json")" "metric"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
