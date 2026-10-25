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
TV_HOST=<tv-ip> launcher/build-and-install.sh
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

## Notes for this set

- The remote's Input button keeps working without Google TV's home: TCL draws that panel itself, so the
  launcher's optional `INPUT_KEY_SERVICE` is not needed.
- Firmware updates come through Play Services and are untouched.
- Screenshots come out black while HDMI or Live TV is on screen; press Home first.
- Switch USB debugging off when you're done; the launcher keeps working without it.
