#!/usr/bin/env bash
# Full undo: put the TV back the way it was on 2026-10-22, before the debloat.
#  1. tools/restore.sh re-enables every package in debloat.txt (from the record it saved before the
#     first change), gives Home back to the Google TV launcher and resets the animation scales.
#  2. Private DNS goes back to Android's default (both settings were unset before).
#  3. The new launcher's accessibility service is removed from the enabled list.
#  4. The screensaver setting goes back to the default, Ambient mode (it was unset before).
#  5. The launcher loses WRITE_SECURE_SETTINGS, so its DNS guard cannot switch Private DNS again.
#  6. UNINSTALL_LAUNCHER=1 also uninstalls the new launcher (com.gathin.tvhome).
#  7. UNINSTALL_AERIAL_VIEWS=1 also uninstalls Aerial Views (com.neilturner.aerialviews). Without it the
#     app stays installed, but step 4 makes Ambient mode the screensaver again.
# The two VPN apps were uninstalled on request; reinstall them from the Play Store if you want them.
# The saved Wi-Fi network that setupwraith owned is gone; join it again in Settings if you need it.
#
# Usage: TV_HOST=192.168.0.3 ./restore.sh   (UNINSTALL_LAUNCHER=1 and UNINSTALL_AERIAL_VIEWS=1 remove those apps too)
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.0.3 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }

# The record is named after the IP the TV had on 2026-10-22 (192.168.0.3). With another TV_HOST,
# tools/restore.sh finds no record and enables every line of debloat.txt, which is the same result
# here: nothing was disabled before the debloat (backup/20261022-034349/packages-disabled.txt is empty).
tools/restore.sh debloat.txt com.google.android.apps.tv.launcherx

# enabled_accessibility_services was unset and accessibility_enabled was 0 before; keep any other
# service that is in the list now.
printf '%s\n' \
  "settings delete global private_dns_mode" \
  "settings delete global private_dns_specifier" \
  "s=\$(settings get secure enabled_accessibility_services); n=\$(echo \"\$s\" | tr ':' '\n' | grep -v '^com.gathin.tvhome/' | paste -sd: -); if [ -z \"\$n\" ] || [ \"\$n\" = null ]; then settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0; else settings put secure enabled_accessibility_services \"\$n\"; fi" \
  "settings delete secure screensaver_components" \
  | "$PY" -I tools/tv.py --batch

# The launcher may stay installed: take back the permission that lets it switch Private DNS.
"$PY" -I tools/tv.py "pm revoke com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; true"
if [ "${UNINSTALL_LAUNCHER:-0}" = 1 ]; then
  "$PY" -I tools/tv.py "pm uninstall com.gathin.tvhome"
fi
if [ "${UNINSTALL_AERIAL_VIEWS:-0}" = 1 ]; then
  "$PY" -I tools/tv.py "pm uninstall com.neilturner.aerialviews"
fi
"$PY" -I tools/tv.py "input keyevent KEYCODE_HOME; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1"
echo "Done. Home should be the Google TV launcher again."
