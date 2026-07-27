<#
.SYNOPSIS
    Roam release. Bumps the version, tags it, and lets GitHub Actions build,
    sign and publish the APKs.

.DESCRIPTION
    The build happens in CI, not here -- so you do not need the keystore or an
    Android SDK on this machine, and every release is reproducible from the tag.

    This script:
      1. checks gh, git and the four signing secrets
      2. bumps versionCode / versionName in app/build.gradle.kts
      3. commits, creates an ANNOTATED tag whose message is the release notes,
         and pushes
      4. watches the Actions run and prints the release URL

    .github/workflows/release.yml takes it from there and always publishes with
    --latest, never as a pre-release, so the in-app updater sees every build.

    One-time setup: ./setup-secrets.ps1

.PARAMETER Notes
    Release notes (positional). Becomes the tag annotation and the release body.
    If omitted, generated from git log since the last tag.

.PARAMETER Bump
    minor | major. Default minor. There is no patch: the patch digit is the
    commit count, which CI fills in, so `0.1` becomes `0.1.<commits>` on every
    build. This flag only moves the part you actually choose.

.PARAMETER SetVersion
    Explicit base, e.g. 2.0. Overrides -Bump.

.PARAMETER Verify
    Run ./gradlew assembleDebug locally first. Catches a compile error before
    it burns a version number and a CI run. Needs a local Android SDK.

.PARAMETER NoWatch
    Push and exit without following the Actions run.

.PARAMETER DryRun
    Print the plan. Change nothing, push nothing.

.EXAMPLE
    ./push.ps1 "Fixes album art on FLAC, adds shuffle to artist rows"

.EXAMPLE
    ./push.ps1 -Bump minor "Network drive support"

.EXAMPLE
    ./push.ps1 -Verify "Risky refactor"
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Notes = "",

    [ValidateSet('minor', 'major')]
    [string] $Bump = 'minor',

    [string] $SetVersion,

    [switch] $Verify,
    [switch] $NoWatch,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# PowerShell 7.4+ makes non-zero native exit codes throw when EAP is Stop.
# This script checks $LASTEXITCODE itself and gives better messages, so opt out.
if (Get-Variable PSNativeCommandUseErrorActionPreference -Scope Global -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

# ----------------------------------------------------------------------------
$GradleFile      = 'app/build.gradle.kts'
$MainBranch      = @('main', 'master')
$ReleaseWorkflow = 'release.yml'
$RequiredSecrets = @('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')

function Write-Step { param($m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok   { param($m) Write-Host "    $([char]0x2713) $m" -ForegroundColor Green }
function Write-Warn { param($m) Write-Host "    ! $m" -ForegroundColor Yellow }
function Write-Info { param($m) Write-Host "    $m" -ForegroundColor DarkGray }
function Fail       { param($m) throw $m }

$script:RollbackGradle = $null
$script:RollbackTag    = $null

function Invoke-Rollback {
    if ($script:RollbackGradle) {
        Set-Content -Path $GradleFile -Value $script:RollbackGradle -NoNewline -Encoding UTF8
        Write-Warn "Reverted $GradleFile"
    }
    if ($script:RollbackTag) {
        git tag -d $script:RollbackTag 2>&1 | Out-Null
        Write-Warn "Deleted local tag $script:RollbackTag"
    }
}

# ============================================================================
try {

# ----------------------------------------------------------------------------
# 1. Preflight
# ----------------------------------------------------------------------------
Write-Step 'Preflight'

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail 'git is not on PATH.' }
if (-not (Get-Command gh  -ErrorAction SilentlyContinue)) { Fail 'GitHub CLI (gh) is not on PATH. https://cli.github.com' }

git rev-parse --is-inside-work-tree 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'Not inside a git repository.' }

$repoRoot = (git rev-parse --show-toplevel).Trim()
Set-Location $repoRoot
Write-Ok "Repo root: $repoRoot"

gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'gh is not authenticated. Run: gh auth login' }

$slug = (gh repo view --json nameWithOwner --jq .nameWithOwner 2>&1)
if ($LASTEXITCODE -ne 0) { Fail 'No GitHub remote. Run: gh repo create roam --source=. --remote=origin --push' }
$repoSlug = "$slug".Trim()
Write-Ok "GitHub: $repoSlug"

if (-not (Test-Path $GradleFile)) { Fail "$GradleFile not found." }
if (-not (Test-Path ".github/workflows/$ReleaseWorkflow")) {
    Fail ".github/workflows/$ReleaseWorkflow is missing -- nothing would build the release."
}

# The workflow cannot sign without these. Better to find out now than after
# the tag is already pushed.
$secretList = (gh secret list --json name --jq '.[].name' 2>&1)
if ($LASTEXITCODE -eq 0) {
    $have = @("$secretList" -split "`n" | ForEach-Object { $_.Trim() })
    $missing = @($RequiredSecrets | Where-Object { $_ -notin $have })
    if ($missing.Count -gt 0) {
        Fail @"
Missing repository secrets: $($missing -join ', ')

The release workflow signs the APK with these. Run the one-time setup:

    ./setup-secrets.ps1
"@
    }
    Write-Ok 'Signing secrets present'
} else {
    Write-Warn 'Could not list secrets (insufficient token scope?). Continuing.'
}

$dirty = git status --porcelain
if ($dirty -and -not $DryRun) {
    Write-Host ''
    git status --short
    Fail 'Working tree is not clean. Commit or stash first.'
}
Write-Ok 'Working tree clean'

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -notin $MainBranch) { Write-Warn "On branch '$branch', not $($MainBranch -join '/')." }

