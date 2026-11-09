#!/usr/bin/env bash
# Bounded resource probe, also usable natively on the Mac. It never connects to the lab or a provider.
#
# probe.sh [--seconds 1..120] [--children 1..8] [--oom]
#
# Starts CPU-bound children in detached sessions, waits for them and prints one JSON line: the cgroup CPU and memory
# limits and counters it ran under, and the CPU it and its children used. --oom instead outgrows the cgroup's memory
# limit (at most 512 MiB), so the bounded task is killed. sandbox.sh runs this text with `bash -c` inside a plain
# image, and Nomad interpolates dollar-brace sequences in task arguments: this file must never contain one. Written for
# macOS's bash 3.2; needs only coreutils, plus setsid or perl to detach the children.
set -o pipefail
export LC_ALL=C  # decimal points in times and awk

usage() { echo "probe: $1" >&2; exit 2; }

seconds=12
children=3
oom=0
while (( $# > 0 )); do
  case "$1" in
  --seconds | --children)
    (( $# > 1 )) || usage "$1 needs a value"
    if [[ "$1" == --seconds ]]; then seconds="$2"; else children="$2"; fi
    shift 2 ;;
  --oom) oom=1; shift ;;
  *) usage "unknown argument $1" ;;
  esac
done
if ! [[ "$seconds" =~ ^[0-9]+([.][0-9]+)?$ && "$children" =~ ^[0-9]+$ ]] ||
  ! awk -v s="$seconds" -v c="$children" 'BEGIN { exit !(s >= 1 && s <= 120 && c >= 1 && c <= 8) }'; then
  usage "seconds must be 1..120 and children 1..8"
fi
children=$((10#$children))

# cgroup_file <name>: the trimmed contents of the cgroup v2 file, or nothing (status 1) without one.
cgroup_file() {
  [[ -f "/sys/fs/cgroup/$1" ]] || return 1
  local value
  value="$(cat "/sys/fs/cgroup/$1")" || return 1
  printf %s "$value"
}

# json_string <name>: the cgroup file as a JSON string, or null.
json_string() {
  local value
  if value="$(cgroup_file "$1")"; then printf '"%s"' "$value"; else printf null; fi
}

# counters <name>: the cgroup file's "key value" lines as a JSON object of strings, empty without one.
counters() {
  local text
  text="$(cgroup_file "$1")" || { printf '{}'; return; }
  printf '%s\n' "$text" | awk 'NF == 2 { printf "%s\"%s\":\"%s\"", (n++ ? "," : ""), $1, $2 } BEGIN { printf "{" } END { printf "}" }'
}

# now: wall-clock seconds, fractional where this bash can tell.
now() {
  if [[ -n "$EPOCHREALTIME" ]]; then echo "$EPOCHREALTIME" | tr , .; else date +%s; fi
}

if (( oom )); then
  # Only run this mode in the bounded Linux container. Fail before allocating on a native host.
  limit="$(cgroup_file memory.max)" || usage "OOM probe requires a cgroup memory.max of at most 512 MiB"
  if ! [[ "$limit" =~ ^[0-9]+$ ]] || (( limit > 512 * 1024 * 1024 )); then
    usage "OOM probe requires a cgroup memory.max of at most 512 MiB"
  fi
  printf '{"oomProbe": true, "memoryMax": "%s"}\n' "$limit"
  # This shell itself holds twice the limit in one string, so the kernel ends the task.
  allocation="$(head -c $((limit * 2)) /dev/zero | tr '\0' x)"
  printf %s "$allocation" | wc -c
  exit 1
fi

# detached <command...>: run the command in a session of its own, replacing this process.
detached() {
  if command -v setsid > /dev/null; then exec setsid "$@"; fi
  exec perl -MPOSIX -e 'POSIX::setsid() or die "setsid: $!\n"; exec @ARGV or die "exec: $!\n"' "$@"
}

before="$(counters cpu.stat)"
start="$(now)"
pids=""
# Detached sessions exercise cleanup of descendants outside the immediate process group.
# shellcheck disable=SC2016 # each child expands it
program='trap "exit 0" TERM; (sleep "$1"; kill -TERM $$) & x=1; while :; do x=$(( (x * 3 + 1) % 1000003 )); done'
for (( i = 0; i < children; i++ )); do
  ( detached bash -c "$program" child "$seconds" ) &
  pids="$pids $!"
done
# shellcheck disable=SC2086 # the PIDs are words
trap 'kill -KILL $pids 2> /dev/null' EXIT
exits=""
any=0
for pid in $pids; do
  wait "$pid"
  status=$?
  exits="$exits,$status"
  any=$((any | status))
done
exits="$(printf %s "$exits" | sed 's/^,//')"
trap - EXIT
elapsed="$(awk -v a="$start" -v b="$(now)" 'BEGIN { printf "%.6f", b - a }')"
# `times` prints this shell's user and system time, then its waited children's, as 0m1.234s pairs.
# Run here, not in a command substitution, whose subshell would report only its own times.
used="$(mktemp)" || exit 1
times > "$used"
cpu="$(awk '{ for (i = 1; i <= NF; i++) { split($i, t, "m"); sub("s", "", t[2]); sum += t[1] * 60 + t[2] } }
  END { printf "%.6f", sum }' "$used")"
rm -f "$used"
rate="$(awk -v c="$cpu" -v e="$elapsed" 'BEGIN { printf "%.6f", (e > 0 ? c / e : 0) }')"
if cgroup_file cpu.max > /dev/null; then enforcement=hard-quota; else enforcement=advisory-native; fi
printf '{"platform": "%s", "cpuMax": %s, "memoryMax": %s, "cpuBefore": %s, "cpuAfter": %s, "memoryEvents": %s, ' \
  "$(uname -s | tr '[:upper:]' '[:lower:]')" "$(json_string cpu.max)" "$(json_string memory.max)" "$before" \
  "$(counters cpu.stat)" "$(counters memory.events)"
printf '"wallSeconds": %s, "cpuSeconds": %s, "cpuRate": %s, "children": %s, "childExits": [%s], "pid": %s, ' \
  "$elapsed" "$cpu" "$rate" "$children" "$exits" "$$"
printf '"enforcement": "%s"}\n' "$enforcement"
(( any == 0 ))
