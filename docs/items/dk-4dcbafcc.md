---
id: dk-4dcbafcc
type: task
created: 2026-10-28
status: todo
since: 2026-10-28
area: android
priority: P3
rank: w
parent:
fixes: []
blocked_by: []
relates: []
---
# Move ReconnectBackoff to its own file with direct tests


Push review 2026-10-28 (componentization). ReconnectBackoff sits in ConnectionCoordinator.kt:24 and has no direct test. Move to connection/ReconnectBackoff.kt next time the coordinator is touched and add a unit test for its delays.
