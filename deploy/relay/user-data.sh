#!/bin/bash
# The relay's first boot (EC2 user data), rendered by create.sh, which fills in the lab Mac's tunnel public key and the
# domain Caddy serves and gets a Let's Encrypt certificate for.
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -yq caddy unattended-upgrades
# The lab's tunnel: a user that can only hold one reverse forward, 127.0.0.1:8420, and run nothing.
useradd --system --create-home --shell /usr/sbin/nologin tunnel
install -d -m 700 -o tunnel -g tunnel /home/tunnel/.ssh
echo 'restrict,port-forwarding,permitlisten="127.0.0.1:8420",command="/bin/false" __TUNNEL_KEY__' > /home/tunnel/.ssh/authorized_keys
chown tunnel:tunnel /home/tunnel/.ssh/authorized_keys; chmod 600 /home/tunnel/.ssh/authorized_keys
cat > /etc/ssh/sshd_config.d/10-relay.conf <<'SSHD'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
GatewayPorts no
ClientAliveInterval 30
ClientAliveCountMax 3
SSHD
systemctl restart ssh
cat > /etc/caddy/Caddyfile <<'CADDY'
__DOMAIN__ {
	encode gzip
	reverse_proxy 127.0.0.1:8420
}
CADDY
systemctl enable --now caddy
systemctl reload caddy
