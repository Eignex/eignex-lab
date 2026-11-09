#!/usr/bin/env bash
# Tests of deploy/infra/aws/audit-inputs.sh against a stub AWS CLI. Needs jq.
# bash deploy/infra/tests/test_audit_inputs.sh [test name...]
# shellcheck disable=SC2016,SC2119,SC2120,SC2329 # literal shell text in stubs; tests called indirectly
set -uo pipefail
infra="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$infra/aws/audit-inputs.sh"

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}

# A stub `aws`: answers from $root/aws/<service>-<operation>.json (exit 254 with the error code in
# <service>-<operation>.error) and records every call's arguments in $root/calls, one call per line, separated by tabs.
# The fixture VPC has a main route table rtb-main and rtb-inputs explicitly associated with subnet-inputs; the
# endpoint routes rtb-inputs only. Workers: i-1 in subnet-inputs, i-2 an image builder in the main table's subnet.
setup() {
  [[ -z "${root:-}" ]] || rm -rf "$root"
  root="$(mktemp -d)"
  mkdir -p "$root/bin" "$root/aws"
  cat > "$root/bin/aws" <<STUB
#!/usr/bin/env bash
(IFS=\$'\t'; echo "\$*") >> "$root/calls"
name="$root/aws/\$1-\$2"
if [[ -f "\$name.error" ]]; then echo "An error occurred (\$(cat "\$name.error")) when calling \$2" >&2; exit 254; fi
[[ -f "\$name.json" ]] && cat "\$name.json"
exit 0
STUB
  chmod +x "$root/bin/aws"
  echo '{"LocationConstraint": "eu-north-1"}' > "$root/aws/s3api-get-bucket-location.json"
  echo '{"VpcEndpoints": [{"VpcEndpointId": "vpce-1", "VpcId": "vpc-1", "State": "available", "VpcEndpointType": "Gateway",
    "ServiceName": "com.amazonaws.eu-north-1.s3", "RouteTableIds": ["rtb-inputs"]}]}' > "$root/aws/ec2-describe-vpc-endpoints.json"
  echo '{"RouteTables": [
    {"RouteTableId": "rtb-main", "Associations": [{"Main": true, "RouteTableAssociationId": "rtbassoc-main"}]},
    {"RouteTableId": "rtb-inputs", "Associations": [{"Main": false, "SubnetId": "subnet-inputs"}]}]}' \
    > "$root/aws/ec2-describe-route-tables.json"
  instances '[{"InstanceId": "i-1", "SubnetId": "subnet-inputs", "Tags": [{"Key": "lab:pool", "Value": "spot-x86"}]},
    {"InstanceId": "i-2", "SubnetId": "subnet-other", "Tags": [{"Key": "lab:pool", "Value": "image-build-arm"}]}]'
  echo '{"ResultsByTime": [{"TimePeriod": {"Start": "2026-11-01", "End": "2026-11-02"}, "Estimated": true,
    "Groups": [{"Keys": ["Amazon Simple Storage Service", "EUN1-Requests-Tier2"],
      "Metrics": {"UnblendedCost": {"Amount": "0.01", "Unit": "USD"}}}]}]}' > "$root/aws/ce-get-cost-and-usage.json"
}
instances() {  # instances <JSON array of instances>: the workers describe-instances answers, in one reservation
  jq -n --argjson instances "$1" '{Reservations: [{Instances: $instances}]}' > "$root/aws/ec2-describe-instances.json"
}
audit() {  # audit [extra arguments...]: the tool's status; output in $root/out, errors in $root/err
  PATH="$root/bin:$PATH" bash "$script" --bucket inputs --region eu-north-1 --endpoint vpce-1 --start 2026-11-01 \
    --end 2026-11-08 --output "$root/report.json" "$@" > "$root/out" 2> "$root/err"
}

test_routed_workers_pass_and_the_report_keeps_billing_private() {
  setup
  check "runs" audit --cli-credentials
  equal '[{"instance":"i-1","pool":"spot-x86","routeTable":"rtb-inputs","regionalRoute":true}]' \
    "$(jq -c '.workers' "$root/out")" "image builders are not workers"
  equal '["observedAt","bucket","region","endpoint","regionalConfigurationValid","allObservedWorkersRouted","workers","billingScope"]' \
    "$(jq -c 'keys_unsorted' "$root/out")" "summary without billing"
  equal 'inputs eu-north-1 vpce-1 true true' \
    "$(jq -r '"\(.bucket) \(.region) \(.endpoint) \(.regionalConfigurationValid) \(.allObservedWorkersRouted)"' "$root/out")" \
    "summary"
  check "observed in UTC" grep -qE '"observedAt":"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}\+00:00"' "$root/out"
  equal "$(jq -c 'del(.billing)' "$root/report.json")" "$(cat "$root/out")" "the report holds the summary"
  equal '"0.01"' "$(jq -c '.billing[0].Groups[0].Metrics.UnblendedCost.Amount' "$root/report.json")" "billing in the report"
  check "account-wide billing" grep -q 'account-wide, not attributable to this bucket' "$root/report.json"
  check "indented report" grep -q '^  "bucket": "inputs",$' "$root/report.json"
  equal 600 "$(stat -c %a "$root/report.json" 2>/dev/null || stat -f %Lp "$root/report.json")" "private report"
}

