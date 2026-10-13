#!/bin/bash
# Run with sudo on the lab Mac: install the tunnel to the relay at <relay-ip> as a launchd daemon that starts at boot,
# runs as the lab's user and reconnects whenever the connection drops. It holds one reverse forward, the relay's
# 127.0.0.1:8420 to the lab's.
#   sudo deploy/relay/install-tunnel.sh <relay-ip> [user]     user defaults to the one running sudo
set -euo pipefail
ip="${1:?usage: sudo install-tunnel.sh <relay-ip> [user]}"
user="${2:-${SUDO_USER:?run with sudo, or name the user}}"
home="$(dscl . -read "/Users/$user" NFSHomeDirectory | awk '{print $2}')"
label=com.eignex.klause-relay
daemon="/Library/LaunchDaemons/$label.plist"
mkdir -p "$home/klause-lab-data/logs"
cat > "$daemon" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$label</string>
  <key>UserName</key><string>$user</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/ssh</string>
    <string>-N</string>
    <string>-i</string><string>$home/.ssh/klause-lab-relay</string>
    <string>-o</string><string>ExitOnForwardFailure=yes</string>
    <string>-o</string><string>ServerAliveInterval=30</string>
    <string>-o</string><string>ServerAliveCountMax=3</string>
    <string>-o</string><string>StrictHostKeyChecking=accept-new</string>
    <string>-o</string><string>UserKnownHostsFile=$home/.ssh/klause-relay-known-hosts</string>
    <string>-R</string><string>127.0.0.1:8420:127.0.0.1:8420</string>
    <string>tunnel@$ip</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>15</integer>
  <key>StandardErrorPath</key><string>$home/klause-lab-data/logs/relay.log</string>
</dict>
</plist>
PLIST
chown root:wheel "$daemon"; chmod 644 "$daemon"
# A rebuilt relay has a new host key under the same address.
sudo -u "$user" ssh-keygen -R "$ip" -f "$home/.ssh/klause-relay-known-hosts" >/dev/null 2>&1 || true
launchctl bootout "system/$label" 2>/dev/null || true
launchctl bootstrap system "$daemon"
sleep 5
launchctl print "system/$label" | grep -E '^\s*(state|pid) ='
