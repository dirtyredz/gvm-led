<#
.SYNOPSIS
  Shared plumbing for owner-run PowerShell scripts (dot-source it; it defines functions only).

.DESCRIPTION
  Template version: 1.0.0 (bump on any API change; copies record the version they came from).
  Canonical copy: ~/.claude/templates/scripts/owner-run.ps1. Copy verbatim into scripts/owner/ (or the
  repo's declared scripts home); extend through your own scripts, not by editing the copy.

  Responsibilities: repo root found from $PSScriptRoot (walks up to the nearest .git or package.json,
  never the caller's cwd), a timestamped log under <repo>\.tmp\owner-runs\, tool lookup, child-process
  capture, secret redaction, and the one final status line
  "OK (exit 0) log: <path>" / "FAILED (exit N) log: <path>".

  Functions: Initialize-OwnerRun, Write-Log, Complete-OwnerRun, Find-Tool, Invoke-Native, Add-LogSecret.
#>
$script:OwnerRunVersion = '1.0.0'
$script:Secrets = @()

function Initialize-OwnerRun([string]$Name) {
  $dir = $PSScriptRoot
  while ($dir -and -not ((Test-Path -LiteralPath (Join-Path $dir '.git')) -or (Test-Path -LiteralPath (Join-Path $dir 'package.json')))) {
    $dir = Split-Path -Parent $dir
  }
  if (-not $dir) { throw "No repo root (.git or package.json) above $PSScriptRoot" }
  $script:RepoRoot = $dir
  $logDir = Join-Path $dir '.tmp/owner-runs'
  New-Item -ItemType Directory -Path $logDir -Force | Out-Null
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $script:LogFile = Join-Path $logDir "$Name-$stamp-$PID.log"
}

# Register a value that must never reach the log or console (tokens, passwords).
function Add-LogSecret([string]$Value) {
  if ($Value -and $Value.Length -ge 4) { $script:Secrets += $Value }
}

function Hide-Secrets([string]$Text) {
  foreach ($secret in ($script:Secrets | Sort-Object Length -Descending)) { $Text = $Text.Replace($secret, '[REDACTED]') }
  return $Text
}

function Write-Log([string]$Message) {
  $line = '{0} {1}' -f (Get-Date -Format 'HH:mm:ss'), (Hide-Secrets $Message)
  Write-Host $line
  Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8
}

# Ends the run: prints exactly one final status line (also appended to the log) and exits with $Code.
function Complete-OwnerRun([int]$Code, [string]$Reason) {
  if ($Reason) { Write-Log "STOPPED: $Reason" }
  $final = if ($Code -eq 0) { "OK (exit 0) log: $($script:LogFile)" } else { "FAILED (exit $Code) log: $($script:LogFile)" }
  Add-Content -LiteralPath $script:LogFile -Value $final -Encoding UTF8
  Write-Host $final
  exit $Code
}

function Find-Tool([string]$Name) {
  $cmd = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($cmd) { return $cmd.Source }
  return $null
}

# Runs a native command with output teed to the console and the log; returns its exit code.
function Invoke-Native([string]$Exe, [string[]]$Arguments, [string]$WorkingDirectory) {
  if (-not $WorkingDirectory) { $WorkingDirectory = $script:RepoRoot }
  Write-Log ('> {0} {1}' -f $Exe, ($Arguments -join ' '))
  $prevEap = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'   # Windows PowerShell 5.1 turns native stderr into errors under 'Stop'
  Push-Location -LiteralPath $WorkingDirectory
  try {
    & $Exe @Arguments 2>&1 | ForEach-Object { Write-Log ([string]$_) }
    return [int]$LASTEXITCODE
  } finally {
    Pop-Location
    $ErrorActionPreference = $prevEap
  }
}
