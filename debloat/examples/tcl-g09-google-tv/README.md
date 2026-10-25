# TCL Smart TV Pro G09 (Google TV, Android 12)

UK firmware `G09_4K_GB` (STT2.230203.001 AS24, Amlogic T5M, 2 GB RAM), set up in Brazil. Both halves
work on this set: the debloat list and the Clean Your TV launcher as Home. Tested on 19 October 2026,
including a reboot.

| File | What it is |
|---|---|
| `debloat.txt` | The 30 packages that were disabled, grouped and commented |
| `config.json` | Dock order, hidden apps, Live TV app, columns; push it with `tools/theme.sh` |

All commands run from the repo root, with the TV's IP in `TV_HOST`. USB debugging must be on while you
run them (Settings → System → Developer options).

## Install, from scratch or again

The order matters: the setup wizard (setupwraith) is disabled while Google TV's home still owns Home,
then `build-and-install.sh` starts the new launcher and only then disables launcherx.

```bash
TV_HOST=<tv-ip> tools/disable.sh examples/tcl-g09-google-tv/debloat.txt
INPUT_KEY_SERVICE=1 TV_HOST=<tv-ip> launcher/build-and-install.sh
TV_HOST=<tv-ip> tools/theme.sh examples/tcl-g09-google-tv
TV_HOST=<tv-ip> tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.glasstv.tvhome/.ClockDream'
```

The last line swaps the Ambient screensaver (`dreamx`, disabled by the list) for the launcher's Clock.

## Undo everything

```bash
TV_HOST=<tv-ip> tools/restore.sh examples/tcl-g09-google-tv/debloat.txt com.google.android.apps.tv.launcherx
TV_HOST=<tv-ip> tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop'
```

The launcher stays installed but is no longer Home. Never re-enable just
`com.google.android.tungsten.setupwraith` on its own while the new launcher is Home; `restore.sh` brings
Google TV's home back first.

## Re-enable one app

```bash
TV_HOST=<tv-ip> tools/venv/bin/python -I tools/tv.py 'pm enable com.tcl.ui_mediaCenter'
```

Comment its line out in `debloat.txt` so the next run leaves it alone. Without a computer: Settings →
Apps → See all apps → Show system apps → pick the app → Enable. The two off by choice are
`com.google.android.apps.mediashell` (Chromecast built-in) and `com.tcl.ui_mediaCenter` (USB media
player).

## Change the dock order

Edit `dock_apps` in `config.json`: package names, left to right; an app that isn't installed is skipped.
`tools/tv.py 'pm list packages -3'` lists the installed ones. Push it again with the `theme.sh` line
above, no rebuild needed. `com.tcl.tv` and `com.tcl.suspension` are in `hidden_apps` because the Inputs
pill already covers Live TV and the inputs. All keys: [`themes/README.md`](../../themes/README.md).

## Remote app buttons

With Google TV's home disabled, the Netflix, Prime Video and YouTube buttons on the TCL remote do nothing:
they send TCL's own keycodes, which TCL's shortcut manager hands to the stock home. `config.json` maps them
back through the launcher's `InputKeyService`, which is why the install line above passes
`INPUT_KEY_SERVICE=1`. Re-pass it on every reinstall; a reinstall drops the service from the accessibility list.

| Button | Keycode | Scancode | Note |
|---|---|---|---|
| Netflix | 4062 | 472 | `app_keys` |
| Prime Video | 4099 | 473 | `app_keys` |
| YouTube | 4065 | 569 | `app_keys` |
| Disney+ | | | keeps working on its own |
| TCL Channel | 4020 | 456 | opens `com.tcl.waterfall.overseas`, which the list disables; map it to any app you like |
| Shield (TCL Guard) | 4140 | 660 | opens TGuard, which the list disables; free to map |

Found with `logcat` while pressing each button: TCL logs every press as `handleKeyBeforeQueueing event=KeyEvent
{ ... keyCode=..., scanCode=... }` from `WindowManager`. Keys injected over ADB never reach `InputKeyService`,
so check with the real remote.

## Notes for this set

- The remote's Input button keeps working without Google TV's home: TCL draws that panel itself.
  `INPUT_KEY_SERVICE` is only needed for the app buttons above.
- Same lists and buttons on a second unit with firmware STT2.230203.001 AS10 (G09_4K_GB_32BIT).
- Firmware updates come through Play Services and are untouched.
- Screenshots come out black while HDMI or Live TV is on screen; press Home first.
- Switch USB debugging off when you're done; the launcher keeps working without it.
