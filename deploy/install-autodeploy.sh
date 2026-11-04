#!/usr/bin/env bash
# Install deploy/autodeploy.py as a root launchd job on the lab Mac: every 5 minutes it deploys the newest green build of
# main (see the script). The script lives under the data directory and refreshes itself from each deployed commit.
# Usage (on the Mac, from a checkout): sudo bash deploy/install-autodeploy.sh [OWNER]
set -euo pipefail
OWNER=${1:-${SUDO_USER:-$(id -un)}}
HOME_DIR=$(dscl . -read "/Users/$OWNER" NFSHomeDirectory | awk '{print $2}')
DIR="$HOME_DIR/eignex-lab-data/autodeploy"
LABEL=com.eignex.lab.autodeploy
PLIST=/Library/LaunchDaemons/$LABEL.plist
install -d -o "$OWNER" -m 755 "$DIR"
install -o root -m 755 "$(dirname "$0")/autodeploy.py" "$DIR/autodeploy.py"
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>-I</string><string>$DIR/autodeploy.py</string></array>
  <key>EnvironmentVariables</key><dict><key>LAB_AUTODEPLOY_USER</key><string>$OWNER</string></dict>
  <key>StartInterval</key><integer>300</integer>
  <key>RunAtLoad</key><false/>
  <key>StandardOutPath</key><string>$HOME_DIR/eignex-lab-data/logs/autodeploy.stdout.log</string>
  <key>StandardErrorPath</key><string>$HOME_DIR/eignex-lab-data/logs/autodeploy.stdout.log</string>
</dict>
</plist>
PLIST
chmod 644 "$PLIST"
plutil -lint "$PLIST" > /dev/null
launchctl bootout "system/$LABEL" 2>/dev/null || true
launchctl bootstrap system "$PLIST"
echo "installed $LABEL: $DIR/autodeploy.py every 300 s"