# ----------------------------------------------------------------------------
# 2. Optional local compile check
# ----------------------------------------------------------------------------
if ($Verify -and -not $DryRun) {
    Write-Step 'Local compile check'
    $gradlew = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
    if (-not (Test-Path $gradlew)) { Fail "$gradlew not found. Run: gradle wrapper --gradle-version 8.9" }
    & $gradlew assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { Fail 'Local debug build failed. Nothing was bumped or tagged.' }
    Write-Ok 'Compiles'
}

# ----------------------------------------------------------------------------
# 3. Version
# ----------------------------------------------------------------------------
Write-Step 'Version'

$gradleText = Get-Content $GradleFile -Raw
$script:RollbackGradle = $gradleText

$mName = [regex]::Match($gradleText, '(?m)^\s*versionName\s*=\s*"(\d+)\.(\d+)')
if (-not $mName.Success) { Fail "Could not find 'versionName = `"x.y...`"' in $GradleFile" }

$oldMaj = [int]$mName.Groups[1].Value
$oldMin = [int]$mName.Groups[2].Value
Write-Info "current base: $oldMaj.$oldMin"

if ($SetVersion) {
    if ($SetVersion -notmatch '^(\d+)\.(\d+)') { Fail "-SetVersion must start x.y, got '$SetVersion'" }
    $maj = [int]$Matches[1]; $min = [int]$Matches[2]
} else {
    $maj = $oldMaj; $min = $oldMin
    switch ($Bump) {
        'major' { $maj++; $min = 0 }
        'minor' { $min++ }
    }
}

# The patch digit is the commit count, so the tag matches exactly what CI will
# build. This script adds precisely one commit, hence +1.
$newCode = [int](git rev-list --count HEAD).Trim() + 1
$newName = "$maj.$min.$newCode"
$tag     = "v$newName"

Write-Ok "new:     $newName (versionCode $newCode)  ->  tag $tag"
Write-Info 'versionCode is the commit count -- never hand-edited, always increasing'

if (git tag --list $tag) { Fail "Tag $tag already exists locally." }

# ----------------------------------------------------------------------------
# 4. Release notes
# ----------------------------------------------------------------------------
Write-Step 'Release notes'

if ([string]::IsNullOrWhiteSpace($Notes)) {
    $describe = (git describe --tags --abbrev=0 2>&1)
    $lastTag  = if ($LASTEXITCODE -eq 0) { "$describe".Trim() } else { $null }
    $range    = if ($lastTag) { "$lastTag..HEAD" } else { 'HEAD' }
    $log      = @(git log $range --no-merges --pretty=format:'- %s')
    $Notes    = if ($log.Count -gt 0) { ($log -join "`n") } else { 'Maintenance release.' }
    Write-Info "generated from git log ($range)"
} else {
    Write-Info 'supplied on the command line'
}

Write-Host ''
Write-Host (@($Notes -split "`n" | ForEach-Object { "      $_" })) -Separator "`n" -ForegroundColor DarkGray

