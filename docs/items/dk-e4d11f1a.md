---
id: dk-e4d11f1a
type: feature
created: 2026-10-28
status: wip
since: 2026-10-28
area: android
priority: P1
rank: yt
parent:
fixes: []
blocked_by: [dk-368560e7]
relates: []
---
# Android step 2B: verified APK download and installer hand-off


Slice 2B of docs/plans/android-app.md Amendment A. Download and install: stream the signed APK to a private .part file under the contract ceiling, verify size and SHA-256 against the validated manifest, inspect package/version/minSdk/signer against the bundled pin and the installed identity, then hand a FileProvider content URI to the Android installer. REQUEST_INSTALL_PACKAGES with the unknown-sources settings excursion; Open release page fallback; cancellation and restart clean incomplete files; success only from the installed version read back.

Acceptance (owner, on the phone): installing a higher signed release over the existing one keeps data (names, later groups); first-use permission, denial and installer cancellation recover; record actual taps (target: Check, Download and install, Install).
