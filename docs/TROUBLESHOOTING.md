# Troubleshooting

Things that bit us or contributors, so they don't bite you. Commands use `tools/tv.py`; see the
[README](../README.md#doing-it-by-hand) for setting it up.

## Connecting

- **macOS: `adb connect` says "No route to host"** although the port is open. Recent macOS treats
  the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs, Xiaomi among them. USB debugging alone opens
  network ADB on port 5555.
- **USB debugging is greyed out and there is no Wireless debugging entry either**, seen on a carrier
  box ([Airtel Xstream, Android 11](https://github.com/Gathin23/cleanyourtv/issues/227)). Some ISP
  firmware removes both switches, and then no shell can be reached: `tools/tv.py` fails,
  `adb connect <ip>:5555` times out, and so does a port check. That timeout is the firmware, not
  Wi-Fi, the router or the cable, so don't go rebuilding the network for it. Greyed-out rather than
  missing points at a device-owner policy or a provisioning lock; the toggle comes back after a
  firmware update from the ISP, or not at all. Without one of the two switches this repo has nothing
  to talk to.
- **Developer options is hidden, or Build number asks for a password**, seen on an ISP box (FPT Play
  650, Android TV 14): tapping Build seven times opens a password prompt instead of unlocking the menu.
  Often only the way in is locked, not the screen itself: TvSettings still has
  `com.android.tv.settings/.system.development.DevelopmentActivity`, and any app can open it by its
  name or with the `android.settings.APPLICATION_DEVELOPMENT_SETTINGS` action. Install an app that
  opens activities by name (Activity Launcher) from the Play Store, open that screen and switch on
  USB debugging there. When no app can open it either, see the carrier-box bullet above.
- **USB debugging never opens port 5555** on some Google TV sets (a Skyworth-built Philips among
  them). Pair once through Wireless debugging with the stock `adb` (`adb pair <ip>:<port> <code>`,
  then `adb connect`), run `adb tcpip 5555`, and `tools/tv.py` works from then on. `dns-sd -B
  _adb-tls-pairing._tcp` (or `adb mdns services`) shows the pairing port while the code is on screen.
  Android switches Wireless debugging off whenever Wi-Fi drops; turn it back on and `adb` reconnects
  without a new code. Port 5555 from `adb tcpip` usually lasts only until the next reboot.
- **USB devices go dead while USB debugging is on**, seen on the Xiaomi Mi Box S: its firmware
  switches the USB port to ADB device mode, which cuts the power to a USB speaker or drive. Switch
  USB debugging off when you're done; the launcher doesn't need it.
- **Close port 5555 when you're done**: plaintext, and a shell for anyone on the LAN when `ro.adb.secure=0`. Steps in the README FAQ.
- **The TV sits on a separate VLAN or IoT network** and `tools/tv.py` times out although debugging
  is on: the router keeps the computer from reaching it. Add a temporary firewall rule from your
  computer to the TV on tcp/5555 only, and remove it again when you close ADB. Opening the whole
  segment, or moving the TV onto the main network for good, undoes the reason it was separate.
- **Windows PowerShell 5.1 adds a byte-order mark** to text piped into a program, which turned the
  first batch command into gibberish. `tv.py` now strips it, and the `.ps1` scripts pipe clean UTF-8.
- **"Allow USB debugging?" comes back** although `adb devices` already works: `tv.py` used its own
  key next to the one platform-tools had registered. It now reuses `~/.android/adbkey` when that
  exists. Every attempt with an unaccepted key queues one more prompt on the TV, so dismiss them all
  (Back) before testing with key presses.
- **Git Bash rewrites paths** like `/data/local/tmp` into Windows paths. The scripts set
  `MSYS_NO_PATHCONV=1` for you. Set `JAVA_HOME` and `ANDROID_HOME` before building there.

## Working on the TV

- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does exactly that.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **The Input key and CEC die after the hidden factory menu is opened**, seen on a Haier/CVTE set
  (Durabrand `DURAT32ISDB`, `dupont`, Android TV 11 build `RTM6.230109.232`,
  [#470](https://github.com/Gathin23/cleanyourtv/issues/470)). `com.cvte.tv.hkcfac` is the
  manufacturer's production app: `dumpsys package` shows it as a factory component, and it has a
  launchable activity, so "let me see what this package does" is enough to trigger it. Opening it
  sets `customer_factory_mode=true` in `/vendor/tvcertificate/fdata.ini` and
  `vendor.mtk.factory.disable.input=1`. The Input button then does nothing and HDMI-CEC is off, and
  at the next boot `com.at.factory.factorymanager` disables Play services, Netflix,
  `com.google.android.katniss` and `com.google.android.apps.mediashell` - `pm enable` from the shell
  is refused for those, so casting and the store stay gone until the mode is cleared. Read the
  package instead of starting it. If it has already been opened, start it, send the factory-exit
  keycode twice **with it in front**, reboot, then turn CEC back on:
  ```sh
  tools/tv.py 'am start -n com.cvte.tv.hkcfac/.MainActivity'
  tools/tv.py 'input keyevent 218; input keyevent 218'
  tools/tv.py reboot
  tools/tv.py 'settings put global hdmi_control_enabled 1'
  ```
  Press the real Input button afterwards and check the packages are back:
  `tools/tv.py 'pm list packages -e | grep -E "gms|netflix|mediashell|katniss"'`. More on the unit in
  the [device notes](devices/durabrand-durat32isdb.md).
- **`adb exec-out screencap -p` gives a broken PNG** on some MediaTek sets (Xiaomi, and Amazon's
  own Fire TV smart TVs): the binary prints `Init wrapper sys mutex successful` on stdout first.
  Save on the TV and pull the file instead: `screencap -p /data/local/tmp/s.png`, or cut
  everything before the `\x89PNG` header.
- **Play Protect blocked the launcher** ("unsafe app blocked, built for an older version of Android")
  and `pm install` hung, then failed with `INSTALL_FAILED_VERIFICATION_FAILURE`, while it targeted
  SDK 29. It targets 34 now; if you built an old checkout, rebuild.
- **Screenshots come out empty** while Live TV, an HDMI input or Netflix is on screen, seen on TCL
  and a [Sony KDL-49W800G](https://github.com/Gathin23/cleanyourtv/issues/92). Press Home before
  `screencap`. An empty capture means nothing about the ADB connection or playback; check those
  separately.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app. Launch the replacement by hand first. `set-home-activity` can report
  `Success` while HOME still resolves to the stock launcher (priority 2) until that one is disabled.
  And check which packages actually handle HOME before disabling anything by name: on the Sony
  above, `com.google.android.leanbacklauncher` has a boot receiver but no HOME activity.
  `tools/disable.sh` now does that check itself: when the list holds the app that owns Home and
  nothing else on the TV answers HOME, it skips that entry and says so (`FORCE_HOME=1` overrides),
  so applying a list before the handover no longer leaves the TV without a launcher. Apply the list
  again after the install script has made the new launcher Home to disable the old one.
- **China-market Xiaomi rejects a default Home despite `set-home-activity` reporting success**,
  verified on MiTV-AETP0 (dofus), Android 9, firmware 22.9.2.851: the framework filters third-party
  HOME candidates by a package-name allowlist. Disabling the stock launcher then selects
  `FallbackHome`. An opt-in application-ID build worked with the stock launcher still enabled;
  see the [device notes and rollback steps](devices/xiaomi-mitv-aetp0-dofus.md). This is different
  from a missing default-home picker or a remote that emits ALL_APPS.
- **The new launcher still doesn't own Home** after the stock one is disabled, seen on Google TV
  (Xiaomi MiTV-MOOR2). Another system app answers HOME: `com.google.android.tungsten.setupwraith`,
  with `.MainActivity` at intent-filter priority 4 and `.RecoveryActivity` at priority 1. You
  cannot outrank it, and the obvious workarounds don't help: Android ignores `android:priority` on
  HOME filters for apps that aren't on the system image, `cmd package set-home-activity` prints
  `Success` without changing anything (not even after a reboot), and `pm disable-user` on one of
  setupwraith's components is refused with a SecurityException. Disabling the whole
  `com.google.android.tungsten.setupwraith` package is what frees the slot. It is the first-run
  setup wizard, so everyday use is unaffected, but re-enable it before a factory reset - list it in
  your `debloat.txt` and `tools/restore.sh` does that for you. Measured again on a Google TV
  Streamer (`kirkwood`, `UTTK.260317.003`): a launcher built with `android:priority="3"` on its HOME
  filter (`aapt2 dump xmltree` finds it in the installed APK) still has no `mPriority` line in
  `dumpsys package`, and `cmd role get-role-holders android.app.role.HOME` names the new launcher
  while the HOME key lands on `setupwraith/.RecoveryActivity` anyway. `cmd package resolve-activity`
  and `query-activities` answer with the resolver table's winner, which is not necessarily the
  activity that takes focus: `dumpsys activity activities | grep mFocusedApp` is the ground truth,
  and it is what to check before saying the takeover worked.
- **The TV drops off Wi-Fi for good the moment setupwraith is disabled**, seen on Google TV 14 (Sony
  BRAVIA 9 II, Philips R8G, Skyworth SWTV-24NA-4K NF, Xiaomi TV Box S 3rd gen MiTV-AFMU0) and on a TCL
  G07 on Google TV 11 (build AR17; on AR20 the network belonged to `android.uid.system`). A Wi-Fi
  network saved during the first-run wizard belongs to setupwraith, and Android deletes an app's saved
  networks when the app is disabled. The TV is fine but offline, so ADB and the phone remote stop
  working, and a restart doesn't bring the network back. Check before disabling it: `tools/tv.py "dumpsys wifi" | grep -o 'cname=[^ ]*'`.
  If it names setupwraith, forget the network and join it again in Settings > Network & Internet (it
  then belongs to `android.uid.system`), or use Ethernet. `tools/disable.sh` skips setupwraith while it
  owns the network (`FORCE_SETUPWRAITH=1` overrides). Already offline? Rejoin from the TV with its own
  remote or a USB keyboard, then turn Wireless debugging back on if it was in use.
- **Casting from a phone fails with "Untrusted device" after setupwraith is disabled**, seen on
  Chromecast with Google TV HD (`boreal`) on Google TV 14 build UTTC.250917.004, the build the
  `examples/google-chromecast-gtv-4k/` list was made on. YouTube on the phone says "Untrusted device.
  ... couldn't be verified", and logcat repeats `cast_ClientAuthCredsProvisioning: Loading factory
  credentials for 1P device`, `Failed to find provider info for ...CastInfoProvider` and
  `cast_shell: No private key, hence empty signature`. The Cast receiver
  (`com.google.android.apps.mediashell`) loads its factory device credentials from a content provider
  inside setupwraith (`...tv.setupwraith.libs.castinfo.CastInfoProvider`), so disabling the wizard
  takes the credentials with it. Re-enable it and within a minute the log shows
  `SetupWraith|CastInfoProvider: querying cast infos` and `Created singleton instance of
  ClientAuthSigner`, and casting works. You cannot keep the provider and give up only Home:
  `pm disable-user` on `setupwraith/.RecoveryActivity` is refused with `SecurityException: Shell
  cannot change component state`. So either debloat without setupwraith and launcherx, or re-enable
  setupwraith after each boot, wait for `ClientAuthSigner`, then disable it again: the receiver keeps
  the credentials in memory, so casting survives until the next reboot - and only in that process.
  `am force-stop com.google.android.apps.mediashell` followed by the receiver coming back prints
  `Retry loading factory credentials` and `No private key, hence empty signature` again, so anything
  that restarts it (a crash, a force-stop, an update) needs the same sequence. Reported on Google's
  own Chromecast hardware (`boreal`) and, with the same log lines, on a Google TV Streamer
  (`kirkwood`, `UTTK.260317.003`), where the `CastInfoProvider` also lives inside the setupwraith
  APK; the Xiaomi Mi Box S 2nd gen list says casting from a Pixel still works there with
  setupwraith off.
- **Casting disappears although the Cast receiver is still running**, seen on a TCL 75QM751G (QM7,
  Google TV 14) after its list, and the same "alive is not discoverable" conclusion on a TCL
  BeyondTV2 (Android TV 9, R851T02 V660) in the same thread. Phones and a PC stop seeing the TV,
  `com.google.android.apps.mediashell` is up and listening on 8008/8009, and there is no
  `No private key` or `CastInfoProvider` line — so this is not the setupwraith case above, and
  re-enabling setupwraith does not help. Check discovery instead of the process:
  `tools/tv.py "logcat -d -s cast_shell | grep -E 'Done announcing|responses sent'"` and
  `tools/tv.py "dumpsys activity service com.google.android.apps.mediashell | grep WargService"`.
  A receiver that is advertising logs `MdnsAppFilter: Done announcing.`,
  `responses sent in 60 seconds: N` and `Gcm.Register.Success=2`, with `WargService` up. If those are
  missing while the process lives, the receiver is hung: the QM7 report saw its connections sitting
  in `CLOSE_WAIT` on 8009 with unread data. `tools/tv.py "am force-stop
  com.google.android.apps.mediashell"` restarts it (the TV starts it again on demand), and
  advertising returned within seconds, without a reboot. Device notes:
  [TCL 75QM751G](devices/tcl-75qm751g-qm7.md).
- **The TV drops off Wi-Fi every minute right after the install**, seen on Google TV 14 (Xiaomi
  MiTV-MZTU0): the same setupwraith `.RecoveryActivity` took Home and system_server crash-looped
  on it. The install script now starts the new launcher before disabling the stock one, sets the
  home preference last (Android drops it whenever the set of HOME apps changes), then checks where
  Home resolves and re-enables the stock launcher if it is not the new one. Disable setupwraith as
  above. To confirm it is this bug: `logcat -b crash` shows `FATAL EXCEPTION IN SYSTEM PROCESS:
  android.display`, and the main log shows `setupwraith/.RecoveryActivity` being started every
  200 ms or so. On a Google TV Streamer (`kirkwood`, `UTTK.260317.003`) the same loop came with its
  own signature - `IllegalArgumentException: positionChildAt: container=Task=... is not a child of
  container=Task=1 current parent=null` at `WindowContainer.positionChildAt` /
  `Task.moveToBack` / `TransitionController.tryStartCollectFromQueue` - and repeated roughly every
  47 s, each cycle taking setupwraith and SystemUI down with `DeadSystemException` too. If you're
  already in the loop:
  `tools/tv.py 'pm enable --user 0 com.google.android.apps.tv.launcherx'`.
- **`theme.sh` fails with "couldn't create file: Permission denied"**, seen on TCL G07 (Android 11): the
  shell is not in the group that may write `Android/data`. `theme.sh` stages the files in
  `/data/local/tmp` and, when copying them in is refused, writes them through the launcher's
  `ThemeProvider` instead. A launcher installed before that provider existed doesn't have it: run
  `launcher/build-and-install.sh` once, then push the theme again.
- **No on-screen keyboard** after switching launchers, seen on Xiaomi: the default input method
  pointed at a Baidu keyboard that isn't installed. Fix:
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`
- **Third-party launchers** often charge for basics like a custom wallpaper. That's why this repo
  has its own.
- **Wrong time zone, clock off by 30 or 60 minutes**, seen on Xiaomi in Iran: the TV picked a
  neighbouring zone, and older firmware ships time zone data from before Iran dropped daylight
  saving in 2022 (on Android 10 and newer, check with
  `tools/tv.py 'cat /apex/com.android.tzdata/etc/tz/tz_version'`). Set the zone yourself:
  `tools/tv.py 'settings put global auto_time_zone 0; cmd alarm set-timezone Asia/Tehran'`.
  If the clock then jumps an hour ahead in spring, a fixed offset keeps it right:
  `tools/tv.py 'cmd alarm set-timezone GMT+03:30'`. The same applies to any country whose rules
  changed after the firmware was built.
- **The network pill says "Wi-Fi" instead of the network name** after `pm clear com.glasstv.tvhome`
  or a reinstall from scratch: clearing the launcher's data also drops the location permission
  Android 10 needs to read the SSID. `pm grant com.glasstv.tvhome android.permission.ACCESS_FINE_LOCATION`
  (the install script does this).
- **Home is "Select a Home app"** after `tools/restore.sh` followed by re-applying a list: disabling a
  package that also claims HOME (PatchWall on Xiaomi) makes Android drop the default. The install
  script now picks the known stock launcher among the candidates by itself; otherwise choose one on
  the TV or pass `STOCK_LAUNCHER=<package>`.

## Accessibility helper enabled but the remote button does nothing

The optional `HomeKeyService` and `InputKeyService` only filter remote keys once Android has bound to
them. The installer's `enabled` message means the setting was written, not that the service is bound.
Read the state without changing anything:

```sh
tools/tv.py 'am get-current-user'
tools/tv.py 'settings --user current get secure enabled_accessibility_services'
tools/tv.py 'dumpsys accessibility'
```

| Helper | Component in the enabled-services list | Key it filters |
| --- | --- | --- |
| Home button | `com.glasstv.tvhome/.HomeKeyService` | `KEYCODE_ALL_APPS` (not an ordinary `KEYCODE_HOME`) |
| Input button | `com.glasstv.tvhome/.InputKeyService` | `KEYCODE_TV`, `KEYCODE_TV_INPUT`, plus the keycodes in `input_key_keycodes` and `app_keys` (`config.xml` or a theme) |

- **Enabled** means the component is in that user's colon-separated list (the class may be written in
  full, `com.glasstv.tvhome/com.glasstv.tvhome.HomeKeyService`). Match the whole component.
- **Bound** means it appears under that user's `Bound services` section of the dump (`services` on older
  builds). Give it a few seconds after enabling and read again. Labels in the dump can be translated or
  shared with other services; a line containing "Home" is not proof.
- **Working** means the physical button does the right thing from inside another app. An injected key
  over ADB proves navigation, not that the remote sends the code this helper filters. It does not even
  reach the helper: `input keyevent` skips accessibility key filtering, so an injected `KEYCODE_TV` does
  nothing even when the real Input button works.
- **Which keycode** a dead button sends: `dumpsys window policy` lists the "global keys" Android routes
  to one app (on Google TV several go to launcherx, which is disabled), and Xiaomi firmware logs every
  press as `handleAction<keycode>` in `logcat`. Put it in `input_key_keycodes` (opens Inputs) or
  `app_keys` (`keycode:package`), reinstall with `INPUT_KEY_SERVICE=1` and press the real button.
  A press that `logcat` reports as `KEY_UNKNOWN` or `KEY_PROGRAM` cannot be matched by keycode at all,
  because every unmapped button shares those codes: use the Linux scancode from
  `tools/tv.py 'getevent -lt'` in `settings_key_scancodes` and install with `HOME_KEY_SERVICE=1`
  (an RC-36 remote sends its gear on scancode 240 and its notifications button on 362 that way; see
  [the FPD CG50-C3 note](devices/fpd-cg50-c3.md)).

A force-stop switches the helpers off for good: after `am force-stop com.glasstv.tvhome` (or Force stop in
Settings > Apps) Android removes the launcher's services from the enabled list, and they do not come back
when the launcher starts again (seen on a TCL 55C715, Android TV 9). Enable them again the same way you did
the first time, and avoid force-stopping the launcher while testing.

Enabled but never bound points at a firmware restriction on auto-starting sideloaded services (some TCL
builds log `OP_AUTO_START` denials). Look at `logcat` for the binding failure before changing settings,
and do not rewrite the enabled-services list or grant app-ops as a blanket fix. On a TCL 55S470G (Google
TV 12) the line is `[TclAppBoot] ... calleeComponent = 'com.glasstv.tvhome/.HomeKeyService', bootType =
'bind_service', isAllow = 'false', reason = 'callee_does't_have_OP_AUTO_START_permission.'`, and
`appops get com.glasstv.tvhome` shows `APP_AUTO_START: ignore`. Allowing that one op for the launcher only
(`appops set com.glasstv.tvhome APP_AUTO_START allow`) made the service bind on its own after a restart;
toggling the service off and on in Accessibility works until the next restart. When reporting, give the
model, build, current user and the relevant redacted lines, not the raw dump.
