# Sony XR-75X91K (China firmware): launcher compatibility notes

This is a launcher-only device report, not a tested debloat list. The retail
model was supplied by the owner; Android exposes a generic platform model.
The observations below were made using a locally customized launcher derived
from upstream commit `31eb392042009d41bc4ab4b9afadc253db7ab6d6`.
They do not constitute a test of the current upstream main branch.

## Observed firmware

| Field | Observed value |
|---|---|
| Owner-reported retail model | Sony `XR-75X91K` |
| Manufacturer | `Sony` |
| Android model | `BRAVIA 4K VH21` |
| Device | `BRAVIA_VH21` |
| Android / API | `12` / `31` |
| Firmware build | `BRAVIA_VH21_M_CN-user 12 STT2.230505.001.S136 682241 release-keys` |
| Application ABIs | `armeabi-v7a,armeabi` |

The existing `sony-bravia-4k-vh21` example describes a KD-65X85K with Australian
`M_PA` firmware and a different stock Home package. Sharing `BRAVIA_VH21` does
not establish that its debloat choices apply to this `M_CN` firmware.

## Stock Home and handover

The original Home component on this unit was:

```text
com.dangbei.TVHomeLauncher/com.dangbei.leard.leradlauncher.ui.sony.main.MainActivity
```

The reference launcher's APK installed successfully and an explicit start of
`com.glasstv.tvhome/.MainActivity` completed with `Status: ok`. These commands
were then used in the TV's ADB shell:

```sh
cmd package set-home-activity --user 0 com.glasstv.tvhome/.MainActivity
cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
input keyevent 3
```

The resolver and foreground activity both identified the new launcher after
the injected Home key. The original `com.dangbei.TVHomeLauncher` package
remained enabled. No additional packages were disabled for this setup, and
the launcher's optional Home/Input accessibility helpers were not enabled.

When using an installation script that normally disables a stock launcher,
use its leave-stock-enabled option (`STOCK_LAUNCHER=none`) and independently
verify Home ownership. On this tested build, package disabling was unnecessary.

For restoration planning, record the original component and enablement state
before making changes. The original component above is the intended Home
restore target; changing Home back to it was not exercised in this report.

## Live TV and HDMI

The Live TV/player package is `com.sony.dtv.tvx`; its launcher activity resolves
to `com.sony.dtv.tvx/.MainActivity`. Use `live_tv_package=com.sony.dtv.tvx` in
`config.xml`, and optionally include that package in `hidden_apps` so its tile
is represented by the Inputs menu instead.

The Inputs menu listed HDMI 1-4, AV and Live TV. Selecting HDMI 2 brought the
Sony TV player to the foreground with the corresponding external-input
session (`com.sony.dtv.tvinput.external/.ExternalTvInputService/HW3`). An
injected Home key then returned to the custom launcher. This verifies routing,
not external-source picture/audio quality or tuner reception.

The TV did not return a usable screenshot while showing the HDMI input in
that check. The player activity and input session were used as evidence;
launcher screenshots were available after returning Home.

## Validation scope

- APK installation, explicit launcher start, preferred-Home resolution and
  return via an ADB-injected Home key were checked on this unit.
- Basic directional navigation, installed-app launch and return Home were
  exercised. These checks do not establish video playback or account login.
- Restarting the launcher process retained the local launcher settings.
- No new debloat recommendations are provided. Existing app/service states
  were preserved; no raw package inventory is included.
- Cold boot, TV reboot, standby/wake, casting, voice input, firmware updates,
  USB peripherals, tuner reception, every physical remote key and several-day
  stability were not independently verified.

This report intentionally omits network addresses, hardware serials, Android
IDs, account details, installed-app preferences, screenshots and raw dumps.
