# TCL 75c63b: launcher-only compatibility notes

Checked on a real TV in one session on 2026-10-20, using upstream
`f2a336a62965832daad607fa288e1d8f1de528d4` (launcher version 2.3).
This is not a tested debloat list: no general telemetry/preload removal or
several-day regression test was performed.

![Reference launcher on the tested TCL](tcl-75c63b-home.png)

## Identify the firmware, not just the generic model name

| Field | Observed value |
|---|---|
| Retail model | `75c63b`, supplied by the owner; not exposed by `ro.product.model` |
| `ro.product.manufacturer` | `TCL` |
| `ro.product.model` | `Smart TV Pro` |
| `ro.product.name` | `G03_4K_GB` |
| `ro.product.device` | `BeyondTV4` |
| Android / SDK | `11` / `30` |
| `ro.software.version_id` | `V8-R51MT05-LF1V652` |
| `ro.build.display.id` | `RP1A.200622.001 release-keys` |
| `ro.build.version.incremental` | `AR11` |
| Security patch | `2024-12-05` |
| `ro.hardware` / `ro.board.platform` | `rtd288o` / `rtd288o` |

Do not infer a precise retail model from `Smart TV Pro` or `BeyondTV4`.
The existing `tcl-65c725` example shares the generic model/device identifiers,
but its stock-launcher name does not match this unit. The `tcl-43c645`
example lists the same firmware version; these observations are not evidence
that every package choice in either example is suitable here.

## Debugging and connection

After the owner enabled debugging in Developer options, network ADB was
reachable on port 5555. It initially reported `unauthorized`; accepting the
RSA prompt on the TV changed it to `device` and enabled the shell. The exact
combination of debugging toggles was not independently recorded, so this is
not a claim that the USB debugging toggle alone opens the port on every build.

Tests used Ethernet, not Tailscale or a USB cable. The top network pill showed
`Ethernet`. Saved Wi-Fi entries reported `cname=android.uid.system:1000`;
they did not report setupwraith as their creator. Wi-Fi reconnection was not
tested: continued Ethernet/ADB connectivity does not prove Wi-Fi survived.
Check the actual saved-network creator before disabling setupwraith on another
unit, as described in the main README.

## Home handover: the successful command can be misleading

Before the handover, the HOME candidates were:

- `com.google.android.apps.tv.launcherx/.home.HomeActivity`: priority **2**.
- `com.google.android.tungsten.setupwraith/.RecoveryActivity`: priority **1**.
- `com.android.tv.settings/.system.FallbackHome`: priority **-1000**.

The reference APK installed and its `MainActivity` started with `Status: ok`.
However, `cmd package set-home-activity --user 0 com.glasstv.tvhome/.MainActivity`
reported `Success` while `resolve-activity` still returned launcherx at priority 2.
Always query HOME and test the real remote; a successful setter is not enough.

Trying to disable only setupwraith's `RecoveryActivity` was rejected with
`SecurityException: Shell cannot change component state ...` for both
`pm disable-user` (state 3) and `pm disable` (state 2). Neither attempt changed
the component. This unit required package-level changes for the tested handover.

After a backup, a successful explicit start of the new launcher, checking the
saved Wi-Fi creator, and the owner's approval, these were the **only two newly
disabled packages** (commands below run in the TV's ADB shell):

```sh
pm disable-user --user 0 com.google.android.tungsten.setupwraith
pm disable-user --user 0 com.google.android.apps.tv.launcherx
cmd package set-home-activity --user 0 com.glasstv.tvhome/.MainActivity
cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

HOME then resolved to `com.glasstv.tvhome/.MainActivity` at priority 0.
An injected Home key returned to it, and the owner confirmed that the physical
remote's Home and directional buttons worked. No accessibility helper or
runtime location grant was enabled for this installation.

Restoration was also exercised: both packages originally had enablement state
`default` (0), so `pm default-state --user 0 PACKAGE` restored their exact
package states; setting HOME to launcherx and explicitly starting it returned
`Status: ok`. The custom launcher was then made HOME again. Nothing was
uninstalled or cleared. Record your own original states rather than assuming
these defaults apply to another TV.

## Live TV config: distinguish the player from its input services

Use this theme overlay in the launcher's external files directory
(`/sdcard/Android/data/com.glasstv.tvhome/files/config.json`), or the equivalent
`config.xml` values:

```json
{
  "live_tv_package": "com.tcl.tv",
  "hidden_apps": ["com.tcl.tv"]
}
```

`com.tcl.tvinput` supplies the tuner and HDMI services, but has **no**
MAIN + LEANBACK_LAUNCHER activity on this firmware. Using it as
`live_tv_package` omitted the Live TV row. `com.tcl.tv` resolves to
`com.tcl.tv/.TVActivity`; switching to that package made the row appear.
Keep **both** packages enabled.

The hardware tuner input is
`com.tcl.tvinput/.TDTVInputService/HW1413742848`; HDMI inputs use
`com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW...`.
The launcher's normal channel-URI route opened `com.tcl.tv/.TVActivity` for
both the HDMI 1 row and the Live TV row. No custom `input_action` was needed.
External HDMI picture/audio, RF reception and channel scanning were not tested.
Do not infer physical connector count from every input in `dumpsys tv_input`.

## Validation scope and things deliberately left alone

- The unmodified release APK built, its signature verified, and all **96**
  launcher JVM tests passed (10 suites, no failures/errors/skips).
- Launcher dock clicks dispatched to Jellyfin, Yle Areena, Spotify and Moonlight
  activities. This tests app launch, not media playback or login.
- The on-screen Settings pill opened `com.android.tv.settings/.MainSettings`;
  the Ethernet pill opened `.connectivity.NetworkActivity` in the same package.
  These were view-only checks, not network changes.
- The Home handover, original-Home restoration and re-application were tested.
  Restarting the launcher process returned to the custom Home. The crash-buffer
  check after the UI tests contained no entry for `com.glasstv.tvhome`.
- All user-installed apps were retained. Firmware-update, Chromecast, voice,
  Bluetooth/remote, TV-input, network and other vendor services were not disabled.
  Their individual functionality was not all re-tested; no DNS changes were made.
- Cold boot/reboot, standby/wake, dedicated Input/gear/app/mic remote keys,
  casting, USB peripheral power and several-day stability remain **untested**.
  Only the physical Home/directional buttons were confirmed by the owner.

Network ADB is temporary maintenance access: turn USB/network debugging off
when finished and independently check that port 5555 is closed. Do not expose
it through the router. Closing debugging and a subsequent cold-boot test are
not included in the validation results above.
