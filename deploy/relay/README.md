# Optional public relay fallback

The lab returned to direct public IPv4 access on 2026-11-16. The temporary relay and its dedicated AWS
resources were retired. This runbook describes a fallback for a future connection behind carrier-grade NAT;
a dynamic public home address alone does not require a relay. See [Public access](../SETUP.md#public-access)
for the normal setup and automatic address updates.

A fallback relay forwards TCP 80, 443 and 4647 to the Mac over three outbound SSH connections. HTTPS and
Nomad mutual TLS terminate on the Mac; the relay holds neither application credentials nor TLS private keys.
Separate connections keep artifact traffic out of Nomad's SSH transport.

## Provision and verify

Provision an Ubuntu ARM EC2 instance with an Elastic IP and a dedicated security group allowing public TCP
22, 80, 443 and 4647. Use key-only SSH, with password, keyboard-interactive and root login disabled. The tunnel
must accept a changing home source IP. Keep the instance outside worker Auto Scaling groups and omit worker
ownership tags so worker cleanup cannot retire it.

Configure HAProxy for TCP passthrough with these routes:

| Public listener | Loopback reverse listener | Mac destination |
| --- | --- | --- |
| 80 | 127.0.0.1:18080 | 127.0.0.1:80 |
| 443 | 127.0.0.1:18443 | 127.0.0.1:443 |
| 4647 | 127.0.0.1:14647 | 127.0.0.1:4647 |

Give the Mac a dedicated tunnel key and restrict its relay account to remote forwarding on exactly these
three loopback listeners. Disable shell commands, local and Unix-socket forwarding, PTYs, X11 and agent
forwarding for that account. Leave `GatewayPorts` disabled. Configure server keepalives to detect dead clients.
Verify the relay's SSH host key against authenticated EC2 console output before saving it in the Mac's
`~/.ssh/eignex-relay-known-hosts`.

Run one supervised SSH process per route on the Mac, with the corresponding remote and local ports:

```sh
ssh -N -T -i "$HOME/.ssh/eignex-lab-relay" \
  -o BatchMode=yes -o IdentitiesOnly=yes -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=10 \
  -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$HOME/.ssh/eignex-relay-known-hosts" \
  -o ControlMaster=no -o ControlPath=none \
  -R 127.0.0.1:REMOTE_PORT:127.0.0.1:LOCAL_PORT tunnel@RELAY_IP
```

Use launchd `KeepAlive` and `RunAtLoad` with a retry throttle. System LaunchDaemons running as the lab user
support unattended boot; user LaunchAgents start only after that user logs in. Load only one set of services
so duplicate processes cannot compete for the reverse listeners. Keep keys, rendered service files and
resource receipts outside Git.

## Activate the fallback

Under the maintenance lock, back up the live configuration and move
`~/.config/eignex-lab/dynamic-dns.json` to `dynamic-dns.json.disabled-for-relay`, retaining mode 600.
Set the domain's A record and Nomad's `advertise.rpc` to the relay address, retaining port 4647, then restart
the Nomad server and client agents without deleting their state. Keep the existing private loopback alias
and Raft configuration intact.

Verify the leader, local client, authenticated public API and cloud worker registration. Test HTTPS with
`curl --resolve lab.eignex.com:443:RELAY_IP https://lab.eignex.com/health` and the usual private curl auth
configuration. An open TCP port alone does not establish that workers can register and execute work.

## Return to direct access

Verify inbound access from outside the home LAN to a public home IPv4 address on all three ports, including
the expected TLS certificate and authenticated API response. Under the maintenance lock, restore the DNS
settings file and run `deploy/public-address.sh --data "$LAB_DATA" --settings "$HOME/.config/eignex-lab/dynamic-dns.json"`
to reconcile DNS and Nomad. Verify that the scheduled five-minute updater subsequently succeeds.

Unload the Nomad tunnel and verify a cloud worker is ready through a direct connection to the Mac. Allow
the old DNS record's cache lifetime to expire before unloading the HTTP and HTTPS tunnels. Remove their
plists from launchd's service directories so a later login or reboot cannot reconnect to the retired relay.
Verify the authenticated API externally again with all reverse tunnels disabled.

Only then terminate the relay, release its Elastic IP, and remove its dedicated root disk, security group
and EC2 key-pair entry after confirming they have no other users. Save the verification and retirement
receipts in the lab's private operations directory.
