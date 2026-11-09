#!/usr/bin/env bash
# Tests of deploy/nomad/native-services.sh, deploy/launchd.sh and deploy/nomad/hashicorp.sh. Needs jq, zip and unzip.
# bash deploy/nomad/test_native_services.sh [test name...]
# shellcheck disable=SC1091,SC2016,SC2119,SC2120 # sourced libraries; literal shell text in expectations and stubs
set -uo pipefail
nomad="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$nomad/native-services.sh"
. "$nomad/../launchd.sh"
. "$nomad/hashicorp.sh"

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}
# plist_strings <plist> <key>: the strings under a top-level key, one per line.
plist_strings() {
  awk -v key="<key>$2</key>" '$0 == "\t" key { inside = 1; next }
    inside == 1 && /^\t<(array|dict)>$/ { inside = 2; next }
    inside == 1 { sub(/^\t<[a-z]+>/, ""); sub(/<\/[a-z]+>$/, ""); print; exit }
    inside == 2 && /^\t<\/(array|dict)>$/ { exit }
    inside == 2 { sub(/^\t\t<(string|key)>/, ""); sub(/<\/(string|key)>$/, ""); print }' "$1"
}

tls='{"http": true, "rpc": true, "verify_server_hostname": true, "verify_https_client": true,
  "ca_file": "/private/ca", "cert_file": "/private/cert", "key_file": "/private/key"}'
setup() {
  [[ -z "${root:-}" ]] || rm -rf "$root"
  root="$(mktemp -d)"
  root="$(cd "$root" && pwd -P)"
  jq -n --argjson tls "$tls" --arg root "$root" \
    '{data_dir: ($root + "/server"), acl: {enabled: true}, tls: $tls, server: {enabled: true, bootstrap_expect: 1}}' \
    > "$root/server.json"
  jq -n --argjson tls "$tls" --arg root "$root" \
    '{data_dir: ($root + "/client"), acl: {enabled: true}, tls: $tls,
      client: {enabled: true, servers: ["10.0.0.1:4647"], reserved: {cpu: 500, memory: 512}},
      plugin: [{raw_exec: {config: [{enabled: true}]}}]}' > "$root/client.json"
  jq -n --arg root "$root" '{dataDir: $root, labLauncher: "/lab/releases/current/bin/eignex-lab",
    workerLauncher: "/lab/releases/pinned/bin/eignex-lab", nomadBinary: "/lab/nomad-2.0.7", supervisorLauncher: "/lab/releases/supervisor/bin/eignex-lab",
    supervisorSocket: ($root + "/process.sock"), localLock: ($root + "/native.lock"), home: "/Users/lab",
    path: "/usr/bin:/bin", javaHome: "/lab/jdk25", serverConfig: ($root + "/server.json"),
    clientConfig: ($root + "/client.json"), coordinatorConfig: ($root + "/coordinator.json"), user: "lab",
    labEnvironment: {LAB_JOBS: "/Volumes/lab/jobs"}}' > "$root/settings.json"
}
edit() {  # edit <file> <jq filter>
  jq "$2" "$1" > "$1.new" && mv "$1.new" "$1"
}
render() {  # render: the rendered plists in $root/out; the status is the script's
  rm -rf "$root/out"
  bash "$script" --config "$root/settings.json" --output "$root/out" > "$root/rendered" 2>&1
}

