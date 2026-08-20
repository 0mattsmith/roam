<#
.SYNOPSIS
    Build a release APK locally and push it to the phone over adb.

.DESCRIPTION
    The fast loop when a device is connected: no push, no CI, no waiting for a
    release to be published.

        ./install.ps1            build release, install over the top
        ./install.ps1 -NoBuild   install whatever was last built

    Release by default, because that is the copy holding your library.

    Debug is SAFE but separate: applicationIdSuffix makes it
    app.roam.player.debug, a second app installed alongside with its own empty
    database. Useful for trying something risky, useless for testing against
    real data.

    What this script will never do is uninstall to get past a signature
    mismatch. That takes the database with it -- loved flags, play counts and
    every metadata correction.

.PARAMETER NoBuild
    Skip Gradle and install the existing APK.

.PARAMETER Variant
    Build variant. Release by default, and there is rarely a good reason to
    change it while the phone holds a release-signed copy.
#>

[CmdletBinding()]
param(
    [switch] $NoBuild,
    [ValidateSet('release', 'debug')]
    [string] $Variant = 'release'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Get-Variable PSNativeCommandUseErrorActionPreference -Scope Global -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Write-Step { param($m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok   { param($m) Write-Host "    $([char]0x2713) $m" -ForegroundColor Green }
function Write-Warn { param($m) Write-Host "    ! $m" -ForegroundColor Yellow }
function Write-Info { param($m) Write-Host "    $m" -ForegroundColor DarkGray }
function Fail       { param($m) Write-Host "`nFAILED: $m`n" -ForegroundColor Red; exit 1 }

Set-Location (git rev-parse --show-toplevel).Trim()

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail 'adb is not on PATH.' }

# ----------------------------------------------------------------------------
# Signing
# ----------------------------------------------------------------------------
# Checked BEFORE building, because a release build without it falls back to
# unsigned and the install fails ten minutes later for a reason that looks
# nothing like the cause.
if ($Variant -eq 'release' -and -not (Test-Path 'keystore.properties')) {
    Fail @'
keystore.properties is missing, so a release build cannot be signed with the
key already on the phone.

Restore it before building. Installing anything signed differently means
uninstalling first, which destroys the database.
'@
}

if ($Variant -eq 'debug') {
    # applicationIdSuffix keeps this out of the release app's way entirely.
    Write-Info 'Debug installs alongside as app.roam.player.debug,'
    Write-Info 'with its own empty library. It does not touch the release copy.'
}

# ----------------------------------------------------------------------------
# Android SDK
# ----------------------------------------------------------------------------
# CI never hits this: the GitHub runner sets ANDROID_HOME for us. Locally the
# path lives in local.properties, which is gitignored because it is an absolute
# path on one machine -- so a fresh clone has no idea where the SDK is, and
# Gradle's own error for it is easy to misread as a project problem.
if (-not (Test-Path 'local.properties') -and
    -not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {

    $guess = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $guess) {
        Write-Step 'Android SDK'
        Write-Info "found at $guess"
        # Backslashes and the drive colon are escaped: local.properties is a
        # Java properties file, where both are otherwise special.
        $escaped = $guess -replace '\\', '\\\\' -replace ':', '\:'
        "sdk.dir=$escaped" | Set-Content -Path 'local.properties' -Encoding ASCII
        Write-Ok 'local.properties written (gitignored, machine specific)'
    } else {
        Fail @'
No Android SDK. Gradle needs to know where it is, and nothing here tells it.

Either set ANDROID_HOME, or write local.properties with the path, escaping
the backslashes and the colon:

    sdk.dir=C\:\\Users\\Matt\\AppData\\Local\\Android\\Sdk

Android Studio writes this file itself the first time it opens a project, so
opening Roam in it once is the other way to fix this.
'@
    }
}

# ----------------------------------------------------------------------------
# Wrapper
# ----------------------------------------------------------------------------
# CI generates this on the fly, so its absence goes unnoticed until someone
# tries to build locally for the first time.
if (-not (Test-Path 'gradle/wrapper/gradle-wrapper.jar')) {
    Write-Step 'Gradle wrapper missing'
    if (Get-Command gradle -ErrorAction SilentlyContinue) {
        Write-Info 'generating with the Gradle on PATH...'
        gradle wrapper --gradle-version 8.9
        if ($LASTEXITCODE -ne 0) { Fail 'Could not generate the wrapper.' }
        Write-Ok 'wrapper created - commit gradle/wrapper/gradle-wrapper.jar'
    } else {
        Fail @'
gradle/wrapper/gradle-wrapper.jar is absent and no gradle is on PATH.

Android Studio ships one. Either add it to PATH and re-run, or generate the
wrapper once from a terminal that has it:

    gradle wrapper --gradle-version 8.9

Then commit the jar so this never comes up again.
'@
    }
}

# ----------------------------------------------------------------------------
# Device
# ----------------------------------------------------------------------------
Write-Step 'Looking for a device'
$devices = @(adb devices | Select-Object -Skip 1 |
             Where-Object { $_ -match '\S' -and $_ -notmatch 'offline' })
if ($devices.Count -eq 0) { Fail 'No device. Plug in, or reconnect wireless debugging.' }
Write-Ok ($devices[0] -split '\s+')[0]

# ----------------------------------------------------------------------------
# Build
# ----------------------------------------------------------------------------
$task = if ($Variant -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
$apk  = "app/build/outputs/apk/$Variant/app-$Variant.apk"

if (-not $NoBuild) {
    # Same rule CI uses: versionCode is the commit count, so it only ever goes
    # up. Left unset it defaults to 1, and Android rejects that as a downgrade
    # against whatever is already installed.
    $env:ROAM_VERSION_CODE = (git rev-list --count HEAD).Trim()
    Write-Step "Building $Variant (versionCode $($env:ROAM_VERSION_CODE))"

    ./gradlew $task --console=plain
    if ($LASTEXITCODE -ne 0) { Fail 'Build failed.' }
    Write-Ok 'built'
}

if (-not (Test-Path $apk)) { Fail "No APK at $apk" }
Write-Info ("{0:N1} MB" -f ((Get-Item $apk).Length / 1MB))

# ----------------------------------------------------------------------------
# Install
# ----------------------------------------------------------------------------
Write-Step 'Installing'

# -r replaces in place and KEEPS THE DATA. Never -t, never a bare install
# after an uninstall: the database is the thing being protected here.
$package = if ($Variant -eq 'debug') { 'app.roam.player.debug' } else { 'app.roam.player' }
$out = (adb install -r $apk 2>&1 | Out-String)

if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
    Write-Host ''
    Fail @'
The installed copy was signed with a different key.

DO NOT uninstall to get past this: the database goes with it, and that is
every loved flag, play count and metadata correction.

Debug and release cannot collide -- the suffix keeps them apart -- so this
means keystore.properties does not hold the key CI signed with. Install a
release from GitHub to get running again, and fix the local signing config
before building here.
'@
}

if ($out -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
    Write-Host ''
    Fail @'
This build has a lower versionCode than the one installed.

That usually means ROAM_VERSION_CODE was not set, so it defaulted to 1. This
script sets it from the commit count, so if you are seeing this, the installed
copy came from a later commit than the one checked out.
'@
}

if ($out -notmatch 'Success') {
    Write-Host $out
    Fail 'Install failed. The adb output is above.'
}

Write-Ok 'installed, data intact'

Write-Step 'Launching'
adb shell monkey -p $package -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Write-Ok 'started'
Write-Host ''
Write-Info 'If it closes again, run ./crash.ps1'
Write-Host ''
