<#
.SYNOPSIS
    One-time setup: push the signing keystore into GitHub Actions secrets so CI
    can produce installable release builds.

.DESCRIPTION
    Uploads four repository secrets that .github/workflows/release.yml consumes:

        KEYSTORE_BASE64      the .jks, base64-encoded
        KEYSTORE_PASSWORD
        KEY_ALIAS
        KEY_PASSWORD

    Run this once, and again only if you rotate the key.

    A warning worth repeating: the signing key must never change. Android
    refuses to install an update signed with a different key, and recovering
    means uninstalling -- which takes the Roam database with it. Keep an
    offline backup of the .jks and its passwords.

.PARAMETER Keystore
    Path to the .jks. Defaults to roam-release.jks in the repo root.

.PARAMETER PropertiesFile
    keystore.properties to read the passwords and alias from. If absent, the
    script prompts for them.

.PARAMETER Create
    Generate a new keystore first, via keytool. Refuses to overwrite one that
    already exists.

.EXAMPLE
    ./setup-secrets.ps1

.EXAMPLE
    ./setup-secrets.ps1 -Create
#>

[CmdletBinding()]
param(
    [string] $Keystore = 'roam-release.jks',
    [string] $PropertiesFile = 'keystore.properties',
    [switch] $Create
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Get-Variable PSNativeCommandUseErrorActionPreference -Scope Global -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

<#
    keytool ships with every JDK but is rarely on PATH on Windows. Android
    Studio bundles one as the JetBrains Runtime, which is the JDK 17 this
    project already builds against -- so look there rather than making the
    user edit their PATH for a one-off chore.
#>
function Find-Keytool {
    $onPath = Get-Command keytool -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $exe = if ($env:OS -eq 'Windows_NT') { 'keytool.exe' } else { 'keytool' }

    $candidates = @(
        (Join-Path $env:JAVA_HOME "bin/$exe")
        "$env:ProgramFiles/Android/Android Studio/jbr/bin/$exe"
        "$env:ProgramFiles/Android/Android Studio/jre/bin/$exe"
        "${env:ProgramFiles(x86)}/Android/Android Studio/jbr/bin/$exe"
        "$env:LOCALAPPDATA/Programs/Android Studio/jbr/bin/$exe"
        "$env:LOCALAPPDATA/JetBrains/Toolbox/apps/AndroidStudio/ch-0/*/jbr/bin/$exe"
        "$env:ProgramFiles/Eclipse Adoptium/jdk*/bin/$exe"
        "$env:ProgramFiles/Java/jdk*/bin/$exe"
        "$env:ProgramFiles/Microsoft/jdk*/bin/$exe"
        "/usr/lib/jvm/*/bin/$exe"
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/$exe"
    ) | Where-Object { $_ -and $_ -notmatch '^\\bin' }

    foreach ($c in $candidates) {
        $hit = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

function Write-Step { param($m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok   { param($m) Write-Host "    $([char]0x2713) $m" -ForegroundColor Green }
function Write-Info { param($m) Write-Host "    $m" -ForegroundColor DarkGray }
function Fail       { param($m) Write-Host "`nFAILED: $m`n" -ForegroundColor Red; exit 1 }

# ----------------------------------------------------------------------------
Write-Step 'Preflight'

if (-not (Get-Command gh  -ErrorAction SilentlyContinue)) { Fail 'GitHub CLI (gh) is not on PATH.' }
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail 'git is not on PATH.' }

git rev-parse --is-inside-work-tree 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'Not inside a git repository.' }
Set-Location (git rev-parse --show-toplevel).Trim()

gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'gh is not authenticated. Run: gh auth login' }

$slug = (gh repo view --json nameWithOwner --jq .nameWithOwner 2>&1)
if ($LASTEXITCODE -ne 0) { Fail 'No GitHub remote. Run: gh repo create roam --source=. --remote=origin --push' }
Write-Ok "Repo: $("$slug".Trim())"

# ----------------------------------------------------------------------------
# Optionally generate the keystore
# ----------------------------------------------------------------------------
if ($Create) {
    Write-Step 'Generating keystore'
    if (Test-Path $Keystore) { Fail "$Keystore already exists. Refusing to overwrite a signing key." }
    $keytool = Find-Keytool
    if (-not $keytool) {
        Fail @'
Could not find keytool.

It ships with every JDK. If Android Studio is installed, it is usually at:
    C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe

Point the script at it for this run:
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    ./setup-secrets.ps1 -Create
'@
    }
    Write-Info "keytool: $keytool"

    $alias = Read-Host 'Key alias [roam]'
    if ([string]::IsNullOrWhiteSpace($alias)) { $alias = 'roam' }

    $pw1 = Read-Host 'Keystore password' -AsSecureString
    $pw2 = Read-Host 'Confirm password'  -AsSecureString
    $p1 = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw1))
    $p2 = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw2))
    if ($p1 -ne $p2) { Fail 'Passwords do not match.' }
    if ($p1.Length -lt 6) { Fail 'keytool requires at least 6 characters.' }

    & $keytool -genkeypair -v -keystore $Keystore -alias $alias `
              -keyalg RSA -keysize 4096 -validity 10000 `
              -storepass $p1 -keypass $p1 `
              -dname "CN=Roam, OU=Personal, O=Roam, C=GB"
    if ($LASTEXITCODE -ne 0) { Fail 'keytool failed.' }
    Write-Ok "Created $Keystore"

    @(
        "storeFile=../$Keystore"
        "storePassword=$p1"
        "keyAlias=$alias"
        "keyPassword=$p1"
    ) | Set-Content -Path $PropertiesFile -Encoding UTF8
    Write-Ok "Wrote $PropertiesFile (gitignored)"

    Write-Host ''
    Write-Host '  BACK THIS UP NOW.' -ForegroundColor Yellow
    Write-Host "  $Keystore plus that password are the only way you will ever" -ForegroundColor DarkGray
    Write-Host '  be able to ship an update to an installed Roam.' -ForegroundColor DarkGray
}

# ----------------------------------------------------------------------------
# Read credentials
# ----------------------------------------------------------------------------
Write-Step 'Reading signing config'

if (-not (Test-Path $Keystore)) {
    Fail "$Keystore not found. Generate one with: ./setup-secrets.ps1 -Create"
}

$storePassword = $null; $keyAlias = $null; $keyPassword = $null

if (Test-Path $PropertiesFile) {
    foreach ($line in Get-Content $PropertiesFile) {
        if ($line -match '^\s*storePassword\s*=\s*(.+)$') { $storePassword = $Matches[1].Trim() }
        if ($line -match '^\s*keyAlias\s*=\s*(.+)$')      { $keyAlias      = $Matches[1].Trim() }
        if ($line -match '^\s*keyPassword\s*=\s*(.+)$')   { $keyPassword   = $Matches[1].Trim() }
    }
    Write-Ok "Read $PropertiesFile"
} else {
    Write-Info "$PropertiesFile not found -- prompting"
}

if (-not $keyAlias) {
    $keyAlias = Read-Host 'Key alias [roam]'
    if ([string]::IsNullOrWhiteSpace($keyAlias)) { $keyAlias = 'roam' }
}
if (-not $storePassword) {
    $s = Read-Host 'Keystore password' -AsSecureString
    $storePassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
}
if (-not $keyPassword) { $keyPassword = $storePassword }

# Fail here rather than in CI, where the error is a wall of Gradle output.
$keytool = Find-Keytool
if (-not $keytool) { Fail 'Could not find keytool. Set $env:JAVA_HOME to a JDK (Android Studio bundles one at ...\Android Studio\jbr).' }
& $keytool -list -keystore $Keystore -alias $keyAlias -storepass $storePassword 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "keytool could not open $Keystore with alias '$keyAlias' and that password." }
Write-Ok 'Keystore opens with these credentials'

# ----------------------------------------------------------------------------
# Upload
# ----------------------------------------------------------------------------
Write-Step 'Uploading secrets'

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $Keystore)))
$tmp = Join-Path ([IO.Path]::GetTempPath()) 'roam-keystore.b64'
Set-Content -Path $tmp -Value $b64 -NoNewline -Encoding ASCII

try {
    Get-Content $tmp -Raw | gh secret set KEYSTORE_BASE64
    if ($LASTEXITCODE -ne 0) { Fail 'gh secret set KEYSTORE_BASE64 failed.' }
    Write-Ok ("KEYSTORE_BASE64      ({0:N0} KB)" -f ($b64.Length / 1KB))
} finally {
    Remove-Item $tmp -ErrorAction SilentlyContinue
}

gh secret set KEYSTORE_PASSWORD --body $storePassword
if ($LASTEXITCODE -ne 0) { Fail 'gh secret set KEYSTORE_PASSWORD failed.' }
Write-Ok 'KEYSTORE_PASSWORD'

gh secret set KEY_ALIAS --body $keyAlias
if ($LASTEXITCODE -ne 0) { Fail 'gh secret set KEY_ALIAS failed.' }
Write-Ok 'KEY_ALIAS'

gh secret set KEY_PASSWORD --body $keyPassword
if ($LASTEXITCODE -ne 0) { Fail 'gh secret set KEY_PASSWORD failed.' }
Write-Ok 'KEY_PASSWORD'

Write-Host ''
gh secret list
Write-Host ''
Write-Host '  CI can now sign release builds.' -ForegroundColor Green
Write-Host '  Cut your first release with:  ./push.ps1 "Initial release"' -ForegroundColor DarkGray
Write-Host ''