# ----------------------------------------------------------------------------
if ($DryRun) {
    Write-Step 'Dry run - plan only'
    Write-Info "bump     $oldName ($oldCode)  ->  $newName ($newCode)"
    Write-Info "commit   chore: release $newName"
    Write-Info "tag      $tag  (annotated, notes as the message)"
    Write-Info "push     origin $branch --follow-tags"
    Write-Info "CI       $ReleaseWorkflow builds, signs, publishes with --latest"
    Write-Host "`nNothing was changed.`n" -ForegroundColor Cyan
    $script:RollbackGradle = $null
    exit 0
}

# ----------------------------------------------------------------------------
# 5. Bump, commit, tag, push
# ----------------------------------------------------------------------------
Write-Step 'Applying version'

# Only the major.minor base is stored; both workflows append the commit count.
$updated = [regex]::Replace($gradleText, '(?m)^(\s*versionName\s*=\s*)"[^"]+"', "`${1}`"$maj.$min.0`"")
Set-Content -Path $GradleFile -Value $updated -NoNewline -Encoding UTF8
Write-Ok "$GradleFile updated"

Write-Step 'Git'

git add $GradleFile
git commit -m "chore: release $newName" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'git commit failed.' }
Write-Ok "committed: chore: release $newName"

# The tag annotation IS the release body -- the workflow reads it back with
# `git tag -l --format='%(contents)'`. Notes therefore live in git rather than
# in a file that can drift from the code it describes.
$tagMsgFile = Join-Path ([IO.Path]::GetTempPath()) "roam-tagmsg-$newCode.txt"
Set-Content -Path $tagMsgFile -Value $Notes -Encoding UTF8
git tag -a $tag -F $tagMsgFile
$tagExit = $LASTEXITCODE
Remove-Item $tagMsgFile -ErrorAction SilentlyContinue
if ($tagExit -ne 0) { Fail 'git tag failed.' }
$script:RollbackTag = $tag
Write-Ok "tagged $tag"

git push origin $branch --follow-tags
if ($LASTEXITCODE -ne 0) { Fail 'git push failed.' }
Write-Ok "pushed to origin/$branch"
$script:RollbackTag    = $null
$script:RollbackGradle = $null

# ----------------------------------------------------------------------------
# 6. Follow the build
# ----------------------------------------------------------------------------
$actionsUrl = "https://github.com/$repoSlug/actions"

if ($NoWatch) {
    Write-Host "`n  Tag pushed. CI is building.`n  $actionsUrl`n" -ForegroundColor Cyan
    exit 0
}

Write-Step 'Waiting for GitHub Actions'

$runId = $null
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 4
    $raw = (gh run list --workflow $ReleaseWorkflow --limit 10 --json databaseId,headBranch,status 2>&1)
    if ($LASTEXITCODE -eq 0) {
        try {
            $runs = @("$raw" | ConvertFrom-Json)
            $match = $runs | Where-Object { $_.headBranch -eq $tag } | Select-Object -First 1
            if ($match) { $runId = $match.databaseId; break }
        } catch { }
    }
    Write-Info "waiting for the run to appear... ($($i + 1))"
}

if (-not $runId) {
    Write-Warn 'Could not find the workflow run. It may still be queuing.'
    Write-Host "`n  $actionsUrl`n" -ForegroundColor Cyan
    exit 0
}

Write-Ok "run $runId"
Write-Host ''
gh run watch $runId --exit-status --compact
$watchExit = $LASTEXITCODE

if ($watchExit -ne 0) {
    Write-Host ''
    Write-Warn 'The release build failed. The tag is already pushed, so fix and re-run:'
    Write-Info  "gh workflow run $ReleaseWorkflow -f tag=$tag"
    Write-Host "`n  gh run view $runId --log-failed`n" -ForegroundColor Cyan
    exit 1
}

$viewed = (gh release view $tag --json url --jq .url 2>&1)
$url    = if ($LASTEXITCODE -eq 0) { "$viewed".Trim() } else { $null }

Write-Host ''
Write-Host "  Roam $newName published as Latest" -ForegroundColor Green
if ($url) { Write-Host "  $url" -ForegroundColor Cyan }
Write-Host "  Devices on auto-update pick it up within 24h, or tap Check now in Settings.`n" -ForegroundColor DarkGray

}
catch {
    Invoke-Rollback
    Write-Host "`nFAILED: $($_.Exception.Message)`n" -ForegroundColor Red
    exit 1
}