test_four_independent_services_and_a_pinned_supervisor_release() {
  setup
  check "renders" render
  equal "com.eignex.lab.nomad-client.plist com.eignex.lab.nomad-coordinator.plist com.eignex.lab.nomad-server.plist com.eignex.lab.process-supervisor.plist" \
    "$(cd "$root/out" && echo *)" "four services"
  local plist label expected
  for plist in "$root/out"/*.plist; do
    label="$(basename "$plist" .plist)"
    equal "$label" "$(plist_strings "$plist" Label)" "label of $label"
    check "$label never runs a dev agent" test -z "$(plist_strings "$plist" ProgramArguments | grep -x -- -dev)"
    if [[ "$label" != *nomad-coordinator ]]; then
      expected="HOME JAVA_HOME PATH"
      [[ "$label" != *process-supervisor ]] || expected="EIGNEX_LAB_OPTS HOME JAVA_HOME PATH"
      equal "$expected" "$(plist_strings "$plist" EnvironmentVariables | sed -n 'p;n' | xargs)" \
        "$label inherits no lab configuration"
    fi
  done
  equal "/bin/sh|-c|exec \"\$0\" \"\$@\"|/lab/releases/supervisor/bin/eignex-lab|process-supervisor|--socket|$root/process.sock|--local-lock|$root/native.lock" \
    "$(plist_strings "$root/out/com.eignex.lab.process-supervisor.plist" ProgramArguments | paste -sd'|' -)" \
    "supervisor runs from its pinned release with the ownership lock"
  equal "-Xms4m -Xmx48m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1" \
    "$(plist_strings "$root/out/com.eignex.lab.process-supervisor.plist" EnvironmentVariables | sed -n '/^EIGNEX_LAB_OPTS$/{n;p;}')" \
    "the supervisor's small JVM"
  check "renders and registers nothing" grep -q "registered nothing" "$root/rendered"
}

test_rejects_unversioned_worker_and_conflicting_lock() {
  setup
  edit "$root/settings.json" '.workerLauncher = .labLauncher'
  render && { echo "  failed: accepted a worker on releases/current" >&2; exit 1; }
  check "explains" grep -q "worker launcher must pin an immutable release" "$root/rendered"
  setup
  edit "$root/settings.json" '.supervisorLauncher = .labLauncher'
  render && { echo "  failed: accepted a supervisor on releases/current" >&2; exit 1; }
  check "explains" grep -q "supervisor launcher must pin an immutable release" "$root/rendered"
  setup
  edit "$root/settings.json" '.labEnvironment.LAB_NATIVE_EXECUTION_LOCK = "/other/lock"'
  render && { echo "  failed: accepted a conflicting lock" >&2; exit 1; }
  check "explains" grep -q "same data and ownership lock" "$root/rendered"
}

test_input_signer_is_separate_and_never_inherits_lab_configuration() {
  setup
  edit "$root/settings.json" '.inputSigner = {config: "/cache/signer.json"}
    | .labEnvironment.LAB_SHARED_INPUT_SIGNER = "http://127.0.0.1:8093/"'
  check "renders" render
  local signer="$root/out/com.eignex.lab.input-signer.plist"
  equal $'/bin/sh\n-c\nexec "$0" "$@"\n/lab/releases/current/bin/eignex-lab\ninput-signer\n--config\n/cache/signer.json' \
    "$(plist_strings "$signer" ProgramArguments)" "signer runs the lab launcher"
  equal "HOME JAVA_HOME PATH" "$(plist_strings "$signer" EnvironmentVariables | sed -n 'p;n' | xargs)" \
    "signer inherits no lab configuration"
  check "coordinator has the signer address" grep -q "LAB_SHARED_INPUT_SIGNER" "$root/out/com.eignex.lab.nomad-coordinator.plist"
  edit "$root/settings.json" '.inputSigner += {python: "/cache/venv/bin/python", script: "/cache/input_signer.py"}'
  render && { echo "  failed: accepted the retired Python signer" >&2; exit 1; }
  check "explains" grep -q "remove its python and script settings" "$root/rendered"
}

test_installation_restarts_a_changed_signer_alone() {
  setup
  stub_darwin
  edit "$root/settings.json" '.inputSigner = {config: "/cache/signer.json"}'
  check "first installation" install_services
  equal 5 "$(grep -c '^launchctl bootstrap' "$root/sudo.log")" "all five registered"
  : > "$root/sudo.log"
  check "unchanged signer stays up" install_services
  equal "launchctl bootout system/com.eignex.lab.nomad-coordinator" "$(grep bootout "$root/sudo.log")" \
    "only the coordinator restarts"
  : > "$root/sudo.log"
  edit "$root/settings.json" '.inputSigner.config = "/cache/other.json"'
  check "changed signer installs without --restart-agents" install_services
  equal $'launchctl bootout system/com.eignex.lab.nomad-coordinator\nlaunchctl bootout system/com.eignex.lab.input-signer' \
    "$(grep bootout "$root/sudo.log")" "the coordinator and the signer restart"
  check "installed" cmp -s "$root/daemons/com.eignex.lab.input-signer.plist" \
    "$root/nomad/service-files/com.eignex.lab.input-signer.plist"
}

test_rejects_development_or_unprotected_agent() {
  setup
  edit "$root/client.json" '.tls.verify_https_client = false'
  render && { echo "  failed: accepted an agent without client verification" >&2; exit 1; }
  check "explains" grep -q "mutual TLS" "$root/rendered"
  setup
  edit "$root/server.json" '.dev_mode = true'
  render && { echo "  failed: accepted a dev agent" >&2; exit 1; }
  check "explains" grep -q "non-dev state and ACLs" "$root/rendered"
}

test_loopback_agent_requires_explicit_http_advertise() {
  setup
  edit "$root/client.json" '.bind_addr = "127.0.0.1"'
  render && { echo "  failed: accepted an implicit loopback advertisement" >&2; exit 1; }
  check "explains" grep -q "advertise" "$root/rendered"
  edit "$root/client.json" '.advertise = {http: "127.0.0.1:4649"}'
  check "renders with an explicit advertisement" render
}

test_plists_are_written_as_plistlib_writes_them() {
  setup
  edit "$root/settings.json" '.labEnvironment += {PATH: "/opt/x:/usr/bin", HOME: "/Users/other", AAA: "",
    ZED: "a&b<c>d \"q\" '"'"'s'"'"'", LAB_DATA: .dataDir} | .javaHome = "/lab//jdk25/./"'
  check "renders" render
  equal "$(sed "s#ROOT#$root#g" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>EnvironmentVariables</key>
	<dict>
		<key>AAA</key>
		<string></string>
		<key>HOME</key>
		<string>/Users/other</string>
		<key>JAVA_HOME</key>
		<string>/lab/jdk25</string>
		<key>LAB_DATA</key>
		<string>ROOT</string>
		<key>LAB_JOBS</key>
		<string>/Volumes/lab/jobs</string>
		<key>LAB_NATIVE_EXECUTION_LOCK</key>
		<string>ROOT/native.lock</string>
		<key>PATH</key>
		<string>/opt/x:/usr/bin</string>
		<key>ZED</key>
		<string>a&amp;b&lt;c&gt;d "q" 's'</string>
	</dict>
	<key>KeepAlive</key>
	<true/>
	<key>Label</key>
	<string>com.eignex.lab.nomad-coordinator</string>
	<key>ProgramArguments</key>
	<array>
		<string>/bin/sh</string>
		<string>-c</string>
		<string>exec "$0" "$@"</string>
		<string>/lab/releases/current/bin/eignex-lab</string>
		<string>nomad-coordinator</string>
		<string>ROOT/coordinator.json</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>StandardErrorPath</key>
	<string>ROOT/logs/nomad-coordinator.log</string>
	<key>StandardOutPath</key>
	<string>ROOT/logs/nomad-coordinator.log</string>
	<key>ThrottleInterval</key>
	<integer>30</integer>
	<key>UserName</key>
	<string>lab</string>
	<key>WorkingDirectory</key>
	<string>ROOT</string>
</dict>
</plist>
PLIST
)" "$(cat "$root/out/com.eignex.lab.nomad-coordinator.plist")" "coordinator plist"
}

test_service_plist_options() {
  local plist
  plist="$(printf 'B=2\nA=1\nB=3\nA1=4\n' | launchd_plist com.example.job lab /w /log on-failure 300 one 'a<b')"
  equal $'A\n1\nA1\n4\nB\n3' "$(plist_strings /dev/stdin EnvironmentVariables <<< "$plist")" "sorted, a later value wins"
  check "restarts only after failures" grep -q $'^\t\t<key>SuccessfulExit</key>$' <<< "$plist"
  equal 300 "$(awk '/StartInterval/ { getline; gsub(/[^0-9]/, ""); print }' <<< "$plist")" "interval"
  equal $'one\na&lt;b' "$(plist_strings /dev/stdin ProgramArguments <<< "$plist")" "escaped arguments"
  check "no interval when unscheduled" test -z "$(: | launchd_plist l u /w /log false "" x | grep StartInterval)"
}

# A Mac's launchd, as far as installation sees it: $root/running lists loaded labels, $root/daemons stands in for
# /Library/LaunchDaemons and $root/sudo.log records what ran as root.
stub_darwin() {
  mkdir -p "$root/bin" "$root/daemons"
  : > "$root/running"
  : > "$root/sudo.log"
  cat > "$root/bin/uname" <<STUB
#!/bin/sh
[ "\$1" = -m ] && echo arm64 || echo Darwin
STUB
  cat > "$root/bin/launchctl" <<STUB
#!/bin/sh
case "\$1" in
print) grep -qx "\${2#system/}" "$root/running" ;;
bootout) grep -vx "\${2#system/}" "$root/running" > "$root/running.new"; mv "$root/running.new" "$root/running" ;;
bootstrap) basename "\$3" .plist >> "$root/running" ;;
esac
STUB
  cat > "$root/bin/sudo" <<STUB
#!/bin/sh
[ "\$2" = print ] || echo "\$*" >> "$root/sudo.log"
if [ "\$1" = install ]; then shift 7; exec cp "\$1" "$root/daemons/\$(basename "\$2")"; fi
exec "\$@"
STUB
  cat > "$root/bin/cmp" <<STUB
#!/bin/sh
exec $(command -v cmp) "\$1" "\$(echo "\$2" | sed "s#^/Library/LaunchDaemons/#$root/daemons/#")" "\$3"
STUB
  chmod +x "$root/bin"/*
}
install_services() {  # install_services [--restart-agents]: the script's status
  PATH="$root/bin:$PATH" bash "$script" --config "$root/settings.json" --install "$@" > "$root/installed" 2>&1
}

test_installation_restarts_agents_only_when_asked() {
  setup
  stub_darwin
  check "first installation" install_services
  equal 4 "$(grep -c '^launchctl bootstrap' "$root/sudo.log")" "all four registered"
  check "logs directory" test -d "$root/logs"
  : > "$root/sudo.log"
  check "unchanged agents stay up" install_services
  equal "launchctl bootout system/com.eignex.lab.nomad-coordinator" "$(grep bootout "$root/sudo.log")" \
    "only the coordinator restarts"
  : > "$root/sudo.log"
  edit "$root/settings.json" '.path = "/usr/local/bin:/usr/bin:/bin"'
  install_services && { echo "  failed: changed running agents without --restart-agents" >&2; exit 1; }
  check "explains" grep -q "agent/supervisor changes require explicit --restart-agents maintenance" "$root/installed"
  equal "" "$(cat "$root/sudo.log")" "nothing ran as root"
  check "maintenance installation" install_services --restart-agents
  equal 4 "$(grep -c '^launchctl bootout' "$root/sudo.log")" "all four restarted"
  check "installed" cmp -s "$root/daemons/com.eignex.lab.nomad-client.plist" \
    "$root/nomad/service-files/com.eignex.lab.nomad-client.plist"
}

test_versions_are_read_as_jq_reads_them() {
  local path
  for path in $(jq -r 'paths(strings) | map(tostring) | join(".")' "$hashicorp_versions"); do
    equal "$(jq -r "getpath(\"$path\" | split(\".\"))" "$hashicorp_versions")" "$(hashicorp_pin "$path")" "$path"
  done
  hashicorp_pin nomad.missing 2> /dev/null && { echo "  failed: found a missing pin" >&2; exit 1; }
  return 0
}

test_downloads_are_verified_against_the_pins() {
  root="$(mktemp -d)"
  mkdir -p "$root/bin" "$root/release"
  printf 'pinned nomad' > "$root/release/nomad"
  (cd "$root/release" && zip -q nomad.zip nomad)
  printf '#!/bin/sh\nwhile [ "$1" != -o ]; do shift; done\necho "$3" > %s\ncp %s "$2"\n' \
    "$root/url" "$root/release/nomad.zip" > "$root/bin/curl"
  chmod +x "$root/bin/curl"
  jq --arg sha "$(hashicorp_sha256 "$root/release/nomad.zip")" '.nomad.sha256.linux_amd64 = $sha' \
    "$hashicorp_versions" > "$root/versions.json"
  local hashicorp_versions="$root/versions.json" version
  version="$(hashicorp_pin nomad.version)"
  check "fetches" env PATH="$root/bin:$PATH" bash -c '. "$1"; hashicorp_versions="$2"; hashicorp_fetch nomad linux_amd64 "$3"' \
    fetch "$nomad/hashicorp.sh" "$hashicorp_versions" "$root/nomad"
  equal "https://releases.hashicorp.com/nomad/$version/nomad_${version}_linux_amd64.zip" "$(cat "$root/url")" "url"
  equal "pinned nomad" "$(cat "$root/nomad")" "binary"
  env PATH="$root/bin:$PATH" bash -c '. "$1"; hashicorp_versions="$2"; hashicorp_fetch nomad linux_arm64 "$3"' \
    fetch "$nomad/hashicorp.sh" "$hashicorp_versions" "$root/other" 2> "$root/reason" &&
    { echo "  failed: accepted an archive that does not match its pin" >&2; exit 1; }
  check "explains" grep -q "checksum mismatch" "$root/reason"
  check "writes nothing" test ! -e "$root/other"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
