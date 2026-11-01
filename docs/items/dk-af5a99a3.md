---
id: dk-af5a99a3
type: task
created: 2026-10-31
status: todo
since: 2026-10-31
area: android
priority: P2
rank: zzzzzz
parent:
fixes: []
blocked_by: []
relates: []
---
# Move BluetoothAddress from data/model into light/


Baseline review 2026-10-31 (sign-off).

Facts: light/ble/BleScanner and light/connection/{ConnectionCoordinator,DiscoveryFilter,LightSession} import data.model.BluetoothAddress while data/repository imports light/*, so light and data depend on each other.

Direction: move BluetoothAddress into light/ (connection/ or an identity folder); data imports it from there.
