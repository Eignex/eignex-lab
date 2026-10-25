#!/usr/bin/env bash
# Build the public relay for the lab: an EC2 instance with a fixed address that serves https://<domain> with Caddy and
# forwards it to the lab through an SSH tunnel the lab Mac opens to it. The home network sits behind the ISP's
# carrier-grade NAT, so nothing can connect in to it; the Mac connects out instead.
#   deploy/relay/create.sh [domain] [user@lab-host]     default lab.eignex.com and rasmusros@192.168.50.104
# Settings: AWS_REGION (default eu-north-1), ADMIN_PROFILE (default: the CLI's default profile), INSTANCE_TYPE
# (default t4g.micro; a t4g.nano runs out of memory on its first package install).
# It creates, or reuses when they exist: the Mac's tunnel key (~/.ssh/eignex-lab-relay), an admin key pair
# `eignex-relay-admin` (private key in ~/.ssh here), a security group `eignex-relay` open to 22, 80 and 443, the
# instance `eignex-relay`, and an Elastic IP `eignex-relay`, which a rebuilt relay takes over so DNS stays put. With
# a DigitalOcean token in ~/.config/digitalocean/token it points the domain's A record at the address. It tags
# nothing `eignex-lab`, so the lab's own cleanup of its instances never touches the relay. Last, run
# install-tunnel.sh with sudo on the Mac, which it prints.
set -euo pipefail
domain="${1:-lab.eignex.com}"
host="${2:-rasmusros@192.168.50.104}"
region="${AWS_REGION:-eu-north-1}"
type="${INSTANCE_TYPE:-t4g.micro}"
name=eignex-relay
aws=(aws --region "$region" --output text ${ADMIN_PROFILE:+--profile "$ADMIN_PROFILE"})
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

key="$(ssh "$host" 'test -f ~/.ssh/eignex-lab-relay || ssh-keygen -q -t ed25519 -N "" -C eignex-lab-relay-tunnel -f ~/.ssh/eignex-lab-relay; cat ~/.ssh/eignex-lab-relay.pub')"
sed -e "s|__TUNNEL_KEY__|$key|" -e "s|__DOMAIN__|$domain|" "$here/user-data.sh" > "$work/user-data.sh"

admin="$HOME/.ssh/$name-admin"
test -f "$admin" || ssh-keygen -q -t ed25519 -N "" -C "$name-admin" -f "$admin"
"${aws[@]}" ec2 describe-key-pairs --key-names "$name-admin" >/dev/null 2>&1 ||
  "${aws[@]}" ec2 import-key-pair --key-name "$name-admin" --public-key-material "fileb://$admin.pub" >/dev/null

vpc="$("${aws[@]}" ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId')"
sg="$("${aws[@]}" ec2 describe-security-groups --filters Name=group-name,Values=$name Name=vpc-id,Values="$vpc" --query 'SecurityGroups[0].GroupId')"
if [[ "$sg" == None ]]; then
  sg="$("${aws[@]}" ec2 create-security-group --group-name $name --description "eignex lab relay: HTTPS and key-only SSH" --vpc-id "$vpc" --query GroupId)"
  for port in 22 80 443; do
    "${aws[@]}" ec2 authorize-security-group-ingress --group-id "$sg" \
      --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=0.0.0.0/0}],Ipv6Ranges=[{CidrIpv6=::/0}]" >/dev/null
  done
fi

ami="$("${aws[@]}" ssm get-parameter --name /aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id --query Parameter.Value)"
id="$("${aws[@]}" ec2 run-instances --image-id "$ami" --instance-type "$type" --key-name "$name-admin" --security-group-ids "$sg" \
  --user-data "file://$work/user-data.sh" --metadata-options HttpTokens=required \
  --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=8,VolumeType=gp3,Encrypted=true}' \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$name}]" --query 'Instances[0].InstanceId')"
"${aws[@]}" ec2 wait instance-running --instance-ids "$id"

read -r allocation ip < <("${aws[@]}" ec2 describe-addresses --filters "Name=tag:Name,Values=$name" --query 'Addresses[0].[AllocationId,PublicIp]')
if [[ "$allocation" == None ]]; then
  read -r allocation ip < <("${aws[@]}" ec2 allocate-address --domain vpc \
    --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$name}]" --query '[AllocationId,PublicIp]')
fi
"${aws[@]}" ec2 associate-address --instance-id "$id" --allocation-id "$allocation" --allow-reassociation >/dev/null
echo "relay $id at $ip"

token="$HOME/.config/digitalocean/token"
if [[ -f "$token" ]]; then
  zone="${domain#*.}"; record="${domain%%.*}"
  auth="Authorization: Bearer $(tr -d '[:space:]' < "$token")"
  existing="$(curl -fsS -H "$auth" "https://api.digitalocean.com/v2/domains/$zone/records?type=A&name=$domain" | jq -r '.domain_records[0].id // empty')"
  body="{\"type\":\"A\",\"name\":\"$record\",\"data\":\"$ip\",\"ttl\":300}"
  if [[ -n "$existing" ]]; then
    curl -fsS -X PUT -H "$auth" -H 'Content-Type: application/json' -d "$body" "https://api.digitalocean.com/v2/domains/$zone/records/$existing" >/dev/null
  else
    curl -fsS -X POST -H "$auth" -H 'Content-Type: application/json' -d "$body" "https://api.digitalocean.com/v2/domains/$zone/records" >/dev/null
  fi
  echo "DNS: $domain -> $ip"
else
  echo "add the DNS record yourself: A $domain -> $ip"
fi
echo "then, on the lab Mac: sudo ~/Workspaces/eignex-lab/deploy/relay/install-tunnel.sh $ip"
