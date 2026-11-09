#!/usr/bin/env bash
# Tests of the cloud worker host scripts: deploy/infra/common/{bootstrap,interruption}.sh and the provider wrappers
# aws/bootstrap-aws.sh and gcp/bootstrap-gcp.sh, against stub host commands and a stub metadata service. Needs jq and GNU coreutils.
# bash deploy/infra/tests/test_host_scripts.sh [test name...]
# shellcheck disable=SC1091,SC2016,SC2154,SC2329 # sourced script and its values; literal shell text in stubs; tests called indirectly
set -uo pipefail
infra="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$infra/common/bootstrap.sh"

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}
refuses() {  # refuses <reason> <command...>: the command fails and says why
  local reason="$1"
  shift
  if "$@" > "$root/out" 2> "$root/err"; then echo "  failed: accepted, expected: $reason" >&2; exit 1; fi
  grep -qF "$reason" "$root/err" || { printf '  failed: expected %s, said: %s\n' "$reason" "$(cat "$root/err")" >&2; exit 1; }
}

secret='{"ca": "-----BEGIN CERTIFICATE-----", "certificate": "-----BEGIN CERTIFICATE-----",
  "privateKey": "-----BEGIN PRIVATE KEY-----"}'
probe=$'detected=1\narch=x86_64\nfeatures=avx avx2 \nmodel=CPU'
setup() {
  [[ -z "${root:-}" ]] || rm -rf "$root"
  root="$(mktemp -d)"
  root="$(cd "$root" && pwd -P)"
  jq -n '{architecture: "x86_64", minimumFeatures: ["avx2"], vcpus: 16, memoryMiB: 65536,
    servers: ["100.64.0.10:4647"], pool: "general", nodeClass: "general", datacenter: "lab",
    workerImage: ("registry/worker@sha256:" + ("a" * 64))}' > "$root/config.json"
}
edit() {  # edit <file> <jq filter>
  jq "$2" "$1" > "$1.new" && mv "$1.new" "$1"
}
render() {  # render [instance] [probe] [cpus] [memory] [secret]
  printf '%s' "${5:-$secret}" | bootstrap_render "$root/config.json" "${1:-i-instance}" "${2:-$probe}" "${3:-16}" "${4:-64000}"
}

test_draining_tls_and_cloud_identity_are_host_only() {
  setup
  check "renders" render > "$root/agent.json"
  equal "$(jq -S . <<'EOF'
{"data_dir": "/var/lib/nomad", "datacenter": "lab", "bind_addr": "0.0.0.0", "name": "general-i-instance",
 "leave_on_terminate": true, "acl": {"enabled": true},
 "tls": {"http": true, "rpc": true, "verify_server_hostname": true, "verify_https_client": true,
   "ca_file": "/etc/eignex-lab/ca.pem", "cert_file": "/etc/eignex-lab/client.pem",
   "key_file": "/etc/eignex-lab/client-key.pem"},
 "client": {"enabled": true, "servers": ["100.64.0.10:4647"], "node_class": "general",
   "meta": {"lab_capacity_id": "i-instance", "lab_pool": "general", "lab_cpu_model": "CPU"},
   "reserved": {"cpu": 2000, "memory": 1024},
   "host_volume": {"lab-status": {"path": "/run/eignex-lab/status", "read_only": true}},
   "drain_on_shutdown": {"deadline": "60s", "force": false, "ignore_system_jobs": false}},
 "consul": {"auto_advertise": false, "server_auto_join": false, "client_auto_join": false},
 "plugin": [{"raw_exec": {"config": [{"enabled": false}]}},
   {"docker": {"config": [{"allow_privileged": false, "volumes": {"enabled": false}}]}}]}
EOF
)" "$(jq -S . "$root/agent.json")" "the Nomad client configuration"
  check "carries no token" test -z "$(grep -i token "$root/agent.json")"
  check "carries no key material" test -z "$(grep -- '-----BEGIN' "$root/agent.json")"
  equal "10.1.2.3 1500" "$(edit "$root/config.json" '.reservedMHz = 1500'
    render i-instance "$probe" 16 64000 "$(jq '.bindAddress = "10.1.2.3"' <<< "$secret")" |
    jq -r '"\(.bind_addr) \(.client.reserved.cpu)"')" "explicit bind address and reserve"
  equal "general-instance-7" "$(render projects/p/zones/z/instances/instance-7 | jq -r .name)" "name from a GCP path"
  equal "projects/p/zones/z/instances/instance-7" \
    "$(render projects/p/zones/z/instances/instance-7 | jq -r .client.meta.lab_capacity_id)" "full capacity id"
}

