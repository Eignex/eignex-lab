#!/usr/bin/env bash
# Tests of deploy/host-maintenance.sh and deploy/public-address.sh. Needs zsh (for the Gradle lock), gzip and jq.
# bash deploy/tests/test_host_maintenance.sh [test name...]
# shellcheck disable=SC1091,SC2012,SC2016,SC2329 # sourced scripts; stubs and child scripts the tests call indirectly
set -uo pipefail
deploy="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$deploy/host-maintenance.sh"
. "$deploy/public-address.sh"
set +e

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}
inode_of() { ls -di "$1" | awk '{ print $1 }'; }
at() { TZ=UTC touch -t "$1" "${@:2}"; }  # at <[[CC]YY]MMDDhhmm[.SS]> <file...>, both access and modification time

setup_home() {
  root="$(mktemp -d)"
  root="$(cd "$root" && pwd -P)"
  home="$root"
  logs="$home/daemon/9.7.0"
  mkdir -p "$logs"
  cache="$home/caches/build-cache-1"
  mkdir -p "$cache"
  lock="$cache/build-cache-1.lock"
  printf 'Gradle lock protocol must remain unchanged' > "$lock"
}
clean() {  # clean [active pids...]: the home's report with a 10 byte cache, 8 byte logs, 3 copies and 7 days
  local active=" "
  for pid in "$@"; do active+="$pid "; done
  clean_home "$home" "$active" 10 8 3 7 "$(date +%s)"
}

test_rotation_keeps_three_bounded_tails_and_preserves_inode() {
  setup_home
  local path="$logs/daemon-123.out.log" inode tail index expected
  : > "$path"
  inode="$(inode_of "$path")"
  for tail in 11111111 22222222 33333333 44444444; do
    printf '%s' "discard old content$tail" > "$path"
    equal 1 "$(clean | jq .rotated)" "rotated once"
  done
  equal "$inode" "$(inode_of "$path")" "same inode"
  equal 0 "$(size_of "$path")" "truncated"
  index=1
  for expected in 44444444 33333333 22222222; do
    equal "$expected" "$(gzip -dc "$path.$index.gz")" "tail $index"
    index=$((index + 1))
  done
  check "no fourth tail" test ! -e "$path.4.gz"
  check "no temporary left" test -z "$(find "$logs" -name '.log-rotation-*')"
}

test_active_daemon_keeps_its_open_log_and_offset() {
  setup_home
  local path="$logs/daemon-123.out.log"
  exec 3> "$path"
  printf 'active log must not be truncated' >&3
  equal 1 "$(clean 123 | jq .activeLogsSkipped)" "active log skipped"
  printf ' next line' >&3
  exec 3>&-
  equal "active log must not be truncated next line" "$(cat "$path")" "log untouched"
}

test_expiry_preserves_active_logs_and_registry() {
  setup_home
  local name
  for name in daemon-123.out.log daemon-456.out.log daemon-456.out.log.1.gz registry.bin; do
    printf old > "$logs/$name"
    at 197001010000 "$logs/$name"
  done
  equal 2 "$(clean 123 | jq .expiredLogs)" "expired logs"
  check "active log kept" test -e "$logs/daemon-123.out.log"
  check "registry kept" test -e "$logs/registry.bin"
}

test_eviction_keeps_newest_outputs_and_never_removes_metadata_or_links() {
  setup_home
  local name minute=1 report
  for name in a b c; do
    printf 12345 > "$cache/$name"
    at "19700101000$minute" "$cache/$name"
    minute=$((minute + 1))
  done
  printf metadata > "$cache/gc.properties"
  printf preserved > "$home/outside"
  ln -s "$home/outside" "$cache/d"
  ln -s "$home/outside" "$logs/daemon-789.out.log"
  report="$(clean)"
  equal 5 "$(jq .cacheBytesRemoved <<< "$report")" "bytes removed"
  equal '{"path":"'"$cache"'","bytes":10,"limitBytes":10}' "$(jq -c '.caches[0]' <<< "$report")" "cache report"
  check "oldest output removed" test ! -e "$cache/a"
  check "newer output kept" test -e "$cache/b"
  equal metadata "$(cat "$cache/gc.properties")" "metadata kept"
  equal "Gradle lock protocol must remain unchanged" "$(cat "$lock")" "lock unchanged"
  equal preserved "$(cat "$home/outside")" "link target kept"
}

