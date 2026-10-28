---
id: dk-be833fab
type: task
created: 2026-10-28
status: todo
since: 2026-10-28
area: android
priority: P2
rank: zzzzn
parent:
fixes: []
blocked_by: []
relates: []
---
# Controls: one Field vocabulary with limits, read/write and report accessors


Push review 2026-10-28 (componentization + abstraction). Field sits at the bottom of light/control/ModeCommandPlanner.kt:77 and only half-covers the wanted set; StateMatcher.reported, LightEdit.applyTo/pick, TargetStateReducer.reduce and WantedStateCodec encode/decode each re-enumerate the fields. Legal ranges are spread across TargetEditPlanner.kt:66, ModeCommandPlanner.kt:37/46, WantedStateCodec.kt:93 and LightControls.kt:50,98,99 (UI hue 0..71 vs Field.Hue 0..72; brightness has no Field). Direction: Field in its own file beside WantedState, carrying limits, read/write and a reported(FullStateReport) accessor; consumers and UI slider bounds iterate it.
