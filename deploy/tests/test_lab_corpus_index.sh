#!/usr/bin/env bash
# Tests of `deploy/lab corpus-index`: metadata misses must remain distinct from transport and old-server failures.
# Needs jq. bash deploy/tests/test_lab_corpus_index.sh [test name...]
# shellcheck disable=SC2016 # literal shell text for the fake curl
set -uo pipefail
deploy="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
  mkdir "$root/bin"
  # The fake curl records the URL, writes BODY to its -o file, prints HTTP as the status and exits with CURL_EXIT.
  cat > "$root/bin/curl" << 'EOF'
#!/usr/bin/env bash
output=""
while (($# > 1)); do
  [[ "$1" == -o ]] && output="$2"
  shift
done
printf %s "$1" > "$CALLS"
printf %s "$BODY" > "$output"
printf %s "$HTTP"
exit "${CURL_EXIT:-0}"
EOF
  chmod 755 "$root/bin/curl"
}
# client [http status] [body] [curl exit] [collection]: run the client; its output in $root/out, its URL in $root/calls.
client() {
  local body='{"collection":"pb-comp-2024","files":[]}'
  (( $# < 2 )) || body="$2"
  rm -f "$root/calls"
  PATH="$root/bin:$PATH" LAB_PASSWORD=fixture LAB_URL=https://lab.invalid HTTP="${1:-200}" \
    BODY="$body" CURL_EXIT="${3:-0}" CALLS="$root/calls" \
    bash "$deploy/lab" corpus-index "${4-pb-comp-2024}" > "$root/out" 2> "$root/err"
}

test_success_uses_only_metadata_route() {
  setup
  client
  equal 0 "$?" "status"
  equal pb-comp-2024 "$(jq -r .collection "$root/out")" "collection"
  equal https://lab.invalid/corpus/indexes/pb-comp-2024 "$(cat "$root/calls")" "url"
}

test_only_confirmed_index_miss_exits_three() {
  setup
  client 404 '{"error":"no published corpus index for pb-comp-2024"}'
  equal 3 "$?" "status of a confirmed miss"
  equal "" "$(cat "$root/out")" "output of a confirmed miss"
  local failure
  for failure in "404||0" '503|{"error":"backend unavailable"}|0' "401|{}|0" "200|{}|18" "000||7"; do
    IFS='|' read -r code body transport <<< "$failure"
    client "$code" "$body" "$transport"
    equal 1 "$?" "status of $failure"
    equal "" "$(cat "$root/out")" "output of $failure"
  done
}

test_invalid_ids_are_refused_before_http() {
  setup
  local name
  for name in ../secret a/b "bad id" ""; do
    client 200 "" 0 "$name"
    equal 2 "$?" "status of '$name'"
    check "no request for '$name'" test ! -e "$root/calls"
  done
}

test_malformed_success_is_a_failure() {
  setup
  client 200 "not json"
  check "fails" test "$?" -ne 0
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
