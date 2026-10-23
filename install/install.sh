#!/usr/bin/env bash
# Installs Free My TV on an Android TV / Google TV from macOS or Linux.
# Usage:  ./install.sh 192.168.1.50 [path/to/FreeMyTV-generic.apk]
set -euo pipefail
IP="${1:?Usage: ./install.sh <TV IP address> [apk]}"
APK="${2:-$(dirname "$0")/../FreeMyTV-generic.apk}"
[ -f "$APK" ] || { echo "APK not found: $APK (build it with ./build.sh --generic, or take it from the GitHub release)"; exit 1; }
command -v adb >/dev/null 2>&1 || { echo "adb is not installed. macOS: brew install android-platform-tools. Debian/Ubuntu: sudo apt install adb"; exit 1; }
echo "Connecting to $IP ... (on the TV, tick 'Always allow' when it asks 'Allow USB debugging?')"
adb connect "$IP:5555"
sleep 3
adb devices
echo "Installing $APK ..."
adb -s "$IP:5555" install -r "$APK"
echo
echo "Done. On the TV: open 'Free My TV' -> 'Home Screen' -> 'Free My TV' (accept the debugging prompt once more)."
