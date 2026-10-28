# FEATURES

- Scan and identify both lights (`src/research/ble/`) — working
- Hello and state query, decode state reports (`-Check`) — working on both lights
- Visible commands: power, brightness, hue, saturation, CCT, mode and representative scenes — proven on both lights on 2026-10-26
- Colour-bug-free colour set (mode select then values) — proven on both lights on 2026-10-26
- Control of both lights as a pair — proven by the reference runner; app support planned
- Native Android placeholder shell and pure Kotlin legacy packet codec — implemented in slice 1
- Android BLE discovery and read-only live state (both lights by address, one session each, hello,
  notifications, acknowledged 0x03 reports shown per light; renaming; permission and Bluetooth-off
  states) — implemented in slice 2; both lights reached Live in the owner's 0.1.1 test (2026-10-28)
- Android Settings with installed version and owner-initiated GitHub update check (2A) — implemented
  and unit-tested; owner acceptance pending (needs a release newer than 0.1.1)
- Android verified in-app download and hand-off to the system installer, browser fallback (2B) —
  implemented and unit-tested; on-phone installer, permission and data-retention acceptance pending
- Any number of lights: persistent catalog (version-1 names migrated), Find, Add and Rename in
  Settings (2C) — implemented and unit-tested with three fake lights; N>2 hardware acceptance pending
- Named groups: create, edit, delete; Lights target chips, read-only (2D) — implemented and unit-tested
- Per-light wanted state, persisted separately and adopted from the first report (revised slice 3)
  — implemented and unit-tested; owner acceptance is the upgrade/restart check
- Confirmed apply with reconnect recovery: mode-first full replay on every connect, resume and edit,
  confirmation only from a fresh full report, 3 rounds then Not confirmed and reconnect (revised
  slice 4, the colour-bug fix) — implemented and unit-tested; on-light acceptance pending
- Individual and named-group controls: power, mode, brightness, CCT (members' range intersection),
  hue (5-degree steps), saturation, scenes 1-20 with applicable parameters, Mixed/Unknown values and
  per-member status (revised slice 5) — implemented and unit-tested; on-light acceptance pending.
  Owner steps: [controls-acceptance.md](mobile/controls-acceptance.md)
- Android release pipeline: CI unsigned APK on a draft release, retained-artifact draft recovery,
  local signing with a GVM-only key from 1Password, verified publication and `adb` install with
  installed-version read-back (`scripts/owner/{new-signing-key,publish-release,install-release}.ps1`)
  — implemented; signed release 0.1.1 (tag android-v0.1.1, versionCode 2) is published and under
  owner test. The owner installs by downloading the signed APK from GitHub Releases on the phone

_Living doc — refresh with /project-docs when it drifts._
