# shellcheck shell=bash
# Sourced by install.sh and nomad/native-services.sh: the lab's one launchd plist writer and job reloader. Written for
# macOS's bash 3.2.

# plist_escape <text>: <text> with &, < and > escaped as plistlib escapes them.
plist_escape() {
  local text="$1"
  # Unquoted, so that bash 3.2 removes the quotes that keep bash 5.2+ from reading & as the match.
  text=${text//&/"&amp;"}
  text=${text//</"&lt;"}
  text=${text//>/"&gt;"}
  printf '%s' "$text"
}

# launchd_plist <label> <user> <working dir> <log> <keep alive> <start interval> <program argument>...
# Prints a launchd daemon's plist byte for byte as Python's plistlib.dumps writes it: keys sorted, tab-indented. The
# job's environment comes as KEY=VALUE lines on stdin, a later line for the same key winning. <keep alive> is true,
# false or on-failure (start again only after an unsuccessful exit); an empty <start interval> leaves the job unscheduled.
launchd_plist() {
  local label="$1" user="$2" workdir="$3" log="$4" keepalive="$5" interval="$6" argument line environment
  shift 6
  # Sorted as key, tab, value: a tab sorts before anything in a key, so the lines sort as their keys do.
  environment="$(awk '/=/ { key = $0; sub(/=.*/, "", key); value[key] = substr($0, length(key) + 2)
      if (!(key in seen)) { seen[key] = 1; keys[++n] = key } }
    END { for (i = 1; i <= n; i++) print keys[i] "\t" value[keys[i]] }' | LC_ALL=C sort)"
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' \
    '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">' \
    '<plist version="1.0">' '<dict>' $'\t<key>EnvironmentVariables</key>'
  if [[ -z "$environment" ]]; then
    printf '\t<dict/>\n'
  else
    printf '\t<dict>\n'
    while IFS= read -r line; do
      printf '\t\t<key>%s</key>\n\t\t<string>%s</string>\n' "$(plist_escape "${line%%$'\t'*}")" \
        "$(plist_escape "${line#*$'\t'}")"
    done <<< "$environment"
    printf '\t</dict>\n'
  fi
  printf '\t<key>KeepAlive</key>\n'
  case "$keepalive" in
  true) printf '\t<true/>\n' ;;
  false) printf '\t<false/>\n' ;;
  on-failure) printf '\t<dict>\n\t\t<key>SuccessfulExit</key>\n\t\t<false/>\n\t</dict>\n' ;;
  *) echo "launchd_plist: keep alive must be true, false or on-failure, not $keepalive" >&2; return 1 ;;
  esac
  printf '\t<key>Label</key>\n\t<string>%s</string>\n' "$(plist_escape "$label")"
  printf '\t<key>ProgramArguments</key>\n'
  if (( $# == 0 )); then
    printf '\t<array/>\n'
  else
    printf '\t<array>\n'
    for argument in "$@"; do printf '\t\t<string>%s</string>\n' "$(plist_escape "$argument")"; done
    printf '\t</array>\n'
  fi
  printf '\t<key>RunAtLoad</key>\n\t<true/>\n'
  printf '\t<key>StandardErrorPath</key>\n\t<string>%s</string>\n' "$(plist_escape "$log")"
  printf '\t<key>StandardOutPath</key>\n\t<string>%s</string>\n' "$(plist_escape "$log")"
  [[ -z "$interval" ]] || printf '\t<key>StartInterval</key>\n\t<integer>%s</integer>\n' "$interval"
  printf '\t<key>ThrottleInterval</key>\n\t<integer>30</integer>\n'
  printf '\t<key>UserName</key>\n\t<string>%s</string>\n' "$(plist_escape "$user")"
  printf '\t<key>WorkingDirectory</key>\n\t<string>%s</string>\n' "$(plist_escape "$workdir")"
  printf '%s\n' '</dict>' '</plist>'
}

# reload_daemon <label> <plist file>: replace the system launchd daemon <label> with <plist file>. A loaded job is
# booted out first; nothing is replaced when that fails.
reload_daemon() {
  local label="$1" plist="$2" target="/Library/LaunchDaemons/$1.plist"
  if sudo launchctl print "system/$label" >/dev/null 2>&1; then
    sudo launchctl bootout "system/$label" || return 1
    # bootout returns before the job is gone, and bootstrapping the label while it is
    # still loaded fails with "Input/output error".
    for _ in $(seq 60); do sudo launchctl print "system/$label" >/dev/null 2>&1 || break; sleep 1; done
  fi
  sudo install -m 644 -o root -g wheel "$plist" "$target" || return 1
  sudo launchctl bootstrap system "$target"
}
