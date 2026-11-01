---
id: dk-1eff97a2
type: task
created: 2026-11-01
status: todo
since: 2026-11-01
area: android
priority: P2
rank: zzzzzzz
parent:
fixes: []
blocked_by: []
relates: [dk-9ebfa71a]
---
# Colour wheel: compute wheel geometry once for draw and hit-test

ColorWheelPicker.kt:96-117: hit-testing (at(), reading the shared size state) and the Canvas draw scope each call wheelRadius(...) with their own inputs. size.width doubles as the height, which only works because of aspectRatio(1f). Compute a small WheelGeometry(centre, radius) once from the measured size and have both paths read it. Do it when the file is next touched, e.g. together with dk-9ebfa71a. From the abstraction lens on f0ffe48.