test_capacity_id_meta_is_the_shared_wire_format() {
  equal "$(jq -r .capacityIdMeta "$infra/../capacity-wire.json")" "$capacity_id_meta" "capacity id meta"
}

test_incompatible_hardware_and_mutable_images_never_enable_scheduling() {
  setup
  refuses "capability detection mismatch" render i-instance "${probe/detected=1/detected=0}"
  refuses "capability detection mismatch" render i-instance "${probe/detected=1/}"
  refuses "SIMD capability mismatch" render i-instance "${probe/features=avx avx2/features=avx}"
  refuses "differs from the homogeneous pool" render i-instance "$probe" 8
  refuses "differs from the homogeneous pool" render i-instance "$probe" 16 32000
  render i-instance "$probe" 16 62260 > /dev/null || { echo "  failed: refused 95% of the memory" >&2; exit 1; }
  refuses "differs from the homogeneous pool" render i-instance "$probe" 16 62259
  edit "$root/config.json" '.workerImage = "worker:latest"'
  refuses "immutable worker image required" render
  edit "$root/config.json" '.workerImage = "registry/worker@sha256:\("a" * 64)\nother"'
  refuses "immutable worker image required" render
  setup
  edit "$root/config.json" '.servers = []'
  refuses "explicit servers" render
  setup
  refuses "mutual TLS material required" render i-instance "$probe" 16 64000 "$(jq 'del(.privateKey)' <<< "$secret")"
  refuses "mutual TLS material required" render i-instance "$probe" 16 64000 "$(jq '.ca = "ca"' <<< "$secret")"
  refuses "mutual TLS material required" render i-instance "$probe" 16 64000 '[]'
  if printf '' | bootstrap_render "$root/config.json" i-instance "$probe" 16 64000 > "$root/out" 2>&1; then
    echo "  failed: rendered without a secret" >&2; exit 1
  fi
}

test_linux_arm64_alias_retains_hardware_validation() {
  setup
  edit "$root/config.json" '.architecture = "arm64" | .minimumFeatures = ["asimd", "sve2"]'
  local arm=$'detected=1\narch=aarch64\nfeatures=asimd sve sve2\nmodel=CPU'
  equal general "$(render i-arm "$arm" | jq -r .client.node_class)" "node class"
  refuses "capability detection mismatch" render i-arm "${arm/aarch64/x86_64}"
  refuses "SIMD capability mismatch" render i-arm "${arm/ sve2/}"
}

