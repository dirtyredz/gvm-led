# STRUCTURE

## Overview

Custom controller for two GVM 800D3X-RGB lights over the legacy plain BLE GATT path. The tracked
BLE reference and owner-run proof tooling are complete. Android slice 1 provides the scaffold and
pure packet codec; slice 2 adds read-only BLE discovery, one session per light and live state.
Controls (wanted state, command planning, confirmation) remain future slices.

## Architecture at a glance

Native Android app -> BLE GATT write-without-response to one characteristic per light -> Telink-based light.
See [ARCHITECTURE](docs/ARCHITECTURE.md).

## Layout

```
.github/
└── workflows/ci.yml       # strict Android unit, assembly and lint gate
docs/
├── research/    # protocol write-up (static analysis + live findings)
├── plans/       # implementation plans
└── items/       # Docket work items (dk only)
src/
├── mobile/android/          # independent pinned Gradle build
│   ├── app/src/
│   │   ├── main/            # Android application and resources
│   │   └── test/            # local JUnit tests (mirrored packages, fake BLE)
│   └── gradle/              # wrapper and dependency verification
└── research/ble/            # Python reference + visible proof run (bleak)
scripts/
└── owner/       # owner-run PowerShell steps and plumbing
.tmp/            # git-ignored: disposable research inputs and run output
```

**Enforced homes:**

- `src/mobile/android/` — independent pinned Gradle build, wrapper and dependency verification.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/` — process ownership (`GvmApplication`), explicit dependency wiring (`AppContainer`) and the activity (composition, lifecycle, permission/enable dialogs).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/ble/` — Android BLE adapters (`BleScanner`, `GattConnection`, `BluetoothRadio`) plus their pure helpers (`GattOperationQueue`, `BlePermissionPolicy`, `LegacyGattProfile`).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/connection/` — Android-free connection logic: the `LightTransport`/`LightScanner` seams, `DiscoveryFilter`, one `LightSession` per address, `ConnectionCoordinator` and the visible state types.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/data/local/` — versioned SharedPreferences (`LightPreferences`: addresses, names).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/data/repository/` — per-light observations joined with names (`LightsRepository`).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/protocol/` — Android-free CRC, shared frame format, packet encoding, typed message parsing and bounded stream decoding.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/feature/lights/` — light list screen, card rendering, ViewModel and display mapping (`LightsUiState`).
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/ui/theme/` — Material 3 root surface.
- `src/mobile/android/app/src/main/res/` — application strings, theme, launcher and backup policy.
- `src/mobile/android/app/src/test/java/com/dirtyredz/gvmled/` — JUnit tests mirroring the production packages (`protocol/`, `ble/`, `connection/` with the fake transport and scanner, `data/repository/`, `feature/lights/`).
- `.github/workflows/ci.yml` — strict Android unit, debug assembly and lint gate; no publication.
- `src/research/ble/` — Python reference for the legacy protocol: `protocol.py` (framing, CRC, state decode; pure), `client.py` (one light's connection, hello, ack), `camera.py` (Agent Eye capture), `live_test.py` (visible proof orchestration), `test_protocol.py` and `test_camera.py` (unittest), `requirements.txt` (pinned bleak)
- `scripts/owner/` — owner-run .ps1 steps: `owner-run.ps1` 1.0.0 (copied verbatim from ~/.claude/templates/scripts), `owner-run.test.ps1`, `example-owner-step.ps1`, and `prove-light-commands.ps1` (the slice-0 proof)

## Components

- **Research** — `docs/research/protocol.md`: source of truth for the protocol.
- **Android app** — `src/mobile/android/`: an Android-free codec (bounded stream decoder, typed replies), Android-free connection logic behind a transport seam, thin Android BLE adapters, and a read-only Compose screen of live per-light state.
- **Owner-run plumbing** — `scripts/owner/`: logging/dry-run wrapper plus an example step and test.
- **BLE reference** — `src/research/ble/` contains the tracked codec, client, camera observer, proof runner and tests.

## Where to find things

Protocol framing, commands, lights' addresses: `docs/research/protocol.md`. Work: `dk list`.

## Structural debt

The useful BLE research code is tracked under `src/research/ble/`; `.tmp/` holds only disposable inputs and run output.

_Living doc — refresh with /project-docs when it drifts._
