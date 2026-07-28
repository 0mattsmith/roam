<#
.SYNOPSIS
    Commit, push, watch CI, and print the failure if it goes red.

.DESCRIPTION
    The development loop. Use this for ordinary work.

        ./commit.ps1     stage everything, push, follow ci.yml     <- day to day
        ./push.ps1       bump version, tag, publish a signed APK   <- releases only

    On a red build it pulls the failed log and surfaces just the error lines,
    so you can paste those rather than hunting through the Actions UI.

.PARAMETER Message
    Commit message (positional). If omitted, generated from the changed files.

.PARAMETER Amend
    Fold the changes into the previous commit and force-push with
    --force-with-lease. Handy in a fix-the-build loop where you would otherwise
    end up with eleven commits called "fix ci". Do not use on a shared branch.

.PARAMETER NoWatch
    Push and exit without following the run.

.PARAMETER DryRun
    Show what would be committed. Change nothing, push nothing.

.EXAMPLE
    ./commit.ps1 "Rename Dagger providers off Java reserved words"

.EXAMPLE
    ./commit.ps1 -Amend "Fix Media3 callback signatures"

.EXAMPLE
    ./commit.ps1 -DryRun
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Message = "",

    [switch] $Amend,
    [switch] $NoWatch,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Get-Variable PSNativeCommandUseErrorActionPreference -Scope Global -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$CiWorkflow = 'ci.yml'

function Write-Step { param($m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok   { param($m) Write-Host "    $([char]0x2713) $m" -ForegroundColor Green }
function Write-Warn { param($m) Write-Host "    ! $m" -ForegroundColor Yellow }
function Write-Info { param($m) Write-Host "    $m" -ForegroundColor DarkGray }
function Fail       { param($m) Write-Host "`nFAILED: $m`n" -ForegroundColor Red; exit 1 }

# ----------------------------------------------------------------------------
# Preflight
# ----------------------------------------------------------------------------
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail 'git is not on PATH.' }
if (-not (Get-Command gh  -ErrorAction SilentlyContinue)) { Fail 'GitHub CLI (gh) is not on PATH.' }

git rev-parse --is-inside-work-tree 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'Not inside a git repository.' }
Set-Location (git rev-parse --show-toplevel).Trim()

$branch = (git rev-parse --abbrev-ref HEAD).Trim()

$slug = (gh repo view --json nameWithOwner --jq .nameWithOwner 2>&1)
if ($LASTEXITCODE -ne 0) { Fail 'No GitHub remote. Run: gh repo create roam --source=. --remote=origin --push' }
$repoSlug = "$slug".Trim()

# ----------------------------------------------------------------------------
# What is changing
# ----------------------------------------------------------------------------
Write-Step "Changes on $branch"

$status = @(git status --porcelain)
if ($status.Count -eq 0 -and -not $Amend) {
    Write-Info 'Nothing to commit.'
    $answer = Read-Host 'Re-run CI on the current commit anyway? [y/N]'
    if ($answer -match '^[Yy]') {
        gh workflow run $CiWorkflow --ref $branch
        if ($LASTEXITCODE -ne 0) { Fail 'Could not dispatch the workflow.' }
        Write-Ok 'CI dispatched'
    }
    exit 0
}

git status --short
Write-Host ''
Write-Info ("{0} file(s) changed" -f $status.Count)

# ----------------------------------------------------------------------------
# Dependency sanity check
# ----------------------------------------------------------------------------
# Most early CI failures on this project were a module importing something it
# never declared. This catches that in a second rather than a round trip.
if (Test-Path 'tools/check-deps.py') {
    $py = (Get-Command python -ErrorAction SilentlyContinue) ?? (Get-Command python3 -ErrorAction SilentlyContinue)
    if ($py) {
        Write-Step 'Checking declared dependencies'
        & $py.Source tools/check-deps.py
        if ($LASTEXITCODE -ne 0) {
            Write-Warn 'Push anyway? These are heuristics and can be wrong.'
            $answer = Read-Host '[y/N]'
            if ($answer -notmatch '^[Yy]') { Fail 'Stopped before committing.' }
        }
    }
}

