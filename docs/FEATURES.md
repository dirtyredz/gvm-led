# FEATURES

- Scan and identify both lights (`src/research/ble/`) — working
- Hello and state query, decode state reports (`-Check`) — working on both lights
- Visible commands: power, brightness, hue, saturation, CCT, mode and representative scenes — proven on both lights on 2026-10-26
- Colour-bug-free colour set (mode select then values) — proven on both lights on 2026-10-26
- Control of both lights as a pair — proven by the reference runner; app support planned
- Native Android placeholder shell and pure Kotlin legacy packet codec — implemented in slice 1
- Android BLE discovery and read-only live state (both lights by address, one session each, hello,
  notifications, acknowledged 0x03 reports shown per light; renaming; permission and Bluetooth-off
  states) — implemented in slice 2 and unit-tested with a fake transport; not yet confirmed on the phone
- Android app controls (set commands, wanted state, confirmation) — planned (slices 3-5)
- Android release pipeline: CI unsigned APK on a draft release, retained-artifact draft recovery,
  local signing with a GVM-only key from 1Password, verified publication and `adb` install with
  installed-version read-back (`scripts/owner/{new-signing-key,publish-release,install-release}.ps1`)
  — implemented and unit-tested (slices 6-7); no key, CI run, release or install yet

_Living doc — refresh with /project-docs when it drifts._
