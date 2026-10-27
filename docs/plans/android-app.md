## 1. Goal

Build a native Android app for the owner’s two GVM 800D3X-RGB lights, supporting individual and combined power, CCT, HSI and scene control over the confirmed legacy BLE GATT protocol.

On every connection and resume, replay the complete wanted mode with command `06` first, then confirm the applicable values through fresh, valid `0x03` reports. A successful BLE write alone must never mean “applied.”

## 2. Placement

### Android foundation and conventions

Use `src/mobile/android/`, matching Command Deck’s independent `:app` Gradle build.

| Setting | Required value | Command Deck source |
|---|---|---|
| Language/build | Kotlin, Gradle Kotlin DSL | `src/mobile/android/{settings,build}.gradle.kts` |
| Gradle | 8.13; retain distribution checksum | `src/mobile/android/gradle/wrapper/gradle-wrapper.properties` |
| AGP / Kotlin | 8.13.2 / 2.3.21 | `src/mobile/android/gradle/libs.versions.toml` |
| UI | Compose BOM 2026.03.01, Material 3 | Same catalog |
| SDK / bytecode | min 26, compile/target 36, JVM 17 | `src/mobile/android/app/build.gradle.kts` |
| CI Java / Android tools | Java 21; build-tools 35.0.0 | `.github/workflows/ci.yml` |
| Supporting libraries | activity-compose 1.12.4, Lifecycle 2.10.0, coroutines 1.10.2, serialization 1.9.0, JUnit 4.13.2 | Version catalog |

Copy the wrapper, catalog conventions, strict dependency verification, debug suffixes, release minification and unsigned release policy. Review checksum metadata for the actual dependency subset, including Linux tooling.

Use package `com.dirtyredz.gvmled`, with debug package `com.dirtyredz.gvmled.debug`. This identity change is necessary; do not reuse Command Deck’s package or signing certificate.

Follow these specific Command Deck patterns:

- `AppContainer.kt`, `DeckApplication.kt`, `MainActivity.kt`: explicit constructor wiring, application ownership, activity limited to composition and lifecycle.
- `connection/{AppVisibility,ConnectionCoordinator}.kt`: lifecycle signals, injected time/backoff, generation fencing.
- `feature/jobs/{JobsScreen,JobsViewModel}.kt`: separate rendering and state/actions.
- `ui/navigation/DeckNavGraph.kt`: ViewModel factory and lifecycle-aware collection.
- `ui/theme/Theme.kt`: Material 3 theme and root surface.
- Mirrored `app/src/test/java/` packages with JUnit and coroutine test scheduling.

These paths are under Command Deck’s `src/mobile/android/app/src/main/java/com/dirtyredz/commanddeck/`, except its test paths.

### Application files

Below, `K` means `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/`. Brace lists name separate files.

