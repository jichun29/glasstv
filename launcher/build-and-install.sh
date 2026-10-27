#!/usr/bin/env bash
# Install the GlassTV launcher APK on a TV and make it the default Home screen.
#
# Deliberately two phases, because the second one can leave a TV with no working Home:
#   1. push + install + start + set as Home        <- default, touches nothing else on the TV
#   2. optionally disable the stock launcher       <- only with --take-over / STOCK_LAUNCHER=<pkg>
#
# Usage:
#   TV_HOST=192.168.1.50 launcher/build-and-install.sh                  # build, install, set Home
#   TV_HOST=192.168.1.50 launcher/build-and-install.sh --apk path/to.apk  # install an existing APK
#   TV_HOST=192.168.1.50 launcher/build-and-install.sh --take-over=com.google.android.tvlauncher
#
# Most people do not need --take-over: installing the APK and setting it as Home is enough, and the
# stock launcher stays installed so the TV cannot end up with no Home at all.
set -euo pipefail
cd "$(dirname "$0")"

# Single place for the app identity. A rename changes this line and nothing else.
APP_ID="${APP_ID:-com.glasstv.tvhome}"           # applicationId; a firmware allowlist build overrides it
MAIN_CLASS="com.glasstv.tvhome.MainActivity"     # class name, independent of the applicationId
COMPONENT="$APP_ID/$MAIN_CLASS"
APK_PATH="${APK:-}"                              # an already built APK skips the build
STOCK_LAUNCHER="${STOCK_LAUNCHER:-}"             # set to a package name (or none) to also disable it

usage() {
  sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)          APK_PATH="$2"; shift 2 ;;
    --apk=*)        APK_PATH="${1#*=}"; shift ;;
    --take-over)    TAKE_OVER_REQUESTED=1; shift ;;
    --take-over=*)  STOCK_LAUNCHER="${1#*=}"; shift ;;
    --stock)        STOCK_LAUNCHER="$2"; shift 2 ;;
    --stock=*)      STOCK_LAUNCHER="${1#*=}"; shift ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"

# macOS: java_home or Homebrew; Linux: the java on PATH.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || { [ -d /opt/homebrew/opt/openjdk@17 ] && echo /opt/homebrew/opt/openjdk@17; } || java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')}"
# Android Studio's default SDK location on macOS, then on Linux, then Homebrew's command-line tools.
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -d "$d" ] && echo "$d" && break; done)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"

if [ -z "$APK_PATH" ]; then
  [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
  ./gradlew assembleRelease -q --console=plain
  APK_PATH=app/build/outputs/apk/release/app-release.apk
fi
[ -f "$APK_PATH" ] || { echo "ERROR: APK not found: $APK_PATH" >&2; exit 1; }

TOOLS="$(cd ../tools && pwd)"   # absolute, so the venv's Python doesn't warn about "../" in its prefix
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
if [ ! -x "$PY" ]; then
  echo "ERROR: $TOOLS/venv is missing. Set it up once with:" >&2
  echo "  python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2
  exit 1
fi
export MSYS_NO_PATHCONV=1

HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
query_home() {   # Home's owner, or an empty string when the TV cannot be reached
  "$PY" -I ../tools/tv.py "$HOME_QUERY" 2>/dev/null | tail -n 1 | tr -d '\r' || true
}
# Who owned Home before this script ran. Phase 2 must not re-read it after setting Home to us, or it
# would conclude "already ours, nothing to disable" and skip the take-over it was asked for.
HOME_BEFORE=$(query_home)

# Turn pm's one-line failure into the actual cause. Every one of these has been reported as
# "install failed" when the APK itself was fine.
explain_install_failure() {
  echo "ERROR: pm install failed; nothing on the TV was changed." >&2
  case "$1" in
    *INSTALL_FAILED_OLDER_SDK*)
      echo "  This APK needs a newer Android than the TV has. On Android 4.4-5.0 use" >&2
      echo "  GlassTV-Launcher-lite44.apk (minSdk 19) instead of the universal one." >&2 ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      echo "  A package with this name is already installed, signed with a different key (usually a" >&2
      echo "  build made elsewhere, or one re-signed by hand). Remove it first:" >&2
      echo "    adb shell pm uninstall $APP_ID" >&2
      echo "  then install the APK from this repository's Releases. Uninstalling drops the saved" >&2
      echo "  tile order and wallpaper choice." >&2 ;;
    *INSTALL_PARSE_FAILED*)
      echo "  The APK file is damaged or truncated: the download stopped early, or a mirror served an" >&2
      echo "  HTML page instead of the file. Download it again and compare its size with the Release." >&2 ;;
    *INSTALL_FAILED_VERIFICATION_FAILURE*)
      echo "  The firmware refused sideloading, not this APK. Allow installing unknown apps on the TV" >&2
      echo "  (and turn off install verification) before trying again." >&2 ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      echo "  Not enough free space on the TV. Free some up and retry." >&2 ;;
    *INSTALL_FAILED_INVALID_APK*)
      echo "  The system rejected the APK structure. Confirm the download is the unmodified Release file." >&2 ;;
  esac
}

