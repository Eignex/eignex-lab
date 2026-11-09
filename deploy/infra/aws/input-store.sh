#!/usr/bin/env bash
# Attach an existing input bucket to a free regional S3 gateway endpoint, preserving its data and policies.
#
# input-store.sh --bucket BUCKET --region REGION --vpc VPC --route-table RTB [--route-table RTB...] \
#   --native-user USER --receipt FILE
#
# Idempotent. Uses the AWS CLI's own credentials (environment, profile or `aws login`) and needs jq. Writes the
# receipt (the endpoint, whether it was created, and the bucket policy before this run) before changing the policy.
set -uo pipefail

fail() { printf 'input-store: %s\n' "$*" >&2; exit 1; }

# The prefixes of shared inputs; result-blobs (cloud solve outputs) only the Mac signs and reads, never a worker.
BUILD_ARCHIVE_RULE=LabBuildArchiveExpiry
RESULT_BLOB_RULE=LabResultBlobExpiry
EXPIRY_DAYS=30

# statements <bucket> <endpoint>: the bucket policy statements this tool manages, as a JSON array. Reads of the input
# prefixes are credential-free, but only through the workers' gateway endpoint.
statements() {
  jq -cn --arg arn "arn:aws:s3:::$1" --arg endpoint "$2" '{StringEquals: {"aws:SourceVpce": $endpoint}} as $network | [
    {Sid: "LabRegionalInputRead", Effect: "Allow", Principal: "*", Action: "s3:GetObject",
     Resource: [("corpus", "corpus-packs", "input-blobs", "build-blobs") | "\($arn)/\(.)/*"], Condition: $network},
    {Sid: "LabRegionalInputMiss", Effect: "Allow", Principal: "*", Action: "s3:ListBucket", Resource: $arn,
     Condition: $network}]'
}

# native_policy <bucket>: the Mac signer's user policy: reads of every prefix it signs, and only conditional creation.
native_policy() {
  jq -cn --arg arn "arn:aws:s3:::$1" '{Version: "2012-10-17", Statement: [
    {Effect: "Allow", Action: "s3:GetObject",
     Resource: [("corpus", "input-blobs", "corpus-packs", "build-blobs", "result-blobs") | "\($arn)/\(.)/*"]},
    {Effect: "Allow", Action: "s3:ListBucket", Resource: $arn},
    {Effect: "Allow", Action: "s3:PutObject",
     Resource: [("input-blobs", "corpus-packs", "build-blobs", "result-blobs") | "\($arn)/\(.)/*"],
     Condition: {StringEquals: {"s3:if-none-match": "*"}}}]}'
}

