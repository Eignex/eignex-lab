#!/usr/bin/env bash
# Pull this repository, build it into a new release, and restart the services on it. Needs no sudo: the service
# files stay as install.sh wrote them, and they already point at releases/current. Rerun install.sh instead when
# lab.env or the service setup itself changed.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
. "$here/deploy/release.sh"
envfile="$HOME/.config/eignex-lab/lab.env"
unset JAVA_HOME
if [[ -f "$envfile" ]]; then set -a; . "$envfile"; set +a; fi
LAB_DATA="${LAB_DATA:-$HOME/eignex-lab-data}"
[[ -L "$LAB_DATA/releases/current" ]] || { echo "no release yet: run deploy/install.sh first"; exit 1; }

git -C "$here" pull --ff-only
# In the background (on macOS, confined to the efficiency cores) and without a daemon, which an earlier build may have
# started outside it, so a build run by hand while solves run takes as little from them as it can; `lab update`
# avoids the overlap altogether.
background=(nice -n 19); [[ "$(uname)" == Darwin ]] && background=(taskpolicy -b)
(cd "$here" && "${background[@]}" ./gradlew --no-daemon clean installDist --max-workers="${LAB_GRADLE_WORKERS:-2}" -q)
JAVA_HOME="${JAVA_HOME:-$(cd "$here" && ./gradlew -q printJavaHome)}"
export JAVA_HOME
bin="$(release "$here" "$LAB_DATA")"
"$bin" check
# Nomad agents and its supervisor stay on their pinned binaries; accepted remote work survives this restart.
restart_services
echo "running $(readlink "$LAB_DATA/releases/current")"
