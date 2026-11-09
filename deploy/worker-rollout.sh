#!/usr/bin/env bash
# Roll the Nomad workers onto the controller's release. When releases/current (built by update.sh or install.sh, its
# commit in COMMIT) is ahead of the worker release pinned in the queue settings, and that commit's worker images are
# published (the worker-image workflow tags them <commit>-amd64 and <commit>-arm64 on public ECR), it pins the images
# by digest, points the native launcher at the new release's own directory, keeps the old release in workerReplaces
# so its sessions finish, and restarts the coordinator, which reads the settings at start. The old files and a receipt
# go to $LAB_DATA/operations/worker-<sha8>-<stamp>; copying the .before files back and restarting the coordinator
# undoes it.
#
# worker-rollout.sh --data DIR [--queue FILE] [--native FILE] [--dry-run]
#
# Prints one JSON line ({"rolled":false,"reason":...} or {"rolled":true,...}); exit 1 on an error. host-maintenance.sh
# runs it every five minutes as the lab user, so images published after the controller update still roll out.
# Written for macOS's bash 3.2; needs curl and jq.
set -uo pipefail

REGISTRY=public.ecr.aws
IMAGE=i7d5w3s6/eignex-lab-worker
# The manifest types the earlier rollouts pinned; asking for the same ones keeps the digests comparable.
MANIFEST_TYPES='application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json'

# image_digest <tag>: the digest of public ECR tag <tag> on stdout; nothing, status 0, when it is not published.
image_digest() {
  local token headers status
  token="$(curl -fsS --max-time 30 "https://$REGISTRY/token/?scope=repository:$IMAGE:pull" | jq -er .token)" ||
    { echo "no registry token" >&2; return 1; }
  headers="$(curl -sS --max-time 30 -I -H "Authorization: Bearer $token" -H "Accept: $MANIFEST_TYPES" \
    "https://$REGISTRY/v2/$IMAGE/manifests/$1" | tr -d '\r')" || { echo "registry unreachable" >&2; return 1; }
  status="$(awk 'toupper($1) ~ /^HTTP/ { s = $2 } END { print s }' <<< "$headers")"
  case "$status" in
  200) awk 'tolower($0) ~ /^docker-content-digest:/ { sub(/^[^:]*: */, ""); print }' <<< "$headers" | grep -E '^sha256:[0-9a-f]{64}$' ||
    { echo "registry gave no digest for $1" >&2; return 1; } ;;
  404) return 0 ;;
  *) echo "registry answered ${status:-nothing} for $1" >&2; return 1 ;;
  esac
}

# rollout_queue <queue file> <commit> <launcher> <amd64 image> <arm64 image>: the queue settings with every target on
# <commit>: cloud targets take the image of their arch: tag, the mac target the launcher; the old release joins
# workerReplaces once. Fails, printing nothing, on a cloud target without a known arch.
rollout_queue() {
  jq --arg sha "$2" --arg launcher "$3" --arg amd64 "$4" --arg arm64 "$5" '
    .release as $old
    | .release = $sha
    | .workerReplaces = ((.workerReplaces // []) | if $old == null or any(.[]; . == $old) then . else . + [$old] end)
    | .targets |= map(
        if .template.platform == "mac" then
          .template.command[0] = $launcher | .template.environment.LAB_WORKER_LAUNCHER = $launcher
        else
          ([.tags[]? | select(startswith("arch:")) | ltrimstr("arch:")] | first) as $arch
          | .template.image = (
              if $arch == "amd64" or $arch == "x86_64" then $amd64
              elif $arch == "arm64" or $arch == "aarch64" then $arm64
              else error("target \(.template.nodeClass // "?") has no known arch: tag") end)
        end)' "$1"
}

sha_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# replace <file> <content file>: <file> becomes the content, keeping its mode, by a rename in its directory.
replace() {
  local temporary
  temporary="$(mktemp "$(dirname "$1")/.worker-rollout.XXXXXX")" || return 1
  cp -p "$1" "$temporary" && cat "$2" > "$temporary" && mv -f "$temporary" "$1" || { rm -f "$temporary"; return 1; }
}

say() {  # say <rolled: true|false> <reason or extra JSON fields>
  if [[ "$1" == false ]]; then jq -cn --arg reason "$2" '{rolled: false, reason: $reason}'
  else printf '{"rolled":true,%s}\n' "$2"; fi
}

restart_coordinator() {
  # launchd (KeepAlive) or systemd starts it again on the same release with the new settings.
  pkill -TERM -f 'com\.eignex\.lab\.(cli\.)?MainKt nomad-coordinator( |$)' || true
}

main() {
  local data="" queue="" native="" dry=""
  while (( $# > 0 )); do
    case "$1" in
    --data) data="${2:?--data needs a directory}"; shift 2 ;;
    --queue) queue="${2:?--queue needs a file}"; shift 2 ;;
    --native) native="${2:?--native needs a file}"; shift 2 ;;
    --dry-run) dry=1; shift ;;
    *) echo "usage: worker-rollout.sh --data DIR [--queue FILE] [--native FILE] [--dry-run]" >&2; return 2 ;;
    esac
  done
  [[ -n "$data" ]] || { echo "worker-rollout.sh needs --data" >&2; return 2; }
  queue="${queue:-$data/nomad/queue.json}"
  native="${native:-$data/nomad/native-services.json}"
  [[ -f "$queue" && -f "$native" ]] || { say false "no Nomad queue settings"; return 0; }

  local name commit pinned launcher
  name="$(readlink "$data/releases/current")" || { say false "no current release"; return 0; }
  name="${name%/}"; name="${name##*/}"
  commit="$(tr -d '[:space:]' 2>/dev/null < "$data/releases/$name/COMMIT")"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { say false "release $name records no commit"; return 0; }
  pinned="$(jq -r '.release // ""' "$queue")" || { echo "unreadable queue settings $queue" >&2; return 1; }
  [[ "$pinned" != "$commit" ]] || { say false "workers already on ${commit:0:8}"; return 0; }
  launcher="$data/releases/$name/bin/eignex-lab"
  [[ -x "$launcher" ]] || { echo "release $name has no executable launcher" >&2; return 1; }

  local amd64 arm64
  amd64="$(image_digest "$commit-amd64")" || return 1
  arm64="$(image_digest "$commit-arm64")" || return 1
  if [[ -z "$amd64" || -z "$arm64" ]]; then say false "waiting for ${commit:0:8}'s worker images"; return 0; fi

  local lock="$data/nomad/.worker-rollout.lock" status
  if ! mkdir "$lock" 2>/dev/null; then
    # A lock older than an hour is left by a run that died.
    if [[ -n "$(find "$lock" -maxdepth 0 -mmin +60 2>/dev/null)" ]]; then rmdir "$lock" && mkdir "$lock" || return 1
    else say false "another rollout is running"; return 0; fi
  fi
  roll "$data" "$queue" "$native" "$pinned" "$commit" "$launcher" "$amd64" "$arm64" "$dry"
  status=$?
  rmdir "$lock" 2>/dev/null
  return "$status"
}

