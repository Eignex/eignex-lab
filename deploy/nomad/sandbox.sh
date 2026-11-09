#!/usr/bin/env bash
# A separate, persistent single-node Nomad sandbox with mTLS and ACLs; never uses the lab database.
#
# sandbox.sh [--state DIR] [--port N] [--lab LAUNCHER] [--image IMAGE] ACTION [NOMAD ARGS...]
#   install       download the checksum-pinned Nomad into DIR/bin and create the sandbox's private CA and certificates
#   start         start the agent (a privileged Docker container on Linux, a detached process on the Mac) and wait
#                 for its ACL token and a ready client
#   stop          stop the agent, keeping its state
#   verify        Linux: shared placement, CPU quotas, throttling, memory ceilings, memory exhaustion and cancellation
#                 of bounded probe sessions; writes DIR/verification.json
#   native-probe  Mac: one native raw_exec probe session; writes DIR/native-verification.json
#   cli ARGS      the sandbox's own nomad with its TLS files and token, never the caller's NOMAD_* environment
#
# DIR defaults to build/nomad-prototype and N (with N+1 and N+2) to 14646; LAUNCHER is the installed eignex-lab,
# which renders the probe sessions; IMAGE is the pinned probe image of versions.json. Needs curl, jq, unzip, and
# Docker on Linux. Written for macOS's bash 3.2. connected-probe.sh sources this file for its functions.
# shellcheck disable=SC1091 # hashicorp.sh is checked on its own
set -o pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
. "$here/hashicorp.sh"

fail() { printf 'sandbox: %s\n' "$*" >&2; exit 1; }
check() { [[ "$1" == true ]] || fail "verification failed: $2"; }  # check <true|false> <detail>

# run <command...>: the command's standard output; on failure its error output and the end of this script.
run() {
  local errors status
  errors="$(mktemp)" || fail "cannot create a temporary file"
  "$@" 2> "$errors"
  status=$?
  if (( status != 0 )); then
    printf 'sandbox: %s exited with %s\n' "$1" "$status" >&2
    cat "$errors" >&2
    rm -f "$errors"
    exit 1
  fi
  rm -f "$errors"
}

