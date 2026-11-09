#!/usr/bin/env bash
# Pull this repository, build it into a new release, and restart the services on it. Needs no sudo: the service
# files stay as install.sh wrote them, and they already point at releases/current. Rerun install.sh instead when
# lab.env or the service setup itself changed.
# Braces: bash reads the whole script before running any of it, so the pull below cannot change the rest of this
# run (it used to continue at its old byte offset in the pulled file). After the pull it starts again on the pulled
# version, whose steps belong to the release it builds.
{
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
. "$here/deploy/release.sh"
envfile="$HOME/.config/eignex-lab/lab.env"
unset JAVA_HOME
if [[ -f "$envfile" ]]; then set -a; . "$envfile"; set +a; fi
LAB_DATA="${LAB_DATA:-$HOME/eignex-lab-data}"
[[ -L "$LAB_DATA/releases/current" ]] || { echo "no release yet: run deploy/install.sh first"; exit 1; }

if [[ -z "${LAB_UPDATE_PULLED:-}" ]]; then
  git -C "$here" pull --ff-only
  LAB_UPDATE_PULLED=1 exec bash "$here/deploy/update.sh" "$@"
fi
# In the background (on macOS, confined to the efficiency cores) and without a daemon, which an earlier build may have
# started outside it, so a build run by hand while solves run takes as little from them as it can; `lab update`
# avoids the overlap altogether.
background=(nice -n 19); [[ "$(uname)" == Darwin ]] && background=(taskpolicy -b)
(cd "$here" && "${background[@]}" ./gradlew --no-daemon clean installDist --max-workers="${LAB_GRADLE_WORKERS:-2}" -q)
JAVA_HOME="${JAVA_HOME:-$(cd "$here" && ./gradlew -q printJavaHome)}"
export JAVA_HOME
bin="$(release "$here" "$LAB_DATA")"
"$bin" check
# The periodic maintenance runs copies outside the checkout; keep them on this checkout's version. A job install.sh
# registered before the bash scripts runs its own frozen gradle_maintenance.py until install.sh registers it again.
if [[ -f "$LAB_DATA/maintenance/host-maintenance.sh" ]]; then
  for script in host-maintenance.sh public-address.sh worker-rollout.sh; do
    install -m 700 "$here/deploy/$script" "$LAB_DATA/maintenance/$script.new"
    mv "$LAB_DATA/maintenance/$script.new" "$LAB_DATA/maintenance/$script"
  done
elif [[ -f "$LAB_DATA/maintenance/gradle_maintenance.py" ]]; then
  echo "note: maintenance still runs the retired gradle_maintenance.py; rerun deploy/install.sh to register host-maintenance.sh"
fi
# Nomad agents and its supervisor stay on their pinned binaries; accepted remote work survives this restart.
restart_services
echo "running $(readlink "$LAB_DATA/releases/current")"
exit
}
