#!/usr/bin/env bash
# Host-only interruption adapter. Drain through the client's RPC identity; no node-write ACL on workers.
#
# Every five seconds asks the provider's metadata service whether this Spot host is being reclaimed. When it is,
# writes the drain marker workers see through the read-only lab-status volume and stops the Nomad client, whose
# drain_on_shutdown gives the current iteration 60 seconds, then exits. Needs curl and jq. LAB_HOST_ROOT prefixes
# every host path (tests only).
set -uo pipefail
root="${LAB_HOST_ROOT:-}"
metadata=http://169.254.169.254

# interrupted <provider>: status 0 when the host is being reclaimed, 1 when not; anything else is printed as why the
# answer is unknown, with status 2.
interrupted() {
  local token status
  case "$1" in
  aws)
    token="$(curl -fsS -m 2 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' "$metadata/latest/api/token")" ||
      { echo "token unavailable"; return 2; }
    status="$(curl -sS -m 2 -o /dev/null -w '%{http_code}' -H "X-aws-ec2-metadata-token: $token" \
      "$metadata/latest/meta-data/spot/instance-action")" || { echo "metadata unavailable"; return 2; }
    case "$status" in
    200) return 0 ;;
    404) return 1 ;;
    *) echo "HTTP_$status"; return 2 ;;
    esac
    ;;
  gcp)
    status="$(curl -fsS -m 2 -H 'Metadata-Flavor: Google' "$metadata/computeMetadata/v1/instance/preempted")" ||
      { echo "metadata unavailable"; return 2; }
    status="${status#"${status%%[![:space:]]*}"}"
    status="$(printf '%s' "${status%"${status##*[![:space:]]}"}" | tr '[:upper:]' '[:lower:]')"
    [[ "$status" == true ]]
    ;;
  *) echo "unknown infrastructure interruption adapter"; return 2 ;;
  esac
}

provider="$(jq -er .provider "$root/etc/eignex-lab/node.json")" || exit 1
while true; do
  reason="$(interrupted "$provider")"
  case $? in
  0)
    if touch "$root/run/eignex-lab/status/draining" &&
      # Nomad drain_on_shutdown disables scheduling immediately, then gives the current iteration 60s.
      timeout 90 systemctl stop nomad-client.service; then
      exit 0
    fi
    echo "interruption adapter held: drain failed"
    ;;
  2) echo "interruption adapter held: $reason" ;;
  esac
  sleep 5
done