# sandbox_init <state dir> <port>: the sandbox this shell works on, its state directory made absolute.
sandbox_init() {
  state="$1"
  [[ "$state" == /* ]] || state="$PWD/$state"
  if [[ -d "$state" ]]; then
    state="$(cd "$state" && pwd -P)"
  elif [[ -d "$(dirname "$state")" ]]; then
    state="$(cd "$(dirname "$state")" && pwd -P)/$(basename "$state")"
  fi
  port="$2"
  name="eignex-nomad-prototype-$(printf %s "$state" | hashicorp_sha256 /dev/stdin | cut -c1-12)"
  nomad="$state/bin/nomad"
}

# now_ms: milliseconds of wall-clock time, whole seconds where this bash cannot tell finer.
now_ms() {
  if [[ -n "${EPOCHREALTIME:-}" ]]; then
    local t="${EPOCHREALTIME/[.,]/}"
    echo $((10#${t:0:${#t}-3}))
  else
    echo $(($(date +%s) * 1000))
  fi
}
seconds() { printf '%d.%03d' $(($1 / 1000)) $(($1 % 1000)); }  # seconds <milliseconds>
pause() { sleep "$(seconds "$1")"; }  # pause <milliseconds>

# bounded <seconds> <command...>: the command, ended after <seconds> with status 124.
bounded() {
  if command -v timeout > /dev/null; then timeout "$@"; return; fi
  local limit="$1" pid watchdog status
  shift
  "$@" &
  pid=$!
  ( sleep "$limit"; kill -TERM "$pid" 2> /dev/null ) &
  watchdog=$!
  wait "$pid"
  status=$?
  if kill -0 "$watchdog" 2> /dev/null; then kill "$watchdog" 2> /dev/null; else status=124; fi
  wait "$watchdog" 2> /dev/null
  return "$status"
}

# cli [--timeout SECONDS] <nomad arguments...>: the sandbox's nomad with its own TLS and ACL token.
cli() {
  local limit="" variable token="" removed=()
  if [[ "$1" == --timeout ]]; then limit="$2"; shift 2; fi
  for variable in $(compgen -e); do
    [[ "$variable" == NOMAD_* ]] && removed+=(-u "$variable")
  done
  [[ -f "$state/token" ]] && token="$(tr -d '[:space:]' < "$state/token")"
  set -- env ${removed[@]+"${removed[@]}"} NOMAD_ADDR="https://127.0.0.1:$port" NOMAD_CACERT="$state/nomad-agent-ca.pem" \
    NOMAD_CLIENT_CERT="$state/global-cli-nomad.pem" NOMAD_CLIENT_KEY="$state/global-cli-nomad-key.pem" \
    ${token:+NOMAD_TOKEN="$token"} "$nomad" "$@"
  if [[ -n "$limit" ]]; then bounded "$limit" "$@"; else "$@"; fi
}

# allocation_logs <allocation>: its worker task's standard output.
allocation_logs() {
  # A terminal server allocation may precede the client's local log-state publication. Keep the same allocation and
  # every resource assertion; only this precise readiness error retries, within one ten-second deadline.
  local allocation="$1" deadline remaining limit output errors status unavailable failed=""
  unavailable="Failed to read stdout file: error reading file: Unexpected response code: 404 (state for allocation $allocation not found on client)"
  { output="$(mktemp)" && errors="$(mktemp)"; } || fail "cannot create a temporary file"
  deadline=$(($(now_ms) + 10000))
  while :; do
    remaining=$((deadline - $(now_ms)))
    if ((remaining <= 0)); then
      if [[ -z "$failed" ]]; then echo "sandbox: allocation log readiness deadline expired" >&2; status=1; fi
      cat "$errors" >&2
      rm -f "$output" "$errors"
      return "$status"
    fi
    limit=$((remaining < 5000 ? remaining : 5000))
    cli --timeout "$(seconds "$limit")" alloc logs "$allocation" worker > "$output" 2> "$errors"
    status=$?
    if ((status == 0)); then
      cat "$output"
      rm -f "$output" "$errors"
      return 0
    fi
    if ((status != 1)) || [[ "$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$errors")" != "$unavailable" ]]; then
      cat "$errors" >&2
      rm -f "$output" "$errors"
      return "$status"
    fi
    failed=1
    remaining=$((deadline - $(now_ms)))
    limit=$((remaining < 250 ? remaining : 250))
    ((limit > 0)) && pause "$limit"
  done
}

# api <path> [method] [body]: the sandbox API's JSON answer; status 1 with the HTTP status on error output on failure.
api() {
  local token=() answer status
  [[ -f "$state/token" ]] && token=(-H "X-Nomad-Token: $(tr -d '[:space:]' < "$state/token")")
  answer="$(curl -sS --max-time 5 --cacert "$state/nomad-agent-ca.pem" --cert "$state/global-cli-nomad.pem" \
    --key "$state/global-cli-nomad-key.pem" -X "${2:-GET}" -H "Content-Type: application/json" \
    ${token[@]+"${token[@]}"} ${3:+--data-binary "$3"} -w '\n%{http_code}' "https://127.0.0.1:$port/v1/$1")" || return 1
  status="${answer##*$'\n'}"
  answer="${answer%$'\n'*}"
  if [[ "$status" != 2?? ]]; then echo "HTTP $status from /v1/$1" >&2; return 1; fi
  printf '%s\n' "$answer"
}

# The sandbox API's answer, ending this script when it fails.
api_or_fail() { api "$@" || fail "Nomad API request for /v1/$1 failed"; }

install() {
  { mkdir -p "$state" && chmod 700 "$state" && mkdir -p "$state/bin"; } || fail "cannot create $state"
  sandbox_init "$state" "$port"
  local platform
  platform="$(hashicorp_platform)" || exit 1
  hashicorp_fetch nomad "$platform" "$nomad" || fail "cannot install Nomad"
  chmod 755 "$nomad"
  if [[ ! -e "$state/nomad-agent-ca.pem" ]]; then (cd "$state" && run "$nomad" tls ca create > /dev/null) || exit 1; fi
  for role in server cli; do
    if [[ ! -e "$state/global-$role-nomad.pem" ]]; then
      (cd "$state" && run "$nomad" tls cert create "-$role" > /dev/null) || exit 1
    fi
  done
  chmod 600 "$state"/*-key.pem
  echo "Verified Nomad $(hashicorp_pin nomad.version); private sandbox state: $state"
}

linux() { [[ "$(uname -s)" == Linux ]]; }

# config: the agent's configuration. JSON avoids interpolating paths into HCL strings or shell commands.
config() {
  mkdir -p "$state/status"
  local class=lab-prototype-mac raw_exec=true
  if linux; then class=lab-prototype-linux raw_exec=false; fi
  jq -n --arg name "$name" --arg state "$state" --argjson port "$port" --arg class "$class" --argjson raw_exec "$raw_exec" '{
    name: $name, data_dir: ($state + "/data"), datacenter: "lab-prototype", bind_addr: "127.0.0.1", log_level: "INFO",
    ports: {http: $port, rpc: ($port + 1), serf: ($port + 2)},
    advertise: {http: "127.0.0.1:\($port)", rpc: "127.0.0.1:\($port + 1)", serf: "127.0.0.1:\($port + 2)"},
    server: {enabled: true, bootstrap_expect: 1},
    client: {enabled: true, node_class: $class,
      host_volume: [{"lab-status": {path: ($state + "/status"), read_only: true}}],
      servers: ["127.0.0.1:\($port + 1)"], reserved: {cpu: 500, memory: 256},
      # The verification reads finished allocations logs. Nomad default client GC collects terminal allocations
      # every minute once the disk is 80% used, which a CI runner after its builds often is, and their logs then
      # answer 404. This throwaway agent keeps them until it is stopped.
      gc_interval: "24h", gc_disk_usage_threshold: 99, gc_inode_usage_threshold: 99, gc_max_allocs: 1000},
    acl: {enabled: true},
    tls: {http: true, rpc: true, verify_server_hostname: true, verify_https_client: true,
      ca_file: ($state + "/nomad-agent-ca.pem"), cert_file: ($state + "/global-server-nomad.pem"),
      key_file: ($state + "/global-server-nomad-key.pem")},
    plugin: [{raw_exec: {config: [{enabled: $raw_exec}]}}],
    consul: {auto_advertise: false, server_auto_join: false, client_auto_join: false}}'
}

# detached <command...>: start the command in the background in a session of its own.
detached() {
  if command -v setsid > /dev/null; then setsid "$@" & return; fi
  perl -MPOSIX -e 'POSIX::setsid() or die "setsid: $!\n"; exec @ARGV or die "exec: $!\n"' "$@" &
}

start() {
  if [[ ! -e "$nomad" || ! -e "$state/global-cli-nomad.pem" ]]; then install; fi
  local agent="$state/agent.json" image token deadline nodes
  config > "$agent" || fail "cannot write $agent"
  if linux; then
    image="$(run docker build -q --build-arg "BASE=$(hashicorp_pin linux_agent_image)" -f "$here/agent.Dockerfile" "$here")" ||
      exit 1
    # A root Linux client needs host cgroups and the Docker socket. All task/data paths retain identical host and
    # container names so the Docker driver's allocation mounts resolve on the actual Docker host.
    run docker run -d --name "$name" --privileged --network host --cgroupns host \
      --label "eignex.lab.nomad.prototype=$state" --mount "type=bind,src=$state,dst=$state" \
      --mount type=bind,src=/sys/fs/cgroup,dst=/sys/fs/cgroup \
      --mount type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock \
      "$image" "$nomad" agent -config "$agent" > /dev/null || exit 1
  else
    detached "$nomad" agent -config "$agent" >> "$state/agent.log" 2>&1
    echo "$!" > "$state/agent.pid"
  fi
  wait_ready
  if [[ ! -e "$state/token" ]]; then
    token="$(run cli acl bootstrap -json | jq -er .SecretID)" || fail "cannot bootstrap the sandbox ACLs"
    (umask 077 && set -o noclobber && printf %s "$token" > "$state/token") || fail "cannot write $state/token"
  fi
  deadline=$(($(now_ms) + 45000))
  while (($(now_ms) < deadline)); do
    nodes="$(api_or_fail nodes)" || exit 1
    if jq -e 'any(.[]; .Status == "ready")' <<< "$nodes" > /dev/null; then
      echo "Sandbox ready at https://127.0.0.1:$port with TLS and ACLs"
      return
    fi
    sleep 1
  done
  fail "client did not become ready; inspect the sandbox agent logs"
}

wait_ready() {
  local deadline=$(($(now_ms) + 45000))
  while (($(now_ms) < deadline)); do
    api status/leader 2> /dev/null | jq -e '. != null and . != ""' > /dev/null && return
    sleep 0.5
  done
  fail "Nomad leader did not become ready; inspect the sandbox agent logs"
}

stop() {
  if linux; then
    local inspected errors info
    errors="$(mktemp)" || fail "cannot create a temporary file"
    if ! inspected="$(docker inspect "$name" 2> "$errors")"; then
      if grep -q "No such object" "$errors"; then
        rm -f "$errors"
        echo "Sandbox agent is not running"
        return
      fi
      cat "$errors" >&2
      rm -f "$errors"
      fail "cannot inspect $name"
    fi
    rm -f "$errors"
    info="$(jq '.[0]' <<< "$inspected")"
    [[ "$(jq -r '.Config.Labels["eignex.lab.nomad.prototype"] // ""' <<< "$info")" == "$state" ]] ||
      fail "refusing to stop a container not owned by this sandbox"
    run docker logs "$name" > "$state/agent.log" || exit 1
    run docker rm -f "$name" > /dev/null || exit 1
    if [[ -e "$state/data" ]]; then
      # The root client writes private state; return ownership so `gradlew clean` works for the operator.
      run docker run --rm --network none --mount "type=bind,src=$state/data,dst=/data" "$(jq -r .Image <<< "$info")" \
        chown -R "$(id -u):$(id -g)" /data > /dev/null || exit 1
    fi
  else
    # Match the recorded process and its state path before signaling; never trust a reused PID alone.
    if [[ ! -e "$state/agent.pid" ]]; then
      echo "Sandbox agent is not running"
      return
    fi
    local pid command
    pid="$(cat "$state/agent.pid")"
    [[ "$pid" =~ ^[0-9]+$ ]] || fail "no PID in $state/agent.pid"
    command="$(run ps -p "$pid" -o command=)" || exit 1
    [[ "$command" == *"$nomad"* && "$command" == *"$state/agent.json"* ]] || fail "sandbox PID no longer names this agent"
    kill -TERM "$pid" || fail "cannot stop $pid"
    rm -f "$state/agent.pid"
  fi
  echo "Stopped sandbox agent; state retained"
}

# wait_allocation <job> [terminal]: the job's first started (or with "terminal", finished) allocation.
wait_allocation() {
  local job="$1" statuses='["running", "complete", "failed"]' deadline id what=start
  if [[ "${2:-}" == terminal ]]; then statuses='["complete", "failed"]' what=finish; fi
  deadline=$(($(now_ms) + 60000))
  while (($(now_ms) < deadline)); do
    id="$(api_or_fail "job/$job/allocations" |
      jq -r --argjson statuses "$statuses" 'first(.[] | select(.ClientStatus as $s | $statuses | index($s))) | .ID')" ||
      exit 1
    if [[ -n "$id" ]]; then api_or_fail "allocation/$id"; return; fi
    sleep 0.5
  done
  fail "allocation did not $what for $job"
}

# containers <allocation>: the IDs of its running Docker containers.
containers() { run docker ps -q --filter "label=com.hashicorp.nomad.alloc_id=$1"; }

deregister() {  # deregister <job>: purge it; a job Nomad does not know is fine
  local code
  code="$(curl -sS --max-time 5 --cacert "$state/nomad-agent-ca.pem" --cert "$state/global-cli-nomad.pem" \
    --key "$state/global-cli-nomad-key.pem" -X DELETE -H "X-Nomad-Token: $(tr -d '[:space:]' < "$state/token")" \
    -o /dev/null -w '%{http_code}' "https://127.0.0.1:$port/v1/job/$1?purge=true")" || return 1
  [[ "$code" == 2?? || "$code" == 404 ]] || { echo "sandbox: deregistering $1 answered HTTP $code" >&2; return 1; }
}

# one_ready_node: the details of the sandbox's only ready node.
one_ready_node() {
  local ready
  ready="$(api_or_fail nodes | jq -r '[.[] | select(.Status == "ready") | .ID] | if length == 1 then .[0] else empty end')" ||
    exit 1
  [[ -n "$ready" ]] || fail "$1"
  api_or_fail "node/$ready"
}

random_hex() { od -An -N5 -tx1 /dev/urandom | tr -d ' \n'; }

# submit <manifest>: render the session with the lab and register it; sets job and records it for deregistration.
submit() {
  job="$(jq -r .id <<< "$1")"
  printf '%s\n' "$1" > "$state/$job.json"
  local rendered
  rendered="$(run "$lab" nomad-spec "$state/$job.json")" || exit 1
  jobs="$jobs $job"
  api_or_fail jobs POST "$(jq -c '{Job: .}' <<< "$rendered")" > /dev/null
}

deregister_jobs() {
  local each
  for each in $jobs; do deregister "$each"; done
  jobs=""
}

verify() {
  linux || fail "automated hard-limit checks require Linux; use the native probe instructions on Mac"
  [[ "$image" == *@sha256:* ]] || fail "--image must be an image with bash pinned with @sha256:<digest>"
  local node capacity probe allocations="" reports="[]" kind budget report alloc task first oom cancel running
  node="$(one_ready_node "verification requires exactly one ready sandbox node")" || exit 1
  capacity="$(jq -c '{usableMHz: (.Attributes["cpu.usablecompute"] | tonumber), logicalCpus: .NodeResources.Cpu.TotalCpuCores}' <<< "$node")"
  probe="$(cat "$here/probe.sh")" || fail "cannot read probe.sh"
  jobs=""
  trap deregister_jobs EXIT

  # probe_job <kind> <cpu millis> [seconds] [--oom]: submit one probe session.
  probe_job() {
    local oom=false
    [[ "${4:-}" == --oom ]] && oom=true
    submit "$(jq -n --arg id "lab-probe-$1-$(random_hex)" --argjson capacity "$capacity" --argjson cpu "$2" \
      --arg image "$image" --arg probe "$probe" --arg seconds "${3:-12}" --argjson oom "$oom" '{
      id: $id, platform: "linux", nodeClass: "lab-prototype-linux", cpuCapacity: $capacity,
      budget: {cpuMillis: $cpu, memoryMiB: (if $oom then 128 else 256 end)}, image: $image,
      command: (["bash", "-c", $probe, "probe", "--seconds", $seconds] + (if $oom then ["--oom"] else [] end))}')"
  }

  # Three independent sessions, like two measured experiments and a concurrent build.
  for kind in solve-a:500 solve-b:500 build:250; do
    probe_job "${kind%%:*}" "${kind#*:}"
    wait_allocation "$job" > /dev/null
  done
  echo "Three bounded sessions started on one node"
  first="$jobs"
  for each in $first; do
    alloc="$(wait_allocation "$each" terminal)" || exit 1
    allocations="$allocations$alloc"$'\n'
    task="$(jq -c .TaskStates.worker <<< "$alloc")"
    [[ "$(jq -r .Failed <<< "$task")" != true ]] || fail "resource probe failed: $(jq -c .Events <<< "$task")"
    case "$each" in lab-probe-build-*) budget=250 ;; *) budget=500 ;; esac
    report="$(allocation_logs "$(jq -r .ID <<< "$alloc")" | sed -e '/^[[:space:]]*$/d' | tail -n 1)" ||
      fail "cannot read the logs of $each"
    jq -e . <<< "$report" > /dev/null 2>&1 || fail "verification failed: no probe report from $each: $report"
    check "$(jq --argjson budget "$budget" '(.cpuMax | split(" ") | map(tonumber)) as [$quota, $period]
      | 0 <= $budget / 1000 - $quota / $period and $budget / 1000 - $quota / $period < 0.02' <<< "$report")" "$report"
    check "$(jq '(.memoryMax | tonumber) == 256 * 1024 * 1024' <<< "$report")" "$report"
    check "$(jq --argjson budget "$budget" '.cpuRate <= $budget / 1000 + 0.15' <<< "$report")" "$report"
    check "$(jq '(.cpuAfter.nr_throttled | tonumber) > (.cpuBefore.nr_throttled | tonumber)' <<< "$report")" "$report"
    check "$(jq '.childExits == [0, 0, 0]' <<< "$report")" "$report"
    reports="$(jq -c --argjson report "$report" '. + [$report]' <<< "$reports")"
  done
  check "$(jq -s '[.[].NodeID] | unique | length == 1' <<< "$allocations")" "sessions used different nodes"
  # Nomad's RFC 3339 times compare as text, as the original prototype compared them.
  check "$(jq -s '([.[].TaskStates.worker.StartedAt] | max) < ([.[].TaskStates.worker.FinishedAt] | min)' <<< "$allocations")" \
    "sessions did not overlap"
  echo "Verified shared placement, CPU quotas, throttling and memory ceilings for detached children"

  probe_job oom 250 12 --oom
  oom="$(wait_allocation "$job" terminal)" || exit 1
  task="$(jq -c .TaskStates.worker <<< "$oom")"
  check "$(jq .Failed <<< "$task")" "memory-exhaustion probe unexpectedly succeeded"
  check "$(jq 'any(.Events[]; .ExitCode == 137 or (tojson | ascii_downcase | contains("oom")))' <<< "$task")" "$task"
  echo "Verified memory exhaustion terminates the bounded task"

  probe_job cancel 250 120
  local cancel_job="$job" deadline
  cancel="$(wait_allocation "$cancel_job" | jq -r .ID)" || exit 1
  running="$(containers "$cancel")" || exit 1
  [[ -n "$running" ]] || fail "verification failed: cancel probe has no running container"
  # Give its detached descendants time to start before cancelling.
  sleep 2
  deregister "$cancel_job" || exit 1
  deadline=$(($(now_ms) + 45000))
  while [[ -n "$running" ]] && (($(now_ms) < deadline)); do
    sleep 0.5
    running="$(containers "$cancel")" || exit 1
  done
  [[ -z "$running" ]] || fail "verification failed: cancel left a container and its detached processes running"
  echo "Verified cancellation cleans up the task container and detached descendants"
  jq -n --arg version "$(hashicorp_pin nomad.version)" --arg image "$image" --argjson capacity "$capacity" \
    --argjson reports "$reports" '{nomadVersion: $version, image: $image, capacity: $capacity, resourceProbes: $reports,
      oom: "passed", cancel: "passed", nativeMac: "not-run"}' > "$state/verification.json"
}

native_probe() {
  [[ "$(uname -s)" == Darwin ]] || fail "native-probe requires the Mac; Linux hard-limit checks use verify"
  local node result alloc
  node="$(one_ready_node "native probe requires exactly one ready sandbox node")" || exit 1
  jobs=""
  trap deregister_jobs EXIT
  submit "$(jq -n --arg id "lab-probe-native-$(random_hex)" --argjson node "$node" --arg probe "$here/probe.sh" '{
    id: $id, platform: "mac", nodeClass: "lab-prototype-mac",
    cpuCapacity: {usableMHz: ($node.Attributes["cpu.usablecompute"] | tonumber),
      logicalCpus: $node.NodeResources.Cpu.TotalCpuCores},
    budget: {cpuMillis: 1000, memoryMiB: 256}, command: ["/bin/bash", $probe, "--seconds", "12"]}')"
  alloc="$(wait_allocation "$job" terminal)" || exit 1
  check "$(jq '.TaskStates.worker.Failed | not' <<< "$alloc")" "$(jq -c .TaskStates.worker <<< "$alloc")"
  result="$(allocation_logs "$(jq -r .ID <<< "$alloc")" | sed -e '/^[[:space:]]*$/d' | tail -n 1)" ||
    fail "cannot read the native probe's logs"
  check "$(jq '.platform == "darwin" and .enforcement == "advisory-native"' <<< "$result" 2> /dev/null || echo false)" "$result"
  jq . <<< "$result" > "$state/native-verification.json"
  echo "Native Mac process probe passed; real solver/JFR and abrupt-death supervision gates remain"
}

usage() {
  echo "usage: sandbox.sh [--state DIR] [--port N] [--lab LAUNCHER] [--image IMAGE] install|start|stop|verify|native-probe|cli [ARGS...]" >&2
  echo "sandbox.sh: $1" >&2
  exit 2
}

main() {
  local state_dir="$root/build/nomad-prototype" action
  port=14646
  lab="$root/build/install/eignex-lab/bin/eignex-lab"
  image="$(hashicorp_pin probe_image)" || exit 1
  while (($# > 0)); do
    case "$1" in
    --state | --port | --lab | --image)
      (($# > 1)) || usage "$1 needs a value"
      case "$1" in
      --state) state_dir="$2" ;;
      --port) port="$2" ;;
      --lab) lab="$2" ;;
      --image) image="$2" ;;
      esac
      shift 2 ;;
    install | start | stop | verify | native-probe | cli) action="$1"; shift; break ;;
    *) usage "unknown argument $1" ;;
    esac
  done
  [[ -n "${action:-}" ]] || usage "an action is required"
  if ! [[ "$port" =~ ^[0-9]+$ ]] || ((10#$port < 1024 || 10#$port > 65533)); then usage "port must be 1024..65533"; fi
  port=$((10#$port))
  sandbox_init "$state_dir" "$port"
  case "$action" in
  install) install ;;
  start) start ;;
  stop) stop ;;
  verify) verify ;;
  native-probe) native_probe ;;
  cli) run cli "$@" ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