test_locked_cache_is_skipped_until_other_process_releases_it() {
  setup_home
  local holder ready="$root/ready" waited=0 report
  head -c 20 /dev/zero > "$cache/a"
  # Another process holds Gradle's lock as Gradle does, with fcntl.
  with_lock "$lock" sh -c 'echo $$ > "$1.tmp" && mv "$1.tmp" "$1" && exec sleep 30' sh "$ready" &
  holder=$!
  while [[ ! -s "$ready" ]] && (( waited < 100 )); do sleep 0.1; waited=$((waited + 1)); done
  check "holder took the lock" test -s "$ready"
  report="$(clean)"
  equal '"in use"' "$(jq '.caches[0].skipped' <<< "$report")" "cache skipped"
  check "output kept" test -e "$cache/a"
  kill "$holder" "$(cat "$ready")"
  wait "$holder" 2>/dev/null
  equal 20 "$(clean | jq .cacheBytesRemoved)" "evicted after release"
}

test_missing_lock_never_creates_a_gradle_lock_or_deletes_outputs() {
  setup_home
  rm "$lock"
  head -c 20 /dev/zero > "$cache/a"
  local report
  report="$(clean)"
  equal 0 "$(jq .cacheBytesRemoved <<< "$report")" "nothing removed"
  equal '"missing regular Gradle lock"' "$(jq '.caches[0].skipped' <<< "$report")" "cache skipped"
  check "no lock created" test ! -e "$lock"
}

test_service_logs_are_bounded_in_place_and_removed_services_logs_expire() {
  setup_home
  local service="$root/logs" inode report
  mkdir "$service"
  head -c 20 /dev/zero | tr '\0' x > "$service/runner.log"
  inode="$(inode_of "$service/runner.log")"
  printf old > "$service/relay.log"
  printf tail > "$service/relay.log.1.gz"
  at 200101010000 "$service/relay.log"
  report="$(clean_service_logs "$service" 8 3 7 "$(date +%s)")"
  equal '["runner.log"]' "$(jq -c .rotated <<< "$report")" "rotated"
  equal '["relay.log","relay.log.1.gz"]' "$(jq -c .removed <<< "$report")" "removed"
  equal "$inode" "$(inode_of "$service/runner.log")" "same inode"
  equal 0 "$(size_of "$service/runner.log")" "truncated"
  equal xxxxxxxx "$(gzip -dc "$service/runner.log.1.gz")" "tail"
  equal "runner.log runner.log.1.gz" "$(cd "$service" && echo *)" "remaining logs"
}

test_report_follows_the_python_layout() {
  equal '{"a": [], "b": {}, "c": [1, {"d": "x,y:[z]\"{"}], "e": {"f": 2}}' \
    "$(json_format compact <<< '{"a":[],"b":{},"c":[1,{"d":"x,y:[z]\"{"}],"e":{"f":2}}')" "compact"
  equal $'{\n  "a": [],\n  "c": [\n    1,\n    {\n      "d": "x"\n    }\n  ]\n}' \
    "$(json_format indent <<< '{"a":[],"c":[1,{"d":"x"}]}')" "indented"
  equal '"a\\b\"c\nd\te"' "$(json_string $'a\\b"c\nd\te')" "escaped"
}

test_maintenance_writes_its_report_and_skips_while_another_run_holds_the_lock() {
  setup_home
  local data="$root/data" out status report
  mkdir -p "$data/logs"
  printf 12345 > "$cache/a"
  out="$(bash "$deploy/host-maintenance.sh" --data "$data" --gradle-home "$home" --gradle-home "$home/." --quiet \
    --cache-gib 0.000000001 --dns-settings "$root/none.json")"
  status=$?
  equal 0 "$status" "exit status"
  equal "" "$out" "quiet"
  report="$(cat "$data/maintenance/gradle-maintenance.json")"
  equal '["'"$home"'"]' "$(jq -c '[.homes[].home]' <<< "$report")" "one resolved home"
  equal 5 "$(jq '.homes[0].cacheBytesRemoved' <<< "$report")" "evicted"
  equal '[]' "$(jq -c .errors <<< "$report")" "no errors"
  equal "$data/logs" "$(jq -r .logs.path <<< "$report")" "service logs"
  check "indented report" grep -q '^  "homes": \[$' "$data/maintenance/gradle-maintenance.json"
  with_lock "$data/maintenance/gradle-maintenance.lock" bash "$deploy/host-maintenance.sh" --data "$data" \
    --gradle-home "$home" > "$root/skipped"
  equal '{"skipped": "maintenance already running"}' "$(cat "$root/skipped")" "skipped while locked"
  bash "$deploy/host-maintenance.sh" --data "$data" --cache-gib 0 2> "$root/usage"
  equal 2 "$?" "rejects non-positive limits"
  check "explains" grep -q 'retention limits must be positive' "$root/usage"
}

