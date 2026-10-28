---
id: dk-e7dd94c2
type: task
created: 2026-10-28
status: todo
since: 2026-10-28
area: android
priority: P2
rank: zzzn
parent:
fixes: []
blocked_by: []
relates: []
---
# Group ble/connection/protocol under one light-transport parent before control/


Push review 2026-10-28 (topology). App package root is at the 10-entry cap; slice 3's control/ would make 11. Group ble/, connection/, protocol/ under one responsibility parent and place control/ inside it or under feature/. Handed to the controls build as its first commit.
