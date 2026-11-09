#!/usr/bin/env bash
# Tests of deploy/install.sh, with an isolated home and distribution and no service registration, and of
# deploy/release.sh. Needs git and jq, and plutil on the Mac.
# bash deploy/tests/test_install.sh [test name...]
# shellcheck disable=SC2016 # literal shell text for the stubs
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
}
# without_lab <command...>: the command without any LAB_* variable from the caller.
without_lab() {
  local variable removed=()
  for variable in $(compgen -e); do [[ "$variable" == LAB_* ]] && removed+=(-u "$variable"); done
  env ${removed[@]+"${removed[@]}"} "$@"
}
plist_json() { plutil -convert json -o - "$1"; }  # plist_json <plist>: the plist as JSON

# install <configured data: yes|no>: run install.sh in a dry run and check the service files it writes.
install() {
  setup
  local project="$root/checkout" home="$root/home" name launcher expected units role unit plist
  mkdir -p "$project/deploy" "$home/.config/eignex-lab"
  for name in install.sh release.sh launchd.sh lab.env.example host-maintenance.sh public-address.sh; do
    cp -p "$deploy/$name" "$project/deploy/$name"
  done
  printf 'LAB_DOCKER=none\n' > "$home/.config/eignex-lab/lab.env"
  if [[ "$1" == yes ]]; then printf 'LAB_DATA="%s"\n' "$root/custom-data" >> "$home/.config/eignex-lab/lab.env"; fi
  printf '#!/bin/bash\nif [[ "$*" == *printJavaHome* ]]; then echo /test-jdk; else [[ "$*" == *"clean installDist"* ]] || exit 1; fi\n' \
    > "$project/gradlew"
  chmod 755 "$project/gradlew"
  launcher="$project/build/install/eignex-lab/bin/eignex-lab"
  mkdir -p "$(dirname "$launcher")"
  printf '#!/bin/bash\nprintf "checked data: %%s\\n" "$LAB_DATA"\n' > "$launcher"
  chmod 755 "$launcher"
  without_lab HOME="$home" LAB_INSTALL_DRY_RUN=1 bash "$project/deploy/install.sh" > "$root/out" 2> "$root/err" ||
    { cat "$root/err" >&2; echo "  failed: install.sh" >&2; exit 1; }
  units="$(sed -n 's/^dry run: service files in //p' "$root/out")"
  check "names its service files" test -d "$units"
  if [[ "$1" == yes ]]; then expected="$root/custom-data"; else expected="$home/eignex-lab-data"; fi
  check "checks the host with its data" grep -qF "checked data: $expected" "$root/out"
  for role in api runner; do
    if [[ "$(uname -s)" == Darwin ]]; then
      plist="$units/com.eignex.lab.$role.plist"
      equal "$expected" "$(plist_json "$plist" | jq -r .EnvironmentVariables.LAB_DATA)" "$role LAB_DATA"
      equal 1 "$(grep -c '<key>LAB_DATA</key>' "$plist")" "$role sets LAB_DATA once"
      equal "$expected" "$(plist_json "$plist" | jq -r .WorkingDirectory)" "$role working directory"
      equal "[\"$launcher\",\"$role\"]" "$(plist_json "$plist" | jq -c '.ProgramArguments[-2:]')" "$role command"
    else
      unit="$units/eignex-lab-$role.service"
      check "$role LAB_DATA" grep -qF "Environment=\"LAB_DATA=$expected\"" "$unit"
      equal 1 "$(grep -c 'Environment="LAB_DATA=' "$unit")" "$role sets LAB_DATA once"
      check "$role working directory" grep -qF "WorkingDirectory=$expected" "$unit"
      check "$role command" grep -qF "ExecStart=$launcher $role" "$unit"
    fi
  done
  if [[ "$(uname -s)" == Darwin ]]; then
    plist="$(plist_json "$units/com.eignex.lab.gradle-maintenance.plist")"
    equal 300 "$(jq .StartInterval <<< "$plist")" "maintenance interval"
    equal false "$(jq .KeepAlive <<< "$plist")" "maintenance is not kept alive"
    equal true "$(jq .RunAtLoad <<< "$plist")" "maintenance runs at load"
    check "maintenance runs an existing script" test -f "$(jq -r '.ProgramArguments[1]' <<< "$plist")"
    equal "$(jq -cn --arg script "$project/deploy/host-maintenance.sh" --arg data "$expected" \
      '["/bin/bash", $script, "--data", $data, "--quiet", "--cache-gib", "2", "--log-mib", "20"]')" \
      "$(jq -c .ProgramArguments <<< "$plist")" "maintenance command"
  fi
  rm -rf "$units"
}

test_default_data_is_exported_to_host_check_and_services() { install no; }

test_explicit_data_is_preserved_without_duplicate_environment() { install yes; }

test_queue_settings_retain_immutable_releases_even_without_native_service_files() {
  setup
  local project="$root/checkout" data="$root/data" index release
  check "creates a repository" git init -q "$project"
  check "commits" git -C "$project" -c user.name=Fixture -c user.email=fixture@example.com commit --allow-empty -qm "test: fixture"
  mkdir -p "$project/build/install/eignex-lab/bin"
  printf fixture > "$project/build/install/eignex-lab/bin/eignex-lab"
  for index in 0 1 2 3; do
    mkdir -p "$data/releases/previous-$index"
    TZ=UTC touch -t "20000101000$index" "$data/releases/previous-$index"
  done
  release="$(without_lab LAB_NOMAD_QUEUE_SETTINGS="$root/queue.json" bash -c '. "$1"; release "$2" "$3"' fixture \
    "$deploy/release.sh" "$project" "$data")" || { echo "  failed: release" >&2; exit 1; }
  check "the release is a file" test -f "$release"
  for index in 0 1 2 3; do check "keeps previous-$index" test -d "$data/releases/previous-$index"; done
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
