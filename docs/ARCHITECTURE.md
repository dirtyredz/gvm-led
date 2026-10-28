# ARCHITECTURE

## Overview
A controller talks directly to each light over BLE; no cloud, no mesh, no pairing.

## Android slice 1
`src/mobile/android/` is an independent Compose app. Slice 1 added the Compose shell and codec; slice 2 (below) adds sessions and a read-only screen.
The Android-free `protocol/` package owns shared frame constants, CRC, outgoing framing and typed
hello/full/short replies. Each LightSession owns its own LegacyFrameDecoder, calling reset on
disconnect. The decoder bounds buffering to 17 bytes, resynchronizes the byte stream and verifies
frame length and CRC before handing a complete frame to LegacyMessageParser. The parser dispatches
payload validation to the reply type that owns it. Resynchronization can skip a truncated candidate
when a later complete valid frame arrives.
Only a decoded FullStateReport can be acknowledged; its nine named, validated fields re-serialise to the exact payload.
ShortStateReply has no mode/HSI fields and cannot serve as full-state confirmation.

## Android slice 2: discovery and read-only live state
Ports and adapters: `connection/` is Android-free and depends only on two seams, `LightScanner`
(advertisements) and `LightTransport` (one GATT link: connect, subscribe, write, events, close).
`ble/` implements them (`BleScanner`, `GattConnection`) and `AppContainer` wires them; tests use fakes.

- **Radio gate.** `BluetoothRadio` publishes `RadioState` (Unavailable, PermissionRequired,
  PermissionDenied, Off, Ready) from `BlePermissionPolicy`; only Ready lets anything scan or connect.
- **Coordinator.** While the activity is started (`MainActivity.onStart/onStop`; configuration changes
  keep links), `ConnectionCoordinator` runs one shared unfiltered scan only while some light is
  Searching, classifies advertisements by address (`DiscoveryFilter`; unknown `BT_LED` devices are
  listed, never connected) and runs one loop per configured address: search, connect once, wait
  (1 s after a live link, doubling to 30 s after failures), then require a fresh sighting. Lifecycle
  transitions are serialized; stopping cancels and joins the complete maintenance tree before a
  subsequent start can create its replacement.
- **Session.** `LightSession` is the only owner of its light's link. Each `connectOnce` is a new
  generation that creates, uses and always closes one transport. Establishment holds a shared mutex,
  so lights connect one after another; the live phase runs concurrently. Order: connect and
  discover (the adapter validates the legacy service/characteristic), enable notifications and wait
  for the CCCD write, then hello every 1 s for up to 5 attempts. Identity and CCT bounds come only
  from a valid hello. Each valid full report matching that identity becomes the observed state and
  is acknowledged (0x48). Writes are paced at least 200 ms apart. Short replies never change state.
- **Android adapter.** `GattConnection` serializes GATT operations through `GattOperationQueue`
  (callback-completed, kind-matched, timed out), writes without response, and ignores callbacks
  after close or from another `BluetoothGatt`. `BleScanner` remembers each scanned `BluetoothDevice`
  so connections use the address type the scan reported.
- **State and UI.** `LightConnection` keeps the last report with its generation; it is current only
  while Live in that generation, otherwise shown as last known. `LightsRepository` joins names from
  the catalog (below). `LightsViewModel` maps wire units for display
  (`LightsUiState`). No set commands exist yet; wanted state and confirmation arrive in slices 3-4.

## Amendment A: catalog, discovery, groups and updates (slices 2A-2D)
- **Catalog vs wanted state.** `LightCatalogRepository` owns the known lights (normalized address,
  stored name) and named groups (stable generated ID, editable name, member addresses) as one
  versioned JSON snapshot in SharedPreferences (`LightCatalogPreferences`, rules in
  `LightCatalogCodec`). Its initial read and any migration write run asynchronously on the injected
  I/O dispatcher. Version 1 migrates once, keeping custom names; only a fresh install is
  seeded. Mutations are serialized and written with `commit()` before the flow publishes; unreadable
  data pauses changes until an explicit reset. Wanted state (revised slice 3) will be a separate
  per-address store: groups are selections, never owners of settings.