| New files | Responsibility and placement |
|---|---|
| `src/mobile/android/{settings.gradle.kts,build.gradle.kts,gradle.properties,gradlew,gradlew.bat}`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`, `gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}` | Independent, pinned Android build. |
| `src/mobile/android/app/{build.gradle.kts,proguard-rules.pro}` | Application identity, dependencies, test configuration and unsigned release guard. |
| `app/src/main/AndroidManifest.xml`, `res/values/{strings,themes}.xml`, `res/xml/{backup_rules,data_extraction_rules}.xml`, launcher resources | BLE permissions, application entry and native resources; disable backup as CD does. Paths relative to `src/mobile/android/`. |
| `K/{GvmApplication,AppContainer,MainActivity}.kt` | Process ownership, dependency composition and Android entry respectively. |
| `K/protocol/{Crc16Xmodem,LegacyPacketEncoder,LegacyFrameDecoder,LegacyMessages,SceneCatalog}.kt` | Pure CRC, outgoing framing, bounded notification reassembly, typed replies and scene capabilities. No Android dependencies. |
| `K/ble/{BleScanner,GattConnection,GattOperationQueue,BlePermissionPolicy}.kt` | Discovery, platform GATT adapter, serialized operations and permission decisions. |
| `K/connection/{LightSession,ConnectionCoordinator,ConnectionState,AppVisibility}.kt` | One connection owner per address; handshake and reconnect lifecycle; two-session coordination; visible status; lifecycle inputs. |
| `K/control/{DesiredLightState,ModeCommandPlanner,StateMatcher,LightSynchronizer}.kt` | Wanted settings, ordered full-mode commands, applicable-field comparison and retry lifecycle. |
| `K/data/local/LightPreferences.kt` | Versioned persistence of the two addresses, names and wanted settings. |
| `K/data/repository/LightsRepository.kt` | Per-light observations and wanted state; individual/group actions exposed as flows. |
| `K/feature/lights/{LightsScreen,LightsViewModel,LightControls,SceneControls}.kt` | Target selector, status and controls; scene-specific rendering stays separate. |
| `K/ui/theme/Theme.kt` | Application theme. |

Use SharedPreferences for this small persisted configuration, following CD’s existing preferences-backed stores. Room, KSP and navigation are unnecessary for two records and one screen; this is a deliberate dependency reduction, preserving CD’s architecture and versions for dependencies actually used.

### Proof, release tooling and tests

| New or changed files | Responsibility |
|---|---|
| `src/research/ble/{protocol.py,client.py,live_test.py,requirements.txt}` | Promote the Python reference into reproducible tracked proof tooling. Extract framing from connection handling and visible test orchestration. Pin the working Bleak version. |
| `scripts/owner/prove-light-commands.ps1` | Owner-run entry using existing `owner-run.ps1`, including dry-run and log location. |
| `.github/workflows/{ci,release,release-resume}.yml` | Fast verification, unsigned release/draft creation, retained-artifact recovery respectively. |
| `scripts/mobile/{release,secrets}.js` | Thin release and local signing CLIs, matching CD’s names and division. |
| `src/mobile/releases/{release-contract.json,release-contract.js,signing-certificate.json,manifest.js,version.js,prepare.js}` | GVM identity, public certificate pin, version allocation, artifact inspection and provenance. |
| `src/mobile/releases/publishing/{draft,draft-state,policy,ci-reference,ci-evidence,gh-client,gh-session,lock,command,publish,resume}.js` | Attended release publication and exact artifact recovery. |
| `src/mobile/releases/publishing/steps/{verify-source,sign,upload-and-verify,finalize}.js` | Separate publication stages, following CD’s corresponding files. |
| `src/mobile/releases/signing/{key-generation,keystore,certificate,material,apk-inspection,sign}.js` | Local Android key and APK operations. |
| `src/mobile/releases/secrets/{plan,vault,signing-source}.js` | Dry-run plans and 1Password access, following CD’s attended signing implementation. |
| `package.json`, `package-lock.json`, `.gitignore` | Release tooling only: CD’s Node 22.20.0 and development-only `@1password/sdk` 0.5.0; exclude local SDK/build/secret material. |
| Android test files and `test/mobile/releases/` files listed below | Tests mirror production responsibilities. |
| Living documentation listed in section 6 | Architecture, ownership, verification and release instructions. |

Port only CD’s attended release dependency closure. Relocate its shared desktop version/asset helpers into `src/mobile/releases/`; do not introduce a desktop subsystem.

Add exact responsibility bullets under `STRUCTURE.md`’s `## Layout` for these homes, including production packages, mirrored tests, research, releases, workflows and owner scripts. Keep every code file below approximately 800 lines.

## 3. Sequence

Each numbered slice is independently reviewable Docket work. These are proposed titles, not invented item IDs.

### 0. Prove visible legacy commands — prerequisite to Android slice 1

**Yes: complete the on-light proof first.** Hello and state queries are proven; set commands, scene behavior and the colour workaround are not. That uncertainty affects the application’s central synchronization contract.