# merged_policy <bucket policy> <statements>: the policy with this tool's statements, and the retired
# LabRegionalInputPublish, replaced by <statements>; every other statement is kept as it is.
merged_policy() {
  jq -c --argjson additions "$2" '([$additions[].Sid] + ["LabRegionalInputPublish"]) as $managed
    | .Statement = ([.Statement // [] | if type == "array" then .[] else . end
        | select((.Sid // null) as $sid | $managed | index($sid) | not)] + $additions)' <<< "$1"
}

# lifecycle <rules>: the bucket's lifecycle rules with the expiry of cloud build archives and of cloud solve outputs,
# which jobs need only while they run and shortly after; every other rule is kept as it is.
lifecycle() {
  jq -c --arg build "$BUILD_ARCHIVE_RULE" --arg result "$RESULT_BLOB_RULE" --argjson days "$EXPIRY_DAYS" '
    def expiry($id; $prefix): {ID: $id, Status: "Enabled", Filter: {Prefix: $prefix}, Expiration: {Days: $days},
      AbortIncompleteMultipartUpload: {DaysAfterInitiation: 1}};
    [.[] | select(.ID != $build and .ID != $result)] + [expiry($build; "build-blobs/"), expiry($result; "result-blobs/")]' <<< "$1"
}

# read_or_missing <missing error code> <aws arguments...>: the call's JSON output, or nothing when AWS answers with
# the error code; any other failure stops the tool.
read_or_missing() {
  local missing="$1" output status error
  shift
  error="$(mktemp)" || fail "cannot create a temporary file"
  output="$(aws "$@" --output json 2> "$error")"
  status=$?
  if (( status != 0 )); then
    grep -qF "($missing)" "$error" || { cat "$error" >&2; rm -f "$error"; fail "aws $1 $2 failed"; }
    output=""
  fi
  rm -f "$error"
  printf '%s' "$output"
}

usage() {
  echo "usage: input-store.sh --bucket BUCKET --region REGION --vpc VPC --route-table RTB [--route-table RTB...]" >&2
  echo "  --native-user USER --receipt FILE" >&2
  echo "input-store.sh: error: $1" >&2
  exit 2
}

main() {
  local bucket="" region="" vpc="" native_user="" receipt="" option value
  local -a tables=()
  while (( $# )); do
    option="$1"
    case "$option" in
    --*=*) value="${option#*=}"; option="${option%%=*}"; shift ;;
    --bucket | --region | --vpc | --route-table | --native-user | --receipt)
      (( $# >= 2 )) || usage "argument $option: expected one argument"
      value="$2"; shift 2 ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
    case "$option" in
    --bucket) bucket="$value" ;;
    --region) region="$value" ;;
    --vpc) vpc="$value" ;;
    --route-table) tables+=("$value") ;;
    --native-user) native_user="$value" ;;
    --receipt) receipt="$value" ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
  done
  local missing=()
  [[ -n "$bucket" ]] || missing+=(--bucket)
  [[ -n "$region" ]] || missing+=(--region)
  [[ -n "$vpc" ]] || missing+=(--vpc)
  (( ${#tables[@]} )) || missing+=(--route-table)
  [[ -n "$native_user" ]] || missing+=(--native-user)
  [[ -n "$receipt" ]] || missing+=(--receipt)
  (( ${#missing[@]} == 0 )) || usage "the following arguments are required: ${missing[*]}"
  command -v jq > /dev/null || fail "input-store.sh needs jq"
  command -v aws > /dev/null || fail "input-store.sh needs the AWS CLI"

  local location
  location="$(aws s3api get-bucket-location --bucket "$bucket" --region "$region" --output json)" ||
    fail "cannot read the bucket's region"
  [[ "$(jq -r '.LocationConstraint // ""' <<< "$location")" == "$region" ]] ||
    fail "bucket and workers must use the same region"

  local service="com.amazonaws.$region.s3" existing endpoint id created added
  existing="$(aws ec2 describe-vpc-endpoints --region "$region" --output json \
    --filters "Name=vpc-id,Values=$vpc" "Name=service-name,Values=$service")" || fail "cannot read the VPC endpoints"
  endpoint="$(jq -c '.VpcEndpoints[0] // empty' <<< "$existing")" || fail "unreadable VPC endpoints"
  if [[ -n "$endpoint" ]]; then
    created=false
    [[ "$(jq -r '.VpcEndpointType' <<< "$endpoint")" == Gateway ]] || fail "input cache requires a gateway endpoint"
    id="$(jq -r '.VpcEndpointId' <<< "$endpoint")"
    added="$(printf '%s\n' "${tables[@]}" | jq -R . | jq -sr --argjson endpoint "$endpoint" \
      '(unique - ($endpoint.RouteTableIds // [])) | .[]')"
    if [[ -n "$added" ]]; then
      # shellcheck disable=SC2086 # one route table ID per word
      aws ec2 modify-vpc-endpoint --region "$region" --vpc-endpoint-id "$id" --add-route-table-ids $added > /dev/null ||
        fail "cannot add route tables to $id"
    fi
  else
    created=true
    endpoint="$(aws ec2 create-vpc-endpoint --region "$region" --output json --vpc-id "$vpc" --service-name "$service" \
      --vpc-endpoint-type Gateway --route-table-ids "${tables[@]}" \
      --tag-specifications '[{"ResourceType": "vpc-endpoint", "Tags": [{"Key": "Name", "Value": "eignex-lab-inputs"}]}]')" ||
      fail "cannot create the gateway endpoint"
    id="$(jq -er '.VpcEndpoint.VpcEndpointId' <<< "$endpoint")" || fail "no endpoint in the creation response"
  fi

  local previous policy
  previous="$(read_or_missing NoSuchBucketPolicy s3api get-bucket-policy --bucket "$bucket" --region "$region")" || exit 1
  if [[ -n "$previous" ]]; then
    previous="$(jq -c '.Policy | fromjson' <<< "$previous")" || fail "unreadable bucket policy"
  else
    previous='{"Version": "2012-10-17", "Statement": []}'
  fi
  policy="$(merged_policy "$previous" "$(statements "$bucket" "$id")")" || fail "cannot merge the bucket policy"
  ( umask 077 && jq -n --arg endpoint "$id" --argjson created "$created" --argjson previous "$previous" \
    '{endpoint: $endpoint, created: $created, previousPolicy: $previous}' > "$receipt" && chmod 600 "$receipt" ) ||
    fail "cannot write the receipt $receipt"
  aws s3api put-bucket-policy --region "$region" --bucket "$bucket" --policy "$policy" || fail "cannot set the bucket policy"

  local rules
  rules="$(read_or_missing NoSuchLifecycleConfiguration s3api get-bucket-lifecycle-configuration --bucket "$bucket" \
    --region "$region")" || exit 1
  if [[ -n "$rules" ]]; then rules="$(jq -c '.Rules // []' <<< "$rules")" || fail "unreadable lifecycle rules"
  else rules='[]'
  fi
  aws s3api put-bucket-lifecycle-configuration --region "$region" --bucket "$bucket" \
    --lifecycle-configuration "$(jq -c '{Rules: .}' <<< "$(lifecycle "$rules")")" || fail "cannot set the lifecycle rules"
  aws iam put-user-policy --region "$region" --user-name "$native_user" --policy-name eignex-lab-input-cache \
    --policy-document "$(native_policy "$bucket")" || fail "cannot set the native user's policy"
  jq -cn --arg endpoint "$id" --arg region "$region" --arg bucket "$bucket" \
    '{endpoint: $endpoint, region: $region, bucket: $bucket}'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
