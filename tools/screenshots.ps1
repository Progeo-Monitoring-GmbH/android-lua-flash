<#
.SYNOPSIS
  Takes screenshots of every view of the app on an Android emulator (docs/screenshots/).

.DESCRIPTION
  Starts (and if needed creates) the emulator, installs the debug build, clears the app's data,
  then clicks through every view by its visible text. The flash wizard steps come from the
  debug-only DemoStateReceiver, since the emulator has no USB adapter.

  The app starts with the ProGeo login. With a picture of a login QR code (-LoginQr, default
  docs/qr-code.png, e.g. the code from the portal's homepage) the script logs in unattended:
  the app's debug receiver decodes the picture privately and deletes it. Without one it asks
  for user name, password and, if needed, the two-factor code in the terminal; they are typed
  into the emulator only and never stored (LF_SCREENSHOT_USER / LF_SCREENSHOT_PASSWORD work
  too, for accounts without two-factor authentication). At the end the app's data on the
  emulator is cleared, so no session stays behind.

  A step that fails (e.g. a button was renamed) gives a warning and the run goes on; the
  summary at the end lists them.

  VS Code: Terminal → Run Task… → "Android: Screenshots".

.PARAMETER Avd           Emulator to use (created from Android 11 / API 30 if missing).
.PARAMETER Out           Folder for the images (default docs/screenshots, git-ignored).
.PARAMETER SkipBuild     Use the app already installed on the emulator.
.PARAMETER KeepEmulator  Leave the emulator running afterwards (it is stopped only if this run started it).
#>
param(
    [string]$Avd = "lf_screens",
    [string]$Out = (Join-Path $PSScriptRoot "..\docs\screenshots"),
    [switch]$SkipBuild,
    [switch]$KeepEmulator,
    # Picture of a login QR code (e.g. from the portal's homepage) to log in with; if it does
    # not exist, the script asks for user and password. Keep it out of git: it holds a token.
    [string]$LoginQr = (Join-Path $PSScriptRoot "..\docs\qr-code.png")
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$package = "com.progeo.luaflasher"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$work = Join-Path ([IO.Path]::GetTempPath()) "lua-flasher-screenshots"
New-Item -ItemType Directory -Force $work | Out-Null
$warnings = New-Object System.Collections.Generic.List[string]
$shots = New-Object System.Collections.Generic.List[string]

foreach ($tool in $adb, $emulator) {
    if (-not (Test-Path $tool)) { throw "Not found: $tool. Install the Android SDK platform-tools and emulator, or set ANDROID_HOME." }
}

function Step([string]$text) { Write-Host "`n== $text" -ForegroundColor Cyan }

# Runs a native tool. Windows PowerShell 5.1 turns every stderr line of a native tool into an
# error record, which would stop the script; adb writes harmless notes there, so take them as text.
# No param block and no @splatting on purpose: arguments such as "-a" or "-n" must reach the
# tool literally instead of being taken for PowerShell parameter names.
function Native {
    $exe = $args[0]
    $list = @($args | Select-Object -Skip 1 | ForEach-Object { $_ })
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $exe $list 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { $_ } }
    } finally { $ErrorActionPreference = $old }
}

function Adb { Native $adb $args }

# --- emulator ---------------------------------------------------------------

function Find-Emulator {
    (Adb devices) | Where-Object { $_ -match '^(emulator-\d+)\s+device$' } | ForEach-Object { $Matches[1] } | Select-Object -First 1
}

