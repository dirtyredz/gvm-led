# GOTCHAS

- **Android builds are independent:** use the fast gate in CLAUDE.md, with Java 21 and ANDROID_HOME.
  Offline needs prepopulated Gradle caches; never silently change pins to bypass a missing artifact.
- **Linux CI needs an executable wrapper and Linux aapt2 checksums**, even when developed on Windows.
- **The smaller app graph needs extra checksums:** CD metadata did not include annotation-jvm 1.8.1,
  collection-jvm 1.4.2, concurrent-futures 1.0.0 metadata or tracing 1.2.0. Slice 1 records their
  cached checksums (JAR/AAR hashes also match cached module metadata), without changing version pins.
- **Wire inputs are untrusted:** reject bad CRC, unsupported lengths/markers and invalid fields before
  consuming capabilities/state. Reset each decoder on connection retirement; short replies are not full state.
- **Vector provenance:** this checkout's protocol.md ends after read-only checks, without set TX logs.
  Tests freeze hello plus real reports/query from `live-test-20261026-001355.log`,
  real short replies from `live-test-20261026-001316.log`, and set TX from the Python DRY RUN
  `live-test-20261026-001349.log` (type 30 placeholder), read from main's ignored
  `.tmp/owner-runs/`. Dry-run set vectors prove encoding parity, not visible hardware effects.

- **Windows connect fails with "device not found"** -> bleak/WinRT needs the device seen in a scan first -> scan, then connect (the scripts do).
- **Connect refused or characteristic missing** -> a light accepts one connection; the phone app (or a previous connection) holds it -> close the GVM app; connect to lights sequentially; retry once after a disconnect ("Characteristic not found").
- **Packet ignored** -> the app's `\r\n` becomes a trailing `FF` byte on the wire (hex parsing maps the invalid chars to -1) -> always append `FF`; the no-FF variant is untested.
- **Writes error or are dropped** -> the characteristic is write-without-response only -> write with response=False.
- **Mesh opcodes do nothing** -> sections 2-3 of protocol.md (A1/A2/C1/5B...) are the mesh path; these lights use the legacy set -> use CMD 00-08 in protocol.md.
- **Resolution** -> legacy brightness is 1 % steps, hue 5 degree steps.
- **Ack format** -> `ack_packet` (kind 48 + echoed 0x03 payload) follows `ControlUtil.responseDev`; the 2026-10-26 run showed no ill effect (lights kept reporting), though no visible effect of the ack itself was proven.
- **Setting params while off** -> the light stores them and a mode change does not power it on -> in the app send full settings, then power on last (either order works).
- **`snapshot.mjs shot` stdout has three lines** (`log:`, JSON, `OK`) -> parse the line starting with `{`, not the last line.
- **Never send sets before a valid hello** -> the old dry run assumed type 30, the lights are type 33 -> `client.py` refuses sets and queries until hello parsed.

_Living doc — refresh with /project-docs when it drifts._
