#!/usr/bin/env bash
# Render private Nomad Autoscaler configuration. Provisioning credentials stay in the target plugin.
#
# autoscaler.sh SETTINGS CAPACITY_SETTINGS PRIVATE_OUTPUT [--install-runtime]
#
# Writes PRIVATE_OUTPUT/agent.json and one PRIVATE_OUTPUT/policies/POOL.json per capacity pool (directories 0700, files
# 0600; they hold the Nomad ACL token and the capacity key). Every pool needs exactly one aws-asg or gce-mig target.
# Policies scale on the coordinator's fresh demand only, fail on query errors and remove only empty nodes.
# --install-runtime also installs the checksum-pinned nomad-autoscaler binary of versions.json there.
# Needs jq, and curl and unzip for --install-runtime. Written for macOS's bash 3.2.
# shellcheck disable=SC1091 # hashicorp.sh is checked on its own
set -o pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/hashicorp.sh"

# The metric the coordinator serves each pool's desired size as (CapacityWire, deploy/capacity-wire.json).
DESIRED_METRIC=lab_capacity_desired_nodes

fail() { printf 'autoscaler: %s\n' "$*" >&2; exit 1; }
usage() { echo "usage: autoscaler.sh SETTINGS CAPACITY_SETTINGS PRIVATE_OUTPUT [--install-runtime]" >&2; exit 2; }

# trimmed <file>: the file's contents without surrounding whitespace.
trimmed() {
  local value
  value="$(cat "$1")" || fail "cannot read $1"
  value="${value#"${value%%[![:space:]]*}"}"
  printf %s "${value%"${value##*[![:space:]]}"}"
}

# render: the files to write as one JSON object, relative path to content; fails before anything is written.
render() {
  [[ "$output" == /* ]] || fail "private output directory must be absolute"
  jq -e --argjson settings "$settings" '[.pools[].id] | unique == ([$settings.targets[].pool] | unique)' \
    <<< "$capacity" > /dev/null || fail "configure exactly one target for every capacity pool"
  jq -e --argjson settings "$settings" '($settings.targets | length) == ([.pools[].id] | unique | length)' \
    <<< "$capacity" > /dev/null || fail "duplicate target pool"
  local token nomad_token
  token="$(trimmed "$(jq -r .capacityTokenFile <<< "$settings")")" || exit 1
  ((${#token} >= 32)) || fail "private capacity key too short"
  nomad_token="$(trimmed "$(jq -r .nomadTokenFile <<< "$settings")")" || exit 1
  jq -e '(.nomad.skip_verify | . == null or . == false or . == 0 or . == "")
    and ((.nomad.address // "") | startswith("https://"))' <<< "$settings" > /dev/null ||
    fail "Nomad TLS verification required"
  jq -e 'all(.targets[]; .driver == "aws-asg" or .driver == "gce-mig")' <<< "$settings" > /dev/null ||
    fail "unsupported reference target"
  jq -n --argjson settings "$settings" --argjson capacity "$capacity" --arg output "$output" --arg token "$token" \
    --arg nomad_token "$nomad_token" --arg metric "$DESIRED_METRIC" '
    ($capacity.pools | map({key: .id, value: .}) | from_entries) as $pools
    | {log_level: "WARN", log_json: true,
       http: {bind_address: "127.0.0.1", bind_port: ($settings.healthPort // 18092)},
       nomad: ($settings.nomad + {token: $nomad_token}),
       policy: {dir: ($output + "/policies"), source: {nomad: {enabled: false}}},
       apm: {"lab-demand": {driver: "prometheus", config: {
         address: (($settings.coordinator | sub("/+$"; "")) + "/capacity"), header_Authorization: ("Bearer " + $token)}}},
       target: ($settings.targets | map({key: ("pool-" + .pool), value: {driver, config: (.config // {})}}) | from_entries),
       strategy: {"pass-through": {driver: "pass-through"}}} as $agent
    | ($settings.targets | map($pools[.pool] as $pool | {
        key: ("policies/" + $pool.id + ".json"),
        value: {scaling: {($pool.id): {enabled: true, min: 0, max: $pool.maxNodes, policy: {
          cooldown: "30s", evaluation_interval: "10s", on_check_error: "fail",
          check: {"fresh-demand": {source: "lab-demand", query: ($metric + "{pool=\"" + $pool.id + "\"}"),
            query_window: "instant", on_error: "fail", strategy: {"pass-through": {}}}},
          target: {("pool-" + $pool.id): (.options + {node_class: $pool.nodeClass, node_selector_strategy: "empty",
            node_drain_deadline: "60s", node_purge: "true", node_drain_ignore_system_jobs: "false"})}}}}}})
      | from_entries) + {"agent.json": $agent}'
}

# install: the pinned nomad-autoscaler as PRIVATE_OUTPUT/nomad-autoscaler-VERSION; refuses a different binary there.
install() {
  local version platform binary download
  version="$(hashicorp_pin autoscaler.version)" && platform="$(hashicorp_platform)" || exit 1
  binary="$output/nomad-autoscaler-$version"
  download="$(mktemp "$output/.nomad-autoscaler-XXXXXX")" || fail "cannot create a file in $output"
  if ! hashicorp_fetch autoscaler "$platform" "$download"; then rm -f "$download"; fail "cannot install the autoscaler"; fi
  if [[ -e "$binary" ]] && ! cmp -s "$binary" "$download"; then rm -f "$download"; fail "pinned autoscaler already differs"; fi
  { chmod 700 "$download" && mv -f "$download" "$binary"; } || fail "cannot install $binary"
}

arguments=()
install_runtime=0
for argument in "$@"; do
  case "$argument" in
  --install-runtime) install_runtime=1 ;;
  -*) usage ;;
  *) arguments+=("$argument") ;;
  esac
done
((${#arguments[@]} == 3)) || usage
settings="$(jq -c . "${arguments[0]}")" || fail "cannot read ${arguments[0]}"
capacity="$(jq -c . "${arguments[1]}")" || fail "cannot read ${arguments[1]}"
output="${arguments[2]%/}"
files="$(render)" || exit 1
umask 077
{ mkdir -p "$output/policies" && chmod 700 "$output" "$output/policies"; } || fail "cannot create $output"
while IFS= read -r relative; do
  { jq --arg relative "$relative" '.[$relative]' <<< "$files" > "$output/$relative" && chmod 600 "$output/$relative"; } ||
    fail "cannot write $output/$relative"
done < <(jq -r 'keys_unsorted[]' <<< "$files")
if ((install_runtime)); then install; fi
