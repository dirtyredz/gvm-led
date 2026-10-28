---
id: dk-b5fc7bfe
type: task
created: 2026-10-28
status: todo
since: 2026-10-28
area: android
priority: P3
rank: t
parent:
fixes: []
blocked_by: []
relates: []
---
# Split LightSession write pacing and report filtering when controls arrive


Push review 2026-10-28 (componentization). connection/LightSession.kt holds state publishing, the handshake, notification decoding, identity filtering and write pacing. Cohesive now (169 lines); when slice 3/4 adds set-command writes, extract a PacedWriter and identity-checked report handling, keeping LightSession as lifecycle/state owner. Same trigger: split LightsUiState.kt display mapping into LightCardMapper.kt with slice 5.
