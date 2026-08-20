<#
.SYNOPSIS
    Capture the next crash from the phone and surface the useful part.

.DESCRIPTION
    The crash buffer is a ring, so a crash from an hour ago may already have
    been pushed out by other apps. This clears it first, waits for you to
    reproduce, then dumps -- which is the difference between catching the
    crash and catching whatever happened to still be in there.

        ./crash.ps1              clear, wait, capture, summarise
        ./crash.ps1 -NoClear     capture what is already in the buffer

    The full log lands in crash.log (gitignored). The stack trace is printed,
    because that is the bit worth pasting.

.PARAMETER NoClear
    Do not clear the buffer first. Use when the app has already crashed and
    you would rather not reproduce it again.

.PARAMETER Package
    Filter to one package. Defaults to Roam.
#>

[CmdletBinding()]
param(
    [switch] $NoClear,
    [string] $Package = 'app.roam'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Get-Variable PSNativeCommandUseErrorActionPreference -Scope Global -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Write-Step { param($m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok   { param($m) Write-Host "    $([char]0x2713) $m" -ForegroundColor Green }
function Write-Info { param($m) Write-Host "    $m" -ForegroundColor DarkGray }
function Fail       { param($m) Write-Host "`nFAILED: $m`n" -ForegroundColor Red; exit 1 }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail 'adb is not on PATH. It ships with Android Studio under platform-tools.'
}

# ----------------------------------------------------------------------------
# Device
# ----------------------------------------------------------------------------
Write-Step 'Looking for a device'

$devices = @(adb devices | Select-Object -Skip 1 |
             Where-Object { $_ -match '\S' -and $_ -notmatch 'offline' })

if ($devices.Count -eq 0) {
    Fail @'
No device. Check that:
  - the phone is plugged in
  - USB debugging is on (Settings > Developer options)
  - the "Allow USB debugging?" prompt on the phone was accepted
'@
}
if ($devices.Count -gt 1) {
    Write-Info "$($devices.Count) devices connected; adb will use the first."
}
Write-Ok ($devices[0] -split '\s+')[0]

# ----------------------------------------------------------------------------
# Capture
# ----------------------------------------------------------------------------
if (-not $NoClear) {
    # Both buffers: a native crash or an early startup failure can land in main
    # without ever reaching the crash buffer.
    adb logcat -b crash -c 2>&1 | Out-Null
    adb logcat -b main  -c 2>&1 | Out-Null
    Write-Ok 'buffers cleared'

    Write-Host ''
    Write-Host '    Open Roam on the phone now and let it crash.' -ForegroundColor Yellow
    Read-Host  '    Press Enter once it has'
}

Write-Step 'Reading the log'

$crash = @(adb logcat -b crash -d 2>&1)
$main  = @(adb logcat -b main -d 2>&1 | Where-Object {
    $_ -match 'AndroidRuntime|FATAL|E ActivityManager|Process.*died' -or $_ -match $Package
})

$all = @('=== crash buffer ===') + $crash + @('', '=== main buffer (filtered) ===') + $main
$path = Join-Path (Get-Location) 'crash.log'
$all | Set-Content -Path $path -Encoding UTF8
Write-Ok "full log written to crash.log ($($all.Count) lines)"

# ----------------------------------------------------------------------------
# The part worth reading
# ----------------------------------------------------------------------------
# Everything from FATAL EXCEPTION to the end of that trace. Continuation lines
# start with tab-at or "Caused by", so the trace ends at the first line that is
# neither -- printing the whole buffer instead would bury it.
$trace = @()
$inTrace = $false
foreach ($line in $crash) {
    if ($line -match 'FATAL EXCEPTION') { $inTrace = $true }
    if ($inTrace) {
        $trace += $line
        if ($trace.Count -gt 120) { break }
    }
}

if ($trace.Count -eq 0) {
    Write-Step 'No crash found'
    Write-Info 'The crash buffer is empty. Either it did not crash, or it failed'
    Write-Info 'before reaching the Java layer. Check crash.log for the main buffer.'
    Write-Info ''
    Write-Info 'If the app closes with no crash at all, it may be being killed'
    Write-Info 'rather than crashing -- try: adb logcat -d | Select-String "roam"'
    exit 0
}

Write-Step 'Crash'
Write-Host ''
$trace | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
Write-Host ''
Write-Info 'Paste the above. The full log is in crash.log.'
Write-Host ''
