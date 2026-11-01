---
id: dk-9ebfa71a
type: task
created: 2026-11-01
status: todo
since: 2026-11-01
area: android
priority: P2
rank: zzzzzzy
parent:
fixes: []
blocked_by: []
relates: []
---
# Colour wheel: derive THUMB_INSET from a named thumb radius

ColorWheelPicker.kt: THUMB_INSET (16dp) is documented as covering the thumb (14dp radius including stroke), but the thumb is drawn with the literals 12.dp, 14.dp and 3.dp. Name the thumb radius as one private constant and derive both the draw calls and the inset from it, so a thumb resize can't push it out of bounds again. From the structure review of f0ffe48.
