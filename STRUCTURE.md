# STRUCTURE

## Overview

Custom controller for two GVM 800D3X-RGB lights over the legacy plain BLE GATT path. The tracked
BLE reference and owner-run proof tooling are complete. Slice 1 provides the native Android
application scaffold and pure packet codec. BLE sessions and controls remain future slices.

## Architecture at a glance

Native Android app -> BLE GATT write-without-response to one characteristic per light -> Telink-based light.
See [ARCHITECTURE](docs/ARCHITECTURE.md).

## Layout

- `src/mobile/android/` — independent pinned Gradle build, wrapper and dependency verification.
- `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/` — Android activity composition.
- Its `protocol/` package — Android-free CRC, packet encoding, typed replies and bounded frame decoding.
- Its `feature/lights/` package — placeholder rendering; `ui/theme/` — Material 3 root surface.
- `src/mobile/android/app/src/main/res/` — application strings, theme, launcher and backup policy.
- `src/mobile/android/app/src/test/java/com/dirtyredz/gvmled/protocol/` — mirrored JUnit codec tests.
- `.github/workflows/ci.yml` — strict Android unit, debug assembly and lint gate; no publication.

```
docs/
├── research/    # protocol write-up (static analysis + live findings)
├── plans/       # implementation plans
└── items/       # Docket work items (dk only)
src/
└── research/ble/  # Python reference + visible proof run (bleak)
scripts/
└── owner/       # owner-run PowerShell steps and plumbing
.tmp/            # git-ignored: disposable research inputs and run output
```

**Enforced homes:**

- `src/research/ble/` — Python reference for the legacy protocol: `protocol.py` (framing, CRC, state decode; pure), `client.py` (one light's connection, hello, ack), `camera.py` (Agent Eye capture), `live_test.py` (visible proof orchestration), `test_protocol.py` and `test_camera.py` (unittest), `requirements.txt` (pinned bleak)
- `scripts/owner/` — owner-run .ps1 steps: `owner-run.ps1` 1.0.0 (copied verbatim from ~/.claude/templates/scripts), `owner-run.test.ps1`, `example-owner-step.ps1`, and `prove-light-commands.ps1` (the slice-0 proof)

## Components

- **Research** — `docs/research/protocol.md`: source of truth for the protocol.
- **Owner-run plumbing** — `scripts/owner/`: logging/dry-run wrapper plus an example step and test.
- **BLE reference** — `src/research/ble/` contains the tracked codec, client, camera observer, proof runner and tests.

## Where to find things

Protocol framing, commands, lights' addresses: `docs/research/protocol.md`. Work: `dk list`.

## Structural debt

The useful BLE research code is tracked under `src/research/ble/`; `.tmp/` holds only disposable inputs and run output.

_Living doc — refresh with /project-docs when it drifts._