test_reads_only_the_endpoints_vpc_and_the_account_billing() {
  setup
  check "runs" audit
  check "reads one endpoint" grep -qx $'ec2\tdescribe-vpc-endpoints\t--region\teu-north-1\t--output\tjson\t--vpc-endpoint-ids\tvpce-1' "$root/calls"
  check "the endpoint's route tables" grep -qx $'ec2\tdescribe-route-tables\t--region\teu-north-1\t--output\tjson\t--filters\tName=vpc-id,Values=vpc-1' "$root/calls"
  check "running lab instances in its VPC" grep -qF -- $'--filters\tName=tag:lab:deployment,Values=eignex-lab\tName=instance-state-name,Values=pending,running\tName=vpc-id,Values=vpc-1' "$root/calls"
  check "daily S3 and transfer billing from us-east-1" grep -qF -- $'ce\tget-cost-and-usage\t--region\tus-east-1\t--output\tjson\t--time-period\tStart=2026-11-01,End=2026-11-08\t--granularity\tDAILY\t--metrics\tUnblendedCost\tUsageQuantity' "$root/calls"
  check "by service and usage type" grep -qF -- $'--group-by\tType=DIMENSION,Key=SERVICE\tType=DIMENSION,Key=USAGE_TYPE' "$root/calls"
  equal '{"Dimensions":{"Key":"SERVICE","Values":["Amazon Simple Storage Service","AWS Data Transfer"]}}' \
    "$(awk -F'\t' '$2 == "get-cost-and-usage" { for (i = 1; i < NF; i++) if ($i == "--filter") print $(i + 1) }' "$root/calls" | jq -c .)" \
    "billing filter"
  check "changes nothing" test -z "$(grep -vE '^(s3api	get-|ec2	describe-|ce	get-)' "$root/calls")"
}

test_a_worker_on_the_main_route_table_fails_the_audit() {
  setup
  instances '[{"InstanceId": "i-1", "SubnetId": "subnet-inputs"}, {"InstanceId": "i-3", "SubnetId": "subnet-other"}]'
  audit && { echo "  failed: passed a worker without the regional route" >&2; exit 1; }
  equal '[["i-1","","rtb-inputs",true],["i-3","","rtb-main",false]]' \
    "$(jq -c '.workers | map([.instance, .pool, .routeTable, .regionalRoute])' "$root/out")" "subnets without a table use the main one"
  equal 'true false' "$(jq -r '"\(.regionalConfigurationValid) \(.allObservedWorkersRouted)"' "$root/out")" "only routing fails"
  setup
  jq '.VpcEndpoints[0].RouteTableIds += ["rtb-main"]' "$root/aws/ec2-describe-vpc-endpoints.json" > "$root/endpoints" &&
    mv "$root/endpoints" "$root/aws/ec2-describe-vpc-endpoints.json"
  instances '[{"InstanceId": "i-3", "SubnetId": "subnet-other"}]'
  check "routed through the main table" audit
}

test_another_region_or_an_unusable_endpoint_is_invalid_even_without_workers() {
  local change
  for change in 'empty-location' '.VpcEndpoints[0].State = "pending"' '.VpcEndpoints[0].VpcEndpointType = "Interface"' \
    '.VpcEndpoints[0].ServiceName = "com.amazonaws.eu-north-1.dynamodb"'; do
    setup
    instances '[]'
    if [[ "$change" == empty-location ]]; then
      echo '{"LocationConstraint": null}' > "$root/aws/s3api-get-bucket-location.json"
    else
      jq "$change" "$root/aws/ec2-describe-vpc-endpoints.json" > "$root/endpoints" &&
        mv "$root/endpoints" "$root/aws/ec2-describe-vpc-endpoints.json"
    fi
    audit && { echo "  failed: passed with $change" >&2; exit 1; }
    equal 'false false []' "$(jq -c -j '.regionalConfigurationValid, " ", .allObservedWorkersRouted, " ", .workers' "$root/out")" \
      "$change"
    check "writes the report with $change" test -s "$root/report.json"
    [[ "$change" != empty-location ]] ||
      equal '"us-east-1"' "$(jq -c .region "$root/out")" "no location constraint is us-east-1"
  done
}

test_refuses_missing_arguments_unknown_endpoints_and_aws_failures() {
  setup
  PATH="$root/bin:$PATH" bash "$script" --bucket inputs --region eu-north-1 > /dev/null 2> "$root/err"
  equal 2 "$?" "usage status"
  check "names them" grep -q -- "--endpoint --start --end --output" "$root/err"
  setup
  echo '{"VpcEndpoints": []}' > "$root/aws/ec2-describe-vpc-endpoints.json"
  audit && { echo "  failed: accepted a missing endpoint" >&2; exit 1; }
  check "explains" grep -q "exactly one endpoint vpce-1" "$root/err"
  check "writes no report" test ! -e "$root/report.json"
  setup
  echo AccessDeniedException > "$root/aws/ce-get-cost-and-usage.error"
  audit && { echo "  failed: ignored a billing failure" >&2; exit 1; }
  check "explains" grep -q "cannot read the billing" "$root/err"
  check "writes no report" test ! -e "$root/report.json"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
