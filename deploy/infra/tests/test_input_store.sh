#!/usr/bin/env bash
# Tests of deploy/infra/aws/input-store.sh against a stub AWS CLI. Needs jq.
# bash deploy/infra/tests/test_input_store.sh [test name...]
# shellcheck disable=SC1090,SC1091,SC2016,SC2329 # sourced script; literal shell text in stubs; tests called indirectly
set -uo pipefail
infra="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$infra/aws/input-store.sh"
. "$script"

check() {  # check <description> <command...>
  local description="$1"
  shift
  "$@" || { echo "  failed: $description" >&2; exit 1; }
}
equal() {  # equal <expected> <actual> <description>
  [[ "$1" == "$2" ]] || { printf '  failed: %s\n    expected: %s\n    actual:   %s\n' "$3" "$1" "$2" >&2; exit 1; }
}

# A stub `aws`: answers from $root/aws/<service>-<operation>.json (exit 254 with the error code in
# <service>-<operation>.error), records every call's arguments in $root/calls, one call per line, NUL-free and
# separated by tabs, and keeps each JSON payload argument in $root/<option>.json.
setup() {
  [[ -z "${root:-}" ]] || rm -rf "$root"
  root="$(mktemp -d)"
  mkdir -p "$root/bin" "$root/aws"
  cat > "$root/bin/aws" <<STUB
#!/usr/bin/env bash
(IFS=\$'\t'; echo "\$*") >> "$root/calls"
for (( i = 1; i <= \$#; i++ )); do
  case "\${!i}" in --policy | --lifecycle-configuration | --policy-document)
    next=\$((i + 1)); printf '%s' "\${!next}" > "$root/\${!i#--}.json" ;;
  esac
done
name="$root/aws/\$1-\$2"
if [[ -f "\$name.error" ]]; then echo "An error occurred (\$(cat "\$name.error")) when calling \$2" >&2; exit 254; fi
[[ -f "\$name.json" ]] && cat "\$name.json"
exit 0
STUB
  chmod +x "$root/bin/aws"
  echo '{"LocationConstraint": "eu-north-1"}' > "$root/aws/s3api-get-bucket-location.json"
  echo '{"VpcEndpoints": [{"VpcEndpointId": "vpce-1", "VpcEndpointType": "Gateway", "RouteTableIds": ["rtb-a"]}]}' \
    > "$root/aws/ec2-describe-vpc-endpoints.json"
  jq -n '{Policy: ({Version: "2012-10-17", Statement: [{Sid: "Owner", Effect: "Allow"},
    {Sid: "LabRegionalInputPublish", Effect: "Allow"}, {Sid: "LabRegionalInputRead", Effect: "Allow"}]} | tojson)}' \
    > "$root/aws/s3api-get-bucket-policy.json"
  echo '{"Rules": [{"ID": "logs", "Status": "Enabled", "Filter": {"Prefix": "logs/"}, "Expiration": {"Days": 3}}]}' \
    > "$root/aws/s3api-get-bucket-lifecycle-configuration.json"
}
store() {  # store [route tables...]: the tool's status; output in $root/out, errors in $root/err
  local tables=("${@:-rtb-a}") table args=()
  for table in "${tables[@]}"; do args+=(--route-table "$table"); done
  PATH="$root/bin:$PATH" bash "$script" --bucket inputs --region eu-north-1 --vpc vpc-1 "${args[@]}" \
    --native-user lab-native --receipt "$root/receipt.json" > "$root/out" 2> "$root/err"
}

test_regional_access_requires_the_specific_vpc_endpoint() {
  local policy writes
  policy="$(statements input-bucket vpce-test)"
  equal '["vpce-test","vpce-test"]' "$(jq -c 'map(.Condition.StringEquals."aws:SourceVpce")' <<< "$policy")" "every grant"
  equal false "$(jq 'any(.Action == "s3:PutObject")' <<< "$policy")" "no anonymous writes"
  writes="$(native_policy input-bucket | jq -c '.Statement[-1]')"
  equal '"*"' "$(jq -c '.Condition.StringEquals."s3:if-none-match"' <<< "$writes")" "conditional writes"
}

