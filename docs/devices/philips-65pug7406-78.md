# Philips 65PUG7406/78: initial launcher compatibility notes

Checked on the owner's real TV in a single ADB session. This is **not a tested debloat list**: the package changes were owner-specific, and no several-day stability or cold-boot regression test was completed. Do not copy the changes below as a safe package list.

## Identify the exact firmware

| Field | Observed value |
|---|---|
| Retail model | Philips `65PUG7406/78` |
| Android / SDK | Android TV 11 / 30 |
| Software version | `TPM215L_R.105.000.097.000` |
| Software build date | 2024-11-25 |
| Android security patch | 2024-11-01 |
| About-screen model | `2020/2021 UHD Android TV` |
| `ro.product.device` | `PH0M_EA_T32` |
| `ro.product.name` | `PH0M_LT_T32` |

The generic About-screen model is not the retail model. Match the firmware identifiers before applying any future list for another Philips set.

## HOME handover observed on this unit

The original HOME activity was `com.google.android.tvlauncher/.MainActivity`. On this firmware, `cmd package set-home-activity` reported success while the stock launcher was still enabled, but HOME continued to resolve to the stock launcher. After the replacement launcher was installed and the stock launcher was disabled for user 0, HOME resolved to `com.glasstv.tvhome/.MainActivity` and the launcher was verified running.

This records one successful handover on this unit; it does not establish that every Android 11 Philips TV needs the same sequence. The launcher trial used source checkout `022c57da7c4f07466141229cb5625f6ae4fd7ba0`, so it is not validation of the current upstream main branch.

## Owner-selected app changes

The owner later disabled Live TV (`com.mediatek.wwtv.tvcenter`) and Assistant / voice search (`com.google.android.katniss`). These were individual preferences, not a recommended default. Their effect on antenna channels, voice controls, HDMI switching, and remote shortcuts was not fully tested.

Other manufacturer and recommendation packages were changed during the same setup, including packages removed for user 0. This repository's contribution policy uses reversible `pm disable-user` lists and asks for several days of use before calling a list tested; those owner-specific removals are therefore intentionally not turned into a debloat list here.

## Validation limits

- Confirmed the TV model and software identifiers in Android Settings.
- Confirmed the original HOME activity, installed the replacement launcher, and verified its activity after the handover.
- No cold boot, multi-day stability, antenna scan, channel playback, HDMI switching, casting, or full physical-remote test was performed after the package changes.
- ADB was used for setup. Turn debugging off and revoke debugging authorisations when maintenance is finished.

