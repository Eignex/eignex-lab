#!/usr/bin/env bash
# Render or install independent Mac services. Ordinary lab updates never restart Nomad or its supervisor.
#
# native-services.sh --config FILE --output DIR             render the plists into DIR and register nothing
# native-services.sh --config FILE --install [--restart-agents]
#                                                           render into dataDir/nomad/service-files and register them;
#                                                           changed agent or supervisor plists need --restart-agents
# native-services.sh --init-runtime DIR [--server-ip IP]    install the pinned Nomad binary and private TLS files
#
# Needs jq. Written for macOS's bash 3.2.
# shellcheck disable=SC1091 # launchd.sh and hashicorp.sh are checked on their own
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
. "$here/../launchd.sh"
. "$here/hashicorp.sh"

fail() { printf 'native-services: %s\n' "$*" >&2; exit 1; }

# absolute <variable> <path>: set <variable> to <path> normalised as Python's pathlib writes it; fails unless absolute.
absolute() {
  local path="$2" part normalised="" lead=/ IFS=/
  [[ "$path" == /* ]] || fail "service paths must be absolute"
  [[ "$path" == //* && "$path" != ///* ]] && lead=//
  set -f
  for part in $path; do
    [[ -z "$part" || "$part" == . ]] || normalised+="/$part"
  done
  set +f
  if [[ -n "$normalised" ]]; then normalised="${lead%/}$normalised"; else normalised="$lead"; fi
  printf -v "$1" '%s' "$normalised"
}

# setting <key>: a value from the settings as text; fails when it is missing.
setting() {
  jq -er --arg key "$1" '.[$key] | if type == "string" then . elif . == null then empty else tostring end' \
    <<< "$settings" || fail "settings lack $1"
}

# A jq truthiness that matches Python's.
truthy='def truthy: . != null and . != false and . != 0 and . != "" and . != [] and . != {};'

# check_agent <file>: the reason a Nomad agent configuration is unfit, or nothing.
check_agent() {
  jq -r "$truthy"'
    if (.bind_addr == "127.0.0.1" or .bind_addr == "::1") and ((.advertise // {}).http | truthy | not) then
      "a loopback agent requires an explicit HTTP advertise address"
    elif (.dev_mode | truthy) or ((.acl // {}).enabled | truthy | not) then
      "Nomad must use persistent non-dev state and ACLs"
    elif [(.tls // {}) as $tls | "http", "rpc", "verify_server_hostname", "verify_https_client", "ca_file",
        "cert_file", "key_file" | $tls[.] | truthy] | all | not then
      "Nomad requires mutual TLS for HTTP and RPC"
    else empty end' "$1"
}

# render [output dir]: validate the settings in $settings and write one plist per service into the output directory,
# dataDir/nomad/service-files by default. Sets output, labels (in installation order), data, supervisor and pinned.
render() {
  output="$1"
  local launcher worker binary python socket local_lock home java environment agent_environment
  local server_path client_path coordinator_config reason file user role signer_config
  local -a roles
  absolute data "$(setting dataDir)"
  # shellcheck disable=SC2154 # absolute sets it
  local state="$data/nomad"
  [[ -n "$output" ]] || output="$state/service-files"
  absolute launcher "$(setting labLauncher)"
  absolute worker "$(setting workerLauncher)"
  [[ "$worker/" != */current/* ]] || fail "worker launcher must pin an immutable release"
  absolute binary "$(setting nomadBinary)"
  absolute python "$(setting python)"
  absolute socket "$(setting supervisorSocket)"
  absolute local_lock "$(setting localLock)"
  absolute home "$(setting home)"
  setting path > /dev/null
  absolute java "$(setting javaHome)"
  environment="$(jq -c --arg home "$home" --arg java "$java" --arg data "$data" --arg lock "$local_lock" \
    '{HOME: $home, PATH: .path, JAVA_HOME: $java, LAB_DATA: $data, LAB_NATIVE_EXECUTION_LOCK: $lock}
     + (.labEnvironment // {})' <<< "$settings")" || fail "unreadable labEnvironment"
  jq -e --arg data "$data" --arg lock "$local_lock" '.LAB_DATA == $data and .LAB_NATIVE_EXECUTION_LOCK == $lock' \
    <<< "$environment" > /dev/null || fail "native workers and maintenance must use the same data and ownership lock"
  # Supervisor and Nomad agents never inherit the lab's provider/control configuration.
  agent_environment="$(jq -r '{PATH, HOME, JAVA_HOME} | to_entries[] | "\(.key)=\(.value)"' <<< "$environment")"
  environment="$(jq -r 'to_entries[] | "\(.key)=\(.value)"' <<< "$environment")"
  supervisor="$root/src/main/resources/nomad/process_supervisor.py"
  [[ -f "$supervisor" ]] || fail "no supervisor at $supervisor"
  pinned="$state/supervisor-$(hashicorp_sha256 "$supervisor").py"
  absolute server_path "$(setting serverConfig)"
  absolute client_path "$(setting clientConfig)"
  for file in "$server_path" "$client_path"; do
    jq -e 'type == "object"' "$file" > /dev/null 2>&1 || fail "cannot read Nomad configuration $file"
    reason="$(check_agent "$file")" || fail "cannot read Nomad configuration $file"
    [[ -z "$reason" ]] || fail "$reason"
    absolute reason "$(jq -er '.data_dir' "$file" || fail "Nomad configuration $file lacks data_dir")"
  done
  jq -e "$truthy"'((.server // {}).enabled | truthy) and .server.bootstrap_expect == 1' "$server_path" > /dev/null ||
    fail "initial server must be explicitly single-server"
  jq -e "$truthy"'((.client // {}).enabled | truthy) and (.client.servers | truthy)' "$client_path" > /dev/null ||
    fail "native client requires an explicit server route"
  jq -e "$truthy"'[(.plugin // [])[] | ((.raw_exec // {}).config // [{}])[0].enabled | truthy] | any' \
    "$client_path" > /dev/null || fail "native client requires raw_exec"
  jq -e '((.client.reserved // {}).memory // 0) > 0' "$client_path" > /dev/null ||
    fail "native client requires a host memory reserve"
  absolute coordinator_config "$(setting coordinatorConfig)"
  roles=(nomad-server nomad-client process-supervisor nomad-coordinator)
  if jq -e "$truthy"'.inputSigner | truthy' <<< "$settings" > /dev/null; then
    # The signer runs the lab launcher, whose path stays the same across releases: an update never changes its plist.
    jq -e '.inputSigner | type == "object" and (has("python") or has("script")) | not' <<< "$settings" > /dev/null ||
      fail "inputSigner now runs the lab launcher (eignex-lab input-signer): remove its python and script settings"
    absolute signer_config "$(jq -er '.inputSigner.config' <<< "$settings" || fail "inputSigner lacks config")"
    roles+=(input-signer)
  fi
  user="$(jq -r 'if has("user") then .user else empty end' <<< "$settings")"
  [[ -n "$user" ]] || user="$(id -un)"
  [[ "$user" != root ]] || fail "native execution must run as the lab user, never root"
  # shellcheck disable=SC2174 # only the output directory itself is private
  mkdir -p -m 700 "$output" || fail "cannot create $output"
  labels=()
  for role in "${roles[@]}"; do
    labels+=("com.eignex.lab.$role")
    # macOS grants an external volume (where releases may live) per responsible program, and a daemon is its own:
    # starting each through /bin/sh lets one Full Disk Access grant to /bin/sh cover every service and its children.
    # shellcheck disable=SC2016 # expanded by that shell
    set -- /bin/sh -c 'exec "$0" "$@"'
    case "$role" in
    nomad-server) set -- "$@" "$binary" agent -config "$server_path" ;;
    nomad-client) set -- "$@" "$binary" agent -config "$client_path" ;;
    process-supervisor) set -- "$@" "$python" "$pinned" --socket "$socket" --local-lock "$local_lock" ;;
    nomad-coordinator) set -- "$@" "$launcher" nomad-coordinator "$coordinator_config" ;;
    input-signer) set -- "$@" "$launcher" input-signer --config "$signer_config" ;;
    esac
    if [[ "$role" == nomad-coordinator ]]; then printf '%s\n' "$environment"; else printf '%s\n' "$agent_environment"; fi |
      launchd_plist "com.eignex.lab.$role" "$user" "$data" "$data/logs/$role.log" true "" "$@" \
        > "$output/com.eignex.lab.$role.plist" || fail "cannot write $output/com.eignex.lab.$role.plist"
  done
}

install_services() {
  local restart_agents="$1" label running="" agent
  [[ "$(uname -s)" == Darwin ]] || fail "service installation requires macOS"
  for label in "${labels[@]}"; do
    launchctl print "system/$label" >/dev/null 2>&1 && running+=" $label "
    agent=""
    [[ "$label" == *nomad-coordinator || "$label" == *input-signer || -n "$restart_agents" ]] || agent=1
    if [[ -n "$agent" && "$running" == *" $label "* ]] &&
      ! cmp -s "/Library/LaunchDaemons/$label.plist" "$output/$label.plist"; then
      fail "agent/supervisor changes require explicit --restart-agents maintenance"
    fi
  done
  # shellcheck disable=SC2174 # only the state directory itself is private
  mkdir -p -m 700 "$(dirname "$pinned")" || fail "cannot create $(dirname "$pinned")"
  if [[ -e "$pinned" ]]; then
    cmp -s "$pinned" "$supervisor" || fail "pinned supervisor differs"
  else
    { cp "$supervisor" "$pinned" && chmod 700 "$pinned"; } || fail "cannot pin the supervisor at $pinned"
  fi
  [[ -d "$data/logs" ]] || mkdir "$data/logs" || fail "cannot create $data/logs"
  for label in "${labels[@]}"; do
    # The signer holds no state: a changed one restarts here, an unchanged one keeps running.
    if [[ "$label" == *input-signer && -z "$restart_agents" && "$running" == *" $label "* ]]; then
      ! cmp -s "/Library/LaunchDaemons/$label.plist" "$output/$label.plist" || continue
    else
      [[ "$label" == *nomad-coordinator || -n "$restart_agents" || "$running" != *" $label "* ]] || continue
    fi
    reload_daemon "$label" "$output/$label.plist" || fail "cannot register $label"
  done
  echo "Native services installed. Ordinary lab updates restart only the coordinator/API/runner."
}

install_runtime() {
  local state="$1" server_ip="$2" version platform binary download role key
  [[ "$state" == /* ]] || state="$PWD/$state"
  # shellcheck disable=SC2174 # only the state directory itself is private
  { mkdir -p -m 700 "$state" && chmod 700 "$state"; } || fail "cannot create $state"
  state="$(cd "$state" && pwd -P)"
  version="$(hashicorp_pin nomad.version)" || exit 1
  case "$(uname -m)" in
  arm64) platform=darwin_arm64 ;;
  x86_64) platform=darwin_amd64 ;;
  *) fail "no native Nomad build for $(uname -m)" ;;
  esac
  binary="$state/bin/nomad-$version"
  [[ -d "$state/bin" ]] || mkdir "$state/bin" || fail "cannot create $state/bin"
  download="$(mktemp "$state/bin/.nomad-XXXXXX")" || fail "cannot create a download in $state/bin"
  if ! hashicorp_fetch nomad "$platform" "$download"; then
    rm -f "$download"
    exit 1
  fi
  if [[ -e "$binary" ]]; then
    cmp -s "$binary" "$download" || { rm -f "$download"; fail "refusing to overwrite a pinned Nomad binary"; }
    rm -f "$download"
  else
    { chmod 755 "$download" && mv "$download" "$binary"; } || fail "cannot install $binary"
  fi
  tls() {
    local output
    output="$(cd "$state" && "$binary" tls "$@" 2>&1)" || fail "nomad tls $* failed: $output"
  }
  [[ -e "$state/nomad-agent-ca.pem" ]] || tls ca create
  for role in server client cli; do
    [[ ! -e "$state/global-$role-nomad.pem" ]] || continue
    if [[ "$role" == server ]]; then tls cert create -server -additional-ipaddress "$server_ip"
    else tls cert create "-$role"
    fi
  done
  for key in "$state"/*-key.pem; do
    [[ -e "$key" ]] && chmod 600 "$key"
  done
  echo "Verified native Nomad $version and initialized private TLS files in $state"
}

usage() {
  echo "usage: native-services.sh [--config CONFIG] [--output OUTPUT] [--install] [--restart-agents]" >&2
  echo "  [--init-runtime INIT_RUNTIME] [--server-ip SERVER_IP]" >&2
  echo "native-services.sh: error: $1" >&2
  exit 2
}

main() {
  local config="" output="" install="" restart_agents="" init_runtime="" server_ip=127.0.0.1 option value
  while (( $# )); do
    option="$1"
    case "$option" in
    --install) install=1; shift; continue ;;
    --restart-agents) restart_agents=1; shift; continue ;;
    --*=*) value="${option#*=}"; option="${option%%=*}"; shift ;;
    --config | --output | --init-runtime | --server-ip)
      (( $# >= 2 )) || usage "argument $option: expected one argument"
      value="$2"; shift 2 ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
    case "$option" in
    --config) config="$value" ;;
    --output) output="$value" ;;
    --init-runtime) init_runtime="$value" ;;
    --server-ip) server_ip="$value" ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
  done
  if [[ -n "$init_runtime" ]]; then
    [[ "$(uname -s)" == Darwin ]] || fail "native runtime installation requires macOS"
    install_runtime "$init_runtime" "$server_ip"
    return
  fi
  if [[ -z "$config" || ( -n "$output" && -n "$install" ) || ( -z "$output" && -z "$install" ) ]]; then
    usage "provide --config and exactly one of --output or --install"
  fi
  command -v jq >/dev/null || fail "native-services.sh needs jq"
  settings="$(jq -e 'if type == "object" then . else error("settings must be an object") end' "$config")" ||
    fail "cannot read settings $config"
  render "$output"
  if [[ -z "$install" ]]; then
    echo "Rendered native service files in $output; registered nothing"
    return
  fi
  install_services "$restart_agents"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
