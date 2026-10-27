# gvm-led

Custom controller for two GVM 800D3X-RGB lights. The lights speak a plain BLE GATT protocol
(`BT_LED`), so no cloud or mesh is needed. See `docs/research/protocol.md`.

Status: the BLE reference and visible command proof passed on both lights on 2026-10-26. Native
Android is the chosen app platform, with step 1 in progress on a separate branch.
Work items: `dk list`. Docs: `STRUCTURE.md` and `docs/`.