# ----------------------------------------------------------------------------
# Message
# ----------------------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($Message)) {
    $names = @($status | ForEach-Object { ($_ -replace '^.{3}', '').Trim().Split('/')[-1] } |
               Select-Object -Unique)
    $head  = ($names | Select-Object -First 3) -join ', '
    $extra = if ($names.Count -gt 3) { " (+$($names.Count - 3) more)" } else { '' }
    $Message = "Update $head$extra"
    Write-Info "message (auto): $Message"
} else {
    Write-Info "message: $Message"
}

if ($DryRun) {
    Write-Step 'Dry run - plan only'
    Write-Info ("commit  {0}{1}" -f $Message, $(if ($Amend) { '  [amending HEAD]' } else { '' }))
    Write-Info ("push    origin {0}{1}" -f $branch, $(if ($Amend) { ' --force-with-lease' } else { '' }))
    Write-Info "watch   $CiWorkflow"
    Write-Host "`nNothing was changed.`n" -ForegroundColor Cyan
    exit 0
}



# ----------------------------------------------------------------------------
# Commit and push
# ----------------------------------------------------------------------------
Write-Step 'Committing'

git add -A
if ($LASTEXITCODE -ne 0) { Fail 'git add failed.' }

if ($Amend) {
    git commit --amend -m $Message
    if ($LASTEXITCODE -ne 0) { Fail 'git commit --amend failed.' }
    Write-Ok 'amended HEAD'
} else {
    git commit -m $Message | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'git commit failed.' }
    Write-Ok (git log -1 --pretty=format:'%h %s')
}

Write-Step 'Pushing'

$upstream = (git rev-parse --abbrev-ref "$branch@{upstream}" 2>&1)
$hasUpstream = ($LASTEXITCODE -eq 0)

if ($Amend) {
    git push origin $branch --force-with-lease
} elseif ($hasUpstream) {
    git push origin $branch
} else {
    git push -u origin $branch
}
if ($LASTEXITCODE -ne 0) { Fail 'git push failed.' }
Write-Ok "pushed to origin/$branch"

# ----------------------------------------------------------------------------
# Watch
# ----------------------------------------------------------------------------
$actionsUrl = "https://github.com/$repoSlug/actions"

if ($NoWatch) {
    Write-Host "`n  Pushed. CI is running.`n  $actionsUrl`n" -ForegroundColor Cyan
    exit 0
}

Write-Step 'Waiting for CI'

# Match on the commit SHA, never on "a run that isn't the one I saw before".
# The latter picks up an older run whenever the new one hasn't been created
# yet, and then reports ITS conclusion -- a stale green is worse than no
# answer at all.
$headSha = (git rev-parse HEAD).Trim()
$runId = $null
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 4
    $raw = (gh run list --workflow $CiWorkflow --limit 20 --json databaseId,headSha 2>&1)
    if ($LASTEXITCODE -eq 0) {
        try {
            $runs  = @("$raw" | ConvertFrom-Json)
            $match = $runs | Where-Object { $_.headSha -eq $headSha } | Select-Object -First 1
            if ($match) { $runId = $match.databaseId; break }
        } catch { }
    }
    Write-Info "waiting for a run on $($headSha.Substring(0,7))... ($($i + 1))"
}

if (-not $runId) {
    Write-Warn 'Could not find the run. It may still be queuing.'
    Write-Host "`n  $actionsUrl`n" -ForegroundColor Cyan
    exit 0
}

Write-Ok "run $runId"
Write-Host ''
gh run watch $runId --exit-status --compact
$watchExit = $LASTEXITCODE

# Always stamp the outcome. Writing only on failure makes "file unchanged"
# ambiguous between green and still-running, which is useless to anyone
# reading the repo rather than the console.
$sha     = (git rev-parse --short HEAD).Trim()
$subject = (git log -1 --pretty=format:'%s')
$result  = if ($watchExit -eq 0) { 'SUCCESS' } else { 'FAILURE' }
@(
    "status:  $result"
    "run:     $runId"
    "commit:  $sha  $subject"
    "headSha: $headSha"
    "branch:  $branch"
    "when:    $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    "url:     $actionsUrl/runs/$runId"
) | Set-Content -Path (Join-Path (Get-Location) 'ci-status.log') -Encoding UTF8

if ($watchExit -eq 0) {
    # Clear any stale failure log so it cannot be mistaken for current.
    Remove-Item -Path (Join-Path (Get-Location) 'ci-failure.log') -ErrorAction SilentlyContinue
    Write-Ok 'ci-status.log written'
    Write-Host "`n  CI green.`n" -ForegroundColor Green
    exit 0
}

