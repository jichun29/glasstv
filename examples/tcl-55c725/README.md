# TCL 55C725

TCL's 55" QLED from the C725 series (device `BeyondTV4`, product `G03_4K_GB`, model string "Smart TV Pro",
Realtek `rtd288o`, software family R51MT05), 2 GB RAM, 10 GB `/data`. Android TV 11 (SDK 30) running the
Google TV launcher (`launcherx`), build `RP1A.200622.001`, software `V8-R51MT05-LF1V620`. Italian unit, on
Ethernet with Wi-Fi off. Two remotes: the Bluetooth **TCL RC902V** (reports as `TCL_RC902V_U1`) and the infrared
**TCL RC802NU YUI1**.

Debloated and running this repo's launcher as Home, set up and checked on a real unit on 2026-10-20/21,
including a reboot. Nothing is uninstalled; `debloat.txt` lists each disabled package and why. Closest lists:
[`tcl-43c645/`](../tcl-43c645/) (same R51MT05 software, launcherx) and [`tcl-65c725/`](../tcl-65c725/) (same
series and BeyondTV4 device, but with the older `tvlauncher`).

All commands run from the repo root with the TV's IP in `TV_HOST`, and need ADB on: Settings > System >
About > press Android TV OS build 7 times, then Developer options > USB debugging, and accept the prompt. Port
5555 was open with no wireless-debugging pairing; `ro.adb.secure=1`.

## Install

```bash
TV_HOST=<tv-ip> tools/disable.sh examples/tcl-55c725/debloat.txt
TV_HOST=<tv-ip> HOME_KEY_SERVICE=1 launcher/build-and-install.sh
TV_HOST=<tv-ip> tools/theme.sh <your theme folder>      # with the config.json below
```

Set the screensaver before dreamx goes:
`tools/tv.py 'settings put secure screensaver_components com.android.dreams.basic/.Colors'`
(or `com.glasstv.tvhome/.ClockDream`). Then check that the remote-button service is **bound**, not just enabled
(see Quirks): `tools/tv.py 'dumpsys accessibility' | grep 'Bound services'`.

## Undo

```bash
TV_HOST=<tv-ip> tools/restore.sh examples/tcl-55c725/debloat.txt com.google.android.apps.tv.launcherx
```

## What changed

**Disabled in this pass** (12): TCL home-screen content (`tclhome_passive`) and the "TCL Channel" browser
(`waterfall.overseas`), Google `partnersetup` and the `gtvpai` preinstall auto-installer, `onetimeinitializer`,
calendar sync, the AOSP WebView test browser, TCL's own WebView (TV Settings and TCL apps still worked, with no
crash in the log), Play Movies, Play Games, TCL T-Cast, and ambient mode. Then the launcher install disabled
`launcherx`.

**Found already disabled** and kept in the list (16), all from the `tcl-43c645` or `tcl-g10` lists: TCL analytics
(`com.tcl.bi`), FAST channels, app show, partner customizer, store sticker, message box, guard, key help, user
agreement, e-manual, USB media centre, the dashboard, Rakuten TV, Google feedback, TalkBack and the setup
wizard. Also found disabled and left out of the list because they were not checked one by one: `factory.view`
(which the 43C645 list says to keep), gallery, browser, camera, messaging, user centre, Alexa, logkit, YouTube
Music, print spooler, dynamic system updates.

**Kept on purpose:** Netflix, YouTube, Prime Video and every user-installed app; voice search (Google
`katniss` and `com.tcl.assistant`); Chromecast, Miracast; the TCL firmware updater (`versionUpdateApp`); the
Google TV phone remote; Game Bar; T-solo (TV audio to Bluetooth headphones); Live TV and HDMI.

Verified after each batch: Home, Live TV, Ethernet. After the reboot, Home, the 13 disabled packages (including
launcherx), the bound button service and Ethernet were all still in place.

## Remote buttons: remapped

With `launcherx` disabled, the app buttons, TV, Settings and the number-pad button did nothing. TCL sends its
own keycodes, and only the stock launcher answered them. `HomeKeyService` (`HOME_KEY_SERVICE=1`) brings them
back from `app_keys` and `settings_key_keycodes`, matched by **keycode**, so one entry covers both remotes.

The keycodes were read with a temporary log line in the launcher's accessibility service; the names come from
the TV's key layouts, `/system/usr/keylayout/Generic.kl` (Bluetooth) and `mtkinp.kl` (IR), both readable here.
Every button was confirmed with the real remotes.

