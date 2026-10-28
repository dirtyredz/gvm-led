---
id: dk-ccdb45a0
type: task
created: 2026-10-28
status: todo
since: 2026-10-28
area: android
priority: P3
rank: zw
parent:
fixes: []
blocked_by: []
relates: []
---
# STRUCTURE.md tree: show light/ grouping; nest connection/session when it grows


Push review 2026-10-28 (topology). The Layout tree stops at main/ and test/, hiding light/{ble,connection,protocol,control}; the light/protocol bullet sits after update/android. light/connection has 11 files (cap 12): nest session/ (LightSession, SessionWriter, LightCommandPort) when it grows. The topology lens judged light/ a real boundary, not artificial.
