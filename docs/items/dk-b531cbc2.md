---
id: dk-b531cbc2
type: task
created: 2026-10-31
status: todo
since: 2026-10-31
area: android
priority: P2
rank: zzzzzn
parent:
fixes: []
blocked_by: []
relates: []
---
# Shared SHA-256 digest helper for the Android updater


Baseline review 2026-10-31 (abstraction lens).

Facts: ByteArray.toHex() lives in update/UpdateFiles.kt:8 but is used by data/remote/ApkDownloader.kt:7 and update/android/SignerDigests.kt:19; the streaming SHA-256 loop is duplicated in UpdateFiles.readyMatches (51-60) and ApkDownloader.kt:34-45.

Direction: extract a small digest helper owning hex formatting and the stream loop; use it from the three call sites.
