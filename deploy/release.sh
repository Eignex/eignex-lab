# Sourced by install.sh and update.sh. The services run releases/current, a copy of a build that nothing
# rewrites: a rebuild changes the jars a running JVM still loads classes from, and breaks it until it restarts.

# release <repo dir> <data dir>: copy the repo's built distribution into a new release, point current at it, keep
# the three newest, and print the path of its start script.
release() {
  local here="$1" releases="$2/releases" id
  id="$(git -C "$here" rev-parse --short HEAD)-$(date +%Y%m%d%H%M%S)"
  # Explicit returns: this runs inside $(...), where bash 3.2 does not carry set -e.
  mkdir -p "$releases" || return 1
  cp -R "$here/build/install/eignex-lab" "$releases/$id" || return 1
  ln -sfn "$id" "$releases/current" || return 1
  local old
  # A raw_exec worker loads jars lazily from its pinned release. Retain releases while Nomad is configured;
  # explicit GC must first reconcile allocations and prove that no worker still uses a release.
  if [[ -n "${LAB_NOMAD_NATIVE_CONFIG:-}" || -d "$2/nomad/service-files" ]]; then
    echo "$releases/current/bin/eignex-lab"
    return 0
  fi
  for old in $(ls -1dt "$releases"/*/ | sed 's:/$::' | grep -v '/current$' | tail -n +4); do
    [[ "$(basename "$old")" == "$id" ]] || rm -rf "$old"
  done
  echo "$releases/current/bin/eignex-lab"
}

# restart_services: stop both services so their service manager starts them again on releases/current. A command
# the runner had running is left queued and reruns from the start.
restart_services() {
  case "$(uname -s)" in
  Darwin)
    local pids
    # -a: BSD pgrep leaves out its own ancestors, and the runner is one when its self-update runs this.
    pids="$(pgrep -af 'com\.eignex\.lab\.MainKt (api|runner|nomad-coordinator)( |$)' || true)"
    [[ -n "$pids" ]] && kill $pids
    ;;
  Linux)
    systemctl --user restart eignex-lab-api eignex-lab-runner
    if systemctl --user is-enabled eignex-lab-nomad-coordinator >/dev/null 2>&1; then
      systemctl --user restart eignex-lab-nomad-coordinator
    fi
    ;;
  esac
}
