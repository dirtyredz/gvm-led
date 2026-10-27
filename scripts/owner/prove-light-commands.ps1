<#
.SYNOPSIS
  Slice-0 proof: send visible legacy commands to the two GVM lights and check each against the light's own reports.

.DESCRIPTION
  Runs src/research/ble/live_test.py with the research venv's Python (bleak 3.0.2). Close the official
  GVM phone app first (a light accepts one connection). The run connects to each light in turn, requires a
  valid hello reply before sending any set, then walks power, colour, saturation, CCT endpoints, scenes 19/20,
  power-off experiments and the disconnect/reconnect colour test. Per step it logs the wanted values, the
  packets sent and the fresh 0x03 reports.

  Observer: -Camera captures one Agent Eye frame per step (images land in
  .tmp/owner-runs/live-test-<stamp>/); without it the script asks you to type what you saw.
  -DryRun prints every planned packet and camera shot point, never touches Bluetooth or the camera.

  Works from any directory (repo found from $PSScriptRoot). Logs: <repo>\.tmp\owner-runs\prove-light-commands-*.log
  (this wrapper) and live-test-*.log (the Python run). Ends with "OK (exit 0) log: <path>" or "FAILED (exit N) log: <path>".

  Exit codes: 0 run completed and every non-experiment step matched; 1 prerequisite missing (Python / venv);
  2 the run failed or a step did not match (see the log).

.PARAMETER DryRun
  Print the plan only (no Bluetooth, no camera).
.PARAMETER Check
  Queries only: connect, hello, read reports. No visible change.
.PARAMETER Camera
  Capture a frame per step through Agent Eye instead of asking for typed observations.
.PARAMETER VenvPath
  Python venv directory. Default: the repo's .tmp\scan\.venv, else the main checkout's.
.PARAMETER SnapshotScript
  Agent Eye snapshot.mjs (only used with -Camera).
#>
[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$Check,
  [switch]$Camera,
  [string]$VenvPath,
  [string]$SnapshotScript
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'owner-run.ps1')
Initialize-OwnerRun 'prove-light-commands'
$mode = if ($DryRun) { '--dry' } elseif ($Check) { '--check' } else { '--run' }
Write-Log "Prove light commands ($mode). Repo: $script:RepoRoot"

if (-not $VenvPath) {
  $candidates = @((Join-Path $script:RepoRoot '.venv'))
  $git = Find-Tool 'git'
  if ($git) {
    $common = (& $git -C $script:RepoRoot rev-parse --path-format=absolute --git-common-dir 2>$null)
    if ($common) {
      $mainCheckout = Split-Path -Parent ([string]$common)
      $candidates += (Join-Path $mainCheckout '.venv')
      $candidates += (Join-Path (Join-Path $mainCheckout '.tmp\scan') '.venv')
    }
  }
  $VenvPath = $candidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'Scripts\python.exe') } | Select-Object -First 1
}
$python = if ($VenvPath) { Join-Path $VenvPath 'Scripts\python.exe' } else { $null }
if (-not $python -or -not (Test-Path -LiteralPath $python)) {
  Complete-OwnerRun 1 'No venv Python found. Pass -VenvPath <venv> (needs: pip install -r src/research/ble/requirements.txt).'
}
Write-Log "python: $python"

$script = Join-Path $script:RepoRoot 'src\research\ble\live_test.py'
$pyArgs = @($script, $mode)
if ($Camera) { $pyArgs += '--camera' }
if ($SnapshotScript) { $pyArgs += @('--snapshot', $SnapshotScript) }
if (-not $DryRun -and -not $Check) { Write-Log 'Close the official GVM app now. Lights will change colour/power during this run.' }

$code = Invoke-Native $python $pyArgs $script:RepoRoot
if ($code -ne 0) { Complete-OwnerRun 2 "live_test.py exited $code." }
Complete-OwnerRun 0