test_maintenance_reports_errors_and_fails() {
  setup_home
  (( $(id -u) != 0 )) || return 0  # root reads anything
  local data="$root/data" out status
  printf 'x%.0s' {1..20} > "$logs/daemon-1.out.log"
  chmod 000 "$logs/daemon-1.out.log"
  out="$(bash "$deploy/host-maintenance.sh" --data "$data" --gradle-home "$home" --quiet --log-mib 0.000001 \
    --dns-settings "$root/none.json")"
  status=$?
  chmod 600 "$logs/daemon-1.out.log"
  equal 1 "$status" "exit status"
  equal "$home" "$(jq -r '.errors[0].home' <<< "$out")" "error names the home"
  check "error has a reason" test -n "$(jq -r '.errors[0].error' <<< "$out")"
  equal '{"at": ' "${out:0:7}" "printed in Python's layout"
}

setup_address() {
  root="$(mktemp -d)"
  data="$root/data"
  mkdir -p "$data/nomad"
  server="$data/nomad/server.json"
  printf '%s' '{"advertise": {"http": "127.0.0.1:4646", "rpc": "31.208.146.176:4647", "serf": "10.44.0.2:4648"}}' \
    > "$server"
  chmod 600 "$server"
  settings="$root/dynamic-dns.json"
  printf '%s' '{"token": "t", "domain": "eignex.com", "name": "lab"}' > "$settings"
  calls="$root/calls" restarts="$root/restarts"
  : > "$calls"
  : > "$restarts"
  record="31.208.146.176"
  restart_nomad_agents() { echo "$1" >> "$restarts"; }
  digitalocean() {
    echo "$2 $3 ${4:-}" >> "$calls"
    if [[ "$2" == GET ]]; then echo '{"domain_records": [{"id": 7, "data": "'"$record"'"}]}'; else echo '{}'; fi
  }
}

test_a_changed_address_updates_dns_and_the_advertised_server_address() {
  setup_address
  public_ip() { echo 31.208.146.145; }
  local report
  report="$(follow_public_ip "$data" "$settings")"
  equal 0 "$?" "status"
  equal 'PUT domains/eignex.com/records/7 {"data":"31.208.146.145"}' "$(tail -n 1 "$calls")" "record updated"
  equal "31.208.146.145:4647" "$(jq -r .advertise.rpc "$server")" "server advertises the new address"
  equal "10.44.0.2:4648" "$(jq -r .advertise.serf "$server")" "serf kept"
  equal $'{\n  "advertise": {\n    "http": "127.0.0.1:4646",\n    "rpc": "31.208.146.145:4647",\n    "serf": "10.44.0.2:4648"\n  }\n}' \
    "$(cat "$server")" "rewritten as indented JSON"
  equal 600 "$(mode_of "$server" | sed 's/^0*//')" "mode kept"
  equal "$data/nomad" "$(cat "$restarts")" "agents restarted"
  equal '{"ip":"31.208.146.145","dns":{"from":"31.208.146.176","to":"31.208.146.145"},"nomad":{"from":"31.208.146.176:4647","to":"31.208.146.145:4647"}}' \
    "$report" "report"
}

test_an_unchanged_address_touches_nothing() {
  setup_address
  public_ip() { echo 31.208.146.176; }
  local before report
  before="$(cat "$server")"
  report="$(follow_public_ip "$data" "$settings")"
  equal GET "$(awk '{ print $1 }' "$calls")" "only read the record"
  equal "$before" "$(cat "$server")" "server unchanged"
  equal "" "$(cat "$restarts")" "no restarts"
  equal '{"ip":"31.208.146.176"}' "$report" "report"
}

test_an_ambiguous_record_is_refused() {
  setup_address
  public_ip() { echo 31.208.146.145; }
  digitalocean() { echo '{"domain_records": [{"id": 1, "data": "a"}, {"id": 2, "data": "b"}]}'; }
  local status
  ( follow_public_ip "$data" "$settings" ) > /dev/null 2> "$root/reason"
  status=$?
  equal 1 "$status" "refused"
  equal "expected one A record for lab.eignex.com, found 2" "$(cat "$root/reason")" "reason"
  equal "" "$(cat "$restarts")" "no restarts"
}

test_resolver_answers_must_be_one_ipv4_address() {
  root="$(mktemp -d)"
  mkdir "$root/bin"
  local answer
  for answer in "1.2.3.4" "256.1.1.1" "1.2.3.4 5.6.7.8" ";; connection timed out"; do
    printf '#!/bin/sh\nprintf "%%s\\n" "%s"\n' "$answer" > "$root/bin/dig"
    chmod +x "$root/bin/dig"
    if [[ "$answer" == 1.2.3.4 ]]; then
      equal 1.2.3.4 "$(PATH="$root/bin:$PATH" public_ip)" "accepted $answer"
    else
      if ( PATH="$root/bin:$PATH" public_ip ) > /dev/null 2>&1; then echo "  failed: accepted $answer" >&2; exit 1; fi
    fi
  done
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
