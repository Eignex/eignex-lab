#!/usr/bin/env bash
# AWS host identity bootstrap: reads the host secret from Secrets Manager with the instance role's IMDSv2
# credentials and hands it to bootstrap.sh on stdin, never through argv, user data or logs.
#
# bootstrap-aws.sh SECRET_ARN REGION NODE_CONFIG
#
# Signs the Secrets Manager request with curl's --aws-sigv4 (curl 8 or newer, which signs the session token header too), so the host needs no AWS CLI or SDK.
# Credentials and the session token reach curl through its config on stdin. Needs curl and jq.
set -euo pipefail
[[ $# -eq 3 ]] || { echo "usage: bootstrap-aws.sh SECRET_ARN REGION NODE_CONFIG" >&2; exit 2; }
secret="$1" region="$2" config="$3"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
imds=http://169.254.169.254
secretsmanager="https://secretsmanager.$region.amazonaws.com"

token="$(curl -fsS -m 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' "$imds/latest/api/token")"
metadata() {
  printf 'header = "X-aws-ec2-metadata-token: %s"\n' "$token" | curl -fsS -m 3 -K - "$imds/latest/meta-data/$1"
}
instance="$(metadata instance-id)"
role="$(metadata iam/security-credentials/)"
role="${role%%$'\n'*}"
[[ -n "$role" ]] || { echo "no instance role credentials" >&2; exit 1; }
credentials="$(metadata "iam/security-credentials/$role")"
# Bounded like boto3's 3 s connect and 5 s read timeouts with three attempts in total.
value="$(printf '%s' "$credentials" |
  jq -r '"user = \"\(.AccessKeyId):\(.SecretAccessKey)\"", "header = \"x-amz-security-token: \(.Token)\""' |
  curl -fsS --connect-timeout 3 -m 10 --retry 2 --aws-sigv4 "aws:amz:$region:secretsmanager" -K - \
    -H 'Content-Type: application/x-amz-json-1.1' -H 'X-Amz-Target: secretsmanager.GetSecretValue' \
    --data-binary "$(jq -cn --arg id "$secret" '{SecretId: $id}')" "$secretsmanager/" |
  jq -ej '.SecretString | strings')"
printf '%s' "$value" | "$here/bootstrap.sh" "$config" "$instance"