# ----------------------------------------------------------------------------
# Red. Write the full log to disk and surface the error lines.
# ----------------------------------------------------------------------------
Write-Host ''
Write-Step 'Build failed'

$logPath = Join-Path (Get-Location) 'ci-failure.log'

# `gh run watch` returns the instant the run reports completion, but GitHub
# finalises the log archive a few seconds later -- ask too early and
# --log-failed comes back empty.
# The workflow tees Gradle's output to build.log and uploads it. Artifacts are
# published the instant the run ends, unlike the log archive behind
# `gh run view --log-failed`, which can take minutes.
#
# The artifact is NOT always ready the instant the run reports completion, and
# `gh run download` can also just time out against the API. A single attempt
# whose error went to Out-Null is why this has come back empty more than once,
# so retry, and say what went wrong when it still fails.
$raw = $null
$dl = Join-Path ([IO.Path]::GetTempPath()) "roam-buildlog-$runId"
$artifact = Join-Path $dl 'build.log'
$ghErr = ''
for ($try = 1; $try -le 4; $try++) {
    Remove-Item $dl -Recurse -Force -ErrorAction SilentlyContinue
    $ghErr = (gh run download $runId -n build-log -D $dl 2>&1 | Out-String)
    if (Test-Path $artifact) {
        $raw = Get-Content $artifact -Raw
        Write-Ok "build log retrieved from artifact (attempt $try)"
        break
    }
    if ($try -lt 4) {
        Write-Info "artifact not ready (attempt $try of 4), waiting..."
        Start-Sleep -Seconds ($try * 5)
    }
}
if (-not $raw -and $ghErr.Trim()) {
    Write-Info "gh run download said: $($ghErr.Trim() -split "`n" | Select-Object -First 2)"
}

if (-not $raw) {
    Write-Info 'artifact unavailable, falling back to the log archive'
    for ($try = 1; $try -le 5; $try++) {
        Start-Sleep -Seconds ($try * 4)
        $candidate = (gh run view $runId --log-failed 2>&1)
        if ($LASTEXITCODE -eq 0 -and "$candidate".Trim().Length -gt 400) { $raw = $candidate; break }
        Write-Info "log archive not ready (attempt $try of 5)..."
    }
}

if (-not $raw -or "$raw".Trim().Length -eq 0) {
    Write-Info "Could not fetch the log. Try: gh run view $runId --log-failed > ci-failure.log"
    Write-Host "`n  $actionsUrl/runs/$runId`n" -ForegroundColor Cyan
    exit 1
}

# gh emits one line per log entry as:  <job>TAB<step>TAB<timestamp> <message>
# Strip all three, plus ANSI colour codes and the UTF-8 BOM, or the output is
# an unreadable wall.
$clean = @("$raw" -split "`r?`n" | ForEach-Object {
    $parts = $_ -split "`t"
    $line  = if ($parts.Count -ge 3) { $parts[-1] } else { $_ }
    $line  = [regex]::Replace($line, '^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?', '')
    $line  = [regex]::Replace($line, "\x1B\[[0-9;]*[a-zA-Z]", '')
    $line.TrimStart([char]0xFEFF)
})

# Full transcript on disk, gitignored. Paste the path, not the wall of text.
@(
    "# CI failure - run $runId"
    "# $actionsUrl/runs/$runId"
    "# $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    ""
) + $clean | Set-Content -Path $logPath -Encoding UTF8
Write-Ok "full log written to ci-failure.log ($($clean.Count) lines)"

# Kotlin errors start with "e: ", Gradle with FAILURE / Caused by.
$pattern = '^e: |^w: .*error|error:|ERROR:|^FAILURE:|Caused by:|Execution failed|Unresolved reference|Could not (find|resolve)|not a valid name|Compilation error'
$hits = @($clean | Where-Object { $_ -match $pattern } |
                   Where-Object { $_ -notmatch '^\s+at (org\.gradle|org\.jetbrains|java\.|jdk\.)' })

if ($hits.Count -gt 0) {
    Write-Host ''
    $hits | Select-Object -First 30 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    if ($hits.Count -gt 30) { Write-Info "... $($hits.Count - 30) more in ci-failure.log" }
} else {
    Write-Info 'No lines matched the usual error patterns - see ci-failure.log'
}

Write-Host ''
Write-Host "  $actionsUrl/runs/$runId" -ForegroundColor Cyan
Write-Host ''
exit 1
