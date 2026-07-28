<#
.SYNOPSIS
  Generates the missing gradle/wrapper/gradle-wrapper.jar.

.DESCRIPTION
  The JAR was never committed, so CI regenerates it on every run and ./gradlew
  does not work locally at all. Generating it needs a Gradle that already
  exists, and `gradle` is not on PATH after an Android Studio install -- Studio
  drives Gradle through the wrapper rather than a CLI copy.

  Any project ever built leaves a full distribution under
  ~/.gradle/wrapper/dists/<name>/<hash>/gradle-<version>/bin, which is what this
  looks for first.
#>
[CmdletBinding()]
param(
    [string] $GradleVersion = '8.9'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$jar  = Join-Path $repo 'gradle/wrapper/gradle-wrapper.jar'

if (Test-Path $jar) {
    Write-Host "already present: $jar" -ForegroundColor Green
    exit 0
}

function Find-Gradle {
    $onPath = Get-Command gradle -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $roots = @(
        (Join-Path $env:USERPROFILE '.gradle\wrapper\dists'),
        (Join-Path $env:USERPROFILE '.gradle\caches\build-cache-1')
    ) | Where-Object { Test-Path $_ }

    foreach ($root in $roots) {
        $found = Get-ChildItem $root -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
                 Where-Object { $_.DirectoryName -match '[\\/]bin$' } |
                 Sort-Object { $_.FullName } -Descending |
                 Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

$gradle = Find-Gradle
if (-not $gradle) {
    Write-Host @"
No Gradle found.

  Cached distributions live under:
    $env:USERPROFILE\.gradle\wrapper\dists

  If that folder is empty, open the project in Android Studio and let it sync
  once -- that downloads a distribution -- then run this again.

  CI regenerates the wrapper itself, so this is only blocking local builds.
"@ -ForegroundColor Yellow
    exit 1
}

Write-Host "using: $gradle" -ForegroundColor Cyan
Push-Location $repo
try {
    & $gradle wrapper --gradle-version $GradleVersion
    if ($LASTEXITCODE -ne 0) { throw "gradle wrapper exited $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (Test-Path $jar) {
    Write-Host "`ncreated $jar" -ForegroundColor Green
    Write-Host "commit it:  git add gradle/wrapper/ && ./commit.ps1 `"commit the gradle wrapper`"" -ForegroundColor Cyan
} else {
    Write-Host "gradle ran but the jar is still missing" -ForegroundColor Red
    exit 1
}
