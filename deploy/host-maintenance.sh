#!/usr/bin/env bash
# Bound local Gradle output caches, rotate stopped daemons' logs and bound the lab services' own logs; when the dynamic
# DNS settings file is present, follow the connection's public address with public-address.sh beside this script.
#
# host-maintenance.sh --data DIR [--gradle-home DIR]... [--cache-gib N] [--log-mib N] [--copies N]
#   [--log-age-days N] [--dns-settings FILE] [--quiet]
#
# Run periodically as the lab user: launchd's com.eignex.lab.gradle-maintenance runs a copy in $LAB_DATA/maintenance
# every five minutes. The report goes to $LAB_DATA/maintenance/gradle-maintenance.json and, on errors or without
# --quiet, to stdout as one line; the exit status is 1 when it holds errors. Cache eviction takes Gradle's own POSIX
# byte-range lock without modifying its lock protocol; bash cannot take one, so zsh's zsystem flock (fcntl) holds it.
# Active daemons' logs are left alone: Gradle 9.7 opens them without O_APPEND, so copy/truncate disrupts live readers.
# Written for macOS's bash 3.2.
set -uo pipefail
export LC_ALL=C
shopt -s nullglob dotglob

self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
locked_status=75

fail() { printf '%s\n' "$*" >&2; exit 1; }

if stat -c %s / >/dev/null 2>&1; then
  size_of() { stat -c %s -- "$1"; }
  mtime_of() { stat -c %Y -- "$1"; }
  entry_format=(-c '%X %Y %s %n')
else
  size_of() { stat -f %z -- "$1"; }
  mtime_of() { stat -f %m -- "$1"; }
  entry_format=(-f '%a %m %z %N')
fi

# with_lock <file> <command...>: run the command while this holds a POSIX (fcntl) write lock on all of <file>, as
# Gradle's and Python's lockf take; <file> must exist and is never created. Status 75 at once when another process
# holds it, else the command's.
with_lock() {
  zsh -f -c 'zmodload zsh/system || exit 1
sysopen -r -w -u probe "$1" || exit 1
exec {probe}>&-
zsystem flock -t 0 -f held "$1" 2>/dev/null || exit '"$locked_status"'
shift
# Not the last command, so zsh forks it rather than exec, which would drop the lock.
"$@"
exit $?' with_lock "$@"
}

json_string() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\b'/\\b}"
  s="${s//$'\f'/\\f}"
  printf '"%s"' "$s"
}

# json_format compact|indent: lay out minified JSON from stdin as Python's json.dumps does by default or with
# indent=2, without a trailing newline.
json_format() {
  awk -v mode="$1" '
    function newline(depth,  s, i) { s = "\n"; for (i = 0; i < depth; i++) s = s "  "; return s }
    { text = text $0 }
    END {
      n = length(text); depth = 0; quoted = 0; out = ""
      for (i = 1; i <= n; i++) {
        c = substr(text, i, 1)
        if (quoted) {
          out = out c
          if (c == "\\") { i++; out = out substr(text, i, 1) } else if (c == "\"") quoted = 0
        } else if (c == "\"") { quoted = 1; out = out c }
        else if (c == "{" || c == "[") {
          next_char = substr(text, i + 1, 1)
          if (next_char == "}" || next_char == "]") { out = out c next_char; i++ }
          else { depth++; out = out c (mode == "indent" ? newline(depth) : "") }
        }
        else if (c == "}" || c == "]") { depth--; out = out (mode == "indent" ? newline(depth) : "") c }
        else if (c == ",") out = out "," (mode == "indent" ? newline(depth) : " ")
        else if (c == ":") out = out ": "
        else out = out c
      }
      printf "%s", out
    }'
}

# The process IDs of running Gradle daemons, as " 12 34 ".
active_daemons() {
  local output
  output="$(ps -axo pid=,command=)" || return 1
  awk '$1 ~ /^[0-9]+$/ && index($0, "org.gradle.launcher.daemon.bootstrap.GradleDaemon") { printf " %s", $1 + 0 }
    END { print " " }' <<< "$output"
}

