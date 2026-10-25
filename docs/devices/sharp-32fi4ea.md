# Sharp 32FI4EA / AQUOS-TVE19G (Android TV 11)

Initial device report for two owner-reported Sharp 32FI4EA televisions. Both report `AQUOS-TVE19G` and Android 11 over ADB.

- Set A: build `RTOB.250806.001`, incremental `13890547`.
- Set B: build `RTOB.240311.001`, incremental `11556578`.
- Testing date: 2026-10-21. The owner confirms both sets were restarted and apps tested.

Several days of normal use have not yet been documented. These are initial device notes, not a verified reusable debloat list.

## Launcher and TV packages

- Stock HOME: `com.google.android.tvlauncher/.MainActivity`.
- Replacement HOME: `com.glasstv.tvhome/.MainActivity` (reference launcher v2.4, built targeting SDK 34).
- Live TV: `com.mediatek.wwtv.tvcenter`.
- TV input: `com.mediatek.tvinput`.
- TV service: `com.mediatek.tv.service`.

On set A, selecting the replacement HOME alone did not override the stock launcher. The replacement was installed and opened successfully before disabling the stock launcher. Keep the stock launcher installed with its data for recovery. `com.google.android.setupwraith` was retained; it was not reported as a HOME handler during inspection.

The dock was configured for YouTube, Netflix, Plex, Prime Video and TV 2 Play. HOME resolution, Settings access and the input menu were checked on both sets. The input menu listed HDMI 1-3, Component, Composite and Live TV; this does not establish HDMI signal or HDMI-CEC functionality. No accessibility helper was needed for HOME.

## Packages actually disabled during this setup

The following were disabled with `pm disable-user --user 0`, not uninstalled:

- Both sets: `com.google.android.tvlauncher` - stock launcher, only after verifying the replacement.
- Both sets: `fusion.android.tv.demo` - shop/demo app at `/system/app/UMCShopMode`.
- Both sets: `com.google.android.feedback` - feedback component.
- Set A only: `tv.anoki.acr.controller` - ACR controller; absent on set B.

These package names record what was changed, not a recommendation to run a blanket disable command on other firmware. Freeview components were already disabled before these changes and are not attributed to this setup. `com.google.android.tvrecommendations` was retained, including its notification-related activities.

## Preserved functionality and test limits

Streaming apps, Google Play Store and services, Chromecast, Assistant, system-update components, TV/input services, keyboard and Bluetooth components were retained. Retention does not imply that casting, voice control or firmware installation was independently tested.

The owner confirmed streaming playback and the physical Home, Input, Netflix and YouTube buttons on set A, and subsequently confirmed reboot and app testing on both sets. Physical shortcut buttons were not separately confirmed for set B. No faults requiring package re-enabling were reported during these initial checks. No measured performance improvement or long-term stability is claimed.

AdGuard Private DNS was also configured and validated by Android on both sets as an optional household preference. It is not part of the package changes or a requirement for this launcher.

## Recovery

Save each set's original settings and enabled/disabled package state before making changes. Re-enable a changed package with `adb shell pm enable --user 0 PACKAGE`. To restore the stock home, re-enable `com.google.android.tvlauncher` and select `com.google.android.tvlauncher/.MainActivity` as HOME. Prefer the recorded original state over enabling every package indiscriminately.

A tested list under `examples/` can follow after several days of normal use and further confirmation of the retained TV features.