Use existing item `dk-3931270b`, coordinating tracked-script promotion with `dk-5f0c19af` after inspecting their authoritative bodies.

Promote `.tmp/scan/live_test.py` into the files above. Correct its proof weaknesses:

- Reject invalid CRCs instead of merely logging them.
- Stop when hello fails; never send sets with guessed device type.
- Compare complete applicable state, not mode alone.
- Add power off/on, saturation, CCT endpoints, representative scenes and report acknowledgements.
- Record wanted values, sent packets, fresh reports and visible observations.

With the official app closed, connect sequentially and prove each light independently, then combined operation. Confirm mode-first HSI survives disconnect/reconnect without a CCT detour. Test scenes 19 and 20 to cover CCT, colour and effect-time fields; a complete twenty-scene sweep is unnecessary before building.

Record firmware behavior when setting parameters while powered off and whether mode changes affect power. Freeze the power ordering from this evidence before slice 4.

### 1. Android scaffold, packet codec and fast CI

Create the pinned Android shell and pure protocol implementation. The existing Python reference supplies evidence, not production runtime code.

Implement:

- CRC16/XMODEM, initial value zero, big-endian output.
- `LEN` covering device ID through CRC.
- Trailing `FF` on transmitted packets.
- Hello, set and short-query encoding.
- Hello capability parsing, full `0x03` reports and distinct short `0x02` replies.
- Notification fragmentation/coalescing, bounded buffering and recovery after malformed frames.
- `0x48` acknowledgements carrying the exact valid `0x03` payload, with a new frame CRC.

Reject corrupt or malformed input before it changes capabilities or observed state. A short `0x02` reply cannot confirm HSI or scenes.

Add the packet tests before implementing the codec. Wire `ci.yml` immediately.

### 2. BLE discovery and read-only live state

Add the Android BLE adapters and connect the repository to two `LightSession` instances.

Seed the owner’s addresses:

- Light 1: `94:C9:60:E5:D7:6F`
- Light 2: `94:C9:60:EB:67:E6`

Display address suffixes and allow renaming. Identify devices by address, not identical advertised names or RSSI.

Scan for known addresses/`BT_LED`; **do not require the custom service in advertisements**, because these lights advertise different service UUIDs. Validate the legacy service and characteristic after connection.

Connection order:

1. Connect and discover services.
2. Enable local notifications and write the CCCD.
3. Wait for subscription completion.
4. Send hello, retrying at one-second intervals within a bounded handshake attempt.
5. Accept device ID/type and CCT bounds only from a valid hello.
6. Receive and acknowledge state reports.

Use write-without-response and one packet per write. Serialize descriptor/characteristic operations and initially pace command writes at 200 ms.

Enforce one `BluetoothGatt` owner per address, close failed connections, and ignore callbacks from retired generations. Start connections sequentially; once established, both sessions may remain connected.

Handle Bluetooth unavailable/off, missing permissions and denied permissions as visible states. Use Android’s API-specific permission model: Nearby Devices on API 31+, legacy Bluetooth/location permissions through API 30. The app does not derive location. Verify actual discovery with `neverForLocation` on the owner’s phone. [Android permission guidance](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)

### 3. Wanted-state persistence and control planning

Introduce the wanted/observed separation and pure command planner before connecting UI actions to writes.

Persist wanted power and mode-specific settings per light. Observed values retain timestamp and connection generation; cached observations must appear stale after disconnection.

On first use without stored intent, seed wanted settings from the first valid full report. Do not apply arbitrary defaults to a light.

Plan complete transactions:

| Mode | Ordered parameter writes after `06` |
|---|---|
| CCT: `06=01` | `03` CCT, `02` brightness |
| HSI: `06=02` | `04` hue, `05` saturation, `02` brightness |
| Scenes: `06=03` | `07` scene, applicable CCT/colour/time fields, `02` brightness |