test_build_archives_and_solve_outputs_are_granted_and_expire() {
  equal true "$(statements input-bucket vpce-test | jq '.[0].Resource | index("arn:aws:s3:::input-bucket/build-blobs/*") != null')" \
    "workers read build archives"
  equal false "$(statements input-bucket vpce-test | jq '.[0].Resource | any(test("result-blobs"))')" \
    "workers never read solve outputs"
  local native
  native="$(native_policy input-bucket)"
  equal true "$(jq '.Statement[0].Resource | index("arn:aws:s3:::input-bucket/result-blobs/*") != null' <<< "$native")" \
    "the signer reads solve outputs"
  equal true "$(jq '.Statement[2].Resource | index("arn:aws:s3:::input-bucket/result-blobs/*") != null' <<< "$native")" \
    "the signer publishes solve outputs"
  local kept='{"ID":"other","Status":"Enabled","Filter":{"Prefix":"logs/"},"Expiration":{"Days":3}}' rules
  rules="$(lifecycle "[$kept, {\"ID\": \"LabBuildArchiveExpiry\", \"Status\": \"Disabled\"}, {\"ID\": \"LabResultBlobExpiry\"}]")"
  equal "[$kept]" "$(jq -c '.[:-2]' <<< "$rules")" "other rules kept, managed ones replaced"
  equal '[["build-blobs/",30,"Enabled",1],["result-blobs/",30,"Enabled",1]]' \
    "$(jq -c '.[-2:] | map([.Filter.Prefix, .Expiration.Days, .Status, .AbortIncompleteMultipartUpload.DaysAfterInitiation])' <<< "$rules")" \
    "both prefixes expire"
}

test_attaches_an_existing_endpoint_and_keeps_foreign_policy() {
  setup
  check "runs" store rtb-b rtb-a rtb-b
  equal '{"endpoint":"vpce-1","region":"eu-north-1","bucket":"inputs"}' "$(cat "$root/out")" "summary"
  check "adds only the missing route table" grep -qx $'ec2\tmodify-vpc-endpoint\t--region\teu-north-1\t--vpc-endpoint-id\tvpce-1\t--add-route-table-ids\trtb-b' "$root/calls"
  check "creates no endpoint" test -z "$(grep create-vpc-endpoint "$root/calls")"
  equal '["Owner","LabRegionalInputRead","LabRegionalInputMiss"]' "$(jq -c '[.Statement[].Sid]' "$root/policy.json")" \
    "foreign statements kept, managed ones replaced"
  equal '["Owner","LabRegionalInputPublish","LabRegionalInputRead"]' "$(jq -c '[.previousPolicy.Statement[].Sid]' "$root/receipt.json")" \
    "receipt keeps the previous policy"
  equal '"vpce-1"false' "$(jq -j '.endpoint, .created | tojson' "$root/receipt.json")" "receipt endpoint"
  equal 600 "$(stat -c %a "$root/receipt.json" 2>/dev/null || stat -f %Lp "$root/receipt.json")" "private receipt"
  equal '["logs","LabBuildArchiveExpiry","LabResultBlobExpiry"]' "$(jq -c '[.Rules[].ID]' "$root/lifecycle-configuration.json")" \
    "lifecycle"
  equal "$(native_policy inputs)" "$(cat "$root/policy-document.json")" "native user policy"
  check "names the user and policy" grep -qF -- $'--user-name\tlab-native\t--policy-name\teignex-lab-input-cache' "$root/calls"
}

test_creates_an_endpoint_and_starts_policy_and_lifecycle_when_missing() {
  setup
  echo '{"VpcEndpoints": []}' > "$root/aws/ec2-describe-vpc-endpoints.json"
  echo '{"VpcEndpoint": {"VpcEndpointId": "vpce-new", "VpcEndpointType": "Gateway"}}' > "$root/aws/ec2-create-vpc-endpoint.json"
  echo NoSuchBucketPolicy > "$root/aws/s3api-get-bucket-policy.error"
  echo NoSuchLifecycleConfiguration > "$root/aws/s3api-get-bucket-lifecycle-configuration.error"
  check "runs" store rtb-a rtb-b
  check "creates a gateway endpoint on both tables" grep -qF -- $'--vpc-endpoint-type\tGateway\t--route-table-ids\trtb-a\trtb-b\t' "$root/calls"
  equal '["LabRegionalInputRead","LabRegionalInputMiss"]' "$(jq -c '[.Statement[].Sid]' "$root/policy.json")" "new policy"
  equal '"vpce-new"' "$(jq -c '.Statement[0].Condition.StringEquals."aws:SourceVpce"' "$root/policy.json")" "bound to it"
  equal '{"endpoint":"vpce-new","created":true,"previousPolicy":{"Version":"2012-10-17","Statement":[]}}' \
    "$(jq -c . "$root/receipt.json")" "receipt"
  equal '["LabBuildArchiveExpiry","LabResultBlobExpiry"]' "$(jq -c '[.Rules[].ID]' "$root/lifecycle-configuration.json")" "lifecycle"
}

test_refuses_another_region_an_interface_endpoint_and_other_failures() {
  setup
  echo '{"LocationConstraint": "us-west-2"}' > "$root/aws/s3api-get-bucket-location.json"
  store && { echo "  failed: accepted a bucket in another region" >&2; exit 1; }
  check "explains" grep -q "same region" "$root/err"
  setup
  echo '{"VpcEndpoints": [{"VpcEndpointId": "vpce-1", "VpcEndpointType": "Interface"}]}' > "$root/aws/ec2-describe-vpc-endpoints.json"
  store && { echo "  failed: accepted an interface endpoint" >&2; exit 1; }
  check "explains" grep -q "gateway endpoint" "$root/err"
  setup
  echo AccessDenied > "$root/aws/s3api-get-bucket-policy.error"
  store && { echo "  failed: ignored a policy read failure" >&2; exit 1; }
  check "changes nothing" test -z "$(grep -E 'put-|modify-|create-' "$root/calls")"
  check "writes no receipt" test ! -e "$root/receipt.json"
  setup
  PATH="$root/bin:$PATH" bash "$script" --bucket inputs --region eu-north-1 > /dev/null 2> "$root/err" &&
    { echo "  failed: accepted missing arguments" >&2; exit 1; }
  check "names them" grep -q -- "--vpc --route-table --native-user --receipt" "$root/err"
}

failures=0
tests="${*:-$(declare -F | awk '$3 ~ /^test_/ { print $3 }')}"
for test in $tests; do
  ( set +e; trap '[[ -z "${root:-}" ]] || rm -rf "$root"' EXIT; "$test" )
  status=$?
  if (( status == 0 )); then echo "ok $test"; else echo "FAILED $test"; failures=$((failures + 1)); fi
done
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
