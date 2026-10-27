#!/bin/zsh
# Installs build/BubbleDrop.app into /Applications and makes the desktop widget pick it up.
# Replacing the app under a running widget leaves the old widget process serving a deleted copy,
# and macOS's refreshes of it then fail ("timelineReloadFailed") and back off for an hour.
set -euo pipefail
cd "$(dirname "$0")"
[[ -d build/BubbleDrop.app ]] || { echo "Run ./build.sh first"; exit 1; }

osascript -e 'tell application id "dev.bubbledrop.mac" to quit' 2>/dev/null || true
for _ in {1..20}; do pgrep -f "MacOS/BubbleDrop$" >/dev/null || break; sleep 0.5; done
pkill -f "BubbleDropWidget.appex" 2>/dev/null || true

rm -rf /Applications/BubbleDrop.app
ditto build/BubbleDrop.app /Applications/BubbleDrop.app
WIDGET=/Applications/BubbleDrop.app/Contents/PlugIns/BubbleDropWidget.appex
[[ -d $WIDGET ]] && pluginkit -a "$WIDGET"

# Restart the widget service so it drops its view of the old copy and loads the new one.
killall -9 chronod 2>/dev/null || true
open /Applications/BubbleDrop.app
echo "Installed /Applications/BubbleDrop.app"