"$PY" -I ../tools/b64push.py "$APK_PATH" /data/local/tmp/glasstv.apk
# Install on its own: in a batch a failed install would not stop the commands after it.
OUT=$("$PY" -I ../tools/tv.py 'pm install -r /data/local/tmp/glasstv.apk; rm -f /data/local/tmp/glasstv.apk' || true)
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) explain_install_failure "$OUT"; exit 1 ;;
esac
VERSION=$("$PY" -I ../tools/tv.py "dumpsys package $APP_ID | grep -m1 -o 'versionName=[^ ]*'" 2>/dev/null | tr -d '\r' || true)
[ -n "$VERSION" ] && echo "Installed $APP_ID ($VERSION)"

# Start the launcher before touching Home: a package that installs but cannot start would otherwise
# be made the default anyway, and the TV would come up to a blank screen (#239).
# "Status: ok" means the activity really came up; resolving HOME only names a component.
START_OUT=$("$PY" -I ../tools/tv.py "am start -W -n $COMPONENT" || true)
echo "$START_OUT"
if ! printf '%s' "$START_OUT" | grep -qF 'Status: ok' || printf '%s' "$START_OUT" | grep -qi '^Error'; then
  echo "ERROR: the launcher did not start (see above); Home was not changed and the TV's own launcher was left enabled." >&2
  echo "  If the message is 'Activity class ... does not exist', the installed package has another" >&2
  echo "  name than $APP_ID." >&2
  exit 1
fi

"$PY" -I ../tools/tv.py "cmd package set-home-activity --user 0 $COMPONENT" >/dev/null 2>&1 || true
HOME_NOW=$(query_home)
case "$HOME_NOW" in
  "$APP_ID"/*)
    echo "Home now resolves to $HOME_NOW" ;;
  *)
    # Not fatal: several China-market firmwares answer "Success" and keep their own Home until the
    # user picks one on screen (docs/devices/xiaomi-mitv-aetp0-dofus.md).
    echo "WARN: Home still resolves to '${HOME_NOW:-unknown}'." >&2
    echo "  Press Home on the remote, pick GlassTV and tick \"always\". The install itself worked." >&2 ;;
esac

if [ -z "$STOCK_LAUNCHER" ] && [ -z "${TAKE_OVER_REQUESTED:-}" ]; then
  echo "Installed. Nothing was disabled: the TV's own launcher is still installed and enabled."
  echo "Press Home on the remote to open GlassTV."
  exit 0
fi

# ---------------------------------------------------------------------------------------------
# Optional phase 2: disable the stock launcher. Only reached with --take-over / STOCK_LAUNCHER.
# ---------------------------------------------------------------------------------------------
# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing.
# Only a launcher we recognise is accepted: the answer can also be Android's chooser ("android"),
# the setup wizard, or another third-party launcher, none of which should be disabled by accident.
KNOWN_STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"
if [ -z "$STOCK_LAUNCHER" ] || [ "$STOCK_LAUNCHER" = auto ]; then
  STOCK_LAUNCHER=""
  HOME_PKG=$(printf '%s' "$HOME_BEFORE" | cut -d/ -f1)
  case " $KNOWN_STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      if [ "$HOME_PKG" = "$APP_ID" ]; then
        echo "Home already belongs to this launcher; nothing to disable."
        exit 0
      elif [ "$HOME_PKG" = android ]; then
        # Android's chooser: two launchers tie and no default is set. Take the one known stock
        # launcher among the HOME claimants, if there is exactly one.
        CLAIMANTS=$("$PY" -I ../tools/tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
          | tr -d '\r' | grep -o '[A-Za-z0-9_.]*/' | tr -d / | sort -u)
        FOUND=""; N=0
        for k in $KNOWN_STOCK_LAUNCHERS; do
          if printf '%s\n' "$CLAIMANTS" | grep -qx "$k"; then FOUND=$k; N=$((N + 1)); fi
        done
        if [ "$N" -eq 1 ]; then
          STOCK_LAUNCHER=$FOUND; echo "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
        else
          echo "ERROR: Home is Android's launcher chooser and $N known stock launchers claim Home." >&2
          echo "  Pick a default on the TV first, or pass --stock=<package to disable> / --stock=none." >&2
          exit 1
        fi
      else
        echo "ERROR: Home currently resolves to '${HOME_PKG:-nothing}', which is not a launcher this script knows." >&2
        echo "  android                                  = Android's launcher chooser (two launchers tie)" >&2
        echo "  com.google.android.tungsten.setupwraith  = Google TV's setup wizard; see README, Troubleshooting" >&2
        echo "  anything else                            = another launcher is the default" >&2
        echo "  Pass --stock=<package to disable> or --stock=none." >&2
        exit 1
      fi ;;
  esac
