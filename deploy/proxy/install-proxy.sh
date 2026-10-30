#!/bin/bash
# Run with sudo on the lab Mac: serve https://<domain> from the Mac itself with Caddy, which gets and renews its own
# Let's Encrypt certificate and forwards to the lab's 127.0.0.1:8420, and cloud workers' /worker/v1 calls to the
# coordinator's worker API on 127.0.0.1:8091. The home router forwards TCP 80 and 443 to the
# Mac and the domain's A record points at the home address; the lab's own password gate guards everything behind it.
#   sudo deploy/proxy/install-proxy.sh [domain] [user]     default lab.eignex.com; user defaults to the one running sudo
set -euo pipefail
domain="${1:-lab.eignex.com}"
user="${2:-${SUDO_USER:?run with sudo, or name the user}}"
home="$(dscl . -read "/Users/$user" NFSHomeDirectory | awk '{print $2}')"
caddy="$(command -v caddy || echo /opt/homebrew/bin/caddy)"
[[ -x "$caddy" ]] || { echo "install Caddy first: brew install caddy"; exit 1; }
label=com.eignex.lab.proxy
daemon="/Library/LaunchDaemons/$label.plist"
dir="$home/eignex-lab-data/proxy"
sudo -u "$user" mkdir -p "$dir" "$home/eignex-lab-data/logs"
sudo -u "$user" tee "$dir/Caddyfile" >/dev/null <<CADDY
$domain {
	encode gzip
	handle /worker/v1/* {
		reverse_proxy 127.0.0.1:8091
	}
	handle {
		reverse_proxy 127.0.0.1:8420
	}
}
CADDY
"$caddy" validate --config "$dir/Caddyfile" --adapter caddyfile >/dev/null
# macOS lets an ordinary user listen on 80 and 443, so Caddy runs as the lab's user and keeps its certificates in $dir.
cat > "$daemon" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$label</string>
  <key>UserName</key><string>$user</string>
  <key>ProgramArguments</key>
  <array>
    <string>$caddy</string><string>run</string>
    <string>--config</string><string>$dir/Caddyfile</string><string>--adapter</string><string>caddyfile</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>HOME</key><string>$home</string>
    <key>XDG_DATA_HOME</key><string>$dir/data</string>
    <key>XDG_CONFIG_HOME</key><string>$dir/config</string>
  </dict>
  <key>WorkingDirectory</key><string>$dir</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>15</integer>
  <key>StandardOutPath</key><string>$home/eignex-lab-data/logs/proxy.log</string>
  <key>StandardErrorPath</key><string>$home/eignex-lab-data/logs/proxy.log</string>
</dict>
</plist>
PLIST
chown root:wheel "$daemon"; chmod 644 "$daemon"
launchctl bootout "system/$label" 2>/dev/null || true
for _ in $(seq 30); do launchctl print "system/$label" >/dev/null 2>&1 || break; sleep 1; done
launchctl bootstrap system "$daemon"
sleep 3
launchctl print "system/$label" | grep -E '^\s*(state|pid) ='
