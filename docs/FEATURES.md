# FEATURES

- Scan and identify both lights (`src/research/ble/`) — working
- Hello and state query, decode state reports (`-Check`) — working on both lights
- Visible commands: power, brightness, hue, saturation, CCT, mode and representative scenes — proven on both lights on 2026-10-26
- Colour-bug-free colour set (mode select then values) — proven on both lights on 2026-10-26
- Control of both lights as a pair — proven by the reference runner; app support planned
- Native Android placeholder shell and pure Kotlin legacy packet codec — implemented in slice 1
- BLE connections and app controls — planned; not installed/accepted on hardware

_Living doc — refresh with /project-docs when it drifts._