fi
echo "Stock launcher: $STOCK_LAUNCHER"

# Put the stock launcher back and check that it took. A TV whose new Home does not start can only be
# rescued over ADB, so the commands go into a local file as well: when it is the connection that
# broke, the terminal that ran this script may never come back.
rollback_home() {
  if [ "$STOCK_LAUNCHER" = none ]; then
    echo "ERROR: Home is not the new launcher, and no stock launcher was disabled; nothing to roll back." >&2
    return 1
  fi
  "$PY" -I ../tools/tv.py "pm enable --user 0 $STOCK_LAUNCHER" >/dev/null 2>&1 || true
  # set-home-activity needs pkg/activity on Android 9, so resolve the component on the TV first.
  "$PY" -I ../tools/tv.py "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK_LAUNCHER | tail -n 1)" >/dev/null 2>&1 || true
  BACK=$(query_home)
  case "$BACK" in
    "$STOCK_LAUNCHER"/*) echo "$STOCK_LAUNCHER owns Home again." >&2; return 0 ;;
  esac
  REC="${HOME:-$PWD}/glasstv-handover-$(printf '%s' "$TV_HOST" | tr -c 'A-Za-z0-9._-' '_').txt"
  {
    echo "# GlassTV: the launcher handover on $TV_HOST did not finish. From the repo root:"
    echo "TV_HOST=$TV_HOST tools/tv.py 'pm enable --user 0 $STOCK_LAUNCHER'"
    echo "TV_HOST=$TV_HOST tools/tv.py 'cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK_LAUNCHER | tail -n 1)'"
    echo "# then check: TV_HOST=$TV_HOST tools/tv.py '$HOME_QUERY'"
  } > "$REC" 2>/dev/null || REC=""
  echo "ERROR: recovery is incomplete; Home still resolves to '${BACK:-nothing}'." >&2
  if [ -n "$REC" ]; then echo "The commands that finish it are in $REC; the TV still needs ADB for them." >&2
  else echo "Re-run once the TV answers: pm enable --user 0 $STOCK_LAUNCHER, then set its Home activity." >&2; fi
  return 1
}

if [ "$STOCK_LAUNCHER" != none ]; then
  "$PY" -I ../tools/tv.py "pm disable-user --user 0 $STOCK_LAUNCHER" >/dev/null 2>&1 || true
  # Android drops the saved Home preference whenever the set of HOME apps changes, and on Google TV
  # 14 the next candidate is the setup wizard's RecoveryActivity, which crash-loops system_server
  # (see examples/xiaomi-tv-a-pro-2026). So set it again after disabling, then check where it landed.
  "$PY" -I ../tools/tv.py "cmd package set-home-activity --user 0 $COMPONENT" >/dev/null 2>&1 || true
fi

HOME_NOW=$(query_home)
case "$HOME_NOW" in
  "$APP_ID"/*) echo "Home now resolves to $HOME_NOW" ;;
  *)
    if [ -z "$HOME_NOW" ]; then
      echo "ERROR: the TV stopped answering while Home was being switched, so the outcome is unknown." >&2
    else
      echo "ERROR: Home resolves to $HOME_NOW, not to the new launcher." >&2
    fi
    rollback_home || true
    if [ -n "$HOME_NOW" ]; then
      echo "On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to" >&2
      echo "your debloat list (see README, Troubleshooting), apply the list, then run this script again." >&2
    fi
    exit 1 ;;
esac

# Optional accessibility services. Off unless asked for, and Android switches them off again on
# every reinstall, so pass the flag each time you run this script.
#   INPUT_KEY_SERVICE=1   the remote's Input button (KEYCODE_TV or TV_INPUT) opens the input switcher from any
#                         app; Google TV needs this once the stock launcher is gone (InputKeyService).
#                         It also handles input_key_keycodes and app_keys (config.xml or a theme; a YouTube button)
#   HOME_KEY_SERVICE=1    remotes whose Home button sends KEYCODE_ALL_APPS instead of HOME, e.g. Xiaomi
#                         sticks: the key is routed to the launcher (HomeKeyService)
enable_service() {   # appends to enabled_accessibility_services without dropping other services
  svc="$APP_ID/com.glasstv.tvhome.$1"
  "$PY" -I ../tools/tv.py "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo $1 enabled"
}
# The Settings menu's Sleep row; installing the update switched it off, so turn it back on every time.
enable_service SleepService
if [ "${INPUT_KEY_SERVICE:-0}" = 1 ]; then enable_service InputKeyService; fi
if [ "${HOME_KEY_SERVICE:-0}" = 1 ]; then enable_service HomeKeyService; fi
echo "Installed. Press Home on the remote to see it."