# rotate <log> <limit bytes> <copies>: keep bounded diagnostic tails, including on the first oversized log. Keeps the
# log's last <limit> bytes as <log>.1.gz, shifting older tails up to <log>.<copies>.gz, and truncates the log in place.
# Status 1 when the log is within its limit; exits on failure.
rotate() {
  local path="$1" limit="$2" copies="$3" size temporary index previous
  [[ -r "$path" && -w "$path" ]] || fail "cannot open $path to rotate it"
  size="$(size_of "$path")" || fail "cannot read the size of $path"
  (( size > limit )) || return 1
  temporary="$(mktemp "$(dirname "$path")/.log-rotation-XXXXXX")" || fail "cannot create a temporary file beside $path"
  tail -c "+$((size - limit + 1))" "$path" | head -c "$limit" | gzip -9 -n > "$temporary"
  local statuses="${PIPESTATUS[*]}"
  # tail may see head close the pipe when the log grew meanwhile.
  if [[ "$statuses" != "0 0 0" && "$statuses" != "141 0 0" ]]; then
    rm -f "$temporary"
    fail "cannot compress the tail of $path"
  fi
  # GNU sync flushes the one file; macOS's syncs everything.
  sync "$temporary" 2>/dev/null || sync
  for ((index = copies; index > 1; index--)); do
    previous="$path.$((index - 1)).gz"
    if [[ -f "$previous" && ! -L "$previous" ]] && ! mv -f "$previous" "$path.$index.gz"; then
      rm -f "$temporary"
      fail "cannot shift $previous"
    fi
  done
  mv -f "$temporary" "$path.1.gz" || { rm -f "$temporary"; fail "cannot keep the tail of $path"; }
  : > "$path" || fail "cannot truncate $path"
}

# evict <cache> <limit bytes>, run while holding the cache's lock: remove the least recently used outputs until the
# cache fits. Prints the bytes removed, a space and the cache's report.
evict() {
  local cache="$1" limit="$2" listing total victims removed=0
  cd "$cache" || fail "cannot enter $cache"
  listing="$(find . -mindepth 1 -maxdepth 1 -type f -exec stat "${entry_format[@]}" {} +)" || fail "cannot list $cache"
  # Gradle hashes are hexadecimal strings, sometimes without leading zeroes. Prints "used size name".
  listing="$(awk 'NF == 4 { name = substr($4, 3)
      if ($4 ~ /^\.\// && length(name) >= 1 && length(name) <= 32 && name !~ /[^0-9a-f]/)
        { used = $1 > $2 ? $1 : $2; print used + 0, $3, name } }' <<< "$listing")"
  total="$(awk '{ s += $2 } END { printf "%.0f", s }' <<< "$listing")"
  victims="$(sort -n -k1,1 <<< "$listing" |
    awk -v total="$total" -v limit="$limit" 'total + 0 <= limit + 0 { exit } { print $3, $2; total -= $2 }')"
  if [[ -n "$victims" ]]; then
    awk '{ print $1 }' <<< "$victims" | xargs rm -f -- || fail "cannot remove outputs from $cache"
    removed="$(awk '{ s += $2 } END { printf "%.0f", s }' <<< "$victims")"
  fi
  printf '%s {"path":%s,"bytes":%s,"limitBytes":%s}\n' "$removed" "$(json_string "$cache")" \
    "$(awk -v t="$total" -v r="$removed" 'BEGIN { printf "%.0f", t - r }')" "$limit"
}

# clean_home <home> <active daemons> <cache bytes> <log bytes> <copies> <age days> <now>: prints the home's report.
clean_home() {
  local home="$1" active="$2" cache_bytes="$3" log_bytes="$4" copies="$5" age_days="$6" now="$7"
  local rotated=0 expired=0 skipped=0 removed=0 caches="" version path name pid mtime cache lock out status
  local daemon_log='^daemon-([0-9]+)\.out\.log(\.[1-9][0-9]*\.gz)?$'
  if [[ -d "$home/daemon" && ! -L "$home/daemon" ]]; then
    for version in "$home/daemon"/*; do
      [[ -d "$version" && ! -L "$version" ]] || continue
      for path in "$version"/*; do
        name="${path##*/}"
        [[ "$name" =~ $daemon_log ]] || continue
        pid="${BASH_REMATCH[1]}"
        [[ -f "$path" && ! -L "$path" ]] || continue
        if [[ "$name" == *.log ]] && (( ${#pid} < 19 )) && [[ "$active" == *" $((10#$pid)) "* ]]; then
          skipped=$((skipped + 1))
          continue
        fi
        mtime="$(mtime_of "$path")" || fail "cannot read the age of $path"
        if (( now - mtime > age_days * 86400 )); then
          rm -f -- "$path" || fail "cannot remove $path"
          expired=$((expired + 1))
        elif [[ "$name" == *.log ]] && rotate "$path" "$log_bytes" "$copies"; then
          rotated=$((rotated + 1))
        fi
      done
    done
  fi
  if [[ -d "$home/caches" && ! -L "$home/caches" ]]; then
    for cache in "$home/caches"/build-cache-*; do
      [[ -d "$cache" && ! -L "$cache" ]] || continue
      lock="$cache/${cache##*/}.lock"
      if [[ ! -f "$lock" || -L "$lock" ]]; then
        caches+=",{\"path\":$(json_string "$cache"),\"skipped\":\"missing regular Gradle lock\"}"
        continue
      fi
      out="$(with_lock "$lock" "$BASH" "$self" __evict "$cache" "$cache_bytes")"
      status=$?
      case "$status" in
      0) removed=$((removed + ${out%% *})); caches+=",${out#* }" ;;
      "$locked_status") caches+=",{\"path\":$(json_string "$cache"),\"skipped\":\"in use\"}" ;;
      *) exit 1 ;;  # the reason is on stderr
      esac
    done
  fi
  printf '{"home":%s,"rotated":%s,"expiredLogs":%s,"activeLogsSkipped":%s,"cacheBytesRemoved":%s,"caches":[%s]}\n' \
    "$(json_string "$home")" "$rotated" "$expired" "$skipped" "$removed" "${caches#,}"
}

