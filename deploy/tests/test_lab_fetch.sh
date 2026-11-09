#!/usr/bin/env bash
# Tests of `deploy/lab fetch`: the maintained client must never publish a truncated evidence download as complete.
# Needs jq. bash deploy/tests/test_lab_fetch.sh [test name...]
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
sha() { printf %s "$1" | { if command -v sha256sum > /dev/null; then sha256sum; else shasum -a 256; fi; } | cut -d' ' -f1; }
setup() {
  root="$(mktemp -d)"
  root="$(cd "$root" && pwd -P)"
  dest="$root/download"
  mkdir "$root/bin"
  # The fake lab answers each request by FAKE_MODE, logging "METHOD /decoded/path" lines in FAKE_CALLS and each file
  # inventory request's waitSec and --max-time in inventory-options beside it.
  cat > "$root/bin/curl" << 'EOF'
#!/usr/bin/env bash
sha() { printf %s "$1" | { if command -v sha256sum > /dev/null; then sha256sum; else shasum -a 256; fi; } | cut -d' ' -f1; }
method=GET output="" write="" timeout=""
while (($# > 1)); do
  case "$1" in
  -X) method="$2" ;;
  -o) output="$2" ;;
  -w) write=1 ;;
  --max-time) timeout="$2" ;;
  esac
  shift
done
target="$1"
rest="${target#*://*/}"
query=""
[[ "$rest" == *\?* ]] && query="${rest#*\?}"
encoded="/${rest%%\?*}"
path="$(printf '%b.' "${encoded//%/\\x}")"
path="${path%.}"
printf '%s %s\n' "$method" "$path" >> "$FAKE_CALLS"
mode="$FAKE_MODE"
body="" status=200 code=0
if [[ "$path" == */hold ]]; then
  body='{"id":"ab12"}'
elif [[ "$path" == */hold/* ]]; then
  :
elif [[ "$path" == */files ]]; then
  wait="$(sed -n 's/.*waitSec=\([0-9]*\).*/\1/p' <<< "$query")"
  echo "$wait $timeout" >> "$(dirname "$FAKE_CALLS")/inventory-options"
  case "$mode" in
  hashed | bad-hash)
    body="$(jq -cn --arg sha "$(sha complete)" '[{path: "preparation/archive.zip", bytes: 8, sha256: $sha},
      {path: "job.log", bytes: 4}]')" ;;
  case-names | aliased-directories)
    if [[ "$mode" == case-names ]]; then first=A.out other=a.out; else first=a/result.out other=A/result.out; fi
    body="$(jq -cn --arg first "$first" --arg other "$other" --arg one "$(sha first)" --arg two "$(sha other)" \
      '[{path: $first, bytes: 5, sha256: $one}, {path: $other, bytes: 5, sha256: $two}]')" ;;
  literal-name)
    body="$(jq -cn --arg sha "$(sha first)" '[{path: "names/a b\t%#\n", bytes: 5, sha256: $sha}]')" ;;
  *) body='[{"path":"preparation/archive.zip","bytes":8},{"path":"job.log","bytes":4}]' ;;
  esac
  listings="$(grep -c '^GET /jobs/1/files$' "$FAKE_CALLS")"
  if [[ "$mode" == inventory-unavailable || ( "$mode" == inventory-loading && "$listings" -le 2 ) ]]; then
    status=503 body='{"error":"complete file inventory is being prepared"}'
  fi
elif [[ "$path" == *archive.zip ]]; then
  body=complete
  [[ "$mode" == bad-hash ]] && body=incorrec
  [[ "$mode" == short || "$mode" == transport ]] && body=par
  [[ "$mode" == transport || "$mode" == full-body-error ]] && code=16
  [[ "$mode" == http ]] && status=404 body="not found"
elif [[ "$mode" =~ ^(case-names|aliased-directories|literal-name)$ && "$path" == */files/* ]]; then
  if [[ "$path" == */a.out || "$path" == */A/result.out ]]; then body=other; else body=first; fi
