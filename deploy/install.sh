#!/usr/bin/env bash
# Build eignex-lab and register its services with the host's service manager: launchd daemons on macOS, which
# start at boot without a login and run as this user, and systemd user units with lingering on Linux.
# LAB_INSTALL_DRY_RUN=1 checks and writes the service files to a temporary directory, and registers nothing; it
# builds only when there is no build yet.
set -euo pipefail
dry="${LAB_INSTALL_DRY_RUN:-}"
here="$(cd "$(dirname "$0")/.." && pwd)"
. "$here/deploy/release.sh"
envfile="$HOME/.config/eignex-lab/lab.env"
if [[ ! -f "$envfile" ]]; then
  if [[ -n "$dry" ]]; then envfile="$here/deploy/lab.env.example"
  else mkdir -p "$(dirname "$envfile")"; cp "$here/deploy/lab.env.example" "$envfile"
  fi
fi
unset JAVA_HOME LAB_DOCKER LAB_PATH
set -a; . "$envfile"; set +a
LAB_DATA="${LAB_DATA:-$HOME/eignex-lab-data}"
[[ -n "$dry" ]] || mkdir -p "$LAB_DATA/logs"
os="$(uname -s)"

# LAB_JOBS or LAB_CORPUS on an external drive needs it mounted, at boot too, since the services start without a login.
# The lab holds its queue while it is not and never creates the path, so this only warns.
external=""
for var in LAB_JOBS LAB_CORPUS; do
  dir="${!var:-}"
  [[ "$dir" == /Volumes/?* ]] || continue
  external=1
  volume="/Volumes/$(cut -d/ -f3 <<< "$dir")"
  if ! mount | grep -qF " on $volume ("; then
    echo "warning: $var=$dir is on $volume, which is not mounted; the lab holds its queue until it is" >&2
  elif [[ ! -d "$dir" ]]; then
    echo "note: $var=$dir does not exist yet; the lab creates it on $volume"
  fi
done
if [[ -n "$external" && "$os" == Darwin ]] &&
  [[ "$(defaults read /Library/Preferences/SystemConfiguration/autodiskmount AutomountDisksWithoutUserLogin 2>/dev/null)" != 1 ]]; then
  echo "warning: external drives mount only at login; for the services to find it after a reboot run" >&2
  echo "  sudo defaults write /Library/Preferences/SystemConfiguration/autodiskmount AutomountDisksWithoutUserLogin -bool true" >&2
fi

# Toolchains are always provisioned, never taken from the host: a package-manager JDK can carry a trust store
# that lacks roots the corpus hosts use (Homebrew's openjdk refuses miplib.zib.de).
props="$HOME/.gradle/gradle.properties"
if [[ -z "$dry" ]] && ! grep -q '^org.gradle.java.installations.auto-detect=' "$props" 2>/dev/null; then
  mkdir -p "$(dirname "$props")"
  echo 'org.gradle.java.installations.auto-detect=false' >> "$props"
fi

# The services get a fixed PATH rather than this shell's, which may hold per-session directories that vanish.
case "$os" in
Darwin) path="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" ;;
Linux) path="$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" ;;
*) echo "unsupported OS $os"; exit 1 ;;
esac
path="${LAB_PATH:+$LAB_PATH:}$path"

# Docker serves the containerised reference solvers. colima runs it headless on macOS, as a daemon of its own.
docker_mode="${LAB_DOCKER:-}"
if [[ -z "$docker_mode" ]]; then
  if [[ "$os" == Darwin ]] && PATH="$path" command -v colima >/dev/null; then docker_mode=colima
  elif [[ "$os" == Linux ]] && PATH="$path" command -v docker >/dev/null; then docker_mode=system
  else docker_mode=none
  fi
fi
# The runner updates itself from this checkout between jobs (LAB_UPDATE_CHECK_SEC=0 turns that off).
service_env=("LAB_SOURCE=$here")
case "$docker_mode" in
colima) service_env+=("DOCKER_HOST=unix://$HOME/.colima/default/docker.sock" "LAB_REQUIRE_DOCKER=true") ;;
system) service_env+=("LAB_REQUIRE_DOCKER=true") ;;
none) ;;
*) echo "LAB_DOCKER must be colima, system or none, not $docker_mode"; exit 1 ;;
esac
echo "docker: $docker_mode"