# host: a root for LAB_HOST_ROOT with stub host commands on $root/bin that log to $root/calls.
host() {
  setup
  mkdir -p "$root/host/opt/eignex-lab" "$root/host/usr/local/bin" "$root/host/proc" "$root/host/etc" \
    "$root/host/run" "$root/bin"
  printf 'MemTotal:       65536000 kB\n' > "$root/host/proc/meminfo"
  printf 'printf "%%s\\n" detected=1 arch=x86_64 "features=avx avx2" model=CPU\n' \
    > "$root/host/opt/eignex-lab/node-capabilities.sh"
  edit "$root/config.json" ".vcpus = $(getconf _NPROCESSORS_ONLN)"
  local name
  for name in docker iptables systemctl "../host/usr/local/bin/nomad"; do
    printf '#!/usr/bin/env bash\necho "%s $*" >> "%s/calls"\n' "${name##*/}" "$root" > "$root/bin/$name"
    chmod +x "$root/bin/$name"
  done
  # The DOCKER-USER rules are not there yet.
  printf '#!/usr/bin/env bash\necho "iptables $*" >> "%s/calls"\n[[ "$1" != -C ]]\n' "$root" > "$root/bin/iptables"
  printf '#!/usr/bin/env bash\nprintf "%%s|" "$@" > "%s/joined"\n' "$root" > "$root/join"
  chmod +x "$root/join" "$root/bin/iptables"
}
install_host() {  # install_host [secret]: bootstrap.sh on the stub host
  printf '%s' "${1:-$secret}" | env LAB_HOST_ROOT="$root/host" PATH="$root/bin:$PATH" \
    bash "$infra/common/bootstrap.sh" "$root/config.json" i-instance
}

test_install_writes_private_identity_blocks_metadata_and_starts_services() {
  host
  check "installs" install_host "$(jq --arg join "$root/join" '.networkCommand = [$join, "a b", "c"]' <<< "$secret")"
  local etc="$root/host/etc/eignex-lab" name
  equal 700 "$(stat -c %a "$etc")" "private directory"
  for name in ca.pem client.pem client-key.pem client.json; do
    equal 600 "$(stat -c %a "$etc/$name")" "private $name"
  done
  equal "-----BEGIN PRIVATE KEY-----" "$(cat "$etc/client-key.pem")" "key bytes"
  equal 27 "$(wc -c < "$etc/client-key.pem")" "key written without a newline"
  equal "i-instance" "$(jq -r .client.meta.lab_capacity_id "$etc/client.json")" "client configuration"
  equal "a b|c|" "$(cat "$root/joined")" "network join arguments"
  equal 755 "$(stat -c %a "$root/host/run/eignex-lab/status")" "status directory"
  equal "nomad version
docker image inspect registry/worker@sha256:$(printf 'a%.0s' {1..64})
iptables -C DOCKER-USER -d 169.254.169.254/32 -p tcp --dport 80 -j REJECT
iptables -I DOCKER-USER 1 -d 169.254.169.254/32 -p tcp --dport 80 -j REJECT
iptables -C DOCKER-USER -d 169.254.169.254/32 -p tcp --dport 443 -j REJECT
iptables -I DOCKER-USER 1 -d 169.254.169.254/32 -p tcp --dport 443 -j REJECT
systemctl enable --now nomad-client.service lab-interruption.service lab-lifetime.timer" "$(cat "$root/calls")" "host calls"
}

test_install_refuses_before_writing_or_starting_anything() {
  host
  edit "$root/config.json" '.vcpus = 1000'
  refuses "differs from the homogeneous pool" install_host
  check "writes nothing" test ! -e "$root/host/etc/eignex-lab"
  check "starts nothing" test ! -e "$root/calls"
  host
  refuses "explicit trusted executable argv" install_host "$(jq '.networkCommand = ["relative"]' <<< "$secret")"
  check "starts nothing" test -z "$(grep systemctl "$root/calls")"
  host
  refuses "explicit trusted executable argv" install_host "$(jq '.networkCommand = "/bin/true"' <<< "$secret")"
  host
  check "skips an empty network join" install_host "$(jq '.networkCommand = []' <<< "$secret")"
}

