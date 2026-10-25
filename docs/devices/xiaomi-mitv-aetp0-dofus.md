# Xiaomi MiTV-AETP0: package-name Home allowlist

Tested on MiTV-AETP0 (`dofus`, Amlogic, about 2 GB RAM), Android 9 / API 28,
firmware `22.9.2.851`. Stock Home is `com.mitv.tvhome/.MainActivityUserMode`.
This is a launcher-only record; no debloat list was applied.

## What failed and why

The standard `com.glasstv.tvhome` APK opened normally, but setting it as Home reported
`Success` while HOME still resolved to Xiaomi. Disabling Xiaomi Home selected
`com.android.tv.settings/.system.FallbackHome`; re-enabling Xiaomi restored normal use.
An accessibility redirect worked in the current session, but its enabled-service entry
was removed at reboot.

Read-only analysis of this device's framework found that `chooseBestActivity` retains
system HOME candidates or packages allowed by `MitvSettings.isThirdpartyHomeAllowed`
before considering the saved preference. The whitelist lookup compares the exact
package name and feature flags, with no signing-certificate check in that lookup.
The generic security-center XML returned by Xiaomi's cloud-control service listed
`com.ts.launcher` under both `third_party_home` and `boot_autostart`.
The protected on-device XML could not be read directly; the following installation
and Home checks, rather than the cloud response alone, establish compatibility here.

## Build and install the opt-in identity

Use this only after verifying the behavior and allowlist for the particular firmware.
Keep the existing stock launcher enabled. Check `pm path com.ts.launcher` first:
if that package is already installed, stop rather than overwrite another application.
The alternate ID belongs to another application; keep this compatibility build local,
and expect a signing conflict if that application's APK is installed later.

From the repository root, with the normal Java/SDK and Python prerequisites installed:

```bash
export TV_HOST=192.168.1.50
PY=tools/venv/bin/python
"$PY" -I tools/tv.py 'pm path com.ts.launcher'
"$PY" -I tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
(cd launcher && ./gradlew assembleRelease -PlauncherApplicationId=com.ts.launcher)
"$PY" -I tools/b64push.py launcher/app/build/outputs/apk/release/app-release.apk /data/local/tmp/tvhome-allowlist.apk
"$PY" -I tools/tv.py 'pm install -r /data/local/tmp/tvhome-allowlist.apk'
"$PY" -I tools/tv.py 'am start -W -n com.ts.launcher/com.glasstv.tvhome.MainActivity'
```

After inspecting the new screen, select and verify Home:

```bash
"$PY" -I tools/tv.py 'cmd package set-home-activity --user 0 com.ts.launcher/com.glasstv.tvhome.MainActivity'
"$PY" -I tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
```

The expected component is `com.ts.launcher/com.glasstv.tvhome.MainActivity`. The Java
namespace stays `com.glasstv.tvhome`, so `com.ts.launcher/.MainActivity` is incorrect.
The normal install/theme/restore scripts still target `com.glasstv.tvhome`; use these
manual commands for this build. Its optional theme overlay belongs in
`/sdcard/Android/data/com.ts.launcher/files/config.json`. The original installation and
its configuration remain separate. Without the Gradle property, builds retain the
standard application ID.

## Validation and recovery

On-device testing used the 2.0 launcher sources with the alternate ID: installation,
default-HOME resolution and Home from Settings passed, with Xiaomi Home still enabled
and no launcher accessibility service enabled. Jellyfin and Synology DS Video were
kept in the dock. The owner confirmed the replacement worked after reboot. ADB briefly
opened during reboot and then closed, so post-boot foreground inspection was not captured.
The current-source build is checked separately in the PR; it was not installed as part
of that reboot test. Other firmware versions, OTA persistence and extended daily use
remain unverified.

To restore the recorded stock Home on this firmware:

```bash
"$PY" -I tools/tv.py 'pm enable --user 0 com.mitv.tvhome'
"$PY" -I tools/tv.py 'cmd package set-home-activity --user 0 com.mitv.tvhome/.MainActivityUserMode'
"$PY" -I tools/tv.py 'input keyevent KEYCODE_HOME'
"$PY" -I tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
```

Re-enable ADB under Account & Security if it closes at reboot. Directly starting
`MainActivityUserMode` from shell requires Xiaomi's `HOME_STATE` permission; use the
Home key after restoring the preference. No root, property changes, system-app
installation or removal of stock Home is required by the successful procedure.

![Alternate-ID launcher on the tested TV](xiaomi-mitv-aetp0-home.png)
