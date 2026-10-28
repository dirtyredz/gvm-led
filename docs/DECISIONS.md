# DECISIONS

## 2026-10-28 — Settings, explicit GitHub updates and named groups (plan Amendment A)
**Decision:** (1) Updates are checked only when the owner taps Check, against GitHub's public
`releases/latest` (no token, no polling, no telemetry); `INTERNET` is used for nothing else.
(2) A valid newer release is downloaded in-app, verified (size, SHA-256, package, version, minSdk,
pinned signer, installed signer) and handed to Android's installer; the browser release page is the
fallback (missing manifest, debug build, denied install permission). (3) Groups are named
selections of addresses; wanted state stays per light. (4) Any number of lights: per-address
sessions reconciled from the catalog, one shared scan, establishment serialized by one mutex while
live links run concurrently. (5) A small strict in-app JSON codec (`data/json`) instead of org.json
(unavailable in local unit tests) or kotlinx-serialization (a new dependency plus compiler plugin).
**Supersedes:** only the "no updater" part of deviation (6) in the 2026-10-28 release decision below.
**Rejected:** browser-only updates (no integrity check or progress); background checks; a
navigation library or Room (a saved back stack and one SharedPreferences snapshot suffice);
automatic "Both"/"All" groups; group-owned wanted state.

## 2026-10-28 — Android releases: CI unsigned draft, local 1Password signing (Command Deck's attended flow)
**Decision:** Port Command Deck's attended Android release closure (`scripts/mobile/{release,secrets}.js`,
`src/mobile/releases/`, `release.yml` Android chain, `release-resume.yml`) with GVM identity: CI builds an
unsigned APK into draft `android-v0.1.<n>`; the owner signs it on Valhalla with a GVM-only key from the
1Password vault `GVM-LED` (one Windows Hello prompt per run) and publishes; `adb install -r` sideloads it.
Owner entry points are `scripts/owner/{new-signing-key,publish-release,install-release}.ps1`.
**Deviations from CD, with reasons:** (1) own vault and key, never CD's (no cross-project key reuse);
(2) a pending draft from another run blocks the next draft, checked in `android-draft` before any write
(CD replaces unsigned leftovers; here a draft is the owner's open release; the read-only `version` job
cannot see drafts, so the refusal lives at the first write-capable job); (3) the published release is
GitHub's latest (`make_latest: "true"`; GVM has no Windows line to protect, and install-release uses it);
(4) `release-resume.yml` restores an Android draft from the retained artifact instead of republishing
Windows; (5) artifacts kept 7 days, not 3, for an attended signer; (6) no build secrets, Worker,
Firebase, updater, unattended signer or MCP env signing mode; (7) `publish-release.ps1` signs from a
throwaway worktree at the tag (CD's MCP tool pattern) so the owner never checks out tags by hand;
(8) release-sign opens 1Password lazily, after every GitHub/checkout check.
**Rejected:** FCC's lighter `release-sign` (no CI reference/resume, which the plan requires); the `op`
CLI (one prompt per call); CI signing (the key would live in GitHub).

## 2026-10-28 — Android BLE behind a transport seam; foreground-only links
**Decision:** Connection logic (`connection/`) is Android-free and talks to the platform only through
`LightTransport` (one GATT generation) and `LightScanner`; `ble/` holds the thin Android adapters.
Links run only while the activity is started; a reconnect needs a fresh scan sighting and backs off
(1 s after a live link, 2-30 s after failures). Establishment is serialized across lights by a
mutex; established links run concurrently. Read-only slice: hello and report acknowledgements are
the only writes. `FullStateReport` now holds named validated fields (dk-6ae712b8).
**Why:** The plan's fake-transport tests (subscription before hello, single ownership, reconnect)
need the GATT boundary to be replaceable; Kotlin counterpart of dk-162fd49a. Foreground-only keeps
connection ownership predictable and leaves the light free for other clients when the app is closed.
**Rejected:** Blind reconnects to a cached device (slow GATT timeouts while out of range); one
combined session/coordinator class; the full AppVisibility seam now (it belongs to slice 4 resume).

## 2026-10-26 — Build a native Android app
**Decision:** Use the native Android architecture specified in `plans/android-app.md`.
**Why:** A phone keeps the controller near the lights, provides native BLE access, and fits the
proven direct legacy GATT protocol without adding a PC or family command-center dependency.
**Rejected:** A Windows PC app and a family command-center module.

## 2026-10-26 — Native Android scaffold follows Command Deck
Use CD's Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21, Compose BOM 2026.03.01, SDK 36
and JVM 17 bytecode, built with Java 21. Keep strict checksum verification, debug identity suffixes
and unsigned minified releases. CI uses CD's pinned actions, Ubuntu 24.04 and Gradle cache.
The owner dispatched slice 1 alongside proof tooling: scaffold/codec work proceeds from existing
hello/report evidence; no power ordering or hardware control claims are made before visible proof.
Omit Node, relay, Room, KSP, networking, Firebase, signing and release infrastructure (release tooling and Node arrive with slices 6-7; see the 2026-10-28 release decision).
Application/container ownership will arrive with actual process dependencies; the placeholder needs none.
CI runs on pushes to any branch (including the current slice branch), plus manual dispatch, and adds
debug assembly/lint to CD's Android unit gate. SceneCatalog belongs to the control-planning slice.

## 2026-10-26 — Use the legacy GATT path, not Tuya mesh
**Why:** The static analysis of the official app (v1.8.0) suggested Tuya SIG mesh, but a PC scan (2026-10-25) found both lights advertising as `BT_LED` with the legacy service/characteristic UUIDs and no 0x1827/0x1828 mesh advertisement. A live `--check` on both lights got valid CRC replies and state reports with the legacy framing. The path needs no cloud, keys, provisioning or factory reset, and has an explicit mode-select opcode (06) that targets the colour bug.
**Rejected:** Tuya mesh (needs reset and re-provisioning into our own mesh or pulling keys from Tuya's cloud with the vendor OEM secret); mesh does not apply to these lights anyway.

## 2026-10-26 — Keep the codec pure and camera observation optional
**Decision:** `src/research/ble/protocol.py` is the pure codec and protocol specification for the Android port; BLE connection and session behavior stay in `client.py`. Proof runs may use Agent Eye (`snapshot.mjs shot`) as an optional camera observer.
**Why:** Separating byte-level protocol rules from transport makes the reference portable and independently testable. Agent Eye supplies useful visual evidence without making an external, cross-repository tool a requirement for protocol tests or normal operation.

_Living doc — refresh with /project-docs when it drifts._
