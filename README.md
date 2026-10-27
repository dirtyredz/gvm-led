# gvm-led

Custom controller for two GVM 800D3X-RGB lights. The lights speak a plain BLE GATT protocol
(`BT_LED`), so no cloud or mesh is needed. See `docs/research/protocol.md`.

Status: the BLE reference and visible command proof passed on both lights on 2026-10-26. Native
Android scaffold and pure packet codec live under `src/mobile/android/`. The app displays a
placeholder; BLE and controls are later slices.
Build and verification commands: `CLAUDE.md`.
Work items: `dk list`. Docs: `STRUCTURE.md` and `docs/`.
