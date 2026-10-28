# Android distribution runbook

How the permanent GVM LED app (`com.dirtyredz.gvmled`) is built, signed, published and installed.
Adapted from Command Deck's `docs/mobile/android-distribution.md` (attended flow only).
Decision: [DECISIONS](../DECISIONS.md) (2026-10-28). Plan: [android-app.md](../plans/android-app.md)
slices 6-7. Traps: [GOTCHAS](../GOTCHAS.md).

**Status (2026-10-28):** the key exists, and signed release 0.1.1 (tag `android-v0.1.1`, versionCode 2)
is published as GitHub's latest release and under owner test. Updates from inside the app (below)
are implemented and unit-tested; their on-phone acceptance has not run yet.

## Overview

```text
GitHub Actions (release.yml, dispatch on main)     owner PC (Valhalla)                      phone
version ─┐                                         publish-release.ps1                      install-release.ps1
android-verify -> android-build -> android-draft    throwaway worktree at the tag            adb install -r
  unsigned APK + provenance       tag android-v<v>  1Password key -> zipalign, apksigner      versionCode read back
  artifact (7 days)               draft release     upload, re-download, publish (latest)
```

CI never holds the key. The app embeds no credentials, so there are no build secrets and no
`android-build` environment. `release-resume.yml` restores a draft from a retained artifact.

## Identity