Include power command `00` in each complete transaction using the ordering established in slice 0. Mode must remain the first set command.

Use integer wire units: brightness/saturation 0–100, hue 0–72 representing 5-degree steps, CCT in 100 K steps within the current hello range.

Static `rgb33` scene evidence in decompiled `Activity/MainActivity.java` gives:

- Scene IDs 1–20.
- Brightness for every scene.
- CCT for 5, 9, 10, 12 and 19.
- Time for 6, 7, 14, 15, 19 and 20.
- Hue/saturation for 20.
- Effect time 1–50 units, representing 100–5000 ms.

Encode that applicability in `SceneCatalog`, documenting it as static evidence supplemented by slice 0’s representative hardware proof.

### 4. Confirmed application and reconnect recovery

Write the regression test first: a light starts in CCT while wanted state is HSI; the first transaction is dropped; a later retry must send mode, hue, saturation and brightness again and wait for matching reports.

`LightSynchronizer` owns one latest wanted revision per light:

- Trigger a complete replay on connect, every foreground resume, mode change and control edits.
- Coalesce slider edits to the latest wanted revision; never accumulate an unbounded command backlog.
- Cancel obsolete remaining writes and restart with mode first for the newest revision.
- Accept confirmation only from a valid full report received after the transaction’s final write, in the current connection generation.
- Compare power, mode, brightness and all fields applicable to that mode/scene.
- Ignore inactive CCT/HSI/scene fields when deciding convergence.

Allow approximately six seconds for confirmation, accounting for the observed four-second report interval. Retry the whole transaction on mismatch or timeout. After three unsuccessful rounds, show “Not confirmed,” reconnect with capped backoff and continue convergence while foregrounded. New intent supersedes old intent.

A GATT completion or short query reply never confirms success.

Route activity resume through one lifecycle seam so connection completion and resume cannot create duplicate workers. Backgrounding cancels retries and releases connections; returning reconnects and replays persisted intent.

### 5. Individual and combined controls

Connect `LightsViewModel` actions to the repository. Provide Light 1, Light 2 and Both targets, with:

- Power, mode and brightness.
- CCT constrained by hello bounds.
- Hue in five-degree steps and saturation.
- Scene selection and applicable scene parameters.
- Connection, pending, confirmed, stale and failure status.

Both-target edits update each light’s wanted state independently, preserving untouched per-light settings. Show mixed values rather than implying the lights agree.

For combined CCT control, use the intersection of both validated ranges. Disable that control until both ranges are known or if the intersection is empty.

Fan out transactions through independent sessions. Report partial completion explicitly when one light is unavailable. “Both” means coordinated independent writes; BLE provides no atomic two-light commit.

### 6. Unsigned CI release and draft recovery

Adapt CD’s `.github/workflows/{ci,release,release-resume}.yml`:

- `ci.yml`: push verification and manual dispatch, Ubuntu 24.04, pinned actions, Gradle cache, read-only permissions and cancellation of superseded runs.
- Fast gate: `testDebugUnitTest`, including codec, synchronization and ViewModel tests. No emulator or physical lights.
- `release.yml`: explicit dispatch on the default branch; serialized release creation; verify → unsigned build → draft.
- Release build runs `testReleaseUnitTest lintRelease assembleRelease`, with strict dependency verification and explicit unsigned/version properties.
- Inspect exactly one unsigned APK; retain the APK and provenance in an attempt-specific artifact.
- Create a draft tagged `android-v0.1.n`, using version code `n+1`.
- Preserve CD’s exact-artifact-ID handling, provenance and CI-reference evidence.

Use GVM asset names such as `GVM-LED-0.1.n-android-unsigned.apk` and `GVM-LED-0.1.n-android.apk`.

Refuse a new version allocation while a conflicting unsigned draft remains pending. The owner must finish or deliberately resolve that draft first; CD’s allocator otherwise ignores drafts.

