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

echo "restructure done"
