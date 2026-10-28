# STRUCTURE

## Overview

Custom controller for two GVM 800D3X-RGB lights over the legacy plain BLE GATT path. The tracked
BLE reference and owner-run proof tooling are complete. Android slice 1 provides the scaffold and
pure packet codec; slice 2 adds read-only BLE discovery, one session per light and live state.
Amendment A slices 2A-2D add Settings, owner-initiated GitHub updates with a verified installer
hand-off, a persistent N-light catalog with discovery, and named groups. Controls (wanted state,
command planning, confirmation) remain future slices.

## Architecture at a glance

Native Android app -> BLE GATT write-without-response to one characteristic per light -> Telink-based light.
See [ARCHITECTURE](docs/ARCHITECTURE.md).

## Layout

```
.github/
└── workflows/           # ci.yml fast gate; release.yml unsigned draft; release-resume.yml recovery
docs/
├── research/    # protocol write-up (static analysis + live findings)
├── plans/       # implementation plans
├── items/       # Docket work items (dk only)
└── mobile/      # Android distribution runbook (android-distribution.md)
src/
├── mobile/android/          # independent pinned Gradle build
│   ├── app/src/
│   │   ├── main/            # Android application and resources
│   │   └── test/            # local JUnit tests (mirrored packages, fake BLE)
│   └── gradle/              # wrapper and dependency verification
├── mobile/releases/         # Node release tooling: contract, CI draft, local signing (1Password)
└── research/ble/            # Python reference + visible proof run (bleak)
scripts/
├── mobile/      # thin Node CLIs: release.js (CI), secrets.js (local key/signing)
└── owner/       # owner-run PowerShell steps and plumbing
test/mobile/releases/  # node --test suites for the release tooling (fakes only)
.tmp/            # git-ignored: disposable research inputs and run output
```

**Enforced homes:**

