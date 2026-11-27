# shellcheck shell=bash
# Sourced by the deploy shell tests: their assertions and runner. Written for macOS's bash 3.2.

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}

# run_tests [test name...]: the named tests, or every test_* function, each in a subshell that removes the scratch
# directory in $root (or in the variable test_scratch names) as it ends; exits 1 when any failed.
run_tests() {
  # shellcheck disable=SC2034 # the EXIT trap reads scratch
  local tests test status failures=0 scratch="${test_scratch:-root}"
  tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
  for test in $tests; do
    ( set +e; trap '[[ -z "${!scratch:-}" ]] || rm -rf "${!scratch}"' EXIT; "$test" )
    status=$?
    if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
  done
  (( failures == 0 )) || { echo "$failures failed"; exit 1; }
}
