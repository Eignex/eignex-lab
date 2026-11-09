#!/usr/bin/env bash
# Provider-neutral Linux host preparation. The worker has no access to this configuration or identity.
#
# bootstrap.sh NODE_CONFIG INSTANCE_ID < SECRET_JSON
#
# A provider wrapper (bootstrap-aws.sh, bootstrap-gcp.sh) passes the host secret on stdin; it never appears in user
# data, argv or logs. Checks the host against its homogeneous pool, writes the Nomad client's TLS identity and
# configuration under /etc/eignex-lab, blocks container access to the metadata service and starts the client, the
# interruption adapter and the lifetime timer. Needs jq. LAB_HOST_ROOT prefixes every host path (tests only).

# The node meta the coordinator finds a provider instance's Nomad node by (CapacityWire, deploy/capacity-wire.json).
capacity_id_meta=lab_capacity_id

# bootstrap_render NODE_CONFIG INSTANCE_ID PROBE CPUS MEMORY_MIB < SECRET_JSON: the Nomad client configuration as
# JSON on stdout, or the reason the host does not fit its pool on stderr and status 1. PROBE is the key=value output
# of node-capabilities.sh.
bootstrap_render() {
  jq -nc --slurpfile config "$1" --arg instance "$2" --arg probe "$3" --argjson cpus "$4" --argjson memory "$5" \
    --arg meta "$capacity_id_meta" '
    def refuse($why): ($why + "\n") | halt_error(1);
    def get($key; $default): if has($key) then .[$key] else $default end;
    input as $secret | $secret | $config[0] as $c
    | ([$probe | split("\n")[] | select(contains("=")) | (index("=")) as $i | {key: .[:$i], value: .[$i + 1:]}]
       | from_entries) as $p
    | ({aarch64: "arm64", amd64: "x86_64"}[$p.arch // ""] // $p.arch) as $architecture
    | if $p.detected != "1" or $architecture != $c.architecture
      then refuse("worker architecture or capability detection mismatch") else . end
    | if ($c.minimumFeatures - [($p.features // "") | splits("\\s+") | select(. != "")]) != []
      then refuse("worker SIMD capability mismatch") else . end
    | if $cpus != $c.vcpus or $memory < $c.memoryMiB * 0.95
      then refuse("worker shape differs from the homogeneous pool") else . end
    | if ($c.servers | length) == 0
         or ($c.workerImage | type != "string" or (test("\\A[^\\s]+@sha256:[0-9a-f]{64}\\z") | not))
      then refuse("explicit servers and immutable worker image required") else . end
    | if type != "object" or any(("ca", "certificate", "privateKey") as $f
           | $secret[$f] | type != "string" or (startswith("-----BEGIN ") | not))
      then refuse("mutual TLS material required") else . end
    | {
        data_dir: "/var/lib/nomad", datacenter: $c.datacenter, bind_addr: ($secret | get("bindAddress"; "0.0.0.0")),
        name: ($c.pool + "-" + ($instance | split("/") | last)), leave_on_terminate: true,
        acl: {enabled: true},
        tls: {http: true, rpc: true, verify_server_hostname: true, verify_https_client: true,
          ca_file: "/etc/eignex-lab/ca.pem", cert_file: "/etc/eignex-lab/client.pem",
          key_file: "/etc/eignex-lab/client-key.pem"},
        client: {enabled: true, servers: $c.servers, node_class: $c.nodeClass,
          meta: {($meta): $instance, lab_pool: $c.pool, lab_cpu_model: ($p | get("model"; ""))},
          reserved: {cpu: ($c | get("reservedMHz"; 2000)), memory: 1024},
          host_volume: {"lab-status": {path: "/run/eignex-lab/status", read_only: true}},
          drain_on_shutdown: {deadline: "60s", force: false, ignore_system_jobs: false}},
        consul: {auto_advertise: false, server_auto_join: false, client_auto_join: false},
        plugin: [{raw_exec: {config: [{enabled: false}]}},
          {docker: {config: [{allow_privileged: false, volumes: {enabled: false}}]}}]
      }'
}

bootstrap_install() {
  local config="$1" instance="$2" secret="$3" root="${LAB_HOST_ROOT:-}" probe memory cpus agent etc name rule port
  local network=()
  etc="$root/etc/eignex-lab"
  probe="$(sh "$root/opt/eignex-lab/node-capabilities.sh")"
  memory="$(awk '$1 == "MemTotal:" { print int($2 / 1024); found = 1; exit } END { exit !found }' "$root/proc/meminfo")"
  cpus="$(getconf _NPROCESSORS_ONLN)"
  agent="$(printf '%s' "$secret" | bootstrap_render "$config" "$instance" "$probe" "$cpus" "$memory")"
  # Images are baked with the complete toolchain and worker, avoiding network-dependent boot-time builds.
  "$root/usr/local/bin/nomad" version > /dev/null 2>&1
  docker image inspect "$(jq -er .workerImage "$config")" > /dev/null 2>&1
  [[ -d "$etc" ]] || mkdir -m 700 "$etc"
  chmod 700 "$etc"
  for name in ca.pem client.pem client-key.pem client.json; do
    case "$name" in
    ca.pem) printf '%s' "$secret" | jq -j .ca ;;
    client.pem) printf '%s' "$secret" | jq -j .certificate ;;
    client-key.pem) printf '%s' "$secret" | jq -j .privateKey ;;
    client.json) printf '%s' "$agent" ;;
    esac | (umask 077 && cat > "$etc/$name")
    chmod 600 "$etc/$name"
  done
  # Block container access to metadata/host IAM while allowing GCP DNS (UDP/TCP 53).
  for port in 80 443; do
    rule=(-d 169.254.169.254/32 -p tcp --dport "$port" -j REJECT)
    iptables -C DOCKER-USER "${rule[@]}" > /dev/null 2>&1 || iptables -I DOCKER-USER 1 "${rule[@]}"
  done
  # An optional private network join: an explicit trusted executable argv.
  if printf '%s' "$secret" | jq -e '(.networkCommand // false) as $n
       | $n != false and $n != "" and $n != [] and $n != {} and $n != 0' > /dev/null; then
    printf '%s' "$secret" | jq -e '.networkCommand | type == "array" and all(.[]; type == "string" and (contains("\u0000") | not))
         and (.[0] | startswith("/"))' > /dev/null ||
      { echo "private network join must be an explicit trusted executable argv" >&2; return 1; }
    while IFS= read -r -d '' name; do network+=("$name"); done < <(printf '%s' "$secret" | jq -j '.networkCommand[] | . + "\u0000"')
    timeout 120 "${network[@]}" > /dev/null 2>&1
  fi
  mkdir -p "$root/run/eignex-lab/status"
  chmod 755 "$root/run/eignex-lab/status"
  systemctl enable --now nomad-client.service lab-interruption.service lab-lifetime.timer
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  [[ $# -eq 2 ]] || { echo "usage: bootstrap.sh NODE_CONFIG INSTANCE_ID < SECRET_JSON" >&2; exit 2; }
  bootstrap_install "$1" "$2" "$(cat)"
fi
