<#
.SYNOPSIS
  Shared lookups for the Android release owner steps (dot-source after owner-run.ps1; functions only).

.DESCRIPTION
  Used by new-signing-key.ps1, publish-release.ps1 and install-release.ps1. Kept apart from
  owner-run.ps1, which stays a verbatim copy of the template.

  Functions: Get-ReleaseContract, Get-PinnedCertificate, Find-JavaTool, Find-AndroidSdk,
  Find-BuildTools, Find-Adb, Install-ReleaseToolingDependencies.
#>

function Get-ReleaseContract([string]$Root) {
  return Get-Content -LiteralPath (Join-Path $Root 'src\mobile\releases\release-contract.json') -Raw | ConvertFrom-Json
}

# The committed public pin (lowercase hex SHA-256), or $null while no key exists.
function Get-PinnedCertificate([string]$Root) {
  $record = Get-Content -LiteralPath (Join-Path $Root 'src\mobile\releases\signing-certificate.json') -Raw | ConvertFrom-Json
  if ($record.certificateSha256 -and $record.certificateSha256 -match '^[a-f0-9]{64}$') { return [string]$record.certificateSha256 }
  return $null
}

# A JDK tool from JAVA_HOME, else PATH.
function Find-JavaTool([string]$Name) {
  if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\$Name.exe"
    if (Test-Path -LiteralPath $candidate) { return $candidate }
  }
  return Find-Tool $Name
}

function Find-AndroidSdk {
  $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
  if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
  foreach ($root in $roots) {
    if ($root -and (Test-Path -LiteralPath (Join-Path $root 'build-tools'))) { return $root }
  }
  return $null
}

# The highest installed build-tools directory (holds lib\apksigner.jar), or $null.
function Find-BuildTools {
  $sdk = Find-AndroidSdk
  if (-not $sdk) { return $null }
  $dirs = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+(\.\d+){0,2}$' } |
    Sort-Object { [version]($_.Name + '.0') }
  if (-not $dirs) { return $null }
  return ($dirs | Select-Object -Last 1).FullName
}

function Find-Adb {
  $sdk = Find-AndroidSdk
  if ($sdk) {
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { return $candidate }
  }
  return Find-Tool 'adb'
}

# npm ci from the lockfile when @1password/sdk is not installed yet. Returns $true on success.
function Install-ReleaseToolingDependencies([string]$Root) {
  if (Test-Path -LiteralPath (Join-Path $Root 'node_modules\@1password\sdk\package.json')) { return $true }
  $npm = Find-Tool 'npm.cmd'
  if (-not $npm) { $npm = Find-Tool 'npm' }
  if (-not $npm) { Write-Log 'npm was not found on PATH.'; return $false }
  return ((Invoke-Native $npm @('ci', '--no-audit', '--no-fund') $Root) -eq 0)
}
