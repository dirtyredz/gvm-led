---
id: dk-e7dd94c2
type: task
created: 2026-10-28
status: done
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

## Notes

### 2026-10-28T21:52:47.621Z · open · agent

Sign-off 2026-10-28 disagreed: ble/, connection/ and protocol/ are STRUCTURE.md-declared homes, and grouping them adds an artificial parent. Orchestrator kept the regroup per the owner's hierarchical-relief rule (cap 10 per level); raise with the owner if it reads badly.
