#!/usr/bin/env bash
# Tests of deploy/worker-rollout.sh with curl and pkill stubbed. Needs jq.
# bash deploy/tests/test_worker_rollout.sh [test name...]
# shellcheck disable=SC1091,SC2016,SC2329 # sourced script; stubs and tests called indirectly
set -uo pipefail
deploy="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$deploy/worker-rollout.sh"
set +e

OLD=1111111111111111111111111111111111111111
NEW=2222222222222222222222222222222222222222
AMD=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
ARM=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}

# setup [published tags...]: a lab data directory on release OLD with releases/current at NEW, and curl answering for
# the given tags (others 404); pkill records its calls in $root/pkill.
setup() {
  root="$(mktemp -d)"
  data="$root/data"
  mkdir -p "$data/nomad" "$data/releases/$NEW-build/bin" "$root/bin"
  printf '#!/bin/sh\n' > "$data/releases/$NEW-build/bin/eignex-lab"
  chmod +x "$data/releases/$NEW-build/bin/eignex-lab"
  echo "$NEW" > "$data/releases/$NEW-build/COMMIT"
  ln -s "$NEW-build" "$data/releases/current"
  cat > "$data/nomad/queue.json" <<EOF
{"release": "$OLD", "workerReplaces": ["0000000000000000000000000000000000000000"], "targets": [
  {"tags": ["arch:x86_64"], "template": {"nodeClass": "avx2-spot", "image": "old-amd"}},
  {"tags": ["arch:arm64", "simd:neon"], "template": {"nodeClass": "graviton", "image": "old-arm"}},
  {"tags": ["arch:arm64", "local"], "template": {"platform": "mac", "command": ["/old/bin/eignex-lab", "worker"],
    "environment": {"LAB_WORKER_LAUNCHER": "/old/bin/eignex-lab", "OTHER": "kept"}}}]}
EOF
  echo '{"workerLauncher": "/old/bin/eignex-lab", "labEnvironment": {"X": "1"}}' > "$data/nomad/native-services.json"
  chmod 640 "$data/nomad/queue.json"
  local published=" $* "
  cat > "$root/bin/curl" <<EOF
#!/bin/bash
url="\${*: -1}"
case "\$url" in
*/token/*) echo '{"token":"t"}' ;;
*/manifests/*)
  tag="\${url##*/}"
  if [[ "$published" == *" \$tag "* ]]; then
    printf 'HTTP/2 200\r\ndocker-content-digest: %s\r\n\r\n' "\$([[ \$tag == *-amd64 ]] && echo $AMD || echo $ARM)"
  else printf 'HTTP/2 404\r\n\r\n'; fi ;;
esac
EOF
  printf '#!/bin/sh\necho "$*" >> "%s/pkill"\n' "$root" > "$root/bin/pkill"
  chmod +x "$root/bin/curl" "$root/bin/pkill"
  PATH="$root/bin:$PATH"
}

test_rolls_images_by_arch_launcher_and_replaces() {
  setup "$NEW-amd64" "$NEW-arm64"
  out="$(main --data "$data")" || { echo "  failed: main exited $?" >&2; exit 1; }
  equal true "$(jq -r .rolled <<< "$out")" "reported a rollout"
  q="$data/nomad/queue.json"
  equal "$NEW" "$(jq -r .release "$q")" "release"
  equal "public.ecr.aws/i7d5w3s6/eignex-lab-worker@$AMD" "$(jq -r '.targets[0].template.image' "$q")" "x86_64 image"
  equal "public.ecr.aws/i7d5w3s6/eignex-lab-worker@$ARM" "$(jq -r '.targets[1].template.image' "$q")" "arm64 image"
  launcher="$data/releases/$NEW-build/bin/eignex-lab"
  equal "$launcher" "$(jq -r '.targets[2].template.command[0]' "$q")" "native command"
  equal "$launcher" "$(jq -r '.targets[2].template.environment.LAB_WORKER_LAUNCHER' "$q")" "native launcher env"
  equal kept "$(jq -r '.targets[2].template.environment.OTHER' "$q")" "other environment kept"
  equal "null" "$(jq -r '.targets[2].template.image' "$q")" "native target gets no image"
  equal "0000000000000000000000000000000000000000 $OLD" "$(jq -r '.workerReplaces | join(" ")' "$q")" "old release replaced once"
  equal "$launcher" "$(jq -r .workerLauncher "$data/nomad/native-services.json")" "native settings launcher"
  equal 1 "$(jq -r .labEnvironment.X "$data/nomad/native-services.json")" "native settings kept"
  ops="$(jq -r .operations <<< "$out")"
  equal "$OLD" "$(jq -r .release "$ops/queue.json.before")" "old queue kept"
  equal "$NEW" "$(jq -r .to "$ops/receipt.json")" "receipt"
  [[ -s "$root/pkill" ]] || { echo "  failed: coordinator not restarted" >&2; exit 1; }
  equal "640" "$(stat -c %a "$q" 2>/dev/null || stat -f %Lp "$q")" "queue mode kept"
  [[ ! -e "$data/nomad/.worker-rollout.lock" ]] || { echo "  failed: lock left behind" >&2; exit 1; }
  # A second pass finds the workers on the release and changes nothing.
  equal "workers already on ${NEW:0:8}" "$(main --data "$data" | jq -r .reason)" "second pass"
}

test_waits_until_both_images_are_published() {
  setup "$NEW-amd64"
  before="$(cat "$data/nomad/queue.json")"
  equal "waiting for ${NEW:0:8}'s worker images" "$(main --data "$data" | jq -r .reason)" "waits"
  equal "$before" "$(cat "$data/nomad/queue.json")" "queue unchanged"
  [[ ! -e "$root/pkill" ]] || { echo "  failed: restarted while waiting" >&2; exit 1; }
}

test_dry_run_changes_nothing() {
  setup "$NEW-amd64" "$NEW-arm64"
  before="$(cat "$data/nomad/queue.json")"
  equal true "$(main --data "$data" --dry-run | jq -r .dryRun)" "dry run reported"
  equal "$before" "$(cat "$data/nomad/queue.json")" "queue unchanged"
  [[ ! -e "$root/pkill" ]] || { echo "  failed: dry run restarted" >&2; exit 1; }
}

test_unknown_arch_fails_without_change() {
  setup "$NEW-amd64" "$NEW-arm64"
  jq '.targets[0].tags = ["arch:riscv64"]' "$data/nomad/queue.json" > "$root/q" && cat "$root/q" > "$data/nomad/queue.json"
  before="$(cat "$data/nomad/queue.json")"
  if main --data "$data" > /dev/null 2>&1; then echo "  failed: accepted an unknown arch" >&2; exit 1; fi
  equal "$before" "$(cat "$data/nomad/queue.json")" "queue unchanged"
  [[ ! -e "$data/nomad/.worker-rollout.lock" ]] || { echo "  failed: lock left behind" >&2; exit 1; }
}

test_release_without_commit_is_left_alone() {
  setup "$NEW-amd64" "$NEW-arm64"
  rm "$data/releases/$NEW-build/COMMIT"
  equal "release $NEW-build records no commit" "$(main --data "$data" | jq -r .reason)" "no commit"
}

test_running_rollout_is_not_entered() {
  setup "$NEW-amd64" "$NEW-arm64"
  mkdir "$data/nomad/.worker-rollout.lock"
  equal "another rollout is running" "$(main --data "$data" | jq -r .reason)" "locked"
  equal "$OLD" "$(jq -r .release "$data/nomad/queue.json")" "queue unchanged"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
