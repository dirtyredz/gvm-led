# DECISIONS

## 2026-10-26 — Use the legacy GATT path, not Tuya mesh
**Why:** The static analysis of the official app (v1.8.0) suggested Tuya SIG mesh, but a PC scan (2026-10-25) found both lights advertising as `BT_LED` with the legacy service/characteristic UUIDs and no 0x1827/0x1828 mesh advertisement. A live `--check` on both lights got valid CRC replies and state reports with the legacy framing. The path needs no cloud, keys, provisioning or factory reset, and has an explicit mode-select opcode (06) that targets the colour bug.
**Rejected:** Tuya mesh (needs reset and re-provisioning into our own mesh or pulling keys from Tuya's cloud with the vendor OEM secret); mesh does not apply to these lights anyway.

## 2026-10-26 — Keep the codec pure and camera observation optional
**Decision:** `src/research/ble/protocol.py` is the pure codec and protocol specification for the Android port; BLE connection and session behavior stay in `client.py`. Proof runs may use Agent Eye (`snapshot.mjs shot`) as an optional camera observer.
**Why:** Separating byte-level protocol rules from transport makes the reference portable and independently testable. Agent Eye supplies useful visual evidence without making an external, cross-repository tool a requirement for protocol tests or normal operation.

_Living doc — refresh with /project-docs when it drifts._
