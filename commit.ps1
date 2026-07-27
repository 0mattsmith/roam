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

# Note the newest run BEFORE pushing, so we can tell the new one apart from
# whatever is already in the list.
$previousRunId = $null
$raw = (gh run list --workflow $CiWorkflow --limit 1 --json databaseId 2>&1)
if ($LASTEXITCODE -eq 0) {
    try { $previousRunId = @("$raw" | ConvertFrom-Json)[0].databaseId } catch { }
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

$runId = $null
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 4
    $raw = (gh run list --workflow $CiWorkflow --limit 5 --json databaseId,headBranch 2>&1)
    if ($LASTEXITCODE -eq 0) {
        try {
            $runs  = @("$raw" | ConvertFrom-Json)
            $match = $runs | Where-Object {
                $_.headBranch -eq $branch -and $_.databaseId -ne $previousRunId
            } | Select-Object -First 1
            if ($match) { $runId = $match.databaseId; break }
        } catch { }
    }
    Write-Info "waiting for the run to appear... ($($i + 1))"
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

if ($watchExit -eq 0) {
    Write-Host "`n  CI green.`n" -ForegroundColor Green
    exit 0
}

# ----------------------------------------------------------------------------
# Red. Write the full log to disk and surface the error lines.
# ----------------------------------------------------------------------------
Write-Host ''
Write-Step 'Build failed'

$logPath = Join-Path (Get-Location) 'ci-failure.log'
$raw = (gh run view $runId --log-failed 2>&1)

if ($LASTEXITCODE -ne 0 -or -not $raw) {
    Write-Info "Could not fetch the log. Try: gh run view $runId --log-failed"
    Write-Host "`
  $actionsUrl/runs/$runId`
" -ForegroundColor Cyan
    exit 1
}

# gh emits one line per log entry as:  <job>TAB<step>TAB<timestamp> <message>
# Strip all three, plus ANSI colour codes and the UTF-8 BOM, or the output is
# an unreadable wall.
$clean = @("$raw" -split "`?`
" | ForEach-Object {
    $parts = $_ -split "`	"
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
