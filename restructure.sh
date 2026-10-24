#!/usr/bin/env bash
# Moves flat-uploaded files from the repo root into their real folders.
# Safe to run more than once: it only moves a file if it exists in the root.
set -e
cd "$(dirname "$0")"

move() {  # move <file> <destination dir>
  if [ -f "$1" ]; then
    mkdir -p "$2"
    mv -f "$1" "$2/$1"
    echo "moved $1 -> $2/"
  fi
}

# CollapseStyle.kt stays in the root: patch_taskbar.py copies it into the app itself.
move colors.xml          app/src/main/res/values
move strings.xml         app/src/main/res/values
move tb_pref_general.xml app/src/main/res/xml
move U.java              app/src/main/java/com/farmerbb/taskbar/util
move TaskbarController.java app/src/main/java/com/farmerbb/taskbar/ui
move UIHostService.java      app/src/main/java/com/farmerbb/taskbar/ui
move TaskbarBlurWindow.java  app/src/main/java/com/farmerbb/taskbar/ui
move Constants.java         app/src/main/java/com/farmerbb/taskbar/util
move ContextMenuActivity.java app/src/main/java/com/farmerbb/taskbar/activity
move tb_pref_context_menu_launch_options.xml app/src/main/res/xml

# "Close app" fallback needs this permission (idempotent)
MF=app/src/playstore/AndroidManifest.xml
if [ -f "$MF" ] && ! grep -q KILL_BACKGROUND_PROCESSES "$MF"; then
  sed -i '0,/<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"/s//<uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES" \/>\n    &/' "$MF"
  echo "added KILL_BACKGROUND_PROCESSES"
fi

echo "restructure done"