elif [[ "$path" == *job.log ]]; then
  body=$'log\n'
elif [[ "$path" == *cases.jsonl ]]; then
  body=$'{}\n'
  [[ "$mode" == cases-error ]] && body='{' code=16
else
  echo "unexpected test URL" >&2
  exit 1
fi
if [[ -n "$output" ]]; then
  [[ "$output" == /dev/null ]] || printf %s "$body" > "$output"
else
  printf %s "$body"
fi
[[ -z "$write" ]] || printf %s "$status"
exit "$code"
EOF
  chmod 755 "$root/bin/curl"
}
# fetch <mode> [VARIABLE=value...]: fetch job 1 into $dest; its error output in $root/err.
fetch() {
  local mode="$1"
  shift
  env PATH="$root/bin:$PATH" LAB_PASSWORD=fixture LAB_URL=https://fixture.invalid FAKE_MODE="$mode" \
    FAKE_CALLS="$root/calls" "$@" bash "$deploy/lab" fetch 1 "$dest" > "$root/out" 2> "$root/err"
}
released() { check "releases the hold" grep -qxF "DELETE /jobs/1/hold/ab12" "$root/calls"; }
fails() { local status="$1"; shift; ((status != 0)) || { echo "  failed: $* succeeded" >&2; exit 1; }; }
succeeds() { local status="$1"; shift; ((status == 0)) || { cat "$root/err" >&2; echo "  failed: $*" >&2; exit 1; }; }
missing() { jq -r ".missing[0].$1" "$dest/lab-fetch-missing.json"; }  # missing <field>: the first missing download's

test_loading_inventory_is_retried_before_fetching_the_complete_array() {
  setup
  fetch inventory-loading
  succeeds $? "fetch"
  equal 3 "$(grep -c '^GET /jobs/1/files$' "$root/calls")" "inventory requests"
  equal complete "$(cat "$dest/preparation/archive.zip")" "archive"
  released
}

test_unavailable_inventory_has_a_bounded_retry_and_never_claims_empty_success() {
  setup
  fetch inventory-unavailable LAB_FILE_LIST_TIMEOUT=0
  fails $? "fetch"
  check "downloads nothing" test ! -e "$dest/preparation/archive.zip"
  check "explains" grep -q "complete file inventory unavailable" "$root/err"
  released
}

test_inventory_long_poll_has_transport_margin_and_respects_remaining_retry_budget() {
  local budget wait timeout
  for budget in none 5 0; do
    setup
    if [[ "$budget" == none ]]; then fetch success; else fetch success LAB_FILE_LIST_TIMEOUT="$budget"; fi
    succeeds $? "fetch with budget $budget"
    equal 1 "$(wc -l < "$root/inventory-options" | tr -d ' ')" "inventory requests with budget $budget"
    read -r wait timeout < "$root/inventory-options"
    check "waits 1..30 seconds with budget $budget" test "$wait" -ge 1 -a "$wait" -le 30
    equal $((wait + 2)) "$timeout" "transport margin with budget $budget"
    case "$budget" in
    none) equal 30 "$wait" "the default wait" ;;
    5) check "the transport timeout fits the budget" test "$timeout" -le 5 ;;
    0) equal 1 "$wait" "zero disables retries but retains one bounded initial attempt" ;;
    esac
    rm -rf "$root"
  done
}

