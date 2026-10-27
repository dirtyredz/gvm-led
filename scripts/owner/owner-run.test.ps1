<#
.SYNOPSIS
  Plain-PowerShell test for the owner-run template (no Pester).

.DESCRIPTION
  Runs example-owner-step.ps1 from the temp directory (not the repo) and asserts exit codes, the final
  OK/FAILED line, and that the log exists under owner-runs. Exit code = number of failures. Run:
    powershell -NoProfile -ExecutionPolicy Bypass -File <path>\owner-run.test.ps1
  Also works under pwsh. Copy it next to your owner scripts and add a case per script.

.PARAMETER DryRun
  Accepted for standard compliance; the test itself never changes anything.
#>
[CmdletBinding()]
param([switch]$DryRun)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$shell = (Get-Process -Id $PID).Path
$tmp = [System.IO.Path]::GetTempPath()
$failures = 0

function Invoke-Case([string]$Title, [string]$Script, [string[]]$Arguments, [int]$ExpectExit, [string]$ExpectKind) {
  $file = Join-Path $PSScriptRoot $Script
  Push-Location -LiteralPath $tmp
  try { $out = @(& $shell -NoProfile -ExecutionPolicy Bypass -File $file @Arguments 2>&1 | ForEach-Object { [string]$_ }) }
  finally { Pop-Location }
  $code = $LASTEXITCODE
  $last = ($out | Where-Object { $_ -match '\S' } | Select-Object -Last 1)
  $problems = @()
  if ($code -ne $ExpectExit) { $problems += "exit $code, expected $ExpectExit" }
  if ($last -notmatch ('^{0} \(exit (\d+)\) log: (.+)$' -f $ExpectKind)) { $problems += "last line: $last" }
  else {
    if ([int]$Matches[1] -ne $ExpectExit) { $problems += "reported exit $($Matches[1])" }
    $log = $Matches[2].Trim()
    if (-not (Test-Path -LiteralPath $log)) { $problems += "log missing: $log" }
    elseif ($log -notmatch 'owner-runs') { $problems += "log not under owner-runs: $log" }
  }
  if ($problems.Count) { $script:failures++; Write-Host "FAIL: $Title -> $($problems -join '; ')" -ForegroundColor Red; $out | ForEach-Object { Write-Host "  $_" } }
  else { Write-Host "PASS: $Title" -ForegroundColor Green }
}

Invoke-Case 'example -DryRun' 'example-owner-step.ps1' @('-DryRun') 0 'OK'
Invoke-Case 'example executes git --version' 'example-owner-step.ps1' @() 0 'OK'
Invoke-Case 'example reports a missing tool as exit 1' 'example-owner-step.ps1' @('-Tool', 'no-such-tool-xyz') 1 'FAILED'

Write-Host "`nFailures: $failures"
exit $failures
