# Windows version of build-and-install.sh: install the GlassTV launcher on a TV and make it the
# default Home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-Apk <path to .apk>]
#        By default the TV's own launcher is left installed and enabled: the APK is pushed,
#        installed, started and set as Home, and nothing else on the TV is touched.
#
#        [-TakeOver]              also disable the stock launcher (detected from the TV)
#        [-StockLauncher <pkg>]   the stock launcher to disable, or 'none' to disable nothing
#        [-InputKeyService]       remote's Input button opens the input switcher (Google TV), plus
#                                 config.xml input_key_keycodes and app_keys; re-pass on every install
#        [-HomeKeyService]        remotes whose Home button sends KEYCODE_ALL_APPS (Xiaomi sticks)
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Apk = '',
    [switch]$TakeOver,
    [string]$StockLauncher = '',
    [switch]$InputKeyService,
    [switch]$HomeKeyService
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

# Single place for the app identity. A rename changes this line and nothing else.
$AppId = if ($env:APP_ID) { $env:APP_ID } else { 'com.glasstv.tvhome' }
$MainClass = 'com.glasstv.tvhome.MainActivity'   # class name, independent of the applicationId
$Component = "$AppId/$MainClass"

$py = '..\tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'tools\venv is missing: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }

if (-not $Apk) {
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
        if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
        $env:JAVA_HOME = $jbr
    }
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    if (-not (Test-Path local.properties)) {
        "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
    }
    .\gradlew.bat assembleRelease -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
    $Apk = 'app\build\outputs\apk\release\app-release.apk'
}
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

$homeQuery = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
function Get-HomeOwner {
    (& $py -I ..\tools\tv.py $homeQuery | Select-Object -Last 1)
}
# Who owned Home before this script ran: the take-over decision must not re-read it after Home became
# ours, or it would conclude "already ours" and skip the take-over that was asked for.
$homeBefore = (Get-HomeOwner).Trim()

& $py -I ..\tools\b64push.py $Apk /data/local/tmp/glasstv.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = (& $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/glasstv.apk; rm -f /data/local/tmp/glasstv.apk') -join "`n"
Write-Host $out
if ($out -notmatch 'Success') {
    # Turn pm's one-line failure into the actual cause; every one of these gets reported as
    # "install failed" while the APK itself is fine.
    $hint = ''
    if ($out -match 'INSTALL_FAILED_OLDER_SDK') { $hint = 'This APK needs a newer Android than the TV has. On Android 4.4-5.0 use GlassTV-Launcher-lite44.apk (minSdk 19) instead of the universal one.' }
    elseif ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') { $hint = "A package with this name is already installed, signed with a different key (a build made elsewhere, or one re-signed by hand). Remove it first: adb shell pm uninstall $AppId - then install the APK from this repository's Releases. Uninstalling drops the saved tile order and wallpaper choice." }
    elseif ($out -match 'INSTALL_PARSE_FAILED') { $hint = 'The APK file is damaged or truncated: the download stopped early, or a mirror served an HTML page instead of the file. Download it again and compare its size with the Release.' }
    elseif ($out -match 'INSTALL_FAILED_VERIFICATION_FAILURE') { $hint = 'The firmware refused sideloading, not this APK. Allow installing unknown apps on the TV (and turn off install verification) before trying again.' }
    elseif ($out -match 'INSTALL_FAILED_INSUFFICIENT_STORAGE') { $hint = 'Not enough free space on the TV. Free some up and retry.' }
    throw "pm install failed; nothing on the TV was changed. $hint"
}
$version = (& $py -I ..\tools\tv.py "dumpsys package $AppId | grep -m1 -o 'versionName=[^ ]*'" | Select-Object -Last 1)
if ($version) { Write-Host "Installed $AppId ($($version.Trim()))" }

# Start the launcher before touching Home: a package that installs but cannot start would otherwise
# be made the default anyway, and the TV would come up to a blank screen (#239).
$start = (& $py -I ..\tools\tv.py "am start -W -n $Component") -join "`n"
Write-Host $start
if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') {
    throw "the launcher did not start (see above); Home was not changed and the TV's own launcher was left enabled. If the message is 'Activity class ... does not exist', the installed package has another name than $AppId."
}