# clean_service_logs <logs> <log bytes> <copies> <age days> <now>: the lab services' logs under <logs>. launchd appends
# to them, so an in-place truncation is safe while they run. A log nothing has written to for <age days> belongs to a
# removed service and goes, with its rotated tails. Prints the report.
clean_service_logs() {
  local logs="$1" log_bytes="$2" copies="$3" age_days="$4" now="$5"
  local rotated="" removed="" path name base tail expired mtime
  local rotated_tail='^(.+\.log)\.[1-9][0-9]*\.gz$'
  if [[ -d "$logs" ]]; then
    for path in "$logs"/*; do
      [[ -f "$path" && ! -L "$path" ]] || continue
      name="${path##*/}"
      if [[ "$name" =~ $rotated_tail ]]; then
        tail=1
        base="$logs/${BASH_REMATCH[1]}"
      else
        tail=""
        base="$path"
        [[ "$name" == *.log ]] || continue
      fi
      expired=1
      if [[ -e "$base" ]]; then
        mtime="$(mtime_of "$base")" || fail "cannot read the age of $base"
        (( now - mtime > age_days * 86400 )) || expired=""
      fi
      if [[ -n "$expired" ]]; then
        rm -f -- "$path" || fail "cannot remove $path"
        removed+=",$(json_string "$name")"
      elif [[ -z "$tail" ]] && rotate "$path" "$log_bytes" "$copies"; then
        rotated+=",$(json_string "$name")"
      fi
    done
  fi
  printf '{"path":%s,"rotated":[%s],"removed":[%s]}\n' "$(json_string "$logs")" "${rotated#,}" "${removed#,}"
}

# resolve <path>: absolute, with ~ expanded and symlinks in its existing part resolved, as Python's Path.resolve().
resolve() {
  local path="$1" rest=""
  # shellcheck disable=SC2088 # a literal ~ from the command line, as Python's expanduser takes it
  [[ "$path" == "~" || "$path" == "~/"* ]] && path="$HOME${path#\~}"
  [[ "$path" == /* ]] || path="$PWD/$path"
  while [[ "$path" == ?*/ ]]; do path="${path%/}"; done
  while [[ ! -d "$path" ]]; do
    rest="/${path##*/}$rest"
    path="${path%/*}"
    [[ -n "$path" ]] || path=/
  done
  path="$(cd "$path" && pwd -P)"
  printf '%s\n' "${path%/}$rest"
}

# integer <[+-]digits>: its value, leading zeroes being decimal.
integer() {
  local digits="${1#[+-]}"
  digits="$((10#$digits))"
  if [[ "$1" == -* ]]; then echo "$((-digits))"; else echo "$digits"; fi
}

usage() {
  echo "usage: host-maintenance.sh --data DIR [--gradle-home DIR]... [--cache-gib N] [--log-mib N] [--copies N]" >&2
  echo "  [--log-age-days N] [--dns-settings FILE] [--quiet]" >&2
  [[ -z "${1:-}" ]] || echo "host-maintenance.sh: error: $1" >&2
  exit 2
}