| Button | RC902V (BT) scancode | RC802NU YUI1 (IR) scancode | TCL name | Keycode | Mapped to |
|---|---|---|---|---|---|
| Netflix | 242 | 758 | `TCL_NETFLIX` | **4062** | `com.netflix.ninja` |
| YouTube | 245 | — | `TCL_YOUTUBE` | **4065** | `com.google.android.youtube.tv` |
| Prime Video | 752 | 752 | `TCL_PRIMEVIDEO` | **4099** | `com.amazon.amazonvideo.livingroom` |
| TV / guide | 365 | — | `TCL_EPG` | **4001** | `com.tcl.tv` (Live TV) |
| TV (IR) | — | 456 | `TCL_QUICK_MENU` | **4020** | `com.tcl.tv` (Live TV) |
| Settings (gear) | 233 | 561 | `TCL_SET` | **4077** | TV settings |
| Number pad | 235 | — | `TCL_NUMBER` | **4056** | `com.tcl.suspension` (TCL Quick panel), by the owner's choice |
| Input | — | 471 | `TV_INPUT` | 178 | works without help (`com.tcl.suspension`) |

The RC902V reports the same Bluetooth ids (vendor `0x0417`, product `0x0304`) as the RC833 of the
TCL 43P7K (G10 firmware), and Netflix, YouTube, Prime Video and TV send the same keycodes. The gear differs:
4077 here, 83 on that firmware. Do **not** also enable `InputKeyService`, because it reads `app_keys` too and every
app button would open its app twice.

The theme used on this unit (`config.json`, with `themes/aurora/wallpaper.jpg` as `wallpaper.jpg`):

```json
{
  "dock_apps": ["com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom"],
  "hidden_apps": [],
  "live_tv_package": "com.tcl.tv",
  "columns": 4,
  "background_color": "#070A14",
  "wallpaper": true,
  "settings_key_keycodes": [4077],
  "app_keys": ["4062:com.netflix.ninja", "4065:com.google.android.youtube.tv", "4099:com.amazon.amazonvideo.livingroom",
               "4001:com.tcl.tv", "4020:com.tcl.tv", "4056:com.tcl.suspension"]
}
```

## Inputs

Five TV inputs are published: the tuner `com.tcl.tvinput/.TDTVInputService/HW1413742848` and four HDMI
passthroughs, `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413743104`, `…HW1413744128`, `…HW1413744384`
and `…HW1413744640`, which `com.tcl.tv` opens. The launcher's Inputs menu shows the labels set in the TV's settings
(here "XBOX · HDMI 2", "PS3 · HDMI 3") and the connection state, and it leaves out the inputs hidden there
(`tv_input_hidden_inputs`). That is why only two HDMI rows appear on this unit.

## Resources

After the changes, launcher with the aurora wallpaper, 4 per row:

| | Used | Free |
|---|---|---|
| RAM (2 GB) | ~1.55 GB | ~490 MB available |
| Swap (1 GB) | ~130 MB | ~900 MB |
| `/data` (10 GB) | 2.5 GB | 7.4 GB |
| Launcher (`com.glasstv.tvhome`) | ~78 MB PSS | |

Before the changes, 370 MB was available, but that was read at a different moment, so it is not a clean
before/after figure. CPU was not measured at idle: the reading taken 2 minutes after boot (~16%) only shows apps
starting.

## Firmware quirks

- **The button service can stay enabled but unbound** after the launcher is reinstalled and the theme pushed:
  `enabled_accessibility_services` lists it, and `dumpsys accessibility` shows `Bound services:{}`. Android binds
  it again only when the list changes, so set it to `""` and then back to `com.glasstv.tvhome/.HomeKeyService`.
- **Sideloading works.** Every user-installed app here is a split APK. Reinstalling one with an install session
  that writes all its parts (`pm install-create -r` / `install-write` / `install-commit`) succeeded, and so did
  the launcher. The launcher's base64 transfer passed its md5 check here, unlike on the 43C645.
- **Screenshots** come back empty while Live TV or HDMI is on screen; check `dumpsys activity activities | grep
  mResumedActivity`.
- `dumpsys window policy` lists global keys for packages this TV does not have (`com.android.tv`,
  `com.realtek.tv.*`); none of them is the stock launcher's app keys.