# roll <data> <queue> <native> <pinned> <commit> <launcher> <amd64 digest> <arm64 digest> <dry>: the rollout itself,
# under main's lock.
roll() {
  local data="$1" queue="$2" native="$3" pinned="$4" commit="$5" launcher="$6" amd64="$7" arm64="$8" dry="$9"
  local queue_sha native_sha work
  queue_sha="$(sha_of "$queue")"; native_sha="$(sha_of "$native")"
  work="$(mktemp -d)" || return 1
  rollout_queue "$queue" "$commit" "$launcher" "$REGISTRY/$IMAGE@$amd64" "$REGISTRY/$IMAGE@$arm64" > "$work/queue.json" ||
    { rm -rf "$work"; echo "cannot roll the queue settings" >&2; return 1; }
  jq --arg launcher "$launcher" '.workerLauncher = $launcher' "$native" > "$work/native.json" ||
    { rm -rf "$work"; echo "cannot roll the native settings" >&2; return 1; }
  if [[ -n "$dry" ]]; then
    say true "\"dryRun\":true,\"from\":\"$pinned\",\"to\":\"$commit\",\"images\":{\"amd64\":\"$amd64\",\"arm64\":\"$arm64\"}"
    rm -rf "$work"; return 0
  fi

  local ops
  ops="$data/operations/worker-${commit:0:8}-$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$ops" && chmod 700 "$ops" && cp -p "$queue" "$ops/queue.json.before" && cp -p "$native" "$ops/native-services.json.before" ||
    { rm -rf "$work"; echo "cannot keep the old settings in $ops" >&2; return 1; }
  # Nothing else may have changed them since they were read.
  if [[ "$(sha_of "$queue")" != "$queue_sha" || "$(sha_of "$native")" != "$native_sha" ]]; then
    rm -rf "$work"; say false "the settings changed during the rollout"; return 0
  fi
  replace "$queue" "$work/queue.json" && replace "$native" "$work/native.json" || {
    cp -p "$ops/queue.json.before" "$queue"; cp -p "$ops/native-services.json.before" "$native"; rm -rf "$work"
    echo "cannot write the rolled settings; restored the old ones" >&2; return 1
  }
  rm -rf "$work"
  local fields
  fields="\"from\":\"$pinned\",\"to\":\"$commit\",\"images\":{\"amd64\":\"$amd64\",\"arm64\":\"$arm64\"},\"launcher\":\"$launcher\",\"operations\":\"$ops\""
  printf '{"at":"%s",%s,"rollback":"copy the .before files back and restart the coordinator"}\n' "$(date -u +%FT%TZ)" "$fields" > "$ops/receipt.json"
  restart_coordinator
  say true "$fields"
}

# Sourced by the tests; run when executed.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