# Gradle itself needs a Java to launch; any 17+ on PATH does. The services run on the toolchain JDK.
command -v java >/dev/null || [[ -n "${JAVA_HOME:-}" ]] || { echo "install any JDK 17+ so Gradle can launch"; exit 1; }
(cd "$here" && ./gradlew installDist --max-workers="${LAB_GRADLE_WORKERS:-2}" -q)
JAVA_HOME="${JAVA_HOME:-$(cd "$here" && ./gradlew -q printJavaHome)}"
export JAVA_HOME
echo "services run on $JAVA_HOME"
# macOS keeps a daemon off a removable volume until the binary it runs has Full Disk Access, and leaves its calls there
# blocked rather than failed; the lab then holds its queue with that reason until access is granted.
if [[ -n "$external" && "$os" == Darwin ]]; then
  java_bin="$(cd "$JAVA_HOME/bin" && pwd -P)/java"
  echo "warning: LAB_JOBS or LAB_CORPUS is on an external drive: grant Full Disk Access to the services' java," >&2
  echo "  $java_bin" >&2
  echo "  in System Settings → Privacy & Security → Full Disk Access: click +, press Cmd-Shift-G, paste the path above," >&2
  echo "  click Open and switch it on. Do it again whenever the JDK above changes." >&2
fi
if [[ -n "$dry" ]]; then bin="$here/build/install/eignex-lab/bin/eignex-lab"; else bin="$(release "$here" "$LAB_DATA")"; fi
"$bin" check   # fails here, before anything is registered, on a host without SIMD

# lab.env with comments, blanks and the keys set above removed, quotes stripped. macOS ships bash 3.2, where an
# empty array is unbound under `set -u`, hence the guarded expansion.
owned='JAVA_HOME|PATH|LAB_DOCKER|LAB_PATH|LAB_SOURCE'
[[ "$docker_mode" == colima ]] && owned="$owned|DOCKER_HOST|LAB_REQUIRE_DOCKER"
[[ "$docker_mode" == system ]] && owned="$owned|LAB_REQUIRE_DOCKER"
settings() {
  # grep exits 1 on a lab.env with nothing set, which pipefail would turn into a silent exit.
  { grep -v '^\s*#' "$envfile" | grep '=' | grep -vE "^($owned)=" || true; } |
    while IFS='=' read -r key value; do value="${value%\"}"; value="${value#\"}"; echo "$key=$value"; done
  for entry in ${service_env[@]+"${service_env[@]}"}; do echo "$entry"; done
}

xml() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' <<< "$1"; }

# Nomad's server/client/supervisor have independent lifetimes. Existing agents require an explicit maintenance
# command to change or restart; ordinary installs/updates may restart only the coordinator.
if [[ -n "${LAB_NOMAD_NATIVE_CONFIG:-}" ]]; then
  native_args=(--config "$LAB_NOMAD_NATIVE_CONFIG")
  if [[ -n "$dry" ]]; then native_args+=(--output "$(mktemp -d)")
  else native_args+=(--install)
  fi
  PATH="$path" python3 "$here/deploy/nomad/native_services.py" "${native_args[@]}"
fi

# plist <label> <keep-alive dict> <working dir> <log> <program args...>
plist() {
  local label="$1" keepalive="$2" workdir="$3" log="$4"
  shift 4
  cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$label</string>
  <key>UserName</key><string>$(id -un)</string>
  <key>ProgramArguments</key><array>
PLIST
  for arg in "$@"; do echo "    <string>$(xml "$arg")</string>"; done
  cat <<PLIST
  </array>
  <key>WorkingDirectory</key><string>$workdir</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key>$keepalive
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>$log</string>
  <key>StandardErrorPath</key><string>$log</string>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>$path</string>
    <key>HOME</key><string>$HOME</string>
    <key>JAVA_HOME</key><string>$JAVA_HOME</string>
PLIST
  settings | while IFS='=' read -r key value; do
    printf '    <key>%s</key><string>%s</string>\n' "$key" "$(xml "$value")"
  done
  echo '  </dict>'
  echo '</dict></plist>'
}