function Ensure-Avd {
    if ((Native $emulator -list-avds) -contains $Avd) { return }
    Step "Creating emulator $Avd (Android 11, x86)"
    $image = "system-images;android-30;google_apis;x86"
    if (-not (Test-Path (Join-Path $sdk "system-images\android-30\google_apis\x86"))) {
        throw "System image $image is missing. Install it in Android Studio (SDK Manager) or with sdkmanager `"$image`"."
    }
    $avdmanager = Get-ChildItem (Join-Path $sdk "cmdline-tools") -Recurse -Filter avdmanager.bat -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $avdmanager) { throw "avdmanager not found: install the Android SDK Command-line Tools." }
    if (-not $env:JAVA_HOME) {
        $jbr = Get-ChildItem "C:\Program Files\Android" -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName "jbr" } | Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($jbr) { $env:JAVA_HOME = $jbr }
    }
    "no" | Native $avdmanager.FullName create avd -n $Avd -k $image -d pixel_3 --force | Out-Null
}

$startedEmulator = $false
$serial = Find-Emulator
if (-not $serial) {
    Ensure-Avd
    Step "Starting emulator $Avd (no window)"
    Start-Process -FilePath $emulator -ArgumentList '-avd', $Avd, '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-no-boot-anim' -WindowStyle Hidden
    $startedEmulator = $true
    $deadline = (Get-Date).AddMinutes(4)
    do { Start-Sleep 3; $serial = Find-Emulator } until ($serial -or (Get-Date) -gt $deadline)
    if (-not $serial) { throw "The emulator did not come up." }
}
# Only ever talk to the emulator, even when a phone is plugged in too (gradle honours this as well).
$env:ANDROID_SERIAL = $serial
$deadline = (Get-Date).AddMinutes(4)
while (("" + (Adb shell getprop sys.boot_completed)).Trim() -ne "1") {
    if ((Get-Date) -gt $deadline) { throw "The emulator did not finish booting." }
    Start-Sleep 3
}
Write-Host "Using $serial"

# --- app --------------------------------------------------------------------

if (-not $SkipBuild) {
    Step "Building and installing the debug app"
    Push-Location $root
    try {
        Native .\gradlew.bat installDebug --console=plain | Where-Object { $_ -notmatch '^> Task' }
        if ($LASTEXITCODE -ne 0) { throw "gradlew installDebug failed." }
    } finally { Pop-Location }
}

Step "Preparing the emulator"
Adb shell settings put system screen_off_timeout 1800000
foreach ($s in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') { Adb shell settings put global $s 0 }
Adb shell cmd uimode night no | Out-Null
# Same status bar on every image: 12:00, full battery and signal, no notifications.
Adb shell settings put global sysui_demo_allowed 1
Adb shell am broadcast -a com.android.systemui.demo -e command enter | Out-Null
Adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 | Out-Null
Adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false | Out-Null
Adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4 | Out-Null
Adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false | Out-Null
Adb shell input keyevent KEYCODE_WAKEUP
Adb shell wm dismiss-keyguard

# Fresh app data on every run: same starting point, nothing left over from the last run.
Adb shell pm clear $package | Out-Null
Adb shell pm grant $package android.permission.POST_NOTIFICATIONS | Out-Null

New-Item -ItemType Directory -Force $Out | Out-Null
# New images go here first; $Out is only replaced at the end (see "done").
$staging = Join-Path $work "new"
Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $staging | Out-Null

# --- UI helpers -------------------------------------------------------------

$size = [regex]::Match((Adb shell wm size), '(\d+)x(\d+)')
$W = [int]$size.Groups[1].Value; $H = [int]$size.Groups[2].Value

Add-Type -AssemblyName System.Drawing
Add-Type -Path (Join-Path $PSScriptRoot "Stitcher.cs") -ReferencedAssemblies System.Drawing

function Capture([string]$path) {
    Adb shell screencap -p /sdcard/shot.png
    Adb pull /sdcard/shot.png $path | Out-Null
}

# How far one DragUp moves the page.
$dragDistance = [int]($H * 0.40)

# Scrolls the page up by $dragDistance without fling: drag with touch events, then hold still
# before lifting the finger, so the page stops exactly where the finger did.
function DragUp {
    # In the page's 16 dp side margin: there the drag always moves the page and never lands
    # on a text field (which would take the touch, or open the keyboard).
    $x = [int]($W * 0.02)
    $y0 = [int]($H * 0.72)
    Adb shell input motionevent DOWN $x $y0
    foreach ($k in 1..8) { Adb shell input motionevent MOVE $x ([int]($y0 - $dragDistance * $k / 8)) }
    Start-Sleep -Milliseconds 300
    Adb shell input motionevent UP $x ($y0 - $dragDistance)
    Start-Sleep -Milliseconds 600
}

# One image of the whole page: drags it up step by step until it no longer moves and stitches
# the screenshots (tools/Stitcher.cs). -Viewport takes only what is on the screen.
function Shot([string]$name, [switch]$Viewport) {
    Start-Sleep -Milliseconds 700
    $dest = Join-Path $staging "$name.png"
    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add((Join-Path $work "part0.png"))
    Capture $parts[0]
    if (-not $Viewport) {
        foreach ($i in 1..20) {
            DragUp
            $next = Join-Path $work "part$i.png"
            Capture $next
            if ([Stitcher]::Offset($parts[$parts.Count - 1], $next, $dragDistance) -le 0) { break }
            $parts.Add($next)
        }
    }
    $used = [Stitcher]::Stitch($parts.ToArray(), $dragDistance, $dest)
    if ($parts.Count -gt 1) { ScrollTop }
    $shots.Add($name)
    Write-Host ("  saved $name.png" + $(if ($used -gt 1) { " ($used screens)" } else { "" }))
}

function Ui {
    Adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    Adb pull /sdcard/ui.xml (Join-Path $work "ui.xml") | Out-Null
    [xml](Get-Content -Raw -Encoding UTF8 (Join-Path $work "ui.xml"))
}

function Find-Node([string]$pattern, [int]$index = 0) {
    $nodes = (Ui).SelectNodes("//node") | Where-Object { $_.text -match $pattern -or $_.'content-desc' -match $pattern }
    @($nodes)[$index]
}

function Has([string]$pattern) { [bool](Find-Node $pattern) }

function Center($node) {
    $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    @([int](($b[0] + $b[2]) / 2), [int](($b[1] + $b[3]) / 2))
}

# Taps the element whose text or content description matches $pattern (a regex).
function Tap([string]$pattern, [int]$index = 0) {
    $node = Find-Node $pattern $index
    if (-not $node) { throw "nothing on screen matches '$pattern'" }
    $x, $y = Center $node
    Adb shell input tap $x $y
    Start-Sleep -Milliseconds 1200
}

function TapAt([double]$fx, [double]$fy) {
    Adb shell input tap ([int]($W * $fx)) ([int]($H * $fy))
    Start-Sleep -Milliseconds 1200
}

# Types into the focused field. Every character reaches the device literally.
function TypeText([string]$text) {
    $escaped = $text.Replace("'", "'\''").Replace(' ', '%s')
    Adb shell "input text '$escaped'"
    Start-Sleep -Milliseconds 500
}

function Field([string]$label, [string]$value) {
    Tap $label
    TypeText $value
    HideKeyboard
}

function HideKeyboard {
    if ((Adb shell dumpsys input_method) -match 'mInputShown=true') { Adb shell input keyevent KEYCODE_BACK; Start-Sleep -Milliseconds 600 }
}

function Back { Adb shell input keyevent KEYCODE_BACK; Start-Sleep -Milliseconds 1000 }
function ScrollDown { Adb shell input swipe ([int]($W / 2)) ([int]($H * 0.78)) ([int]($W / 2)) ([int]($H * 0.25)) 500; Start-Sleep -Milliseconds 800 }
function ScrollTop { 1..8 | ForEach-Object { Adb shell input swipe ([int]($W * 0.02)) ([int]($H * 0.3)) ([int]($W * 0.02)) ([int]($H * 0.85)) 150 }; Start-Sleep -Milliseconds 800 }

# Drags the page up until something matching $pattern is on the screen; false if it never shows up.
function ScrollTo([string]$pattern) {
    foreach ($i in 1..12) {
        if (Has $pattern) { return $true }
        DragUp
    }
    Has $pattern
}
function Tab([string]$label) { Tap "^$label$" ((@((Ui).SelectNodes("//node") | Where-Object { $_.text -match "^$label$" })).Count - 1) }

function Demo([string]$state) {
    Adb shell am broadcast -n "$package/.debug.DemoStateReceiver" --es state $state | Out-Null
    Start-Sleep -Milliseconds 1500
}

# Bundles in the app's store (run-as works for debug builds); empty if there are none.
function Stored-Bundles { ((Adb shell "run-as $package ls files/bundles 2>/dev/null") -join ' ').Trim() }

function Start-App {
    Adb shell am start -S -n "$package/.MainActivity" | Out-Null
    Start-Sleep 5
}

# Runs one view; on failure warns and goes back to Home, so the next view starts clean.
function View([string]$what, [scriptblock]$block) {
    Step $what
    try { & $block } catch {
        $warnings.Add("${what}: $($_.Exception.Message)")
        Write-Warning "${what}: $($_.Exception.Message)"
        HideKeyboard
        1..3 | ForEach-Object { if (-not (Has '^Home$')) { Back } }
    }
}

# --- login ------------------------------------------------------------------

Start-App
View "Login" {
    if (-not (Has 'User name or email')) { throw "the login screen did not appear" }
    Shot "00-login"

    if ($LoginQr -and (Test-Path $LoginQr)) {
        # Log in with the picture of a login QR code, as if the camera had scanned it. The app's
        # debug receiver decodes it in its private folder and deletes it at once: the token is
        # never on a command line or in a log.
        Write-Host "  logging in with the QR code $LoginQr"
        Adb push $LoginQr /data/local/tmp/lf-login.png | Out-Null
        Adb shell chmod 644 /data/local/tmp/lf-login.png
        Adb shell run-as $package cp /data/local/tmp/lf-login.png cache/login.png
        Adb shell rm -f /data/local/tmp/lf-login.png
        Adb shell am broadcast -n "$package/.debug.DemoStateReceiver" --es login_qr login.png | Out-Null
        $deadline = (Get-Date).AddSeconds(20)
        while (-not (Has '^Home$') -and (Get-Date) -lt $deadline) { Start-Sleep 2 }
        if (Has '^Home$') { return }
        $why = (Adb logcat -d -s DemoStateReceiver | Select-Object -Last 1)
        Write-Warning "Login with the QR code did not work ($why); asking for user and password instead."
    }

    $user = $env:LF_SCREENSHOT_USER
    $plain = $env:LF_SCREENSHOT_PASSWORD
    if (-not $user) { $user = Read-Host "ProGeo user name or email (empty: stop after the login screen)" }
    if (-not $user) { throw "no account given; only the login screen was taken" }
    if (-not $plain) {
        $secure = Read-Host "Password for $user" -AsSecureString
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    }
    Field 'User name or email' $user
    Field '^Password$' $plain
    $plain = $null
    Tap '^(Log in|Sign in|Login)$'
    Start-Sleep 4
    if (Has 'Two-factor code') {
        $otp = Read-Host "Two-factor code (authenticator app)"
        Field 'Two-factor code' $otp
        Tap '^(Log in|Sign in|Login|Continue|Verify)$'
        Start-Sleep 4
    }
    if (-not (Has '^Home$')) { throw "login failed (still not on the home screen)" }
}
$loggedIn = Has '^Home$'
if (-not $loggedIn) {
    Write-Warning "Not logged in: only the screens before the login were taken."
} else {

# --- bundles: sync, else import the newest one through the debug hook --------

View "Home" {
    Start-Sleep 10 # first sync after the login
    Shot "01-home"
}

View "Bundle for the screenshots" {
    $stored = Stored-Bundles
    if ($stored) { Write-Host "  synced bundle: $stored"; return }
    Write-Host "  no bundle from the sync: importing the newest one from the release list (shown as unsigned)"
    $channel = Invoke-RestMethod "https://api.progeo.com/firmware/versions/beta"
    $latest = $channel.bundles | Where-Object { $_.id -eq $channel.latest } | Select-Object -First 1
    $zip = Join-Path $work "bundle.zip"
    Invoke-WebRequest ("https://api.progeo.com/firmware/" + $latest.url) -OutFile $zip -UseBasicParsing
    Adb push $zip /data/local/tmp/lf-bundle.zip | Out-Null
    Adb shell chmod 644 /data/local/tmp/lf-bundle.zip
    Adb shell run-as $package cp /data/local/tmp/lf-bundle.zip cache/import.zip
    Adb shell am broadcast -n "$package/.debug.DemoStateReceiver" --es import import.zip | Out-Null
    Start-Sleep 4
    if (-not (Stored-Bundles)) { throw "import failed (see adb logcat -s DemoStateReceiver)" }
    Start-App
    Shot "01b-home-bundle-ready"
}

View "Bundles" { Tab 'Bundles'; Shot "02-bundles" }

# --- profiles ---------------------------------------------------------------

View "Profiles (empty)" { Tab 'Profiles'; Shot "03-profiles-empty" }

View "New profile (config QR code)" {
    Tab 'Profiles'
    Tap '^Scan QR code$'
    Start-Sleep 3
    # The scanner opens at once (on the emulator it asks for the camera first).
    Shot "04-new-profile-scanner" -Viewport
    Back # closing the scanner without a code returns to the list
    if (-not (Has '^Profiles$')) { Back }
}

View "Profile editor" {
    # Stands in for a scanned config QR code, which an emulator cannot scan.
    Adb shell am broadcast -n "$package/.debug.DemoStateReceiver" --es demo_profile demo-01 | Out-Null
    Start-Sleep 2
    Tab 'Profiles'
    Shot "08-profiles-list"
    Tap '^demo-01$'
    Start-Sleep 2
    Shot "05-profile-editor-values"
    # Swipe to the next section card; the card area takes that card's height.
    $card = Find-Node '^Edit '
    if ($card) {
        $null, $y = Center $card
        Adb shell input swipe ([int]($W * 0.8)) $y ([int]($W * 0.15)) $y 300
        Start-Sleep 1
        Shot "05b-profile-editor-next-card"
    }
    Tap '^Lua$'; Shot "06-profile-editor-lua"
    Tap '^JSON$'; Shot "07-profile-editor-json"
    Back
}

View "Read from controller" {
    Tab 'Profiles'
    Tap '^Read from controller$'
    Shot "09-read-from-controller"
    Back
}

# --- flashing ---------------------------------------------------------------

View "Flash setup" {
    Tab 'Profiles'
    Tap '^Flash$'
    Start-Sleep 2
    Shot "10-flash-setup"
    # Device config: one card per section; the pencil opens the section's dialog.
    if (ScrollTo '^Edit ') {
        Tap '^Edit '
        Start-Sleep 1
        Shot "10b-flash-setup-edit" -Viewport
        Tap '^Cancel$'
    }
    if (ScrollTo '^Add router$') {
        Tap '^Add router$'
        Start-Sleep 1
        Shot "10c-flash-setup-router" -Viewport
        Tap '^Cancel$'
    }
    ScrollTop
}

View "Flash wizard" {
    # The wizard screen must be open; the demo states only change what it shows.
    1..3 | ForEach-Object { if (-not (Has '^Home$')) { Back } }
    Tab 'Home'
    Demo 'boot'
    Tap '^(Flash a board|Continue the running flash)$'
    Start-Sleep 2
    $n = 11
    foreach ($state in 'boot', 'syncing', 'writing', 'normal', 'installing', 'done', 'failed') {
        Demo $state
        ScrollTop
        Shot ("{0}-wizard-{1}" -f $n, $state)
        $n++
    }
    Demo 'idle'
    Back
}

# --- the rest ---------------------------------------------------------------

View "Monitor" { Tab 'Monitor'; Shot "19-monitor" }

View "Settings" {
    Tab 'Settings'
    ScrollTop
    Shot "20-settings"
    if (ScrollTo '^Developer mode$') { Tap '^Developer mode$' } # the whole row toggles
    ScrollTop
    Shot "21-settings-developer-mode"
    if (ScrollTo '^Impressum') { Tap '^Impressum'; Shot "22-impressum"; Back }
}
} # logged in

# --- done -------------------------------------------------------------------

Adb shell am broadcast -a com.android.systemui.demo -e command exit | Out-Null
# Leave no session (token) behind on the emulator.
Adb shell pm clear $package | Out-Null
Adb shell rm -f /data/local/tmp/lf-login.png /data/local/tmp/lf-bundle.zip
if ($startedEmulator -and -not $KeepEmulator) {
    Step "Stopping the emulator"
    Adb emu kill | Out-Null
}

# A run that got past the login replaces the whole set (no stale images of renamed views);
# a partial run only adds and overwrites, so a failed login never wipes the last good set.
if ($loggedIn) { Get-ChildItem $Out -Filter *.png | Remove-Item }
Get-ChildItem $staging -Filter *.png | Copy-Item -Destination $Out -Force

Step "Done: $($shots.Count) screenshots in $(Resolve-Path $Out)"
if ($warnings.Count) {
    Write-Host "`nSteps that did not work (the UI may have changed):" -ForegroundColor Yellow
    $warnings | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    exit 1
}