test_transport_failure_and_short_http_200_body_are_incomplete_and_resumable() {
  local mode
  for mode in transport short full-body-error http; do
    setup
    fetch "$mode"
    fails $? "fetch $mode"
    check "$mode publishes no archive" test ! -e "$dest/preparation/archive.zip"
    check "$mode leaves no partial archive" test ! -e "$dest/preparation/archive.zip.part"
    equal $'log\n.' "$(cat "$dest/job.log"; printf .)" "$mode still fetches the log"
    check "$mode writes a receipt" test -s "$dest/lab-fetch-missing.json"
    equal preparation/archive.zip "$(missing path)" "$mode missing path"
    equal 8 "$(missing bytes)" "$mode missing bytes"
    case "$mode" in transport | full-body-error) equal 16 "$(missing curlExit)" "$mode curl exit" ;;
    *) equal 0 "$(missing curlExit)" "$mode curl exit" ;; esac
    case "$mode" in short | transport) equal 3 "$(missing receivedBytes)" "$mode received" ;;
    full-body-error) equal 8 "$(missing receivedBytes)" "$mode received" ;;
    *) equal 9 "$(missing receivedBytes)" "$mode received" ;; esac
    released
    fetch success
    succeeds $? "the resumed fetch after $mode"
    equal complete "$(cat "$dest/preparation/archive.zip")" "the resumed archive after $mode"
    check "the resumed fetch after $mode removes the receipt" test ! -e "$dest/lab-fetch-missing.json"
    rm -rf "$root"
  done
}

test_failed_case_export_also_leaves_an_incomplete_receipt() {
  setup
  fetch cases-error
  fails $? "fetch"
  check "publishes no case export" test ! -e "$dest/lab-cases.jsonl"
  check "leaves no partial case export" test ! -e "$dest/lab-cases.jsonl.part"
  check "writes a receipt" test -s "$dest/lab-fetch-missing.json"
  equal lab-cases.jsonl "$(missing path)" "missing path"
  equal null "$(missing bytes)" "missing bytes"
  equal 16 "$(missing curlExit)" "curl exit"
  released
}

test_checksum_replaces_same_size_corrupt_resume_and_rejects_wrong_download() {
  setup
  mkdir -p "$dest/preparation"
  printf incorrec > "$dest/preparation/archive.zip"
  fetch hashed
  succeeds $? "fetch"
  equal complete "$(cat "$dest/preparation/archive.zip")" "the replaced archive"
  rm "$dest/preparation/archive.zip"
  fetch bad-hash
  fails $? "fetch of a wrong download"
  check "publishes no wrong archive" test ! -e "$dest/preparation/archive.zip"
  equal checksum-mismatch "$(missing reason)" "reason"
  equal "$(sha complete)" "$(missing expectedSha256)" "expected checksum"
  equal "$(sha incorrec)" "$(missing receivedSha256)" "received checksum"
  equal "" "$(find "$dest" -name '.lab-fetch-*')" "temporary files"
  released
}

test_case_variants_are_preserved_or_destination_is_rejected_before_overwrite() {
  setup
  : > "$root/probe"
  fetch case-names
  local status=$?
  if [[ -e "$root/PROBE" ]]; then
    fails "$status" "fetch into a case-insensitive destination"
    check "writes neither name" test ! -e "$dest/A.out"
    equal destination-case-collision "$(missing reason)" "reason"
  else
    succeeds "$status" "fetch"
    equal first "$(cat "$dest/A.out")" "A.out"
    equal other "$(cat "$dest/a.out")" "a.out"
  fi
  equal "" "$(find "$dest" -maxdepth 1 -name '.lab-fetch-*')" "temporary files"
  released
}

test_final_audit_detects_later_write_through_an_existing_directory_alias() {
  setup
  mkdir -p "$dest/a"
  if [[ -e "$dest/A" ]]; then echo "  skipped: case-insensitive filesystem is covered by the preflight test"; return; fi
  ln -s a "$dest/A"
  fetch aliased-directories
  fails $? "fetch"
  check "reports the overwritten download" jq -e \
    'any(.missing[]; .path == "a/result.out" and .reason == "retained-checksum-mismatch")' \
    "$dest/lab-fetch-missing.json" > /dev/null
}

test_literal_filename_survives_listing_and_component_url_encoding() {
  setup
  fetch literal-name
  succeeds $? "fetch"
  equal first "$(cat "$dest/names/a b"$'\t''%#'$'\n')" "the literal file"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