case "$os" in
Darwin)
  stage="$(mktemp -d)"
  roles=(api runner)
  [[ "$docker_mode" == colima ]] && roles=(colima api runner)
  for role in "${roles[@]}"; do
    label="com.eignex.lab.$role"
    case "$role" in
    colima) plist "$label" '<true/>' "$HOME" "$LAB_DATA/logs/colima.log" "$(PATH="$path" command -v colima)" start -f ;;
    # caffeinate holds the machine awake for as long as the runner lives, whatever the energy settings say.
    runner) plist "$label" '<dict><key>SuccessfulExit</key><false/></dict>' "$LAB_DATA" "$LAB_DATA/logs/runner.log" \
      /usr/bin/caffeinate -i "$bin" runner ;;
    api) plist "$label" '<dict><key>SuccessfulExit</key><false/></dict>' "$LAB_DATA" "$LAB_DATA/logs/api.log" "$bin" api ;;
    esac > "$stage/$label.plist"
    plutil -lint -s "$stage/$label.plist"
  done
  [[ -n "$dry" ]] && { echo "dry run: service files in $stage"; exit 0; }
  # Login agents under the same labels, or brew's own colima agent, would run a second copy once someone logs in.
  for agent in "$HOME"/Library/LaunchAgents/com.eignex.lab.*.plist "$HOME/Library/LaunchAgents/sh.brew.colima.plist"; do
    [[ -f "$agent" ]] || continue
    launchctl bootout "gui/$(id -u)" "$agent" 2>/dev/null || true
    mv "$agent" "$agent.disabled"
    echo "disabled login agent $agent"
  done
  # A colima started by hand would hold the VM the daemon is about to start.
  [[ "$docker_mode" == colima ]] && PATH="$path" colima stop >/dev/null 2>&1 || true
  for role in "${roles[@]}"; do
    label="com.eignex.lab.$role"
    sudo launchctl bootout "system/$label" 2>/dev/null || true
    # bootout returns before the job is gone (colima waits for its VM), and bootstrapping the label while it is
    # still loaded fails with "Input/output error".
    for _ in $(seq 60); do sudo launchctl print "system/$label" >/dev/null 2>&1 || break; sleep 1; done
    sudo install -m 644 -o root -g wheel "$stage/$label.plist" "/Library/LaunchDaemons/$label.plist"
    sudo launchctl bootstrap system "/Library/LaunchDaemons/$label.plist"
  done
  rm -rf "$stage"
  sudo pmset -a sleep 0 disksleep 0
  ;;
Linux)
  units="$HOME/.config/systemd/user"
  [[ -n "$dry" ]] && units="$(mktemp -d)"
  mkdir -p "$units"
  for role in api runner; do
    {
      cat <<UNIT
[Unit]
Description=eignex lab $role
After=network-online.target

[Service]
Environment=PATH=$path
Environment=JAVA_HOME=$JAVA_HOME
UNIT
      settings | while IFS= read -r line; do echo "Environment=\"$line\""; done
      cat <<UNIT
WorkingDirectory=$LAB_DATA
ExecStart=$bin $role
Restart=on-failure
RestartSec=30
# The host check exits 78 on a machine without SIMD; retrying cannot fix that.
RestartPreventExitStatus=78
StandardOutput=append:$LAB_DATA/logs/$role.log
StandardError=append:$LAB_DATA/logs/$role.log

[Install]
WantedBy=default.target
UNIT
    } > "$units/eignex-lab-$role.service"
  done
  [[ -n "$dry" ]] && { echo "dry run: service files in $units"; exit 0; }
  systemctl --user daemon-reload
  systemctl --user enable eignex-lab-api eignex-lab-runner
  systemctl --user restart eignex-lab-api eignex-lab-runner
  loginctl enable-linger "$(id -un)"   # start at boot without a login session
  ;;
esac
echo "api on http://$(hostname):${LAB_PORT:-8420}/"
