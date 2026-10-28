<#
.SYNOPSIS
  Download the published, signed GVM LED APK, verify it against the pinned certificate and install it on the phone with adb.

.DESCRIPTION
  Owner-run step for docs/mobile/android-distribution.md, "Install on the phone" (USB
  sideload, the pattern family-command-center uses for its bootstrap install). In one command:
    1. resolves the release (-Tag, default GitHub's latest) and refuses drafts;
    2. downloads GVM-LED-<version>-android.apk and phone-release.json with gh into
       <repo>\.tmp\install\<tag>\;
    3. checks the APK's SHA-256 and size against the manifest, the manifest's certificate
       against src/mobile/releases/signing-certificate.json, and `apksigner verify
       --print-certs` against the same pin (exactly one signer);
    4. requires exactly one adb device (or -Serial) and runs `adb install -r`;
    5. reads back the installed versionCode with `adb shell dumpsys package` and requires it
       to equal the manifest's, so the run ends at "confirmed installed", not "sent".
  The debug app (com.dirtyredz.gvmled.debug) is a different package and is left alone.
  A signer mismatch means a different-key install of com.dirtyredz.gvmled exists: uninstall
  it by hand first (this script never uninstalls).

  Works from any directory. Logs to <repo>\.tmp\owner-runs\install-release-<stamp>-<pid>.log
  and ends with OK or FAILED. Raises no 1Password or Windows Hello prompt; the phone may ask
  to allow the USB install.

  Exit codes: 0 installed and confirmed (or dry run completed), 1 a prerequisite is missing,
  2 the step failed (download, verification, install or read-back).

.PARAMETER Tag
  The published release tag, e.g. android-v0.1.1. Default: GitHub's latest release.
.PARAMETER Serial
  adb device serial when more than one device is attached.
.PARAMETER DryRun
  Change nothing and contact nothing: check the tools and print the plan.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\dirty\projects\gvm-led\scripts\owner\install-release.ps1 -DryRun
#>
[CmdletBinding()]
param(
  [string]$Tag,
  [string]$Serial,
  [switch]$DryRun
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'owner-run.ps1')
. (Join-Path $PSScriptRoot 'release-tools.ps1')
Initialize-OwnerRun 'install-release'
$mode = if ($DryRun) { 'dry run' } else { 'execute' }
Write-Log "Install the published GVM LED APK ($mode). Repo: $script:RepoRoot"

if ($Tag -and $Tag -notmatch '^android-v\d+\.\d+\.\d+$') { Complete-OwnerRun 1 "-Tag must look like android-v0.1.1, not '$Tag'." }
$contract = Get-ReleaseContract $script:RepoRoot
$repo = [string]$contract.repository
$package = [string]$contract.packageName
$gh = Find-Tool 'gh'
$adb = Find-Adb
$java = Find-JavaTool 'java'
$tools = Find-BuildTools
foreach ($pair in @(@('gh', $gh), @('adb (ANDROID_HOME platform-tools)', $adb), @('java (JAVA_HOME)', $java), @('Android build-tools (ANDROID_HOME)', $tools))) {
  if (-not $pair[1]) { Complete-OwnerRun 1 "$($pair[0]) was not found." }
  Write-Log "$($pair[0]): $($pair[1])"
}
$apksigner = Join-Path $tools 'lib\apksigner.jar'
if (-not (Test-Path -LiteralPath $apksigner)) { Complete-OwnerRun 1 "apksigner.jar is missing from $tools." }
$pin = Get-PinnedCertificate $script:RepoRoot

if ($DryRun) {
  $showTag = if ($Tag) { $Tag } else { "<GitHub's latest release>" }
  $showPin = if ($pin) { $pin } else { '<none pinned yet: a real run would refuse>' }
  Write-Log "DRY RUN: would resolve $showTag in $repo with gh and refuse a draft."
  Write-Log "DRY RUN: would download $($contract.assets.apkPrefix)<version>$($contract.assets.apkSuffix) and $($contract.assets.manifest) to .tmp\install\<tag>\."
  Write-Log "DRY RUN: would check SHA-256/size against the manifest and the single signer against pin $showPin."
  Write-Log "DRY RUN: would run: adb $(if ($Serial) { "-s $Serial " })install -r <apk>, then read back $package versionCode."
  Complete-OwnerRun 0
}

if (-not $pin) { Complete-OwnerRun 1 'No signing certificate is pinned; nothing published can be trusted yet.' }

# 1. The release.
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$route = if ($Tag) { "repos/$repo/releases/tags/$Tag" } else { "repos/$repo/releases/latest" }
$json = & $gh api --hostname github.com $route 2>$null
$viewCode = $LASTEXITCODE
$ErrorActionPreference = $prev
if ($viewCode -ne 0) { Complete-OwnerRun 2 "gh api $route failed (exit $viewCode); is the release published?" }
$release = $json | Out-String | ConvertFrom-Json
if ($release.draft) { Complete-OwnerRun 2 "$($release.tag_name) is still a draft; run publish-release.ps1 first." }
$Tag = [string]$release.tag_name
if ($Tag -notmatch '^android-v(\d+\.\d+\.\d+)$') { Complete-OwnerRun 2 "Latest release $Tag is not an Android release." }
$versionName = $Matches[1]
$apkName = "$($contract.assets.apkPrefix)$versionName$($contract.assets.apkSuffix)"
Write-Log "Release: $Tag ($apkName)"

# 2. Download.
$dir = Join-Path $script:RepoRoot ".tmp\install\$Tag"
if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force -Confirm:$false }
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$code = Invoke-Native $gh @('release', 'download', $Tag, '--repo', "github.com/$repo", '--dir', $dir, '--pattern', $apkName, '--pattern', [string]$contract.assets.manifest) $script:RepoRoot
if ($code -ne 0) { Complete-OwnerRun 2 "gh release download failed (exit $code)." }
$apk = Join-Path $dir $apkName
$manifest = Get-Content -LiteralPath (Join-Path $dir $contract.assets.manifest) -Raw | ConvertFrom-Json