| What | Value |
| --- | --- |
| Repository / branch | `dirtyredz/gvm-led` / `main` |
| Package (debug) | `com.dirtyredz.gvmled` (`com.dirtyredz.gvmled.debug`) |
| Versions | `0.1.<n>`, n = highest published `android-v` + 1; versionCode = n + 1 |
| Tag / assets | `android-v0.1.<n>`; `GVM-LED-0.1.<n>-android-unsigned.apk` (draft only), `GVM-LED-0.1.<n>-android.apk`, `phone-release.json`, `android-provenance.json`, `android-ci-reference.json` |
| 1Password | account `ETCB7LGCDNEP7PUD3FDNS5JXBU`, vault **`GVM-LED`** (own vault, never Command Deck's) |
| Vault items | Document `gvm-led-android-release-keystore` (`gvm-led-release.p12`, RSA-4096 PKCS12, alias `gvm-led-release`, ~30 y); Password `gvm-led-android-release-password` |
| Public pin | `src/mobile/releases/signing-certificate.json` (`certificateSha256`, `notAfter`) |

## Owner steps (run in this order)

Every step is a checked-in script; run it with `-DryRun` first. From any shell:
`powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\dirty\projects\gvm-led\scripts\owner\<step>.ps1`.
Logs: `.tmp\owner-runs\`. Agents run `-DryRun` only.

1. **Key provisioning** — `new-signing-key.ps1` (one Windows Hello / 1Password approval). Creates
   vault `GVM-LED` if absent, generates the key, stores both items, reads them back by item ID,
   re-opens the recovered copy and writes only the public pin into `signing-certificate.json`.
   Commit and push that file to `main`: CI refuses to prepare a release while no pin exists (the
   provenance binds the contract digest, which includes the pin). `-VerifyOnly` later recovers the
   key and checks it against the pin. Back up the vault; losing the key means every installed
   phone must uninstall and reinstall.
2. **Build the draft** — `gh workflow run release.yml --ref main --repo dirtyredz/gvm-led`. The run
   summary prints the tag, release ID and the exact signing command. A red test produces no draft.
3. **Sign and publish** — `publish-release.ps1 [-Tag android-v0.1.<n>] [-ReleaseId <id>]` (one
   Windows Hello / 1Password approval, at the signing step only). Creates a detached worktree at the
   tag's commit under `C:\Users\dirty\projects\.worktrees\gvm-led\sign-<tag>`, runs `npm ci` there and
   `secrets.js release-sign`, which requires the CI reference, authenticates its Actions run and
   exact artifact ID, and verifies the artifact's APK and provenance hashes against the draft plus
   versionCode monotonicity before reading any secret; then zipalign + apksigner (password via
   `env:`), single pinned signer and identity checks, upload of APK and manifest, re-download and hash
   comparison, deletion of only the unsigned asset, publish last as GitHub's latest release.
4. **Install** — `install-release.ps1 [-Tag ...] [-Serial ...]` (no 1Password prompt; the phone may
   ask to allow the USB install). Downloads the signed APK and manifest, checks SHA-256/size, the
   manifest signer and `apksigner verify` against the pin, `adb install -r`, then requires the
   installed versionCode to equal the manifest's ("confirmed installed").

Never bootstrap with a debug certificate: the permanent package is release-signed from its first
install. The debug app is a separate package and can stay installed alongside.

## Installing and updating on the phone (owner path)

The owner installs only from GitHub Releases on the phone: open
https://github.com/dirtyredz/gvm-led/releases/latest, download `GVM-LED-<version>-android.apk` and
open it (Android asks once to allow installs from the browser). After that, updates come from the
app: **Settings > Check for updates > Download and install > Install** (first time only, Android
also asks to allow installs from GVM LED). The app verifies size, SHA-256, package, version and the
pinned signer before opening Android's installer, and reports "Updated" only after reading the new
installed version back. Cancelling the installer offers Install again; the browser release page is
always offered as a fallback. Lights, names and groups are kept across updates (same package, same
key); confirm that on each acceptance run, together with the new version shown in Settings.
`install-release.ps1` (adb) remains a developer tool and is not part of owner acceptance.

## Recovery

- **A draft is pending:** a new Release run refuses (at `android-draft`, before any tag or draft
  write) while any other run's `android-v` draft exists. Finish it with `publish-release.ps1`, or
  delete it on GitHub after confirming nothing is mid-signing, then rerun. Rerunning failed jobs of
  the same run may replace that run's own unsigned draft.
- **Partial publication:** rerun `publish-release.ps1` with the same tag; it verifies already
  uploaded signed assets instead of re-signing. Conflicting or unknown assets are never clobbered.
- **Actions artifact expired or missing:** the signer refuses before opening 1Password because it
  can no longer prove the draft bytes came from CI. Run a new normal Release workflow; do not
  recreate or replace the CI-reference asset by hand.
- **Draft lost or never created, build artifact still retained (7 days):**
  `gh workflow run release-resume.yml --ref main -f source-run-id=<run> -f attempt=<n>`. It verifies
  the original run (finished `workflow_dispatch` of `release.yml` on `main`, `android-verify` and
  `android-build` successful in that attempt, exactly one live artifact created by that build) and
  restores the draft from the same bytes and version. It never rebuilds or signs. An expired
  artifact, or a version already superseded by a published release, needs a new Release run.
- **Leftover lock:** a crashed signer leaves `%TEMP%\dirtyredz-gvm-led-android-publish.lock`. If no
  signing run is active, delete it by hand; it is never broken automatically.
- **Leftover signing worktree:** `git worktree remove --force C:\Users\dirty\projects\.worktrees\gvm-led\sign-<tag>`.
- **Partial key provisioning:** `generate-key` reports public item IDs and never deletes. Remove the
  orphan item in 1Password by hand, then rerun.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** another signer's `com.dirtyredz.gvmled` is installed;
  uninstall it by hand (the script never uninstalls).

## Tooling map

`scripts/mobile/release.js` (CI: `next-version`, `version`, `prepare`, `draft`, `resume-source`,
`resume`) and `scripts/mobile/secrets.js` (owner PC: `generate-key`, `verify-key`, `release-sign`;
`--dry-run` opens nothing) over `src/mobile/releases/` (see STRUCTURE.md). Tests: `npm test`
(`node --test`, fakes only), run in `ci.yml` and before every release build.

## Acceptance still owed (dk-1851dff3, dk-6400c5cc)

- [ ] Key provisioned; pin committed; `-VerifyOnly` passes.
- [ ] One real CI draft; one `release-resume.yml` restore of a retained artifact.
- [ ] `publish-release.ps1` publishes; both read-back hashes match; unsigned asset gone.
- [ ] `install-release.ps1` confirms the installed versionCode on the owner's phone.
- [ ] A second, higher release installs over the first with the same certificate.
