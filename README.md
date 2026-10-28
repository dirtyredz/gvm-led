# gvm-led

Custom controller for two GVM 800D3X-RGB lights. The lights speak a plain BLE GATT protocol
(`BT_LED`), so no cloud or mesh is needed. See `docs/research/protocol.md`.

Status: the BLE reference and visible command proof passed on both lights on 2026-10-26. The
native Android app under `src/mobile/android/` discovers both lights and shows their live state
(read-only); controls are later slices.
Releases are CI-built unsigned, signed locally with a GVM key from 1Password and sideloaded:
`docs/mobile/android-distribution.md`.
Build and verification commands: `CLAUDE.md`.
Work items: `dk list`. Docs: `STRUCTURE.md` and `docs/`.
