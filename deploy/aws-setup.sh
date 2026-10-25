#!/usr/bin/env bash
# One-time AWS setup for running experiments with "host": "aws". Run it once, from a machine with an admin AWS session
# (`aws login`) that can ssh to the lab Mac:
#   deploy/aws-setup.sh [user@lab-host]          default rasmusros@192.168.50.104
# Settings: AWS_REGION (default eu-north-1), INSTANCE_TYPE (default c7i.2xlarge, the smallest size a job gets),
# VCPU_QUOTA (default 128, the account's on-demand vCPU quota, raised from AWS's default of 32), MAX_INSTANCES (default 8,
# a job's default instance count),
# ADMIN_PROFILE (default: the CLI's default profile).
#
# It creates, or reuses when they exist:
#   - an SSH key pair `eignex-lab`, its private key installed on the lab Mac;
#   - a security group `eignex-lab` in the default VPC that admits SSH only from the lab Mac's public address;
#   - an IAM user `eignex-lab` whose only rights are to launch tagged instances of INSTANCE_TYPE with that key and group,
#     tag them at launch, terminate instances tagged eignex-lab, read the public Ubuntu image id, and admit the Mac's
#     current address to the group (its address can change); its access key goes into the Mac's `eignex-lab` profile;
#   - an S3 bucket `eignex-lab-corpus-<account>-<region>` holding a copy of the corpus cache, and an instance role
#     `eignex-lab-instance` that may read and write only that bucket, which the IAM user may pass to its instances;
#   - EC2's spot service-linked role `AWSServiceRoleForEC2Spot`, which the account needs before any spot launch and
#     which the lab's IAM user may not create;
#   - `$LAB_DATA/aws/aws.properties` on the Mac, which the lab reads: no service needs reinstalling.
set -euo pipefail
host="${1:-rasmusros@192.168.50.104}"
region="${AWS_REGION:-eu-north-1}"
type="${INSTANCE_TYPE:-c7i.2xlarge}"
instance_types="${INSTANCE_TYPES:-$type}"
max="${MAX_INSTANCES:-8}"
quota="${VCPU_QUOTA:-128}"
# The sizes of the family a job may get, by the cores its cases need, as far as the vCPU quota reaches.
types="$(for baseline in ${instance_types//,/ }; do
  family="${baseline%%.*}"
  for size in xlarge:4 2xlarge:8 4xlarge:16 8xlarge:32 12xlarge:48 16xlarge:64; do
    if [[ ${size#*:} -le $quota ]]; then printf '"%s.%s",' "$family" "${size%%:*}"; fi
  done
done | sed 's/,$//')"
name=eignex-lab
admin=(aws --region "$region" --output text ${ADMIN_PROFILE:+--profile "$ADMIN_PROFILE"})
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

account="$("${admin[@]}" sts get-caller-identity --query Account)"
echo "account $account, region $region, $type ×$max"
# Ask EC2 for architecture/topology instead of guessing from family names. SIMD labels remain operator hints.
: > "$work/architecture.properties"
for baseline in ${instance_types//,/ }; do
  read -r architecture threads < <("${admin[@]}" ec2 describe-instance-types --instance-types "$baseline" \
    --query 'InstanceTypes[0].[ProcessorInfo.SupportedArchitectures[0],VCpuInfo.DefaultThreadsPerCore]')
  [[ "$architecture" == arm64 || "$architecture" == x86_64 ]] || { echo "unsupported architecture for $baseline: $architecture" >&2; exit 1; }
  printf 'architecture.%s=%s\nthreadsPerCore.%s=%s\n' "${baseline%%.*}" "$architecture" "${baseline%%.*}" "$threads" >> "$work/architecture.properties"
done

[[ "$("${admin[@]}" ec2 describe-instance-type-offerings --filters "Name=instance-type,Values=$type" \
  --query 'InstanceTypeOfferings[0].InstanceType')" == "$type" ]] || { echo "$type is not offered in $region"; exit 1; }

# The lab Mac needs the AWS CLI it will launch instances with.
ssh "$host" 'export PATH=/opt/homebrew/bin:$PATH; command -v aws >/dev/null || brew install awscli >/dev/null'
mac_ip="$(ssh "$host" 'curl -fsS https://checkip.amazonaws.com')"
echo "lab Mac's public address: $mac_ip"

# Key pair: a fresh one whenever the Mac does not hold the private key of the existing one.
if ssh "$host" "test -f ~/eignex-lab-data/aws/$name.pem" && "${admin[@]}" ec2 describe-key-pairs --key-names "$name" >/dev/null 2>&1; then
  echo "key pair $name: kept"
else
  "${admin[@]}" ec2 delete-key-pair --key-name "$name" >/dev/null 2>&1 || true
  "${admin[@]}" ec2 create-key-pair --key-name "$name" --key-type ed25519 --query KeyMaterial > "$work/$name.pem"
  ssh "$host" 'mkdir -p ~/eignex-lab-data/aws && chmod 700 ~/eignex-lab-data/aws'
  scp -q "$work/$name.pem" "$host:eignex-lab-data/aws/$name.pem"
  ssh "$host" "chmod 600 ~/eignex-lab-data/aws/$name.pem"
  echo "key pair $name: created, private key on the Mac"
fi

# Security group in the default VPC, SSH from the Mac only.
vpc="$("${admin[@]}" ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId')"
sg="$("${admin[@]}" ec2 describe-security-groups --filters "Name=group-name,Values=$name" "Name=vpc-id,Values=$vpc" \
  --query 'SecurityGroups[0].GroupId')"
if [[ "$sg" == "None" ]]; then
  sg="$("${admin[@]}" ec2 create-security-group --group-name "$name" --vpc-id "$vpc" \
    --description "SSH from the eignex lab Mac" --query GroupId)"
fi
"${admin[@]}" ec2 authorize-security-group-ingress --group-id "$sg" --protocol tcp --port 22 --cidr "$mac_ip/32" \
  >/dev/null 2>&1 || true
echo "security group $sg"

# Corpus cache: a private bucket, and the role the instances read and write it with.
bucket="$name-corpus-$account-$region"
if ! "${admin[@]}" s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
  "${admin[@]}" s3api create-bucket --bucket "$bucket" --create-bucket-configuration "LocationConstraint=$region" >/dev/null
fi
"${admin[@]}" s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
role="$name-instance"
cat > "$work/trust.json" <<EOF
{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
EOF
cat > "$work/corpus.json" <<EOF
{"Version": "2012-10-17", "Statement": [
  {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::$bucket"},
  {"Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject"], "Resource": "arn:aws:s3:::$bucket/*"}
]}
EOF
"${admin[@]}" iam get-role --role-name "$role" >/dev/null 2>&1 ||
  "${admin[@]}" iam create-role --role-name "$role" --assume-role-policy-document "file://$work/trust.json" >/dev/null
"${admin[@]}" iam put-role-policy --role-name "$role" --policy-name "$name-corpus" --policy-document "file://$work/corpus.json"
if ! "${admin[@]}" iam get-instance-profile --instance-profile-name "$role" >/dev/null 2>&1; then
  "${admin[@]}" iam create-instance-profile --instance-profile-name "$role" >/dev/null
  "${admin[@]}" iam add-role-to-instance-profile --instance-profile-name "$role" --role-name "$role"
fi
echo "corpus bucket $bucket, instance role $role"

# Spot launches need the account's spot service-linked role, which only an admin may create.
"${admin[@]}" iam get-role --role-name AWSServiceRoleForEC2Spot >/dev/null 2>&1 ||
  "${admin[@]}" iam create-service-linked-role --aws-service-name spot.amazonaws.com >/dev/null

# IAM user with the narrowest policy that lets the lab do its job.
cat > "$work/policy.json" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "Describe", "Effect": "Allow", "Resource": "*",
     "Action": ["ec2:DescribeInstances", "ec2:DescribeImages", "ec2:DescribeInstanceStatus", "ec2:DescribeSubnets",
                "ec2:DescribeSecurityGroups", "ec2:DescribeKeyPairs"]},
    {"Sid": "LaunchTaggedInstances", "Effect": "Allow", "Action": "ec2:RunInstances",
     "Resource": "arn:aws:ec2:$region:$account:instance/*",
     "Condition": {"StringEquals": {"aws:RequestTag/$name": "true", "ec2:InstanceType": [$types]}}},
    {"Sid": "LaunchTaggedVolumes", "Effect": "Allow", "Action": "ec2:RunInstances",
     "Resource": "arn:aws:ec2:$region:$account:volume/*",
     "Condition": {"StringEquals": {"aws:RequestTag/$name": "true"}}},
    {"Sid": "LaunchWith", "Effect": "Allow", "Action": "ec2:RunInstances",
     "Resource": ["arn:aws:ec2:$region::image/*", "arn:aws:ec2:$region:$account:subnet/*",
                  "arn:aws:ec2:$region:$account:network-interface/*", "arn:aws:ec2:$region:$account:security-group/$sg",
                  "arn:aws:ec2:$region:$account:key-pair/$name"]},
    {"Sid": "TagOnLaunch", "Effect": "Allow", "Action": "ec2:CreateTags", "Resource": "*",
     "Condition": {"StringEquals": {"ec2:CreateAction": "RunInstances"}}},
    {"Sid": "TerminateOwn", "Effect": "Allow", "Action": "ec2:TerminateInstances",
     "Resource": "arn:aws:ec2:$region:$account:instance/*",
     "Condition": {"StringEquals": {"aws:ResourceTag/$name": "true"}}},
    {"Sid": "UbuntuImage", "Effect": "Allow", "Action": "ssm:GetParameter",
     "Resource": "arn:aws:ssm:$region::parameter/aws/service/canonical/*"},
    {"Sid": "AdmitTheMac", "Effect": "Allow", "Action": "ec2:AuthorizeSecurityGroupIngress",
     "Resource": "arn:aws:ec2:$region:$account:security-group/$sg"},
    {"Sid": "PassInstanceRole", "Effect": "Allow", "Action": "iam:PassRole",
     "Resource": "arn:aws:iam::$account:role/$role",
     "Condition": {"StringEquals": {"iam:PassedToService": "ec2.amazonaws.com"}}}
  ]
}
EOF
"${admin[@]}" iam get-user --user-name "$name" >/dev/null 2>&1 || "${admin[@]}" iam create-user --user-name "$name" >/dev/null
"${admin[@]}" iam put-user-policy --user-name "$name" --policy-name "$name-ec2" --policy-document "file://$work/policy.json"
for old in $("${admin[@]}" iam list-access-keys --user-name "$name" --query 'AccessKeyMetadata[].AccessKeyId'); do
  "${admin[@]}" iam delete-access-key --user-name "$name" --access-key-id "$old"
done
read -r key secret < <("${admin[@]}" iam create-access-key --user-name "$name" \
  --query 'AccessKey.[AccessKeyId,SecretAccessKey]')
echo "IAM user $name: policy set, new access key $key"

# The Mac's side: the profile the lab's AWS CLI uses, and the lab's settings.
ssh "$host" "export PATH=/opt/homebrew/bin:\$PATH; aws configure set aws_access_key_id '$key' --profile $name && \
  aws configure set aws_secret_access_key '$secret' --profile $name && aws configure set region '$region' --profile $name"
# Keep node labels and explicit topology overrides when rerunning setup to extend the IAM type allow-list.
ssh "$host" 'grep -E "^(tags\.|threadsPerCore\.|nodeTags=)" ~/eignex-lab-data/aws/aws.properties 2>/dev/null || true' > "$work/operator-tags.properties"
ssh "$host" "cat > ~/eignex-lab-data/aws/aws.properties" <<EOF
region=$region
profile=$name
instanceType=$type
instanceTypes=$instance_types
# Add per-family labels before using tag selectors, for example:
# tags.c7i=arch:x86_64,simd:avx2,simd:avx512
# tags.c7g=arch:arm64,simd:neon,simd:sve
maxInstances=$max
vcpuQuota=$quota
keyName=$name
keyFile=/Users/$(ssh "$host" whoami)/eignex-lab-data/aws/$name.pem
securityGroup=$sg
corpusBucket=$bucket
instanceProfile=$role
EOF
ssh "$host" 'cat >> ~/eignex-lab-data/aws/aws.properties' < "$work/architecture.properties"
ssh "$host" 'cat >> ~/eignex-lab-data/aws/aws.properties' < "$work/operator-tags.properties"
echo "wrote ~/eignex-lab-data/aws/aws.properties on $host; restart the lab (lab update) to start the AWS worker"
