# GOTCHAS

- **Android builds are independent:** use the fast gate in CLAUDE.md, with Java 21 and ANDROID_HOME.
  Offline needs prepopulated Gradle caches; never silently change pins to bypass a missing artifact.
- **Linux CI needs an executable wrapper and Linux aapt2 checksums**, even when developed on Windows.
- **The smaller app graph needs extra checksums:** CD metadata did not include annotation-jvm 1.8.1,
  collection-jvm 1.4.2, concurrent-futures 1.0.0 metadata or tracing 1.2.0. Slice 1 records their
  cached checksums (JAR/AAR hashes also match cached module metadata), without changing version pins.
  The complete lint graph additionally needs the cached listenablefuture 1.0 JAR and Lifecycle 2.6.2
  common/runtime module descriptors; their cache-key SHA-1s and content coordinates were checked
  before recording their SHA-256 values.
- **Wire inputs are untrusted:** reject bad CRC, unsupported lengths/markers and invalid fields before
  consuming capabilities/state. Reset each decoder on connection retirement; short replies are not full state.
- **Vector provenance:** this checkout's protocol.md ends after read-only checks, without set TX logs.
  Tests freeze hello plus real reports/query from `live-test-20261026-001355.log`,
  real short replies from `live-test-20261026-001316.log`, and set TX from the Python DRY RUN
  `live-test-20261026-001349.log` (type 30 placeholder), read from main's ignored
  `.tmp/owner-runs/`. Dry-run set vectors prove encoding parity, not visible hardware effects.
- **Android: never filter scans by the legacy service.** The lights advertise 0x180F/0x1812, not
  `...1910`; scan unfiltered, identify by address, validate the service after discovery.
- **Android: connect with the scanned `BluetoothDevice`.** The addresses are random static; a device
  built with `getRemoteDevice` may assume a public address type. A reconnect waits for a fresh sighting.
- **Android: CCCD before hello.** Enable notifications locally, write the CCCD and wait for
  `onDescriptorWrite` before the first hello, or the hello reply can be missed. One outstanding GATT
  operation at a time (`GattOperationQueue`); no-response writes still complete via `onCharacteristicWrite`.
- **Android: one `BluetoothGatt` per light, always closed.** A leaked or second GATT client holds the
  light's only connection (like the phone app does); every failed or ended generation calls `close()`.
- **Android: scan budget.** Starting scans more than 5 times in 30 s silently returns nothing; the
  coordinator scans only while a light is searching. Through API 30, scans also need Location on.
- **Rear knobs are disabled in app mode** (owner hardware fact, 0.1.1 test 2026-10-28): while a light is under app control its physical controls do nothing, so state cannot change from the light during an app session. Never use the knobs as a test or acceptance step; change state from this app, or beforehand from the official app or a power cycle.
- **Reports arrive about every 4 s** after hello; an idle screen with a Live status is normal between them.
  All packets fit the default 23-byte MTU (largest is an 18-byte ack), so no MTU request is made.
- **Hello capabilities are generation-current.** A retained hello is history after a link leaves Live;
  CCT validation, group intersection and UI ranges use `LightConnection.currentHello`, so one offline
  member disables combined CCT until every selected member has a live range again.

- **Release: no pin, no release.** CI's `prepare` refuses while `signing-certificate.json` has no
  `certificateSha256`, because provenance binds the contract digest (which includes the pin): a draft
  built before the pin is committed could never be signed. Provision the key and push the pin first.
- **Release: a pending draft blocks the next one.** `android-draft` refuses while another run's
  `android-v` draft exists (allocation ignores drafts, so the next run would reuse its number). Publish
  or delete it first. Only the `android-draft` job can see drafts (the `version` job's read token cannot).
- **Release: workflows only run on `main` in `dirtyredz/gvm-led`** (every job's `if:`); a dispatch on
  another branch skips everything and builds nothing. CD's equivalents use `master`.
- **Release: the signer needs a clean checkout at the tag.** `publish-release.ps1` makes a throwaway
  worktree for that; running `secrets.js release-sign` by hand from a dirty or wrong checkout refuses.
- **Owner scripts: never name a variable `$secrets` (any case).** PowerShell variables are
  case-insensitive, so it overwrites `owner-run.ps1`'s `$script:Secrets` redaction list and the log
  shows `[REDACTED]` for whatever you stored. Use specific names (`$secretsJs`).
- **Release tooling is Node 22 ESM with JSON import attributes** (`with { type: "json" }`); older
  Node fails at import. CI pins 22.20.0; `npm ci` installs `@1password/sdk` plus `yauzl` for bounded
  inspection of the exact Actions artifact (both dev dependencies).

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

- **Updates: install-source permission.** Android asks once per app ("Install unknown apps"); the app
  opens that settings page only when the owner taps Install, rechecks on return and never re-prompts
  by itself. Denial leaves Open release page usable.
- **Updates: debug and release are different apps.** `com.dirtyredz.gvmled.debug` can check but never
  installs a release (a stable APK is not its update). Only a release signed with the pinned key can
  update the installed release; a different signer means uninstalling, which the app never does.
- **Updates: no `phone-release.json`, no direct install.** A release without the manifest shows
  "Release found; update metadata unavailable" with the release page only, never "up to date". The
  updater compares versionCode, never version strings.
- **Updates: archive signer reads.** `getPackageArchiveInfo` may return no `SigningInfo` for an
  archive on some API 28+ builds; `ApkInspector` falls back to legacy signatures and refuses
  (fails closed) when no signer is readable. Needs on-device confirmation on API 26 and current.
- **Discovery: scan demand.** Find is a bounded 30 s window on the same shared scan as reconnects;
  repeated taps extend it rather than restarting the scan (the 5-starts-in-30-s budget). A sighting
  is used up by one connection attempt, so a removed and re-added light needs a new advert.
- **Inactive report fields** (revised slice 3): scene 0 and effect time 0 appear in reports; reported
  CCT still needs hello-range validation. Do not adopt inactive fields as wanted defaults.
- **Notification freshness** (revised slice 4): processing time is not receipt time; queued old
  notifications must not count as fresh confirmation. Stamps are taken at `GattConnection` ingress
  (`SystemClock.elapsedRealtime`) and compared with the final write's completion time, never with
  the wall-clock `reportAtMillis` shown on screen.
- **Set echoes are 8-byte frames** (`4C 54 08 ID TYPE 02 00 CMD VAL CRC`): the decoder discards them as
  an unknown length, which is correct. Neither they nor a GATT write completion confirm anything.
- **Scene reports zero hue/saturation**: entering scene mode the light reported hue 0 and saturation 0
  (proof log), so seeded HSI values from a scene report are 0/0, a real but dull starting point.
  Scene 20's hue/saturation confirmation is static evidence only until hardware acceptance.
- **Controls need a baseline per light**: every member must have connected once (its first report
  is adopted as intent) before group edits work; edits never invent defaults for a missing member.

_Living doc — refresh with /project-docs when it drifts._
