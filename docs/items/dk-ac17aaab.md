---
id: dk-ac17aaab
type: task
created: 2026-10-28
status: todo
since: 2026-10-28
area: android
priority: P3
rank: zt
parent:
fixes: []
blocked_by: []
relates: []
---
# Tidy small tree nits: test mirroring, Json.kt home, STRUCTURE tree depth


Push review 2026-10-28 (topology). GroupsAndLightManagementStateTest sits in feature/settings but covers groups/ and lights/; data/json/ holds only Json.kt; test/mobile/releases has 11 children with fixtures/ holding one file; STRUCTURE.md tree omits the Kotlin packages and releases/ subfolders, and settings/lights and settings/groups lack their own bullets.