- `src/mobile/android/` — independent pinned Gradle build, wrapper and dependency verification.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/` — process ownership (`GvmApplication`), explicit dependency wiring (`AppContainer`) and the activity (composition, lifecycle, permission/enable dialogs).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/ble/` — Android BLE adapters (`BleScanner`, `GattConnection`, `BluetoothRadio`) plus their pure helpers (`GattOperationQueue`, `BlePermissionPolicy`, `LegacyGattProfile`).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/connection/` — Android-free connection logic: the `LightTransport`/`LightScanner` seams, `DiscoveryFilter`, the shared scan and candidates (`LightDiscovery`), one `LightSession` per address, `ConnectionCoordinator` (per-address reconciliation), `ReconnectBackoff` and the visible state types.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/data/json/` — the app's small strict JSON reader/writer (`Json`), shared by update metadata and catalog persistence.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/data/model/` — persisted catalog values (`KnownLight`, `LightGroup`, `LightCatalog` and its validation).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/data/local/` — catalog persistence: pure version-1 migration/encoding (`LightCatalogCodec`) and the SharedPreferences adapter (`LightCatalogPreferences`).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/data/remote/` — bounded HTTPS with redirect/host policy (`HttpsRequests`), the GitHub release client and the streamed APK download.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/data/repository/` — the catalog boundary and serialized mutations (`LightCatalogStore`, `LightCatalogRepository`) and observations joined with names (`LightsRepository`).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/update/` — Android-free updater: bundled contract, manifest validation, version mapping, release policy, ready-file handling, state and the `UpdateController` state machine with its ports.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/update/android/` — platform adapters: contract asset loader, installed identity, APK inspection, signer digests by API level, installer/settings intents.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/protocol/` — Android-free CRC, shared frame format, packet encoding, typed message parsing and bounded stream decoding.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/feature/lights/` — Lights screen, target selection (`LightTargets`: individual lights and named groups), card rendering, ViewModel and display mapping (`LightsUiState`).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/feature/settings/` — Settings screen, app identity and update card (`SettingsUiState`); `lights/` light management (find, add, rename); `groups/` group list and editor.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/ui/navigation/` — `GvmApp`: destinations, the saved back stack and ViewModel factories (no navigation library).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/ui/components/` — shared composables: radio banner/actions, screen frame, name dialog.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/ui/theme/` — Material 3 root surface.
- `src/mobile/android/app/src/main/res/` — application strings, theme, launcher, backup policy and the FileProvider path (`xml/update_paths.xml`).
- `src/mobile/android/app/src/test/java/com/dirtyredz/gvmled/` — JUnit tests mirroring the production packages (`protocol/`, `ble/`, `connection/` with shared vectors plus scriptable transport/factory and scanner fakes, `data/{json,local,remote,repository}/`, `update/` and `update/android/`, `feature/{lights,settings}/`).
- `.github/workflows/` — `ci.yml` (strict Android unit, debug assembly and lint gate plus the release-tooling Node tests; no publication), `release.yml` (dispatch on main: version, verify, unsigned build, draft), `release-resume.yml` (restore a draft from a retained build artifact; never rebuilds or signs).
- `src/mobile/releases/` — release contract and identity (`release-contract.{json,js}`, `signing-certificate.json` public pin), `version.js` (allocation, CI identity), `manifest.js` (phone-release.json), `prepare.js` (CI unsigned staging).
- `src/mobile/releases/process/` — bounded, redacting child execution for release tools.
- `src/mobile/releases/publishing/` — shared draft/provenance policy and the attended local publication (`publish.js`, `command.js`, `draft-state.js`, `lock.js`, `ci-reference.js`, `assets.js`).
- `src/mobile/releases/publishing/ci/` — CI side: draft creation, tag claim, Actions evidence and retained-artifact resume.
- `src/mobile/releases/publishing/github/` — GitHub transports: token client (CI) and `gh` session (owner PC).
- `src/mobile/releases/publishing/steps/` — the publication stages: verify-source, sign, upload-and-verify, finalize.
- `src/mobile/releases/signing/` — keystore identity, key generation/verification, certificate checks, temp material, APK inspection and signing.
- `src/mobile/releases/secrets/` — 1Password plan (dry-run text, item names) and the SDK vault adapter.
- `scripts/mobile/` — `release.js` (CI: next-version, prepare, draft, resume-source, resume) and `secrets.js` (owner PC: generate-key, verify-key, release-sign).
- `test/mobile/releases/` — `node --test` suites mirroring `src/mobile/releases/`, with fakes for GitHub, build-tools and the vault (`helpers/`), and `fixtures/update-contract.json`, the phone-manifest cases the Android updater tests also read.
- `src/research/ble/` — Python reference for the legacy protocol: `protocol.py` (framing, CRC, state decode; pure), `client.py` (one light's connection, hello, ack), `camera.py` (Agent Eye capture), `live_test.py` (visible proof orchestration), `test_protocol.py` and `test_camera.py` (unittest), `requirements.txt` (pinned bleak)
- `scripts/owner/` — owner-run .ps1 steps: `owner-run.ps1` 1.0.0 (copied verbatim from ~/.claude/templates/scripts), `owner-run.test.ps1`, `example-owner-step.ps1`, and `prove-light-commands.ps1` (the slice-0 proof), plus the release steps `new-signing-key.ps1`, `publish-release.ps1`, `install-release.ps1` and their shared helpers in `release-tools.ps1`.

## Components

- **Research** — `docs/research/protocol.md`: source of truth for the protocol.
- **Android app** — `src/mobile/android/`: an Android-free codec (bounded stream decoder, typed replies), Android-free connection logic behind a transport seam, thin Android BLE adapters, a read-only Compose screen of live per-light state, Settings with GitHub updates, light management and named groups.
- **Owner-run plumbing** — `scripts/owner/`: logging/dry-run wrapper plus an example step and test.
- **BLE reference** — `src/research/ble/` contains the tracked codec, client, camera observer, proof runner and tests.

## Where to find things

Protocol framing, commands, lights' addresses: `docs/research/protocol.md`. Work: `dk list`.

## Structural debt

The useful BLE research code is tracked under `src/research/ble/`; `.tmp/` holds only disposable inputs and run output.

_Living doc — refresh with /project-docs when it drifts._
