---
id: dk-ae9c0efc
type: bug
created: 2026-11-01
status: todo
since: 2026-11-01
area: android
priority: P1
rank: zz
parent:
fixes: []
blocked_by: []
relates: []
---
# Colour wheel preview stops following subsequent drags after first change

Owner reports unchanged colour-wheel drag failure on release 0.1.6 (20742b7), including fix 83a18d5.

Diagnosis reproduced the failure in the real Compose picker under Robolectric/API 34, without a scrolling parent, ViewModel, BLE, or a light. First drag previews and commits 90 degrees / 58 percent. Second drag computes 180 degrees / 58 percent but displays 90 degrees while held; releasing correctly commits 180 degrees. A callback-replacement test receives generations [0,0] instead of [0,1].

Cause: ColorWheelPicker.kt:81 passes local callable references ::preview and ::commit. The actual compiled reference objects compare equal despite different captured known values/callbacks. rememberUpdatedState's structural-equality state retains the original reference. The second drag writes a Pick with the initial base (0,100), so displayedSelection hides it against current wanted input (18,58). Inputs are wanted values, not hardware-confirmed values.

A throwaway experiment replacing only the two callable references with capturing lambdas passed both tests. Restoring the uninstrumented original source reproduced both failures again. No fix was retained or committed. All temporary build/source changes were removed; diagnostic fixtures remain under the worktree's .tmp/diag directory.

Implementation acceptance: promote the two failing Compose regressions; replace the local references with lambdas while retaining un-keyed pick state and rememberUpdatedState; require correct held preview with one commit per release and current callback dispatch; run the documented strict Android gate. Correct misleading confirmed-value wording and the GOTCHAS claim that 0.1.6 fixed this completely. Physical-light acceptance remains outstanding.

Full evidence and fix specification: C:/Users/dirty/projects/gvm-led/.tmp/diag/wheel-drag-report.md. Archived test XML/logs: sibling wheel-drag-evidence directory. Diagnostic source fixtures remain in C:/Users/dirty/projects/.worktrees/gvm-led/diag-wheel-drag/.tmp/diag.