**Explicit deviation:** CD’s `release-resume.yml` recovers Windows publication. Here it validates the original Android run, commit, attempt, artifact ID and digest, then restores the draft using the same bytes and version. It neither rebuilds nor signs. Expired artifacts require a new normal release attempt.

### 7. Local signing and installed acceptance

Follow CD’s `docs/mobile/android-distribution.md` and attended signing modules:

1. Owner provisions a GVM-specific PKCS12 key and password in 1Password; commit only its public certificate pin.
2. Local signing validates the draft, tag commit, provenance, unsigned digest and version monotonicity.
3. Retrieve signing material through the 1Password SDK; align, sign and verify the APK locally.
4. Upload signed APK and `phone-release.json`; download both again and verify their hashes.
5. Delete only the exact unsigned release asset, then publish last.
6. Retry partial publication using the same tag/release ID and verified existing assets.

Keep `make_latest:false` for CD parity. CI never receives the signing key. Local signing uses `gh` subprocess authentication, following CD’s `publishing/gh-session.js`.

Omit CD’s Cloudflare distribution credentials, relay, Firebase, in-app updater and unattended signer: none supports this BLE-only scope. Installation is ordinary sideloading of the locally signed APK.

## 4. Tests

Mirror production packages under `src/mobile/android/app/src/test/java/com/dirtyredz/gvmled/`.

| Test files | Required coverage |
|---|---|
| `protocol/{Crc16Xmodem,LegacyPacketEncoder,LegacyFrameDecoder}Test.kt` | Known vectors, byte order, lengths, trailing `FF`, fragmented/coalesced reports, corruption and bounded recovery. |
| `protocol/SceneCatalogTest.kt` | Supported IDs, field applicability and legal wire ranges. |
| `control/{ModeCommandPlanner,StateMatcher}Test.kt` | Mode-first complete sequences, range validation, mode-specific comparisons and short-reply rejection. |
| `control/LightSynchronizerTest.kt` | Colour regression first; dropped writes, stale reports, retries, latest-intent replacement, resume and disconnect fencing. |
| `connection/{LightSession,ConnectionCoordinator,AppVisibility}Test.kt` | Subscription-before-hello, handshake failures, single ownership, lifecycle duplication, reconnect and independent lights. |
| `feature/lights/LightsViewModelTest.kt` | Individual isolation, mixed group state, partial success, range intersection and five-degree controls. |

Freeze the documented hello request:

`4C 54 09 00 00 53 00 00 01 00 94 74 FF`

Freeze the documented hello reply:

`4C 54 0A 00 33 53 00 00 02 20 38 F3 9B`

Assert the reply decodes device type `33` and 3200–5600 K. Add exact captured set/report vectors from slice 0; do not manufacture expected CRCs using the encoder under test.

Use CD’s `ConnectionCoordinatorTest.kt` and `AppVisibilityTest.kt` patterns: fake transport, injected time, `runTest`, `advanceTimeBy` and `runCurrent`.

Under `test/mobile/releases/`, add focused contract, prepare, publication, resume and signing tests. Cover altered artifacts, conflicting drafts, wrong certificate, failed upload retaining draft status, repeat signing/publication, secret-free dry-run and recovery without rebuilding. Use fake GitHub/vault/process boundaries and disposable test keys.

No broad emulator suite is required for the fast gate.

## 5. Verification

This plan is based on reading; no build, test or hardware command was run.

The implementer must run the CD-derived Android checks from the repository root:

- `src\mobile\android\gradlew.bat -p src\mobile\android --dependency-verification strict testDebugUnitTest assembleDebug lintDebug`
- `src\mobile\android\gradlew.bat --stop`
- `adb install -r src\mobile\android\app\build\outputs\apk\debug\app-debug.apk`

Set `ANDROID_HOME` and Java 21; do not hard-code CD’s local SDK path. Commit the Unix wrapper executable bit.

Verify release packaging with:

