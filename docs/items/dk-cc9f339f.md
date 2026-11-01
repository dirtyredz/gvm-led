---
id: dk-cc9f339f
type: task
created: 2026-11-01
status: todo
since: 2026-11-01
area: android
priority: P2
rank: zzzzzzzn
parent:
fixes: []
blocked_by: []
relates: [dk-1eff97a2]
---
# Colour wheel: split Wheel drawing from gesture handling; route rimInsetPx through updated state

ColorWheelPicker.kt:97-136: Wheel mixes hit-testing, gesture handling, size tracking and drawing. Its pointerInput(Unit) blocks capture at(), which closes over the plain value rimInsetPx (line ~112), so a density change leaves hit-testing on a stale inset. This is the same capture-once trap fixed in 83a18d5 (see GOTCHAS). Do it with dk-1eff97a2: one wheel-geometry value read via rememberUpdatedState, and a drawWheel(shown, radius) split. From the componentization and abstraction lenses on 83a18d5.
