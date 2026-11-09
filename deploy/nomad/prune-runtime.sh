#!/usr/bin/env bash
# Prune obsolete lab runtime files, never job results or Nomad allocation state.
#
# prune-runtime.sh --data DIR [--apply]
#
# Dry-run is the default: prints, as JSON, which release directories under DIR/releases are kept and why, and which
# releases and named deployment leftovers in DIR/nomad nothing references. --apply removes those, rechecking live
# references before every deletion, and keeps a retired release's deployment.json provenance under
# DIR/deployments/retired-runtime. References are the Nomad settings files, the lab's launchd services, running
# processes and, in DIR/lab.db, live sessions, pending intents and the frozen recipes of queued, running, failed and
# cancelled jobs: an explicit retry still needs its frozen runtime. Only release directories and named deployment
# leftovers are eligible. Needs jq and sqlite3, and plutil for the services on the Mac. Written for macOS's bash 3.2.
#
# For tests, LAB_PRUNE_PROCESSES replaces the running process list and LAB_PRUNE_SERVICES (newline-separated plist
# paths) the installed services, when set.
set -o pipefail
export LC_ALL=C  # sort and glob by byte

fail() { printf 'prune-runtime: %s\n' "$*" >&2; exit 1; }

LEFTOVERS='release-*.zip queue-before-*.json native-services-before-*.json lab.env.before-* before-queue-efficiency-*
before-gradle-cache-* before-git-archive-* activate_cutover_release.py artifacts.hdd-link artifacts.ssd-rolled-back .hdd-*'

# plist_text <plist>: the plist's strings, each quoted as JSON writes it, for the same substring checks as the files.
plist_text() {
  if [[ "$(head -c 6 "$1")" == bplist ]]; then plutil -convert xml1 -o - "$1"; else cat "$1"; fi |
    sed -n 's/.*<string>\(.*\)<\/string>.*/"\1"/p' | sed -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' \
    -e "s/&apos;/'/g" -e 's/&amp;/\&/g'
}

# references <dir>: write each reference text to a file in <dir> and its reason beside it, in FILE.reason. Private text
# stays there only as long as one plan; reports contain only names and reasons.
references() {
  local into="$1" name path database tables
  # add <reason>: standard input as one reference; it may run in a pipeline's subshell.
  add() {
    local file
    file="$(mktemp "$into/reference.XXXXXX")" && cat > "$file" && printf '%s' "$1" > "$file.reason"
  }
  for name in queue.json native-services.json coordinator.json capacity.json; do
    path="$data/nomad/$name"
    [[ -f "$path" ]] && add "$name" < "$path"
  done
  local services
  if [[ -n "${LAB_PRUNE_SERVICES+set}" ]]; then
    services="$LAB_PRUNE_SERVICES"
  else
    services="$(ls -d /Library/LaunchDaemons/com.eignex.lab.*.plist "$HOME"/Library/LaunchAgents/com.eignex.lab.*.plist 2> /dev/null)"
  fi
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    plist_text "$path" | add "$(basename "$path")" || fail "cannot read $path"
  done <<< "$services"
  if [[ -n "${LAB_PRUNE_PROCESSES+set}" ]]; then
    printf '%s' "$LAB_PRUNE_PROCESSES" | add "running process"
  else
    ps -axo command= | add "running process" || fail "cannot list running processes"
  fi
  database="$data/lab.db"
  if [[ -f "$database" ]]; then
    tables="$(sqlite3 -readonly "$database" "SELECT name FROM sqlite_master WHERE type='table'")" ||
      fail "cannot read $database"
    if grep -qx execution_outbox <<< "$tables"; then
      sqlite3 -readonly "$database" "SELECT o.payload FROM execution_outbox o
        JOIN execution_sessions s ON s.id=o.session_id JOIN jobs j ON j.id=s.job_id
        WHERE s.desired!='stop' OR o.acknowledged=0 OR j.status IN ('QUEUED','RUNNING')" |
        add "live session or pending intent" || fail "cannot read $database"
    fi
    if grep -qx execution_queue_recipes <<< "$tables"; then
      sqlite3 -readonly "$database" "SELECT r.settings FROM execution_queue_recipes r
        JOIN jobs j ON j.id=r.job_id WHERE j.status IN ('QUEUED','RUNNING','FAILED','CANCELLED')" |
        add "frozen active or retryable recipe" || fail "cannot read $database"
    fi
  fi
}