& $py -I ..\tools\tv.py "cmd package set-home-activity --user 0 $Component" | Out-Null
$homeNow = (Get-HomeOwner).Trim()
if ($homeNow -like "$AppId/*") {
    Write-Host "Home now resolves to $homeNow"
} else {
    # Not fatal: several China-market firmwares answer "Success" and keep their own Home until the
    # user picks one on screen (docs/devices/xiaomi-mitv-aetp0-dofus.md).
    Write-Warning "Home still resolves to '$homeNow'. Press Home on the remote, pick GlassTV and tick 'always'. The install itself worked."
}

if (-not $TakeOver -and -not $StockLauncher) {
    Write-Host "Installed. Nothing was disabled: the TV's own launcher is still installed and enabled."
    Write-Host 'Press Home on the remote to open GlassTV.'
    exit 0
}

# ---------------------------------------------------------------------------------------------
# Optional phase 2: disable the stock launcher (-TakeOver / -StockLauncher).
# ---------------------------------------------------------------------------------------------
# Which app owns Home differs by build; ask the TV, but only accept a launcher we recognise (the answer
# can also be Android's chooser "android", the setup wizard, or another third-party launcher).
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
if (-not $StockLauncher -or $StockLauncher -eq 'auto') {
    $homePkg = "$homeBefore".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) { $StockLauncher = $homePkg }
    elseif ($homePkg -eq $AppId) { Write-Host 'Home already belonged to this launcher; nothing to disable.'; exit 0 }
    elseif ($homePkg -eq 'android') {
        # Android's chooser: launchers tie and no default is set (a restore followed by a re-applied list
        # ends here). Take the one known stock launcher among the HOME claimants, if there is exactly one.
        $claimants = (& $py -I ..\tools\tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) { $StockLauncher = $found[0]; Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher." }
        else { throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none." }
    }
    else { throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = launcher chooser, com.google.android.tungsten.setupwraith = setup wizard, see README Troubleshooting; anything else = another launcher is the default). Run again with -StockLauncher <package to disable> or -StockLauncher none." }
}
Write-Host "Stock launcher: $StockLauncher"

if ($StockLauncher -ne 'none') {
    & $py -I ..\tools\tv.py "pm disable-user --user 0 $StockLauncher" | Out-Null
    # Android drops the saved Home preference whenever the set of HOME apps changes, so set it again
    # after disabling, then check where it landed.
    & $py -I ..\tools\tv.py "cmd package set-home-activity --user 0 $Component" | Out-Null
}
$homeNow = (Get-HomeOwner).Trim()
if ($homeNow -notlike "$AppId/*") {
    if ($StockLauncher -ne 'none') {
        & $py -I ..\tools\tv.py "pm enable --user 0 $StockLauncher" | Out-Null
        # set-home-activity needs pkg/activity on Android 9, so resolve the stock component on the TV.
        & $py -I ..\tools\tv.py "cmd package set-home-activity --user 0 `$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $StockLauncher | tail -n 1)" | Out-Null
    }
    throw "Home resolves to '$homeNow', not to the new launcher; $StockLauncher was re-enabled. On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to your debloat list (see README, Troubleshooting), apply it, then run this script again."
}
Write-Host "Home now resolves to $homeNow"

# Optional accessibility services, appended to enabled_accessibility_services without dropping others.
function Enable-Service([string]$Name) {
    $svc = "$AppId/com.glasstv.tvhome.$Name"
    # Through stdin: Windows PowerShell 5.1 drops the inner double quotes from a native argument, and the
    # TV's shell then fails with "no closing quote" while the service stays off.
    ('s=$(settings get secure enabled_accessibility_services); case "$s" in *' + $Name + '*) ;; null|"") settings put secure enabled_accessibility_services ' + $svc + ' ;; *) settings put secure enabled_accessibility_services "$s:' + $svc + '" ;; esac; settings put secure accessibility_enabled 1; echo ' + $Name + ' enabled') |
        & $py -I ..\tools\tv.py --batch
}
# The Settings menu's Sleep row; installing the update switched it off, so turn it back on every time.
Enable-Service 'SleepService'
if ($InputKeyService) { Enable-Service 'InputKeyService' }
if ($HomeKeyService) { Enable-Service 'HomeKeyService' }
Write-Host 'Installed. Press Home on the remote to see it.'
