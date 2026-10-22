#!/usr/bin/env bash
# Emergency: give Home back to the stock Google TV launcher. Use it if the new Home does not start,
# shows the wrong screen, or the TV offers a factory screen when you press Home.
# It changes only Home: the other disabled packages stay disabled (full undo: ./restore.sh).
#
# Usage: TV_HOST=192.168.0.3 ./rescue.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.0.3 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
# launcherx has HOME priority 2 and setupwraith priority 1, so with both enabled launcherx wins.
printf '%s\n' \
  "pm enable --user 0 com.google.android.apps.tv.launcherx" \
  "pm enable --user 0 com.google.android.tungsten.setupwraith" \
  "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity" \
  "input keyevent KEYCODE_HOME" \
  "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" \
  | "$PY" -I tools/tv.py --batch