# plan: the dry-run report as JSON.
plan() {
  local refs releases path links="" reasons file kept="{}" unused="[]" leftovers="" pattern
  refs="$(mktemp -d)" || fail "cannot create a temporary directory"
  references "$refs"
  releases="$data/releases"
  if [[ -d "$releases" ]]; then
    for path in "$releases"/* "$releases"/.*; do
      [[ -L "$path" && -d "$path" ]] && links="$links$(cd "$path" && pwd -P)"$'\n'
    done
    for path in "$releases"/*; do
      [[ ! -L "$path" && -d "$path" ]] || continue
      reasons=""
      for file in "$refs"/*.reason; do
        [[ -e "$file" ]] || continue
        if grep -qaF -e "$path/" -e "$path\"" "${file%.reason}"; then reasons="$reasons$(cat "$file")"$'\n'; fi
      done
      grep -qxF "$(cd "$path" && pwd -P)" <<< "$links" && reasons="${reasons}release symlink"$'\n'
      if [[ -n "$reasons" ]]; then
        kept="$(printf %s "$reasons" | sort -u | jq -R . | jq -s --argjson kept "$kept" --arg name "$(basename "$path")" \
          '$kept + {($name): .}')"
      else
        unused="$(jq -c --arg path "$path" '. + [$path]' <<< "$unused")"
      fi
    done
  fi
  for pattern in $LEFTOVERS; do
    for path in "$data/nomad"/$pattern; do
      [[ -e "$path" && ! -L "$path" ]] || continue
      for file in "$refs"/*.reason; do
        [[ -e "$file" ]] || continue
        grep -qaF -e "$path" -e "$(basename "$path")" "${file%.reason}" && continue 2
      done
      leftovers="$leftovers$path"$'\n'
    done
  done
  rm -rf "$refs"
  printf %s "$leftovers" | sort -u | jq -R . | jq -s --arg data "$data" --argjson kept "$kept" --argjson unused "$unused" \
    '{data: $data, keptReleases: $kept, unusedReleases: $unused, unusedDeploymentFiles: .}'
}

# apply: delete what the plan finds unused, rechecking each before its deletion; the report as JSON.
apply() {
  local initial current path deleted="[]" archive
  initial="$(plan)" || exit 1
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    current="$(plan)" || exit 1
    jq -e --arg path "$path" '.unusedReleases + .unusedDeploymentFiles | index([$path])' <<< "$current" > /dev/null || continue
    [[ ! -L "$path" ]] || fail "refusing to follow a changed runtime symlink"
    # Retain small deployment provenance even when the executable distribution goes.
    if jq -e --arg path "$path" '.unusedReleases | index([$path])' <<< "$initial" > /dev/null && [[ -f "$path/deployment.json" ]]; then
      archive="$data_given/deployments/retired-runtime"
      { mkdir -p "$archive" && chmod 700 "$archive"; } || fail "cannot create $archive"
      jq '. as $value | reduce ("revision", "artifactRevision", "workflowRun", "sha256") as $key
        ({}; if $value | has($key) then .[$key] = $value[$key] else . end)' "$path/deployment.json" \
        > "$archive/$(basename "$path").json" || fail "cannot keep the provenance of $path"
    fi
    if [[ -d "$path" ]]; then rm -rf "$path"; elif [[ -f "$path" ]]; then rm -f "$path"; fi
    deleted="$(jq -c --arg path "$path" '. + [$path]' <<< "$deleted")"
  done < <(jq -r '.unusedReleases[], .unusedDeploymentFiles[]' <<< "$initial")
  current="$(plan)" || exit 1
  jq --argjson deleted "$deleted" '{deleted: $deleted} + .' <<< "$current"
}

usage() { echo "usage: prune-runtime.sh --data DIR [--apply]" >&2; echo "prune-runtime.sh: $1" >&2; exit 2; }

data_given=""
mode=plan
while (($# > 0)); do
  case "$1" in
  --data) (($# > 1)) || usage "--data needs a value"; data_given="$2"; shift 2 ;;
  --apply) mode=apply; shift ;;
  *) usage "unknown argument $1" ;;
  esac
done
[[ -n "$data_given" ]] || usage "--data is required"
[[ -d "$data_given" ]] || fail "no data directory $data_given"
data="$(cd "$data_given" && pwd -P)"
"$mode"
