# TCL 75QM751G (QM7, 2024): the QM6K list on the same G08 board

One owner's report ([#453](https://github.com/Gathin23/cleanyourtv/issues/453)), applied and
checked on the same day and **not** lived with for several days, so this is not a list of its own.
Run [`examples/tcl-qm6k-google-tv/debloat.txt`](../../examples/tcl-qm6k-google-tv/debloat.txt) —
same `G08` board, same `AU14` build — and read the deltas and the two traps below. A second owner
confirmed the cast-receiver symptom on a TCL BeyondTV2 in the same thread.

## Identify the firmware, not the retail name

| Field | Observed value |
|---|---|
| Retail model | `75QM751G` (QM7, 2024, US), supplied by the owner |
| `ro.product.model` | `Smart TV Pro` |
| `ro.product.device` | `G08` |
| `ro.product.manufacturer` | `TCL` |
| Android / SDK | `14` / `34` |
| `ro.build.display.id` | `tcl9618-user 14 UTT2.250416.001 AU14 release-keys` |
| `ro.adb.secure` | `1` |

`Smart TV Pro` is shared across TCL ranges, so it decides nothing on its own. What matters here is
`G08` + `AU14`: that is the pair the QM6K list was already applied on (#23 reported the same board
on AU14), and it is why the same list is the starting point on this set.

## Connection

Network ADB answered over Ethernet without the wireless-debugging pairing step. `ro.adb.secure=1`
still means the RSA prompt on the TV has to be accepted. Whether a wireless-only setup on another
unit of this model needs pairing was not tested.

## What the QM6K list needed on this set

Every package in the QM6K list was disabled, with one no-op: `com.tcl.repairguide` is **not
installed** on this preload (`Unknown package`), so the e-manual entry is missing rather than
disabled. The owner also uncommented these optional lines of that list:

| Package | What goes with it |
|---|---|
| `com.netflix.ninja`, `com.netflix.tokenmanager` | Netflix (the two lines belong together) |
| `com.google.android.katniss` | Google Assistant voice |
| `com.google.android.tv.remote.service` | Google TV phone-app remote; on the C755 list it also carries the remote's microphone |
| `com.tcl.airplay2`, `com.mediatek.AirplayAPK`, `com.mediatek.airplaydaemon` | AirPlay (three lines together) |

The Cast receiver (`com.google.android.apps.mediashell`) was kept, and casting still broke — see
below.

## setupwraith is skipped while it owns the saved Wi-Fi, Ethernet or not

The first `launcher/build-and-install.sh` run rolled back as designed: Home went to
`com.google.android.tungsten.setupwraith/.RecoveryActivity`. `tools/disable.sh` then skipped
setupwraith because the Wi-Fi network saved during setup belonged to it. The TV was on Ethernet and
that changed nothing: the guard asks who owns the saved network, not which interface is up. Only
after forgetting that network in Settings did the second run disable setupwraith and make
`com.glasstv.tvhome` Home.

So on a set like this, decide before the install, not after the rollback:

- check the owner of the saved network first:
  `tools/tv.py "dumpsys wifi" | grep -o 'cname=[^ ]*'`;
- forget the network and rejoin it on the TV (it then belongs to `android.uid.system`), which is
  what this owner did, and what keeps Wi-Fi working afterwards;
- `FORCE_SETUPWRAITH=1` skips the guard instead, which is fine on a set that stays on Ethernet and
  loses nothing when that saved network is deleted.

## Launcher settings that worked

`INPUT_KEY_SERVICE=1`, `live_tv_package` `com.tcl.tv` (the player; `com.tcl.tvinput` has no
launcher activity) and `settings_key_keycodes` `83` for the gear button. These match the other G08
lists, and the owner reports Live TV, inputs and the launcher working with them; the individual
remote keys were not enumerated beyond that.

## Casting: the receiver can be running and still invisible

After the list, phones and a PC could not see the TV. `com.google.android.apps.mediashell` was
running and listening on 8008/8009, so "the process is up" proves nothing. This was also not the
setupwraith credentials failure: briefly re-enabling setupwraith did not help, and the log had no
`No private key` or `CastInfoProvider` lines. The receiver had stopped answering mDNS — its
connections sat in `CLOSE_WAIT` on 8009 with unread data. `am force-stop
com.google.android.apps.mediashell` restarted it, the TV advertised again within seconds, and
casting from a PC worked, without a reboot.

Check discovery, not the process:

```sh
tools/tv.py "logcat -d -s cast_shell | grep -E 'Done announcing|responses sent'"
tools/tv.py "dumpsys activity service com.google.android.apps.mediashell | grep WargService"
```

A receiver that is advertising logs `MdnsAppFilter: Done announcing.`,
`responses sent in 60 seconds: N` and `Gcm.Register.Success=2`, with `WargService` up. Missing
`Done announcing` while the process lives is the hang this owner hit. The second owner did not
reproduce the hang — their list leaves the Cast stack alone — and did not stream video, so the log
evidence covers discovery, not playback.

## Test limits

- One set, one firmware, one session, same day. Nothing here has been lived with for days.
- Regional builds, other panel sizes, wireless-debugging pairing, Wi-Fi after setupwraith is
  disabled, reboot persistence of the disabled packages, the remote's app buttons, and playback
  through Netflix, Assistant or AirPlay after those packages were turned off are all untested.
- Casting was verified from a PC only, for discovery and one stream; casting from a phone and after
  a reboot was not re-checked.
