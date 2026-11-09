#!/usr/bin/env bash
# GCP host identity bootstrap with the metadata server's default credentials; no service-account private key is
# created. Hands the host secret to bootstrap.sh on stdin, never through argv, user data or logs.
#
# bootstrap-gcp.sh SECRET NODE_CONFIG       SECRET is projects/P/secrets/S
#
# Needs curl, jq and base64.
set -euo pipefail
[[ $# -eq 2 ]] || { echo "usage: bootstrap-gcp.sh SECRET NODE_CONFIG" >&2; exit 2; }
secret="$1" config="$2"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

metadata() { curl -fsS -m 3 -H 'Metadata-Flavor: Google' "http://169.254.169.254/computeMetadata/v1/$1"; }
project="$(metadata project/project-id)"
zone="$(metadata instance/zone)"
name="$(metadata instance/name)"
token="$(metadata instance/service-accounts/default/token | jq -er .access_token)"
# The bearer token reaches curl through its config on stdin, not argv.
value="$(printf 'header = "Authorization: Bearer %s"\n' "$token" |
  curl -fsS -m 5 -K - "https://secretmanager.googleapis.com/v1/$secret/versions/latest:access" |
  jq -er .payload.data | base64 -d)"
printf '%s' "$value" | "$here/bootstrap.sh" "$config" "projects/$project/zones/${zone##*/}/instances/$name"
