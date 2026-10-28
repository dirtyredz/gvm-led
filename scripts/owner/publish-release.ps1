<#
.SYNOPSIS
  Sign the CI's unsigned Android draft with the GVM key from 1Password, verify it, upload it and publish the release.

.DESCRIPTION
  Owner-run step for docs/mobile/android-distribution.md, "Sign and publish". In one command:
    1. finds the draft (the single pending android-v draft, or -Tag / -ReleaseId) with gh;
    2. fetches its tag and creates a throwaway detached worktree at exactly that commit
       (C:\Users\dirty\projects\.worktrees\gvm-led\sign-<tag>), so the signer that runs is the
       reviewed tooling of the tagged commit, on a clean checkout;
    3. installs the release tooling's npm dependencies there from the lockfile;
    4. runs `node scripts/mobile/secrets.js release-sign --tag <tag> --release-id <id>`, which
       verifies the draft, tag commit, CI provenance, unsigned digest and versionCode
       monotonicity, THEN reads the key through one 1Password SDK client (one Windows Hello
       approval), zipaligns and signs with apksigner (password via env:, never argv), verifies
       alignment, the single pinned signer and the package identity, uploads the signed APK and
       phone-release.json, downloads both again and compares hashes, deletes only the unsigned
       asset and publishes last as GitHub's latest release;
    5. removes the worktree (unless -KeepWorktree).
  Any failure before the final publish leaves the release a DRAFT; rerunning the same command
  resumes from verified existing assets instead of re-signing.

  Needs: git, gh (signed in with write access to dirtyredz/gvm-led), node 22.20+, a JDK
  (JAVA_HOME) and Android build-tools (ANDROID_HOME or %LOCALAPPDATA%\Android\Sdk).
  Works from any directory. Logs to <repo>\.tmp\owner-runs\publish-release-<stamp>-<pid>.log
  and ends with OK or FAILED. No secret value is ever printed or logged.

  Exit codes: 0 published (or dry run completed), 1 a prerequisite is missing, 2 the step failed.

.PARAMETER Tag
  The draft's tag, e.g. android-v0.1.1. Default: the single pending android-v draft.
.PARAMETER ReleaseId
  The draft's numeric release ID (shown in the Release workflow's summary). Default: looked up from the tag.
.PARAMETER DryRun
  Change nothing and contact nothing: print the plan (secrets.js release-sign --dry-run).
.PARAMETER KeepWorktree
  Leave the signing worktree in place afterwards (for inspection).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\dirty\projects\gvm-led\scripts\owner\publish-release.ps1 -DryRun
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\dirty\projects\gvm-led\scripts\owner\publish-release.ps1 -Tag android-v0.1.1
#>
[CmdletBinding()]
param(
  [string]$Tag,
  [string]$ReleaseId,
  [switch]$DryRun,
  [switch]$KeepWorktree
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'owner-run.ps1')
. (Join-Path $PSScriptRoot 'release-tools.ps1')
Initialize-OwnerRun 'publish-release'
$mode = if ($DryRun) { 'dry run' } else { 'execute' }
Write-Log "Sign and publish the GVM LED Android draft ($mode). Repo: $script:RepoRoot"

if ($Tag -and $Tag -notmatch '^android-v\d+\.\d+\.\d+$') { Complete-OwnerRun 1 "-Tag must look like android-v0.1.1, not '$Tag'." }
if ($ReleaseId -and $ReleaseId -notmatch '^[1-9]\d{0,15}$') { Complete-OwnerRun 1 "-ReleaseId must be a positive integer, not '$ReleaseId'." }

$contract = Get-ReleaseContract $script:RepoRoot
$repo = [string]$contract.repository
$git = Find-Tool 'git'
$gh = Find-Tool 'gh'
$node = Find-Tool 'node'
$java = Find-JavaTool 'java'
$tools = Find-BuildTools
foreach ($pair in @(@('git', $git), @('gh', $gh), @('node', $node), @('java (JAVA_HOME)', $java), @('Android build-tools (ANDROID_HOME)', $tools))) {
  if (-not $pair[1]) { Complete-OwnerRun 1 "$($pair[0]) was not found." }
  Write-Log "$($pair[0]): $($pair[1])"
}
if (-not (Test-Path -LiteralPath (Join-Path $tools 'lib\apksigner.jar'))) { Complete-OwnerRun 1 "apksigner.jar is missing from $tools." }

