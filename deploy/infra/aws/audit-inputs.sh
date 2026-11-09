#!/usr/bin/env bash
# Read regional input routing and account-level S3 billing without transferring any objects.
#
# audit-inputs.sh --bucket BUCKET --region REGION --endpoint VPCE --start YYYY-MM-DD --end YYYY-MM-DD --output FILE
#   [--cli-credentials]
#
# Read-only. Writes the full report, billing included, to FILE (0600) and prints it without billing on one line.
# Exits 0 only when the bucket, the endpoint and every observed worker's subnet route use the regional gateway
# endpoint. Uses the AWS CLI's own credentials (environment, profile or `aws login`), so --cli-credentials, which
# chose that provider for the earlier boto3 tool, changes nothing. Needs jq.
set -uo pipefail

fail() { printf 'audit-inputs: %s\n' "$*" >&2; exit 1; }

BILLING_SCOPE='account-wide, not attributable to this bucket; recent usage may not have arrived'

# report <observed at> <bucket> <region> <endpoint ID> <answers>: the report from the AWS CLI's JSON answers in the
# directory <answers> (location, endpoint, routes, instances and billing .json). A worker's subnet uses its explicitly
# associated route table, else the VPC's main one; image-build instances are not workers.
report() {
  local answers="$5"
  jq -n --arg at "$1" --arg bucket "$2" --arg region "$3" --arg id "$4" --arg scope "$BILLING_SCOPE" \
    --slurpfile location "$answers/location.json" --slurpfile endpoint "$answers/endpoint.json" \
    --slurpfile routes "$answers/routes.json" --slurpfile instances "$answers/instances.json" \
    --slurpfile billing "$answers/billing.json" '
    $endpoint[0] as $endpoint
    | ($location[0].LocationConstraint // "" | if . == "" then "us-east-1" else . end) as $actual
    | ($actual == $region and $endpoint.State == "available" and $endpoint.VpcEndpointType == "Gateway"
        and $endpoint.ServiceName == "com.amazonaws.\($region).s3") as $valid
    | ([$routes[0].RouteTables[] | select(any(.Associations // [] | .[]; .Main == true)) | .RouteTableId] | first
        // error("no main route table in \($endpoint.VpcId)")) as $main
    | (reduce ($routes[0].RouteTables[] | .RouteTableId as $table | .Associations // [] | .[] | select(has("SubnetId"))
        | {(.SubnetId): $table}) as $entry ({}; . + $entry)) as $explicit
    | [$instances[0].Reservations[].Instances[]
        | ([.Tags // [] | .[] | select(.Key == "lab:pool") | .Value] | first // "") as $pool
        | select($pool | startswith("image-build") | not)
        | ($explicit[.SubnetId // ""] // $main) as $table
        | {instance: .InstanceId, pool: $pool, routeTable: $table,
           regionalRoute: ($endpoint.RouteTableIds // [] | index($table) != null)}] as $workers
    | {observedAt: $at, bucket: $bucket, region: $actual, endpoint: $id, regionalConfigurationValid: $valid,
       allObservedWorkersRouted: ($valid and all($workers[]; .regionalRoute)), workers: $workers,
       billingScope: $scope, billing: $billing[0].ResultsByTime}'
}

usage() {
  echo "usage: audit-inputs.sh --bucket BUCKET --region REGION --endpoint ENDPOINT --start START --end END" >&2
  echo "  --output OUTPUT [--cli-credentials]" >&2
  echo "audit-inputs.sh: error: $1" >&2
  exit 2
}

# audit <bucket> <region> <endpoint ID> <start> <end> <output> <answers>: read the AWS answers into the directory
# <answers>, write the report and print its summary.
audit() {
  local bucket="$1" region="$2" endpoint_id="$3" start="$4" end="$5" output="$6" answers="$7" vpc routed
  aws s3api get-bucket-location --bucket "$bucket" --region "$region" --output json > "$answers/location.json" ||
    fail "cannot read the bucket's region"
  aws ec2 describe-vpc-endpoints --region "$region" --output json --vpc-endpoint-ids "$endpoint_id" \
    > "$answers/endpoints.json" || fail "cannot read the endpoint $endpoint_id"
  jq -ce '.VpcEndpoints | if length == 1 then .[0] else empty end' "$answers/endpoints.json" \
    > "$answers/endpoint.json" || fail "expected exactly one endpoint $endpoint_id"
  vpc="$(jq -er '.VpcId' "$answers/endpoint.json")" || fail "the endpoint $endpoint_id names no VPC"
  aws ec2 describe-route-tables --region "$region" --output json --filters "Name=vpc-id,Values=$vpc" \
    > "$answers/routes.json" || fail "cannot read the route tables of $vpc"
  aws ec2 describe-instances --region "$region" --output json --filters "Name=tag:lab:deployment,Values=eignex-lab" \
    "Name=instance-state-name,Values=pending,running" "Name=vpc-id,Values=$vpc" > "$answers/instances.json" ||
    fail "cannot read the workers in $vpc"
  aws ce get-cost-and-usage --region us-east-1 --output json --time-period "Start=$start,End=$end" \
    --granularity DAILY --metrics UnblendedCost UsageQuantity \
    --filter '{"Dimensions": {"Key": "SERVICE", "Values": ["Amazon Simple Storage Service", "AWS Data Transfer"]}}' \
    --group-by Type=DIMENSION,Key=SERVICE Type=DIMENSION,Key=USAGE_TYPE > "$answers/billing.json" ||
    fail "cannot read the billing"

  report "$(date -u +%Y-%m-%dT%H:%M:%S+00:00)" "$bucket" "$region" "$endpoint_id" "$answers" > "$answers/report.json" ||
    fail "cannot read the AWS answers"
  ( umask 077 && jq . "$answers/report.json" > "$output" && chmod 600 "$output" ) || fail "cannot write the report $output"
  jq -c 'del(.billing)' "$answers/report.json"
  routed="$(jq -r '.allObservedWorkersRouted' "$answers/report.json")"
  [[ "$routed" == true ]]
}

main() {
  local bucket="" region="" endpoint_id="" start="" end="" output="" option value
  while (( $# )); do
    option="$1"
    case "$option" in
    --cli-credentials) shift; continue ;;
    --*=*) value="${option#*=}"; option="${option%%=*}"; shift ;;
    --bucket | --region | --endpoint | --start | --end | --output)
      (( $# >= 2 )) || usage "argument $option: expected one argument"
      value="$2"; shift 2 ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
    case "$option" in
    --bucket) bucket="$value" ;;
    --region) region="$value" ;;
    --endpoint) endpoint_id="$value" ;;
    --start) start="$value" ;;
    --end) end="$value" ;;
    --output) output="$value" ;;
    *) usage "unrecognized arguments: $option" ;;
    esac
  done
  local missing=()
  [[ -n "$bucket" ]] || missing+=(--bucket)
  [[ -n "$region" ]] || missing+=(--region)
  [[ -n "$endpoint_id" ]] || missing+=(--endpoint)
  [[ -n "$start" ]] || missing+=(--start)
  [[ -n "$end" ]] || missing+=(--end)
  [[ -n "$output" ]] || missing+=(--output)
  (( ${#missing[@]} == 0 )) || usage "the following arguments are required: ${missing[*]}"
  command -v jq > /dev/null || fail "audit-inputs.sh needs jq"
  command -v aws > /dev/null || fail "audit-inputs.sh needs the AWS CLI"

  local answers status
  answers="$(mktemp -d)" || fail "cannot create a temporary directory"
  ( audit "$bucket" "$region" "$endpoint_id" "$start" "$end" "$output" "$answers" )
  status=$?
  rm -rf "$answers"
  return "$status"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
