---
id: dk-f2f6ee10
type: bug
created: 2026-10-28
status: todo
since: 2026-10-28
area: android
priority: P2
rank: zzzzy
parent:
fixes: []
blocked_by: []
relates: []
---
# Observed-state card shows hue 72 as 360° instead of 0°


Controls sign-off round 3, 2026-10-28 (P2). feature/lights/LightsUiState.kt:114 renders the observed state from the raw report.hue, so a reported hue of 72 shows as 360° while the wanted state and the controls show the canonical 0°. Direction: render it through Field.read(report) / Field.Hue.normalize.
