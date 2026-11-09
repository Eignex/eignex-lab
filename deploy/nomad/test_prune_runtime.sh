#!/usr/bin/env bash
# Tests of deploy/nomad/prune-runtime.sh. Needs jq and sqlite3.
# bash deploy/nomad/test_prune_runtime.sh [test name...]
set -uo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/prune-runtime.sh"

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
}
prune() {  # prune <processes> <services> [--apply]: the report in $root/report
  LAB_PRUNE_PROCESSES="$1" LAB_PRUNE_SERVICES="$2" bash "$script" --data "$root" ${3:+"$3"} > "$root/report"
}

test_keeps_process_service_symlink_session_and_retry_pins_without_touching_results() {
  setup
  local releases="$root/releases" name
  for name in current process service session retry unused; do mkdir -p "$releases/$name/bin"; done
  ln -s current "$releases/selected"
  mkdir -p "$root/nomad" "$root/jobs/123"
  printf 'historical result' > "$root/jobs/123/record.json"
  printf 'unused configuration' > "$root/nomad/queue-before-fixture.json"
  cat > "$root/service.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>ProgramArguments</key>
	<array>
		<string>$releases/service/bin/lab</string>
	</array>
</dict>
</plist>
EOF
  check "creates the database" sqlite3 "$root/lab.db" "
    CREATE TABLE jobs(id INTEGER, status TEXT);
    CREATE TABLE execution_sessions(id TEXT, job_id INTEGER, desired TEXT);
    CREATE TABLE execution_outbox(session_id TEXT, payload TEXT, acknowledged INTEGER);
    CREATE TABLE execution_queue_recipes(job_id INTEGER, settings TEXT);
    INSERT INTO jobs VALUES (1, 'RUNNING'), (2, 'FAILED');
    INSERT INTO execution_sessions VALUES ('live', 1, 'run');
    INSERT INTO execution_outbox VALUES ('live', '{\"command\": \"$releases/session/bin/lab\"}', 1);
    INSERT INTO execution_queue_recipes VALUES (2, '{\"command\": \"$releases/retry/bin/lab\"}');"
  check "applies" prune "$releases/process/bin/lab" "$root/service.plist" --apply
  equal "[\"$releases/unused\",\"$root/nomad/queue-before-fixture.json\"]" "$(jq -c .deleted "$root/report")" "deleted"
  equal '["current","process","retry","service","session"]' "$(jq -c '.keptReleases | keys' "$root/report")" "kept"
  equal '["release symlink"]' "$(jq -c .keptReleases.current "$root/report")" "why current is kept"
  equal '["frozen active or retryable recipe"]' "$(jq -c .keptReleases.retry "$root/report")" "why retry is kept"
  equal "historical result" "$(cat "$root/jobs/123/record.json")" "job results"
  check "removed the unused release" test ! -e "$releases/unused"
}

test_dry_run_preserves_unused_releases_and_never_follows_release_symlinks() {
  setup
  mkdir -p "$root/releases/unused" "$root/nomad" "$root/historical"
  ln -s "$root/historical" "$root/releases/history-link"
  check "plans" prune "" ""
  equal "[\"$root/releases/unused\"]" "$(jq -c .unusedReleases "$root/report")" "unused releases"
  check "keeps the unused release" test -d "$root/releases/unused"
  check "applies" prune "" "" --apply
  check "keeps the symlinked directory" test -d "$root/historical"
  check "removes the unused release" test ! -e "$root/releases/unused"
}

test_keeps_deployment_provenance_and_referenced_leftovers() {
  setup
  mkdir -p "$root/releases/old" "$root/nomad"
  printf '{"workflowRun": 7, "secret": "x", "revision": "r1"}' > "$root/releases/old/deployment.json"
  printf 'old' > "$root/nomad/lab.env.before-1"
  printf 'old' > "$root/nomad/release-1.zip"
  printf '{"note": "lab.env.before-1"}' > "$root/nomad/queue.json"
  check "applies" prune "" "" --apply
  equal "[\"$root/releases/old\",\"$root/nomad/release-1.zip\"]" "$(jq -c .deleted "$root/report")" "deleted"
  equal '{"revision":"r1","workflowRun":7}' "$(jq -c . "$root/deployments/retired-runtime/old.json")" "provenance"
  check "keeps a referenced leftover" test -f "$root/nomad/lab.env.before-1"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
