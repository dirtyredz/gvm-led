<#
.SYNOPSIS
  Example owner-run step: check prerequisites, then do one change (or only describe it with -DryRun).

.DESCRIPTION
  Copy this file, rename it for the real step (e.g. provision-signing.ps1) and replace the body.
  An owner-run step is anything only the owner can approve (1Password, accounts, Windows Hello,
  installs): ONE command, dry-run available, one approval. Never a list of manual clicks.

  Works from any directory (cmd, PowerShell 5.1 or 7, even C:\Windows\System32): the repo is located
  from this script's own path ($PSScriptRoot). Each run writes <repo>\.tmp\owner-runs\<name>-<stamp>-<pid>.log
  (git-ignored, secrets redacted) and ends with "OK (exit 0) log: <path>" or "FAILED (exit N) log: <path>".

  Exit codes: 0 done (or dry run completed), 1 a prerequisite is missing, 2 the step failed.

.PARAMETER DryRun
  Change nothing; print and log what would happen.
.PARAMETER Tool
  Name of the command-line tool the step needs on PATH (default: git).
#>
[CmdletBinding()]
param(
  [switch]$DryRun,
  [string]$Tool = 'git'
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'owner-run.ps1')
Initialize-OwnerRun 'example-owner-step'
$mode = if ($DryRun) { 'dry run' } else { 'execute' }
Write-Log "Example owner step ($mode). Repo: $script:RepoRoot"

$exe = Find-Tool $Tool
if (-not $exe) { Complete-OwnerRun 1 "$Tool was not found on PATH." }
Write-Log "${Tool}: $exe"

if ($DryRun) {
  Write-Log "DRY RUN: would run '$Tool --version' in the repo."
} else {
  $code = Invoke-Native $exe @('--version') $script:RepoRoot
  if ($code -ne 0) { Complete-OwnerRun 2 "$Tool --version failed (exit $code)." }
}
Complete-OwnerRun 0