$secretsRel = 'scripts\mobile\secrets.js'
if ($DryRun) {
  $showTag = if ($Tag) { $Tag } else { '<the single pending android-v draft>' }
  $showId = if ($ReleaseId) { $ReleaseId } else { '<looked up with gh>' }
  Write-Log "DRY RUN: would look up draft $showTag (release $showId) in $repo with gh."
  Write-Log "DRY RUN: would fetch the tag and add a detached worktree at its commit under $(Join-Path $env:USERPROFILE 'projects\.worktrees\gvm-led')."
  Write-Log 'DRY RUN: would run npm ci there, then the signer below (one Windows Hello prompt), then remove the worktree.'
  $cmdArgs = @((Join-Path $script:RepoRoot $secretsRel), 'release-sign', '--dry-run')
  if ($Tag) { $cmdArgs += @('--tag', $Tag) }
  if ($ReleaseId) { $cmdArgs += @('--release-id', $ReleaseId) }
  $code = Invoke-Native $node $cmdArgs $script:RepoRoot
  if ($code -ne 0) { Complete-OwnerRun 2 "secrets.js release-sign --dry-run failed (exit $code)." }
  Complete-OwnerRun 0
}

if ((Invoke-Native $gh @('auth', 'status', '--hostname', 'github.com') $script:RepoRoot) -ne 0) { Complete-OwnerRun 1 'gh is not signed in; run gh auth login.' }

# 1. The draft.
if (-not $Tag -or -not $ReleaseId) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  $json = & $gh api --hostname github.com "repos/$repo/releases?per_page=100" 2>$null
  $listCode = $LASTEXITCODE
  $ErrorActionPreference = $prev
  if ($listCode -ne 0) { Complete-OwnerRun 2 "gh api releases failed (exit $listCode)." }
  $drafts = @(($json | Out-String | ConvertFrom-Json) | Where-Object { $_.draft -and $_.tag_name -match '^android-v' })
  if ($Tag) { $drafts = @($drafts | Where-Object { $_.tag_name -eq $Tag }) }
  if ($drafts.Count -ne 1) { Complete-OwnerRun 2 "Expected exactly one matching android-v draft, found $($drafts.Count). Pass -Tag and -ReleaseId." }
  $Tag = [string]$drafts[0].tag_name
  $ReleaseId = [string]$drafts[0].id
}
Write-Log "Draft: $Tag (release $ReleaseId)"

# 2. A clean checkout at exactly the tag's commit.
if ((Invoke-Native $git @('fetch', 'origin', '--force', "refs/tags/${Tag}:refs/tags/${Tag}") $script:RepoRoot) -ne 0) { Complete-OwnerRun 2 "Could not fetch tag $Tag." }
$commit = (& $git -C $script:RepoRoot rev-parse "$Tag^{commit}").Trim()
if ($commit -notmatch '^[a-f0-9]{40}$') { Complete-OwnerRun 2 "Tag $Tag does not resolve to a commit." }
$worktree = Join-Path $env:USERPROFILE "projects\.worktrees\gvm-led\sign-$Tag"
if (Test-Path -LiteralPath $worktree) { Complete-OwnerRun 1 "$worktree already exists (a previous run?). Remove it with: git worktree remove --force `"$worktree`"" }
if ((Invoke-Native $git @('worktree', 'add', '--detach', $worktree, $commit) $script:RepoRoot) -ne 0) { Complete-OwnerRun 2 'git worktree add failed.' }

$code = 2
try {
  # 3-4. Dependencies, then the signer (it opens 1Password only at the signing step).
  if (-not (Install-ReleaseToolingDependencies $worktree)) { throw 'npm ci for the release tooling failed.' }
  $env:ANDROID_HOME = Split-Path -Parent (Split-Path -Parent $tools)
  Write-Log 'Approve the Windows Hello / 1Password prompt when it appears (once, at the signing step).'
  $code = Invoke-Native $node @((Join-Path $worktree $secretsRel), 'release-sign', '--tag', $Tag, '--release-id', $ReleaseId) $worktree
} catch {
  Write-Log "ERROR: $($_.Exception.Message)"
  $code = 2
} finally {
  if ($KeepWorktree) {
    Write-Log "Kept the signing worktree: $worktree"
  } else {
    [void](Invoke-Native $git @('worktree', 'remove', '--force', $worktree) $script:RepoRoot)
  }
}
if ($code -ne 0) { Complete-OwnerRun 2 "release-sign failed (exit $code). The release stays a draft; rerun this command to resume." }
Write-Log "Published $Tag. Next: scripts\owner\install-release.ps1 -Tag $Tag"
Complete-OwnerRun 0