# Sets data, homes (newline-separated), cache_bytes, log_bytes, copies, age_days, dns_settings and quiet.
parse() {
  local cache_gib=2 log_mib=20 option value number='^[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?$'
  data="" homes="" copies=3 age_days=7 quiet="" dns_settings="$HOME/.config/eignex-lab/dynamic-dns.json"
  while (( $# )); do
    option="$1"
    case "$option" in
    --quiet) quiet=1; shift; continue ;;
    --*=*) value="${option#*=}"; option="${option%%=*}"; shift ;;
    --data | --gradle-home | --cache-gib | --log-mib | --copies | --log-age-days | --dns-settings)
      (( $# >= 2 )) || usage "argument $option: expected one argument"
      value="$2"; shift 2 ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
    case "$option" in
    --data) data="$value" ;;
    --gradle-home) homes+="$value"$'\n' ;;
    --cache-gib) [[ "$value" =~ $number ]] || usage "argument --cache-gib: invalid float value: '$value'"; cache_gib="$value" ;;
    --log-mib) [[ "$value" =~ $number ]] || usage "argument --log-mib: invalid float value: '$value'"; log_mib="$value" ;;
    --copies) [[ "$value" =~ ^[+-]?[0-9]+$ ]] || usage "argument --copies: invalid int value: '$value'"; copies="$value" ;;
    --log-age-days) [[ "$value" =~ ^[+-]?[0-9]+$ ]] || usage "argument --log-age-days: invalid int value: '$value'"
      age_days="$value" ;;
    --dns-settings) dns_settings="$value" ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
  done
  [[ -n "$data" ]] || usage "the following arguments are required: --data"
  copies="$(integer "$copies")" age_days="$(integer "$age_days")"
  if ! awk -v a="$cache_gib" -v b="$log_mib" 'BEGIN { exit !(a + 0 > 0 && b + 0 > 0) }' || (( copies <= 0 || age_days <= 0 )); then
    usage "retention limits must be positive"
  fi
  cache_bytes="$(awk -v g="$cache_gib" 'BEGIN { printf "%.0f", int(g * 1073741824) }')"
  log_bytes="$(awk -v m="$log_mib" 'BEGIN { printf "%.0f", int(m * 1048576) }')"
  while [[ "$data" == ?*/ ]]; do data="${data%/}"; done
  [[ -n "$homes" ]] || homes="$data/gradle"$'\n'"$HOME/.gradle"$'\n'
}

# The run itself, while holding the maintenance lock.
maintain() {
  parse "$@"
  local directory="$data/maintenance" errors="" homes_report="" logs_report="" address_report="" report
  local active now home seen=$'\n' out temporary
  reason="$(mktemp "${TMPDIR:-/tmp}/host-maintenance.XXXXXX")" || exit 1
  trap 'rm -f "$reason"' EXIT
  active="$(active_daemons)" || fail "cannot read the process inventory"  # fail closed
  now="$(date +%s)"
  while IFS= read -r home; do
    [[ -n "$home" ]] || continue
    home="$(resolve "$home")" || continue
    [[ "$seen" == *$'\n'"$home"$'\n'* ]] && continue
    seen+="$home"$'\n'
    if out="$(clean_home "$home" "$active" "$cache_bytes" "$log_bytes" "$copies" "$age_days" "$now" 2> "$reason")"; then
      homes_report+=",$out"
    else
      errors+=",{\"home\":$(json_string "$home"),\"error\":$(json_string "$(failure "$reason")")}"
    fi
  done <<< "$homes"
  if out="$(clean_service_logs "$data/logs" "$log_bytes" "$copies" "$age_days" "$now" 2> "$reason")"; then
    logs_report="$out"
  else
    errors+=",{\"logs\":$(json_string "$data/logs"),\"error\":$(json_string "$(failure "$reason")")}"
  fi
  if [[ -f "$dns_settings" ]]; then
    if out="$("$BASH" "$(dirname "$self")/public-address.sh" --data "$data" --settings "$dns_settings" 2> "$reason")"; then
      address_report="$out"
    else
      errors+=",{\"publicAddress\":$(json_string "$(failure "$reason")")}"
    fi
  fi
  report="{\"at\":$now,\"homes\":[${homes_report#,}],\"errors\":[${errors#,}]"
  [[ -z "$logs_report" ]] || report+=",\"logs\":$logs_report"
  [[ -z "$address_report" ]] || report+=",\"publicAddress\":$address_report"
  report+="}"
  temporary="$(mktemp "$directory/tmpXXXXXXXX")" || fail "cannot write the report in $directory"
  json_format indent <<< "$report" > "$temporary" || { rm -f "$temporary"; fail "cannot write the report"; }
  mv -f "$temporary" "$directory/gradle-maintenance.json" || { rm -f "$temporary"; fail "cannot write the report"; }
  if [[ -z "$quiet" || -n "$errors" ]]; then json_format compact <<< "$report"; echo; fi
  [[ -z "$errors" ]]
}

# failure <file>: a command's error output as one line.
failure() {
  local text
  text="$(awk 'NF { printf "%s%s", sep, $0; sep = "; " }' "$1")"
  printf '%s\n' "${text:-failed without a reason}"
}

main() {
  parse "$@"
  local directory="$data/maintenance" status
  # shellcheck disable=SC2174 # only the maintenance directory itself is private
  mkdir -p -m 700 "$directory" || exit 1
  : >> "$directory/gradle-maintenance.lock" || exit 1
  with_lock "$directory/gradle-maintenance.lock" "$BASH" "$self" __maintain "$@"
  status=$?
  if (( status == locked_status )); then
    [[ -n "$quiet" ]] || echo '{"skipped": "maintenance already running"}'
    exit 0
  fi
  exit "$status"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
  __evict) shift; evict "$@" ;;
  __maintain) shift; maintain "$@" ;;
  *) main "$@" ;;
  esac
fi
