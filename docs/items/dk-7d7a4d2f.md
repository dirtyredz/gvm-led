---
id: dk-7d7a4d2f
type: task
created: 2026-10-26
status: todo
since: 2026-10-26
area: android
priority: P2
rank: zz
parent:
fixes: []
blocked_by: []
relates: []
---
# Name the query-length rule (queryLength(deviceType))

Push review (abstraction) 2026-10-26: LegacyPacketEncoder.kt:14 hard-codes device type 0x30 -> query length 5 else 4, duplicating src/research/ble/protocol.py:48. Direction: a named queryLength(deviceType) in the protocol package, pinned with a test vector from the Python reference.
