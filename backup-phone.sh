#!/bin/sh
# Local-only phone backup (NO CLOUD). Pulls shared storage + a settings/app
# inventory to ~/Documents/phone-backups/A17-<date>/.
# Unrooted Android cannot copy app-internal data (chats, auth seeds) — see
# README "Backups". Store the output on local/encrypted media only.
set -e
ADB="${ADB:-$HOME/android-tools/platform-tools/adb}"
DEST="$HOME/Documents/phone-backups/A17-$(date +%Y-%m-%d_%H%M)"

command -v "$ADB" >/dev/null 2>&1 || { echo "adb not found at $ADB (set \$ADB)"; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "No device. Plug in + authorise USB debugging."; exit 1; }

mkdir -p "$DEST"
echo "Backing up to $DEST"

"$ADB" pull /sdcard "$DEST/sdcard"
"$ADB" shell pm list packages -3 | sed 's/package://' | sort > "$DEST/installed-user-apps.txt"
"$ADB" shell pm list packages -d | sed 's/package://' | sort > "$DEST/disabled-packages.txt"
"$ADB" shell settings list global > "$DEST/settings-global.txt" 2>/dev/null || true
"$ADB" shell settings list secure > "$DEST/settings-secure.txt" 2>/dev/null || true
"$ADB" shell settings list system > "$DEST/settings-system.txt" 2>/dev/null || true

echo "Done. Size:"; du -sh "$DEST"