# 3. Verify bytes, manifest and signer.
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $apk).Length
if ($manifest.packageName -ne $package -or $manifest.apk.name -ne $apkName -or $manifest.apk.sha256 -ne $hash -or [int64]$manifest.apk.size -ne $size) {
  Complete-OwnerRun 2 "The APK does not match $($contract.assets.manifest) (package, name, size or SHA-256)."
}
if ($manifest.certificateSha256 -ne $pin) { Complete-OwnerRun 2 "The manifest names signer $($manifest.certificateSha256), not the pinned $pin." }
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$verify = (& $java -jar $apksigner verify --print-certs $apk 2>&1 | Out-String)
$verifyCode = $LASTEXITCODE
$ErrorActionPreference = $prev
$digests = @([regex]::Matches($verify, 'certificate SHA-256 digest: ([a-f0-9]{64})') | ForEach-Object { $_.Groups[1].Value })
if ($verifyCode -ne 0 -or $digests.Count -ne 1 -or $digests[0] -ne $pin) { Complete-OwnerRun 2 'apksigner verify failed or the APK is not signed by exactly the pinned certificate.' }
Write-Log "Verified $apkName : SHA-256 $hash, versionCode $($manifest.versionCode), signer $pin"

# 4. Install.
$adbBase = @()
if ($Serial) { $adbBase = @('-s', $Serial) }
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$devices = @((& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+device$' })
$ErrorActionPreference = $prev
if (-not $Serial -and $devices.Count -ne 1) { Complete-OwnerRun 1 "Expected exactly one adb device, found $($devices.Count). Connect the phone (USB debugging on) or pass -Serial." }
$code = Invoke-Native $adb ($adbBase + @('install', '-r', $apk)) $script:RepoRoot
if ($code -ne 0) { Complete-OwnerRun 2 "adb install failed (exit $code). INSTALL_FAILED_UPDATE_INCOMPATIBLE means another signer's $package is installed: uninstall it by hand." }

# 5. Read back.
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$dump = (& $adb @($adbBase + @('shell', 'dumpsys', 'package', $package)) 2>&1 | Out-String)
$ErrorActionPreference = $prev
$installed = [regex]::Match($dump, 'versionCode=(\d+)')
if (-not $installed.Success -or [int64]$installed.Groups[1].Value -ne [int64]$manifest.versionCode) {
  Complete-OwnerRun 2 "Installed versionCode is $(if ($installed.Success) { $installed.Groups[1].Value } else { 'unknown' }), expected $($manifest.versionCode)."
}
Write-Log "Confirmed installed: $package versionCode $($manifest.versionCode) ($Tag)."
Complete-OwnerRun 0
