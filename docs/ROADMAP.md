# ROADMAP

1. Research, scan and tracked BLE reference — done (`src/research/ble/`)
2. Prove the visible commands on both lights — done 2026-10-26 (dk-3931270b)
3. Choose native Android as the app platform — done (dk-256c3663; see `plans/android-app.md`)
4. Build the native Android app (`plans/android-app.md` §3) — slice 1 (scaffold and codec) and
   slice 2 (BLE discovery and read-only live state, dk-d19640b7) done; slice 2 awaits the on-phone check.
   Next: slice 3, wanted-state persistence and control planning. Slices 6-7 (unsigned CI release,
   local 1Password signing, adb install; dk-1851dff3, dk-6400c5cc) are implemented and unit-tested
   ahead of 3-5; their CI, signing and on-phone acceptance is owed
   ([android-distribution.md](mobile/android-distribution.md)). Signed 0.1.1 is published.
5. Plan Amendment A (`plans/android-app.md`): 2A Settings and update check (dk-368560e7) -> 2B verified
   install (dk-e4d11f1a) -> 2C N-light catalog and discovery (dk-fbd96793) -> 2D named groups
   (dk-56cb775c), implemented on branch `settings-and-groups`, owner acceptance pending. Then revised
   3 (per-light wanted state, dk-3189df72) -> revised 4 (confirmed apply and colour fix, dk-84d5132d) ->
   revised 5 (individual and group controls, dk-b31f4e95).

_Living doc — refresh with /project-docs when it drifts._
