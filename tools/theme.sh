#!/usr/bin/env bash
# Push a theme (a folder with config.json and optionally wallpaper.jpg) to the launcher on the TV and
# restart Home so it picks it up. The launcher reads the files from its external files directory and
# overlays config.json on its built-in config.xml; see themes/README.md for the keys.
#
# Usage: TV_HOST=192.168.1.50 tools/theme.sh themes/aurora
#        TV_HOST=192.168.1.50 tools/theme.sh --reset        # remove the overlay, back to config.xml
set -euo pipefail
# Resolve the theme folder against the caller's directory before moving into tools/.
# Git Bash's pwd says /c/Users/..., which the Windows Python cannot open; pwd -W gives C:/Users/... (as in disable.sh).
THEME_ARG="${1:-}"
case "$THEME_ARG" in --reset|"") ;; /*) ;; *) THEME_ARG="$(pwd -W 2>/dev/null || pwd)/$THEME_ARG" ;; esac
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 themes/aurora}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.glasstv.tvhome
DIR=/sdcard/Android/data/$PKG/files
# A unique marker lets the launcher clear on-TV layout/motion choices after every theme push,
# including a push of identical config.json bytes and --reset.
REVISION="$("$PY" -I -c 'import uuid; print(uuid.uuid4().hex)')"
tv() { "$PY" -I tv.py "$@"; }
# Restart Home so it rereads the theme. Android drops a force-stopped app's accessibility services from the
# enabled list, which would switch off InputKeyService/HomeKeyService, so put the list back if it had ours.
# Android removes them asynchronously while it handles the restart, so wait a second before writing the
# list back. Writing the same list back is not enough: the force-stop also turns accessibility_enabled off,
# and the service only binds again once the list changes. So restart first, then clear the list, write it
# back and switch accessibility on again as build-and-install does. Some TVs (TCL Smart TV Pro G03, Android 11)
# also keep the service in their "crashed" list after the force-stop and leave it unbound until accessibility
# itself goes off and on again, so switch it off with the list cleared and wait a second before writing back.
RESTART="a=\$(settings get secure enabled_accessibility_services); am force-stop $PKG; am start -n $PKG/.MainActivity >/dev/null; case \"\$a\" in *$PKG*) sleep 1; settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put secure enabled_accessibility_services \"\$a\"; settings put secure accessibility_enabled 1 ;; esac"

# Some firmware (TCL G07, Android 11) locks the shell out of Android/data. Files are staged in
# /data/local/tmp and copied in, or written through the launcher's ThemeProvider when that is refused.
URI=content://$PKG.theme
TMP=/data/local/tmp/cyt-theme
# A file's staging path; wallpapers/a.jpg is staged as $TMP-wallpapers-a.jpg.
stage() { echo "$TMP-${1//\//-}"; }
put() { echo "cp $(stage "$1") $DIR/$1 2>/dev/null || content write --uri $URI/$1 < $(stage "$1"); rm -f $(stage "$1")"; }
del() { echo "rm -f $DIR/$1 2>/dev/null; content delete --uri $URI/$1 2>/dev/null"; }

if [ "${1:-}" = --reset ]; then
  tv "mkdir -p $DIR 2>/dev/null; $(del config.json); $(del wallpaper.jpg); printf '%s' '$REVISION' > $TMP-theme_revision; $(put theme_revision); $RESTART; echo reset" | tail -n 1
  exit 0
fi
THEME="${THEME_ARG:?usage: theme.sh <theme folder> | --reset}"
[ -f "$THEME/config.json" ] || { echo "no config.json in $THEME" >&2; exit 1; }
"$PY" -I -c 'import json,sys; json.load(open(sys.argv[1]))' "$THEME/config.json" 2>/dev/null \
  || { echo "$THEME/config.json is not valid JSON" >&2; exit 1; }

tv "mkdir -p $DIR" >/dev/null 2>&1 || true
tv --push "$THEME/config.json" "$TMP-config.json" >/dev/null
tv "$(put config.json)"
if [ -f "$THEME/wallpaper.jpg" ]; then
  "$PY" -I b64push.py "$THEME/wallpaper.jpg" "$TMP-wallpaper.jpg"
  tv "$(put wallpaper.jpg)"
else
  tv "$(del wallpaper.jpg)" >/dev/null
fi
tv "mkdir -p $DIR/wallpapers 2>/dev/null; rm -f $DIR/wallpapers/*.jpg $DIR/wallpapers/*.jpeg $DIR/wallpapers/*.png $DIR/wallpapers/*.webp $DIR/wallpapers/*.JPG $DIR/wallpapers/*.JPEG $DIR/wallpapers/*.PNG $DIR/wallpapers/*.WEBP 2>/dev/null; content delete --uri $URI/wallpapers 2>/dev/null" >/dev/null
if [ -d "$THEME/wallpapers" ]; then
  for IMAGE in "$THEME"/wallpapers/*; do
    [ -f "$IMAGE" ] || continue
    case "$IMAGE" in
      *.jpg|*.jpeg|*.png|*.webp|*.JPG|*.JPEG|*.PNG|*.WEBP)
        NAME=$(basename "$IMAGE")
        [[ "$NAME" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "unsupported wallpaper filename: $NAME" >&2; exit 1; }
        "$PY" -I b64push.py "$IMAGE" "$(stage "wallpapers/$NAME")"
        tv "$(put "wallpapers/$NAME")" >/dev/null ;;
    esac
  done
fi
tv "printf '%s' '$REVISION' > $TMP-theme_revision; $(put theme_revision)" >/dev/null
tv "$RESTART; echo applied" | tail -n 1
echo "Theme $(basename "$THEME") is on the TV. Undo with: tools/theme.sh --reset"
