# Sony BRAVIA XBR-55X850D

Owner-tested on **Android TV 8.0.0 (API 26), Sony firmware 6.827**. Android reports
the generic model name `BRAVIA 4K 2015`; do not use that label alone to match a set.
A **Roku Ultra LT on HDMI** supplies streaming in this setup.

The [debloat list](debloat.txt) contains exactly the **19 packages actually
disabled for user 0**. Nothing was uninstalled or had its app data cleared.
Use it as a firmware-specific starting point, not a universal Sony list. The
observation window is short: approximately two days of use, a controlled TV
reboot, an unplug/replug start, and a later start after more than 24 hours off.
No packages from this list needed re-enabling during that window.

## Home and input configuration

The tested build was CleanYourTV **v2.3**, built from commit
`6bcd649bfd6fb4ed0e448c369a258b672a0a1a0b`. Only device-specific launcher config
was changed; no launcher Java code was changed:

- `live_tv_package`: `com.sony.dtv.tvx`.
- First `dock_apps` entry: `com.sony.dtv.tvx`, followed by Netflix, Prime Video
  and YouTube in this owner's build.
- `module_cards`: `false`.
- `reduced_motion`: `true`.

**Keep `com.sony.dtv.tvx` enabled. It handles HDMI playback as well as Live TV.**
Select the Roku through the real Inputs menu rather than copying another TV's
hardware input ID. The stock Home package is `com.google.android.tvlauncher`;
the replacement is `com.glasstv.tvhome`.

Build, install, launch and verify the replacement Home while stock Home is still
enabled. Only then complete the Home handover and disable the stock launcher.
The launcher entry is last in this list for that reason. Keep the upstream
Home/input guards; do not bypass them to force the list through.

## What was tested

- The new Home rendered; Settings opened; Home and D-pad focus navigation worked
  through injected keys/UI checks.
- The actual Inputs menu identified the Roku Ultra LT. Its HDMI picture/playback
  and audible sound were confirmed by the owner.
- A controlled TV-only reboot preserved the new default Home and all 19 disabled
  states. All other original package enabled states and registered physical
  input IDs were preserved.
- The launcher build and its **42 unit tests** passed on the tested source above.
  This is historical device/build evidence, not a claim to have rebuilt the
  current upstream launcher as part of this example submission.
- Physical Sony Home/Input buttons were not separately owner-confirmed. Casting,
  DLNA and voice functions were left enabled, not independently end-to-end tested.

Sony TV/input providers, CEC/BRAVIA Sync, remote/pairing, keyboard, Settings,
Google Play Services/Store, MediaTek services, firmware update/recovery and the
Sony boot monitor were preserved. The optional Smart Help, i-Manual, native USB
music player and Google/Crackle apps in this list were not needed by this owner;
keep any that you use. Re-enable `com.sony.dtv.b2b.vendorprotocol` if your
home-automation system needs its Control4/SDDP discovery.

## Owner-reported startup observations

These are individual remote-button/picture-readiness observations, not repeated
instrumented benchmarks:

- **Before cleanup:** about **100 seconds** after roughly 24 hours switched off
  with the remote while still connected to AC. That occurrence showed the Sony/
  Android startup animation and was separately confirmed to involve a new boot.
- **After cleanup, unplug/replug cold start:** **48 seconds**.
- **After cleanup, subsequent long-standby test:** **26 seconds** after more than
  **24 hours off**, reported on October 21, 2026 following the planned
  remote-off/AC-connected trial. Compare this with the earlier 100-second
  long-standby observation, not with the unplug/replug condition.

The shorter wait is an observed outcome of this setup, not proof that a
particular disabled package caused the earlier restart or that no reboot
occurred in the later test. No post-test boot-ID measurement was taken for the
26-second report. A prior separate `remote_start=On` experiment had **not**
prevented the earlier 100-second occurrence; that setting remained on, and the
cleanup did not change power or Android animation settings. The launcher's
`reduced_motion` option changes launcher motion, not system power settings.

No RAM-saving percentage is claimed: the single Home-process PSS samples did
not demonstrate a reduction.

## Recovery and debugging

Use the upstream backup/recovery record to restore original package states and
stock Home. Restore stock Home before disabling the replacement. Do not
uninstall system apps, clear data or disable TVX/input services.

On this firmware the developer setting is named **ADB debugging**, not USB
debugging. Authorize only your trusted computer, and follow the repository's
ADB-security/closeout guidance when finished.