# metadata_stub <script>: a curl on $root/bin that logs its argv and stdin config, then runs <script> with $url set.
metadata_stub() {
  mkdir -p "$root/bin"
  { printf '#!/usr/bin/env bash\nroot=%q\n' "$root"
    printf '%s\n' 'url="${*: -1}"; printf "%s\n" "$*" >> "$root/argv"' \
      'for a in "$@"; do [[ "$a" != -K ]] || { cat >> "$root/config"; break; }; done'
    printf '%s\n' "$1"; } > "$root/bin/curl"
  printf '#!/usr/bin/env bash\necho "systemctl $*" >> %q/calls\n' "$root" > "$root/bin/systemctl"
  printf '#!/usr/bin/env bash\necho "sleep $*" >> %q/calls\n' "$root" > "$root/bin/sleep"
  chmod +x "$root/bin/curl" "$root/bin/systemctl" "$root/bin/sleep"
}
interruption() {  # interruption <provider>
  mkdir -p "$root/host/etc/eignex-lab" "$root/host/run/eignex-lab/status"
  jq -n --arg provider "$1" '{provider: $provider}' > "$root/host/etc/eignex-lab/node.json"
  env LAB_HOST_ROOT="$root/host" PATH="$root/bin:$PATH" timeout 10 bash "$infra/common/interruption.sh"
}

test_aws_bootstrap_configuration_activates_the_interruption_service() {
  setup
  local provider
  provider="$(awk '/node_config = \{/ { inside = 1 } inside && /provider *=/ { gsub(/[",]/, ""); print $3; exit }' \
    "$infra/aws/main.tf")"
  equal aws "$provider" "the AWS node configuration's provider"
  # Two quiet polls, then the Spot warning.
  metadata_stub 'case "$url" in
    */latest/api/token) echo token ;;
    */spot/instance-action) n=$(cat "$root/polls" 2>/dev/null || echo 0); echo $((n + 1)) > "$root/polls"
      (( n < 2 )) && printf 404 || printf 200 ;;
    *) exit 22 ;;
    esac'
  check "drains and exits" interruption "$provider"
  check "writes the drain marker" test -f "$root/host/run/eignex-lab/status/draining"
  equal "sleep 5
sleep 5
systemctl stop nomad-client.service" "$(cat "$root/calls")" "polls, then stops the client"
  check "uses the IMDSv2 token" grep -q 'X-aws-ec2-metadata-token: token' "$root/argv"
}

test_unanswered_metadata_holds_and_gcp_preemption_drains() {
  setup
  metadata_stub 'n=$(cat "$root/polls" 2>/dev/null || echo 0); echo $((n + 1)) > "$root/polls"
    case "$n" in 0) exit 7 ;; 1) printf "FALSE" ;; *) printf " TRUE\n" ;; esac'
  interruption gcp > "$root/out"
  check "drains" test -f "$root/host/run/eignex-lab/status/draining"
  equal "interruption adapter held: metadata unavailable" "$(cat "$root/out")" "held once"
  check "asks GCP metadata" grep -q 'Metadata-Flavor: Google .*/computeMetadata/v1/instance/preempted' "$root/argv"
  setup
  metadata_stub 'echo token'
  check "fails without a node configuration" test ! -e "$root/host/etc/eignex-lab/node.json"
  if env LAB_HOST_ROOT="$root/host" PATH="$root/bin:$PATH" timeout 10 bash "$infra/common/interruption.sh" 2> /dev/null; then
    echo "  failed: ran without a node configuration" >&2; exit 1
  fi
  metadata_stub 'exit 0'
  mkdir -p "$root/host/etc/eignex-lab"
  echo '{"provider": "other"}' > "$root/host/etc/eignex-lab/node.json"
  # A stub sleep that ends the loop after the first held poll.
  printf '#!/usr/bin/env bash\nkill "$PPID"\n' > "$root/bin/sleep"
  { env LAB_HOST_ROOT="$root/host" PATH="$root/bin:$PATH" timeout 10 bash "$infra/common/interruption.sh" > "$root/out"; } \
    2> /dev/null
  equal "interruption adapter held: unknown infrastructure interruption adapter" "$(cat "$root/out")" "unknown provider"
}

# wrapper <provider dir> <script>: the provider wrapper installed beside a stub bootstrap.sh that records its input.
wrapper() {
  mkdir -p "$root/opt"
  cp "$infra/$1/$2" "$root/opt/"
  printf '#!/usr/bin/env bash\ncat > %q/stdin\nprintf "%%s\\n" "$@" > %q/args\n' "$root" "$root" > "$root/opt/bootstrap.sh"
  chmod +x "$root/opt/bootstrap.sh"
}