- **Dynamic sessions, one scan.** `ConnectionCoordinator` reconciles one `LightSession` per catalog
  address; unchanged links keep running, and a removed address's job is cancelled and joined before
  the address can get a new session. `LightDiscovery` is the single scanner: it runs while a light
  is Searching or during a bounded owner-requested Find window (30 s), turns known-light adverts into
  fresh sightings (each used up by one attempt) and unknown `BT_LED` adverts into expiring
  candidates. Adding a candidate only enrolls its address; the session then proves the legacy
  profile and hello. Establishment stays serialized by one mutex; established links run concurrently.
- **Targets.** Lights shows chips for every light and every group (no implicit Both/All); the
  selection is a key (`light:<address>` / `group:<id>`) that falls back to the first light if deleted.
- **Updates (owner-initiated only).** `UpdateController` (process-scoped) asks
  `GitHubReleaseClient` for a typed `releases/latest` listing (the remote adapter owns JSON decoding),
  then applies `ReleasePolicy` (published `android-v` tag,
  contract asset URLs, one manifest, the exact signed APK name), validates `phone-release.json`
  against the bundled contract and pin (`PhoneReleaseManifest`, mirroring `manifest.js` through
  shared fixtures) and compares versionCode with the installed app. Download streams to a part file
  under the size ceiling, checks size and SHA-256, inspects package/version/minSdk/signer
  (`ApkInspector`), then promotes to the ready file in `cache/updates/`. The activity launches
  Android's installer with a FileProvider URI (or the install-source settings page, or the browser)
  from one-shot effects; success is only ever the installed version read back. `HttpsRequests`
  allows HTTPS to api.github.com and github.com, and redirects only to GitHub's asset hosts. Leaving
  the foreground cancels network work; BLE never depends on the network.

## Link
- Light advertises as `BT_LED`, Telink SoC (USB VID 0x248A). Address is the per-light identity.
- Service `00010203-0405-0607-0809-0a0b0c0d1910`, characteristic `...2b10` (notify, write-without-response, read).
- One connection per light at a time.

## Framing
`4C 54 | LEN | DEVID | DEVTYPE | body | CRC_hi CRC_lo | FF`; CRC16/XMODEM over 4C..body, big-endian.
Hello `4C 54 09 00 00 53 00 00 01 00 94 74`; set body `57 00 CMD 01 VAL`; query body `52 00 00 04`.
Lights push a state report (kind 03) after hello and about every 4 s.

## Commands
power 00, pick 01, brightness 02 (0-100), CCT 03 (K/100), hue 04 (deg/5), saturation 05, mode 06 (1 CCT, 2 HSI, 3 scenes), scene 07, effect time 08.
The "colour bug" fix: always send mode 06=02 first, then hue, saturation, brightness, and confirm via the state report.

## Release pipeline
Android releases use an attended two-part pipeline. `release.yml` allocates the next version on
`main`, verifies and builds an unsigned APK, records its provenance, and creates a GitHub draft;
`release-resume.yml` can recreate that draft from the exact retained build artifact without
rebuilding or signing it. CI authenticates through the GitHub token transport and never receives
the signing key.

The owner runs the local signer from a clean throwaway worktree at the draft's tag. It uses the
owner PC's authenticated `gh` session for GitHub transport, then opens the `GVM-LED` 1Password
vault to recover the permanent Android key only after the source, draft and provenance checks pass.
The signer verifies the unsigned digest and version history, signs locally, uploads and reads back
the signed assets, removes only the unsigned APK, and publishes last.

A draft is therefore an unfinished release, not a distributable build. Provenance binds the
contract, source commit, workflow run and exact unsigned artifact; publication requires that
evidence plus a versionCode newer than every eligible published Android release. The signing key
never enters CI, and only the locally signed APK is published. Operational commands, recovery and
key provisioning are in [the Android distribution runbook](mobile/android-distribution.md).

Full detail: [protocol.md](research/protocol.md).

_Living doc — refresh with /project-docs when it drifts._
