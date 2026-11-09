#!/usr/bin/env bash
# Keep the lab reachable after the connection's public address changes: point the DNS record at it, and make the
# Nomad server advertise it, since cloud clients and this machine's client reach the server through it. A client
# keeps dialing the old address it learned, so both agents restart; running allocations survive that.
#
# public-address.sh --data DIR --settings FILE
#
# FILE holds {"token": ..., "domain": "eignex.com", "name": "lab"}: a DigitalOcean token scoped to DNS.
# host-maintenance.sh runs this every five minutes, under its lock, when ~/.config/eignex-lab/dynamic-dns.json exists.
# Prints what it did as one JSON object; on failure prints the reason on stderr and exits 1. Needs dig, curl and jq.
# Written for macOS's bash 3.2.
set -uo pipefail
export LC_ALL=C

fail() { printf '%s\n' "$*" >&2; exit 1; }

if stat -c %a / >/dev/null 2>&1; then
  mode_of() { stat -c %a -- "$1"; }
else
  mode_of() { stat -f %Mp%Lp -- "$1"; }
fi

# This connection's public IPv4 address, asked of OpenDNS's resolver rather than of a web service.
public_ip() {
  local answer status octet
  answer="$(dig +short +time=5 +tries=2 myip.opendns.com @resolver1.opendns.com)"
  status=$?
  (( status == 0 )) || fail "dig for the public address failed with exit status $status"
  answer="$(printf '%s' "$answer" | awk '{ $1 = $1 } NF' ORS=' ')"
  answer="${answer% }"
  [[ "$answer" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || fail "no public IPv4 address in resolver answer"
  for octet in ${answer//./ }; do
    (( 10#$octet <= 255 )) || fail "no public IPv4 address in resolver answer"
  done
  printf '%s\n' "$answer"
}

# digitalocean <token> <method> <path> [body]: the API's JSON answer. The token goes through curl's stdin, never its
# arguments, which any local user could read.
digitalocean() {
  local token="$1" method="$2" path="$3" body="${4:-}" header
  token="${token//\\/\\\\}"
  header="Authorization: Bearer ${token//\"/\\\"}"
  if [[ -n "$body" ]]; then
    curl -fsS --max-time 30 -K - -X "$method" -H 'Content-Type: application/json' --data "$body" \
      "https://api.digitalocean.com/v2/$path" <<< "header = \"$header\""
  else
    curl -fsS --max-time 30 -K - -X "$method" -H 'Content-Type: application/json' \
      "https://api.digitalocean.com/v2/$path" <<< "header = \"$header\""
  fi
}

# restart_nomad_agents <nomad dir>: stop the server and client agents; launchd starts them again with the rewritten
# configuration.
restart_nomad_agents() {
  local nomad="$1" output pids pid
  output="$(ps -axo pid=,command=)" || fail "cannot read the process inventory"
  pids="$(while read -r pid command; do
    [[ "$pid" =~ ^[0-9]+$ && "$command" == *" agent "* ]] || continue
    [[ "$command" == *"$nomad/server.json"* || "$command" == *"$nomad/client.json"* ]] && echo "$pid"
  done <<< "$output")"
  for pid in $pids; do
    kill -15 "$pid" || fail "cannot stop Nomad agent $pid"
  done
}

# setting <file> <key>: a required string from the settings file.
setting() {
  jq -er --arg key "$2" 'if has($key) then .[$key] | tostring else error("missing \($key)") end' "$1" ||
    fail "settings file $1 has no usable $2"
}

# follow_public_ip <data dir> <settings file>: prints {"ip": ..., "dns": {from, to}?, "nomad": {from, to}?}.
follow_public_ip() {
  local data="$1" settings="$2" ip token domain name records count current id server rpc host port updated
  local dns_from="" nomad_from="" nomad_to="" temporary
  command -v jq >/dev/null || fail "public-address.sh needs jq"
  ip="$(public_ip)" || exit 1
  token="$(setting "$settings" token)" || exit 1
  domain="$(setting "$settings" domain)" || exit 1
  name="$(setting "$settings" name)" || exit 1
  records="$(digitalocean "$token" GET "domains/$domain/records?type=A&name=$name.$domain")" ||
    fail "cannot read the A records of $name.$domain"
  count="$(jq -e '.domain_records | length' <<< "$records")" || fail "unexpected answer listing $name.$domain"
  (( count == 1 )) || fail "expected one A record for $name.$domain, found $count"
  current="$(jq -r '.domain_records[0].data' <<< "$records")" || fail "unexpected answer listing $name.$domain"
  id="$(jq -r '.domain_records[0].id' <<< "$records")" || fail "unexpected answer listing $name.$domain"
  if [[ "$current" != "$ip" ]]; then
    digitalocean "$token" PUT "domains/$domain/records/$id" "$(jq -cn --arg ip "$ip" '{data: $ip}')" >/dev/null ||
      fail "cannot point $name.$domain at $ip"
    dns_from="$current"
  fi
  server="$data/nomad/server.json"
  if [[ -e "$server" ]]; then
    rpc="$(jq -r '(.advertise // {}).rpc // ""' "$server")" || fail "cannot read $server"
    host="" port=""
    if [[ "$rpc" == *:* ]]; then host="${rpc%:*}" port="${rpc##*:}"; fi
    if [[ -n "$host" && "$host" != "$ip" ]]; then
      updated="$ip:$port"
      temporary="$server.tmp"
      if ! { jq --indent 2 --arg rpc "$updated" '.advertise.rpc = $rpc' "$server" > "$temporary" &&
        chmod "$(mode_of "$server")" "$temporary" && mv -f "$temporary" "$server"; }; then
        fail "cannot rewrite $server"
      fi
      restart_nomad_agents "$data/nomad" || exit 1
      nomad_from="$rpc" nomad_to="$updated"
    fi
  fi
  jq -cn --arg ip "$ip" --arg dns_from "$dns_from" --arg nomad_from "$nomad_from" --arg nomad_to "$nomad_to" \
    '{ip: $ip}
     + (if $dns_from != "" then {dns: {from: $dns_from, to: $ip}} else {} end)
     + (if $nomad_to != "" then {nomad: {from: $nomad_from, to: $nomad_to}} else {} end)'
}

main() {
  local data="" settings=""
  while (( $# )); do
    case "$1" in
    --data) data="${2:-}"; shift 2 || break ;;
    --settings) settings="${2:-}"; shift 2 || break ;;
    *) break ;;
    esac
  done
  if [[ -z "$data" || -z "$settings" || $# -gt 0 ]]; then
    echo "usage: public-address.sh --data DIR --settings FILE" >&2
    exit 2
  fi
  while [[ "$data" == ?*/ ]]; do data="${data%/}"; done
  follow_public_ip "$data" "$settings"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