test_aws_secret_reaches_bootstrap_on_stdin_with_signed_role_credentials() {
  setup
  wrapper aws bootstrap-aws.sh
  metadata_stub 'case "$url" in
    */latest/api/token) echo imds-token ;;
    */meta-data/instance-id) echo i-0123 ;;
    */iam/security-credentials/) printf "lab-worker\n" ;;
    */iam/security-credentials/lab-worker) echo "{\"AccessKeyId\": \"AKID\", \"SecretAccessKey\": \"SECRETKEY\", \"Token\": \"SESSION\"}" ;;
    https://secretsmanager.eu-north-1.amazonaws.com/) jq -n --arg value "$(cat "$root/secret")" "{SecretString: \$value}" ;;
    *) exit 22 ;;
    esac'
  printf '%s' "$secret" > "$root/secret"
  check "bootstraps" env PATH="$root/bin:$PATH" "$root/opt/bootstrap-aws.sh" arn:secret eu-north-1 /etc/node.json
  equal "$secret" "$(cat "$root/stdin")" "secret on stdin"
  equal $'/etc/node.json\ni-0123' "$(cat "$root/args")" "configuration and instance"
  check "signs for Secrets Manager" grep -q -- '--aws-sigv4 aws:amz:eu-north-1:secretsmanager .*X-Amz-Target: secretsmanager.GetSecretValue .*{"SecretId":"arn:secret"}' "$root/argv"
  check "keeps credentials out of argv" test -z "$(grep -e SECRETKEY -e SESSION -e imds-token -e PRIVATE "$root/argv")"
  check "passes credentials as config" grep -qx 'user = "AKID:SECRETKEY"' "$root/config"
  check "passes the session token as config" grep -qx 'header = "x-amz-security-token: SESSION"' "$root/config"
  setup
  wrapper aws bootstrap-aws.sh
  metadata_stub 'case "$url" in */secretsmanager.*) exit 22 ;; */iam/security-credentials/) echo role ;; *) echo "{}" ;; esac'
  if env PATH="$root/bin:$PATH" "$root/opt/bootstrap-aws.sh" arn:secret eu-north-1 /etc/node.json 2> /dev/null; then
    echo "  failed: bootstrapped without a secret" >&2; exit 1
  fi
  check "never bootstraps without the secret" test ! -e "$root/stdin"
}

test_gcp_secret_reaches_bootstrap_on_stdin_with_metadata_credentials() {
  setup
  wrapper gcp bootstrap-gcp.sh
  printf '%s' "$secret" > "$root/secret"
  metadata_stub 'case "$url" in
    */project/project-id) printf lab-project ;;
    */instance/zone) printf projects/123/zones/europe-north1-a ;;
    */instance/name) printf worker-7 ;;
    */service-accounts/default/token) echo "{\"access_token\": \"BEARER\"}" ;;
    https://secretmanager.googleapis.com/v1/projects/p/secrets/s/versions/latest:access)
      jq -n --arg data "$(base64 -w0 < "$root/secret")" "{payload: {data: \$data}}" ;;
    *) exit 22 ;;
    esac'
  check "bootstraps" env PATH="$root/bin:$PATH" "$root/opt/bootstrap-gcp.sh" projects/p/secrets/s /etc/node.json
  equal "$secret" "$(cat "$root/stdin")" "secret on stdin"
  equal $'/etc/node.json\nprojects/lab-project/zones/europe-north1-a/instances/worker-7' "$(cat "$root/args")" \
    "configuration and instance"
  check "keeps the token out of argv" test -z "$(grep BEARER "$root/argv")"
  check "passes the token as config" grep -qx 'header = "Authorization: Bearer BEARER"' "$root/config"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
