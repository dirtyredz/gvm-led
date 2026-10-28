# ROADMAP

1. Research, scan and tracked BLE reference — done (`src/research/ble/`)
2. Prove the visible commands on both lights — done 2026-10-26 (dk-3931270b)
3. Choose native Android as the app platform — done (dk-256c3663; see `plans/android-app.md`)
4. Build the native Android app (`plans/android-app.md` §3) — slice 1 (scaffold and codec) and
   slice 2 (BLE discovery and read-only live state, dk-d19640b7) done; slice 2 awaits the on-phone check.
   Next: slice 3, wanted-state persistence and control planning. Slices 6-7 (unsigned CI release,
   local 1Password signing, adb install; dk-1851dff3, dk-6400c5cc) are implemented and unit-tested
   ahead of 3-5; their CI, signing and on-phone acceptance is owed
   ([android-distribution.md](mobile/android-distribution.md)).

_Living doc — refresh with /project-docs when it drifts._