`src\mobile\android\gradlew.bat -p src\mobile\android --dependency-verification strict --no-configuration-cache testReleaseUnitTest lintRelease assembleRelease "-PunsignedRelease=true" "-PphoneVersionName=<allocated>" "-PphoneVersionCode=<allocated>"`

Run the new release-tool unit command, documented as `npm test`, and exercise signing dry-runs without accessing production secrets.

On the owner’s phone, confirm:

- Both addresses are discovered; permission denial and Bluetooth-off recovery are usable.
- Individual controls leave the other light unchanged.
- Both-target controls show independent confirmation and partial failure.
- HSI survives background/resume, process restart and out-of-range reconnect without toggling CCT.
- Full reports update displayed actual state.
- CCT endpoints, power off/on and representative scenes work.
- The signed APK installs and a subsequent higher-version APK updates it with the same certificate.

Exercise one actual CI draft and one retained-artifact recovery before considering release tooling complete. Perform signing/publication through the owner-operated workflow.

Run `dk check` before pushing and satisfy the existing repository review gate.

## 6. Docs

- **`STRUCTURE.md`** — Android is the chosen platform; add exact enforced homes and remove “no app code” as slices land.
- **`docs/ARCHITECTURE.md`** — BLE sessions, wanted versus observed state, full-mode replay, confirmation and lifecycle ownership.
- **`docs/DECISIONS.md`** — Native Android/CD stack; hardware proof prerequisite; foreground connection policy; independent group writes; attended signing and explicit CD deviations.
- **`docs/GOTCHAS.md`** — Advertisement/service mismatch, single connection ownership, mandatory `FF`, no-response writes, CCCD ordering, report timing and strict CRC rejection.
- **`docs/FEATURES.md`** — Separate hardware-proven, implemented and accepted features; record actual resolutions and scene support.
- **`docs/ROADMAP.md`** — Docket slice dependencies, beginning with visible-command proof.
- **`docs/research/protocol.md`** — Add current legacy-first summary, exact proof vectors, scene applicability, acknowledgement framing and measured power ordering. Mark earlier mesh conclusions as superseded.
- **`docs/mobile/{mobile-android,android-distribution}.md`** — CD-derived build/install commands, GVM package identity, key provisioning, unsigned drafts, local signing and recovery.
- **`README.md`, `CLAUDE.md`** — Current platform, entry points and verification commands.

## 7. Open questions / assumptions

- **Docket access:** `dk show` could not resolve this sandbox as a Git worktree. Item bodies and open notes were therefore unavailable. Before implementation, use filtered `dk list --json` and `dk show --json`, run `docket guide` before any item write, reconcile the proposed slices with existing facts, and claim/release work normally. No items were changed.
- **Initial ownership of settings:** With no saved intent, adopt the first full report. Subsequently, reconnect/resume restores the last app-wanted settings. Physical controls update observed state; after a completed transaction, they do not trigger perpetual corrective writes until another app action or resume.
- **Power semantics:** Slice 0 must establish whether full parameter sets work while off. The build proceeds only after power ordering is documented.
- **Confirmation limitation:** The wire protocol has no transaction identifier. A matching report received after the final write is the strongest available evidence, not a correlated acknowledgement.
- **Foreground operation:** No background service or continuous background control is planned. This keeps connection ownership predictable; lights retain their last settings while the app is closed.
- **Scenes:** Twenty IDs are statically supported; representative hardware proof is sufficient initially. Do not describe every scene as hardware-verified.
- **Repository/release identity:** Assume `dirtyredz/gvm-led`, default branch `master`, and GVM-specific 1Password items. Confirm these before provisioning release infrastructure; do not reuse CD’s signing identity.
- **Stale source documentation:** GVM’s opening protocol summary still favors mesh despite later contradictory live evidence. CD’s distribution document also contains an obsolete “no key generated” statement despite an existing certificate pin. Follow the verified legacy findings and implemented CD release flow, preserving those discrepancies in documentation updates.