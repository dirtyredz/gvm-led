<#
.SYNOPSIS
  Create the GVM LED Android release key and store it in 1Password, in one command (dry-run available).

.DESCRIPTION
  Owner-run step for docs/mobile/android-distribution.md, "Key provisioning". Runs
  `node scripts/mobile/secrets.js generate-key`, which:
    - refuses if src/mobile/releases/signing-certificate.json already pins a certificate;
    - opens ONE 1Password SDK client (one Windows Hello approval) on account
      ETCB7LGCDNEP7PUD3FDNS5JXBU and creates the vault GVM-LED if it is absent
      (never Command Deck's vault or key);
    - refuses if GVM-LED already holds gvm-led-android-release-keystore or
      gvm-led-android-release-password;
    - generates an RSA-4096 PKCS12 key (alias gvm-led-release, ~30 years) with a random
      password in an owner-only temp directory outside the checkout;
    - stores the Document and the Password, reads both back by item ID, compares SHA-256 and
      re-opens the recovered copy;
    - records ONLY the public fingerprint and expiry in signing-certificate.json.
  Afterwards commit signing-certificate.json (the public pin) before the first CI release.
  With -VerifyOnly it instead runs `secrets.js verify-key`: recover the stored key and check it
  against the committed pin (one Windows Hello approval).

  Works from any directory: the repo is found from this script's path. Installs the release
  tooling's npm dependencies (@1password/sdk) from the lockfile when missing. Each run writes
  <repo>\.tmp\owner-runs\new-signing-key-<stamp>-<pid>.log and ends with OK or FAILED.
  No secret value is ever printed or logged.

  Exit codes: 0 done (or dry run completed), 1 a prerequisite is missing, 2 the step failed.

.PARAMETER DryRun
  Change nothing and open nothing: print and log the plan (secrets.js --dry-run).
.PARAMETER VerifyOnly
  Do not generate: recover the stored key and verify it against the committed pin.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\dirty\projects\gvm-led\scripts\owner\new-signing-key.ps1 -DryRun
#>
[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$VerifyOnly
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'owner-run.ps1')
. (Join-Path $PSScriptRoot 'release-tools.ps1')
Initialize-OwnerRun 'new-signing-key'
$command = if ($VerifyOnly) { 'verify-key' } else { 'generate-key' }
$mode = if ($DryRun) { 'dry run' } else { 'execute' }
Write-Log "GVM LED signing key: $command ($mode). Repo: $script:RepoRoot"

$node = Find-Tool 'node'
if (-not $node) { Complete-OwnerRun 1 'node (22.20+) was not found on PATH.' }
$keytool = Find-JavaTool 'keytool'
if (-not $keytool) { Complete-OwnerRun 1 'keytool was not found (set JAVA_HOME to a JDK 21 or put keytool on PATH).' }
Write-Log "node: $node; keytool: $keytool"

$pin = Get-PinnedCertificate $script:RepoRoot
if ($VerifyOnly -and -not $pin) { Complete-OwnerRun 1 'No certificate is pinned yet; run without -VerifyOnly first.' }
if (-not $VerifyOnly -and $pin) { Complete-OwnerRun 1 "A certificate is already pinned ($pin); the key exists. Use -VerifyOnly to check it." }

$secretsJs = Join-Path $script:RepoRoot 'scripts\mobile\secrets.js'
if ($DryRun) {
  $code = Invoke-Native $node @($secretsJs, $command, '--dry-run') $script:RepoRoot
  if ($code -ne 0) { Complete-OwnerRun 2 "secrets.js $command --dry-run failed (exit $code)." }
  Write-Log 'DRY RUN: nothing was created; no 1Password prompt was raised.'
  Complete-OwnerRun 0
}

if (-not (Install-ReleaseToolingDependencies $script:RepoRoot)) { Complete-OwnerRun 1 'npm ci for the release tooling failed.' }
Write-Log 'Approve the Windows Hello / 1Password prompt when it appears (once).'
$code = Invoke-Native $node @($secretsJs, $command) $script:RepoRoot
if ($code -ne 0) { Complete-OwnerRun 2 "secrets.js $command failed (exit $code). Nothing is deleted automatically; read the message above." }
if (-not $VerifyOnly) {
  Write-Log "Pinned certificate: $(Get-PinnedCertificate $script:RepoRoot)"
  Write-Log 'NEXT: commit src/mobile/releases/signing-certificate.json (public pin only) and push it before the first release.'
}
Complete-OwnerRun 0
