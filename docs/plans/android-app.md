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

### 0. Prove visible legacy commands — complete 2026-10-26

The tracked reference in `src/research/ble/`, run through
`scripts/owner/prove-light-commands.ps1`, completed the on-light proof on both fixtures. The run
validated CRCs, required a valid hello, compared complete applicable state, and recorded wanted
values, packets, fresh reports, and camera observations. Power, CCT endpoints, HSI, scenes 19 and
20, acknowledgements, combined operation, and mode-first HSI after reconnect all passed. See
`docs/research/protocol.md`, “Visible proof run” and “Photo check of the re-run.”

The proof also established the app’s power ordering: settings are accepted while off, a mode change
does not power on the light, and a full settings transaction should send power-on last.

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

Keep `make_latest:false` for CD parity (superseded: publication uses `make_latest: "true"`; see Amendment A). CI never receives the signing key. Local signing uses `gh` subprocess authentication, following CD’s `publishing/gh-session.js`.

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

The BLE hardware proof passed on 2026-10-26. Android build, test, installation, and release checks
remain applicable as their implementation slices land.

The implementer must run the CD-derived Android checks from the repository root:

- `src\mobile\android\gradlew.bat -p src\mobile\android --dependency-verification strict testDebugUnitTest assembleDebug lintDebug`
- `src\mobile\android\gradlew.bat --stop`
- ~~`adb install -r` of the debug APK~~ (superseded by Amendment A: owner acceptance installs signed GitHub releases on the phone; no adb)

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

- **Initial ownership of settings:** With no saved intent, adopt the first full report. Subsequently, reconnect/resume restores the last app-wanted settings. (Corrected 2026-10-28 by Amendment A: the rear knobs are disabled while a light is in app mode, so physical controls cannot change state during an app session; observed state changes only through app writes, or between sessions through the official app or a power cycle. After a completed transaction there are no perpetual corrective writes until another app action or resume.)
- **Power semantics:** The proof established that full parameter sets work while off and mode changes do not power on the light. Send the full settings, then power on last.
- **Confirmation limitation:** The wire protocol has no transaction identifier. A matching report received after the final write is the strongest available evidence, not a correlated acknowledgement.
- **Foreground operation:** No background service or continuous background control is planned. This keeps connection ownership predictable; lights retain their last settings while the app is closed.
- **Scenes:** Twenty IDs are statically supported; representative hardware proof is sufficient initially. Do not describe every scene as hardware-verified.
- **Repository/release identity:** Confirmed `dirtyredz/gvm-led` (public), default branch `main` (not CD's `master`: workflows gate on `refs/heads/main`), and GVM-specific 1Password items. Do not reuse CD’s signing identity.

---

# Amendment A: Settings, GitHub updates and named groups (2026-10-28)

> **Precedence.** This amendment supersedes the fixed two-light assumptions in slices 2-5 above, the
> Light 1 / Light 2 / Both selector, the `make_latest:false` line in slice 7 and the blanket omission of an
> in-app updater. Revised slices 3-5 below replace slices 3-5 above. Everything else above still applies.
> Owner acceptance uses only signed releases downloaded from GitHub Releases on the phone (no adb).

### A1. Goal


This amendment supersedes the fixed-two-light assumptions in slices 2–5, the Light 1 / Light 2 / Both selector, and the blanket omission of an updater. Existing protocol, confirmation and attended-signing requirements remain applicable.

Add Settings with app version, explicit update checking, signed-APK installation, light discovery and named-group management. Support any configured lights through per-light wanted state and independently confirmed transactions.

Baseline: slices 0, 1, 2, 6 and 7 are implemented; signed release 0.1.1 is published and under owner test. Publication already uses `make_latest: "true"`; the original plan’s `false` instruction is stale. This amendment describes proposed work, not completed verification.

### A2. Placement

`K` means `src/mobile/android/app/src/main/java/com/dirtyredz/gvmled/`. `T` means the corresponding `app/src/test/java/com/dirtyredz/gvmled/` directory. Brace lists denote separate files.

#### Settings and updates

| New or changed files | Responsibility and reason |
|---|---|
| `K/ui/navigation/GvmApp.kt` | Compose destinations and ViewModel factories for Lights, Settings and management screens. Extract composition from `MainActivity`; use a small saved destination state without adding a navigation dependency. |
| `K/ui/components/{RadioActions,RadioBanner}.kt` | Extract the existing radio actions/banner from `LightsScreen` so discovery and Lights share permission presentation without importing another feature. |
| `K/feature/settings/{SettingsScreen,SettingsViewModel}.kt` | App identity, update presentation and management links. No HTTP, APK inspection or BLE orchestration here. |
| `K/update/{ReleaseContract,PhoneReleaseManifest,ReleaseVersion,ReleasePolicy,UpdateState,UpdateController,UpdatePorts}.kt` | Android-free release validation, version mapping, update state machine and injected boundaries. Keep policy separate from transport and rendering. |
| `K/data/remote/{GitHubReleaseClient,ApkDownloader,HttpsRequests}.kt` | Release metadata retrieval, streamed APK download and shared bounded HTTPS/redirect handling. Use platform networking on an IO dispatcher; no additional HTTP framework. |
| `K/update/android/{ReleaseContractLoader,InstalledAppReader,ApkInspector,InstallLauncher}.kt` | Load bundled public contract, read installed identity, inspect downloaded APK metadata and construct installer/system-settings intents. |
| `K/{AppContainer,MainActivity}.kt` | Wire dependencies; retain activity ownership of lifecycle and system launches/results. Do not grow the activity into an updater or navigation controller. |
| `src/mobile/android/app/build.gradle.kts` | Bundle `src/mobile/releases/{release-contract,signing-certificate}.json` as generated assets with declared build inputs. Keep their existing files authoritative. Add the shared fixture directory to unit-test resources. |
| `src/mobile/android/app/src/main/AndroidManifest.xml` | Add `INTERNET`, `REQUEST_INSTALL_PACKAGES` and a non-exported FileProvider with application-specific authority. |
| `src/mobile/android/app/src/main/res/xml/update_paths.xml` | Expose only the dedicated update-cache subdirectory through FileProvider. |
| `src/mobile/android/app/src/main/res/values/strings.xml` | Settings, updater, discovery, group and control status strings. |
| `test/mobile/releases/fixtures/update-contract.json`, `test/mobile/releases/update-contract.test.js` | Shared valid/invalid manifest fixtures checked by the existing JavaScript validator and Kotlin tests, preventing contract drift. |

No publication behavior change is required. Preserve `publishing/steps/finalize.js` and its latest-release verification.

#### Lights, groups and control

| New or changed files | Responsibility and reason |
|---|---|
| `K/data/model/{KnownLight,LightGroup,LightCatalog}.kt` | Persisted catalog values: normalized addresses, names, stable group IDs and membership. Groups contain addresses, not sessions or desired settings. |
| `K/data/local/{LightCatalogPreferences,LightCatalogCodec}.kt` | Replace `LightPreferences` with versioned catalog persistence and pure migration/validation. Store lights and groups together so membership cannot reference a partially committed catalog. |
| `K/data/repository/{LightCatalogStore,LightCatalogRepository}.kt` | Storage boundary and catalog operations. Extract `LightNameStore` responsibilities from `LightsRepository`; remove the obsolete interface after its callers migrate. |
| `K/data/repository/LightsRepository.kt` | Join catalog names, observations and later per-light control status. Remove index-dependent default naming. Keep group CRUD outside this repository. |
| `K/connection/{ConnectionCoordinator,DiscoveryFilter}.kt` | Replace the immutable address/session list with reconciliation by normalized address. Classification follows the current configured set. |
| `K/connection/{LightDiscovery,ReconnectBackoff}.kt` | Extract shared scanning, discovery demand and sighting lifetime from the coordinator; move its existing backoff helper into its own file. |
| `K/feature/settings/lights/{LightManagementScreen,LightManagementViewModel}.kt` | Known-light naming and explicit discovery/add workflow. Move the rename interaction out of `LightCard`. |
| `K/feature/settings/groups/{GroupsScreen,GroupsViewModel,GroupEditor}.kt` | Group list, creation, editing and deletion. |
| `K/control/{DesiredLightState,ModeCommandPlanner,StateMatcher,LightSynchronizer,ApplyState}.kt` | Revised slices 3–4: per-light intent, complete command sequences, confirmation and retries. |
| `K/data/local/{WantedStatePreferences,WantedStateCodec}.kt` | Separate versioned persistence of wanted state; do not append this responsibility to catalog persistence. |
| `K/data/repository/LightControlRepository.kt` | Validate and persist batches of per-light intent, expose apply status and resolve individual/group actions. |
| `K/control/{ControlTarget,TargetStateReducer,TargetEditPlanner}.kt` | Stable target identity, mixed/unknown values, capability intersection and pure field-patch expansion. No BLE or Compose dependencies. |
| `K/connection/{LightCommandPort,SessionWriter,AppVisibility}.kt` | Generation-scoped command access, serialized paced writes and foreground/resume ownership. Extract pacing from `LightSession` before adding control writes. |
| `K/connection/{LightSession,LightTransport,ConnectionState}.kt`, `K/ble/GattConnection.kt` | Add command access and monotonic notification-receipt metadata while preserving one session event consumer and transport ownership. |
| `K/protocol/SceneCatalog.kt` | Existing planned home for supported scene fields and ranges. |
| `K/feature/lights/{LightsScreen,LightsViewModel,LightsUiState,LightCard}.kt` | Dynamic target selection, observations and per-member results; remove management presentation moved to Settings. |
| `K/feature/lights/{TargetSelector,LightControls,SceneControls}.kt` | Individual/group controls and mode-specific rendering, separated from list/status rendering. |
| Mirrored `T/` packages and existing connection test fakes | Tests described below. |

Add enforced-home bullets under `STRUCTURE.md`’s `## Layout` before introducing these packages. Keep code files below approximately 800 lines.

**Structural tradeoffs:** SharedPreferences remains appropriate for this small catalog; use serialized durable writes rather than introducing Room. A small saved destination state is sufficient for this navigation tree. Platform HTTP avoids new dependencies but requires explicit timeout, cancellation and redirect tests.

### A3. Sequence

Recommended order:

**2A Settings/checking → 2B installation → 2C expandable catalog/discovery → 2D group management → revised 3 → revised 4 → revised 5.**

Slices 2A–2D can be built now. Neither updates nor group CRUD requires control writes. Group control must wait for slices 3–4.

These labels are proposed slice names, not Docket IDs. The implementer must inspect existing items, follow `docket guide` before item writes, claim work and record dependencies through `dk`.

#### 2A. Settings and explicit GitHub update checking

Add a Settings action to Lights. Settings shows installed version name/code, **Check for updates**, and management destinations as those slices land. Internal navigation must not stop/restart BLE ownership.

Use an explicit foreground check against:

`https://api.github.com/repos/dirtyredz/gvm-led/releases/latest`

Use the public API without credentials, the documented JSON accept header and a supported API-version header. Accept only a published, non-prerelease `android-v…` release. A foreign tag is a release-contract error, not “up to date.” The existing publication policy makes this endpoint sufficient; do not add release-history crawling. [GitHub release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)

Find exactly one `phone-release.json` asset and validate the existing schema:

- Top level: `schema`, `repository`, `channel`, `packageName`, `versionName`, `versionCode`, `minSdk`, `commit`, `certificateSha256`, `apk`.
- APK: `name`, `size`, `sha256`.
- Preserve schema-1 exact-field, identity, version-pairing and hash rules from `manifest.js`.
- Preserve contract limits: 8 KiB manifest, 100 MiB APK and at most 20 release assets.
- Require manifest version/tag consistency and the exact signed APK asset name. Never select the unsigned APK.
- Compare manifest `versionCode` with Android’s installed version code. Do not compare version strings lexically or derive a code from the patch number. The existing release mapping rolls over after 60,000 patches.
- A compatible higher code enables the update action; equal means current; lower means the installed version is newer. An incompatible `minSdk` gets a distinct message.

**Missing manifest:** show “Release found; update metadata unavailable,” with **Open release page**. Do not invent a version code or claim that the installed app is current. Malformed, mismatched or unsupported metadata disables direct installation.

Checks must not affect BLE. Represent checking, available, current, installed-newer, incompatible, unavailable and failure explicitly. Handle offline/DNS/TLS errors, timeout, 404, rate limiting and server failure. Show retry timing when GitHub supplies it; do not automatically loop or request a token. Retain the previous result as dated information after a failed check, without presenting it as fresh.

Networking is user initiated. Add `INTERNET`, which needs no runtime permission dialog; add no background polling, telemetry or network requirement for light control.

**Desk benchmark and felt target:**

| Comparable app | Relevant pattern | GVM decision |
|---|---|---|
| Obtainium | Reads release sources, handles version-detection differences, APK selection and GitHub rate limits. | Fix the source and asset contract; use manifest versionCode so the owner configures nothing. [Tracking](https://wiki.obtainium.imranr.dev/app_tracking/), [sources](https://wiki.obtainium.imranr.dev/sources/) |
| NewPipe | Supports GitHub APK distribution and documents signing-key compatibility across updates. | Preserve the permanent signing identity and surface incompatibility without suggesting uninstall. [Installation documentation](https://github.com/TeamNewPipe/NewPipe/blob/dev/README.md#installation-and-updates) |

This is a documented-pattern comparison, not a measured tap-count study. GVM’s target is **three taps from Settings to installation starting**, after install-source permission is granted: **Check → Download and install → Android Install**. From Lights, add one Settings tap.

**Callers changed:** `MainActivity` composition moves into `GvmApp`; `AppContainer` wires update dependencies; Lights gains its Settings action.

**Owner acceptance:** correct installed version appears; checking succeeds with Bluetooth off; no-newer and offline outcomes are truthful; returning to Lights preserves normal discovery/state behavior.

#### 2B. Verified download and Android installer handoff

Choose an in-app download followed by Android’s installer.

A browser-only handoff avoids the install permission and download machinery, but delegates download/open steps and downloaded-byte checking to other apps. The direct flow provides progress, integrity checks and a predictable common path. Keep **Open release page** as an explicit fallback.

On **Download and install**:

1. Download the selected signed APK into a private `.part` file; enforce the contract size ceiling while streaming.
2. Validate exact byte count and SHA-256 against the validated manifest.
3. Inspect archive package, version name/code, minimum SDK and signing-certificate metadata.
4. Require the release package and certificate to match the bundled public contract/pin and the installed release identity.
5. Promote the verified file to a ready state and hand it to the system installer.

Use HTTPS only, bounded response bodies/timeouts and bounded redirects restricted to the required GitHub asset hosts. Initial asset URLs must belong to the expected repository, tag and asset. Cancellation or failure deletes incomplete files.

The manifest cannot choose a new trusted signer. PackageManager inspection is a preflight; Android’s installer remains responsible for full signature verification and upgrade compatibility. Do not support certificate rotation, downgrade or automatic uninstall in this slice. [Android signing guidance](https://developer.android.com/studio/publish/app-signing)

Declare `REQUEST_INSTALL_PACKAGES`. On the install action, check `canRequestPackageInstalls()` and, if needed, open this app’s unknown-source settings. Recheck on return. Denial leaves a usable screen and browser fallback; do not repeatedly prompt. The first-use settings excursion is additional to the three-tap target. [Android install-source API](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())

Share only the verified APK through a content URI with temporary read permission and APK MIME type. Use the narrow FileProvider path; request no storage permission. [FileProvider guidance](https://developer.android.com/reference/androidx/core/content/FileProvider)

Handle lifecycle explicitly:

- Rotation retains the current task without duplicate checks, downloads or installer launches.
- Leaving the foreground cancels unfinished network work; returning offers Retry.
- A completed APK can survive the install-permission excursion, but must be revalidated before reuse.
- Process restart cleans incomplete files and reconstructs only verified ready state.
- Installer cancellation offers **Install again**; launching the installer never means “Updated.”
- Read installed identity after restart/resume to establish success.
- Installer/system-settings transitions use the existing foreground BLE policy.
- Debug builds show their actual `.debug` identity and remain check/browser-only; a stable APK is not a debug-app update.

**Callers changed:** Settings actions connect to the update controller; `MainActivity` launches system flows and reports their return; the manifest/provider configuration becomes active.

**Owner acceptance:** install a higher, permanently signed release over the existing release without uninstalling. Confirm retained names, correct new version, cancellation recovery and first-use permission behavior. Recheck preservation of groups/wanted state after those features exist. Record actual common-path taps on the owner’s phone.

#### 2C. Persistent N-light catalog and Settings discovery

Migrate the current version-1 preferences without losing addresses or custom names. Seed the two owner addresses only for an uninitialized installation. Do not reinsert seeds on every upgrade.

Persist a catalog with:

- A unique, normalized address per known light.
- Stable stored default/display names, preserving “Light 1” and “Light 2” for the existing records.
- Stable group IDs and member-address sets, initially empty.

Serialize catalog mutations; write the complete new snapshot durably before publishing its flow. Reject duplicate addresses and invalid membership. Surface corrupt or unsupported saved data rather than silently overwriting it with seeds.

Replace the coordinator’s fixed `sessions` list with per-address sessions/jobs. Reconcile changes without restarting unaffected links. Retiring a session must cancel and join its work before that address can acquire another owner. Fence results across session replacement as well as reconnect generations.

Extract scanning into `LightDiscovery`. Combine reconnect demand with an explicit, bounded **Find lights** demand from Settings. There must still be one shared scanner. Discovery must work when all known lights are live and when the catalog is empty.

Show unknown `BT_LED` candidates by address and last-seen freshness; do not identify them by name or RSSI alone. Expire stale candidates, and preserve fresh-sighting reconnection and scan-budget protections.

**Add** explicitly enrolls the selected address, asks for a name and lets the existing coordinator connect through the scanned `BluetoothDevice`. Enrollment is not proof of compatibility: show verification/connection failure until the legacy GATT profile and valid hello are established. Do not send set commands during enrollment, and do not treat every `BT_LED` model as supporting the GVM control catalog.

Light management in this amendment covers **add and rename**. Forgetting lights is deferred; group deletion is included below.

**Connection constraint:** preserve one GATT owner per address and one establishment attempt at a time across the coordinator. The shared mutex covers connect, subscription and hello; established links remain concurrent. A group never creates its own connection.

**Callers changed:** `AppContainer` replaces static preferences/address wiring; `LightsRepository` consumes the catalog; `DiscoveryFilter` receives current membership; rename moves from `LightsViewModel`/`LightCard` into management.

**Owner acceptance:** existing names survive upgrade; an additional compatible light can be discovered and added while the original lights are live; it persists after restart and appears independently. Denied permissions and Bluetooth-off recovery remain usable. N>2 is covered with fakes until a third fixture is available.

#### 2D. Named-group management

Provide **Settings → Groups → Create group**, with a name and multi-select list of known lights. Show names plus address suffixes and availability, allowing offline members to be selected.

Use a stable generated ID; names are editable labels. Require a trimmed nonblank name of at most 24 characters, reject case-insensitive duplicate group names, and require at least one member. Deduplicate membership; allow one-member groups and overlapping groups.

Editing changes name/membership without replacing the group ID. Deletion requires a confirmation naming the group and leaves lights, physical state and per-light wanted state intact.

Do not automatically create “Both” or “All lights.” Selecting a group initially displays its members and read-only state; control affordances arrive after slices 3–4.

**Callers changed:** Settings gains the group destination; Lights gains dynamic individual/group selection using address or group ID. A deleted selected group returns to an available individual target, or the empty-state screen.

**Owner acceptance:** create “Key and Fill,” edit its name/membership, restart and find it preserved; create overlapping groups; delete one without changing lights or the other group.

#### Revised 3. Wanted-state persistence and N-light planning

Keep wanted state exclusively per address, with its own revision. Groups are selections, not competing stores of desired state.

Without stored intent, adopt the first valid current full report. Validate fields against hello capabilities and scene applicability. Inactive report fields are not necessarily usable defaults: scene zero and effect time zero can appear, and reported CCT still needs hello-range validation. Preserve unknown inactive settings as unknown.

For every action:

1. Resolve the target to a deduplicated snapshot of member addresses.
2. Apply only the explicitly edited fields to each member’s existing wanted state.
3. Validate every resulting per-light state before accepting any intent changes.
4. Persist the accepted batch before publishing revisions or starting writes.

If a member lacks an initialized baseline or required mode-specific values, explain which member/field blocks the edit. Do not silently exclude members or fabricate defaults. Selecting a new mode/scene must collect any missing applicable values.

Overlapping groups converge through the same per-light latest-revision rule. Membership changes affect subsequent actions; they do not retroactively add/remove recipients from a submitted action.

Preserve complete mode-first planning, wire-unit resolution, scene applicability and power-on-last ordering. Group brightness or power edits preserve each member’s mode and other settings.

**Callers changed:** `LightControlRepository` joins catalog and session observations; target actions use `TargetEditPlanner`; `LightsRepository` exposes wanted/actual status without owning group mutations.

**Owner acceptance:** existing read-only behavior and saved catalog remain intact after upgrade/restart. This is an infrastructure slice; packet planning, field preservation and persistence acceptance are demonstrated by tests, with visible control acceptance in slice 4.

#### Revised 4. Confirmed application and colour-bug fix for N lights

Implement the original colour regression first: observed CCT, wanted HSI, dropped first transaction, then a complete mode-first retry and fresh full-report confirmation.

Maintain one synchronizer per address, independent of selected target or group membership. It must:

- Coalesce to the latest wanted revision.
- Cancel obsolete remaining set commands and restart with mode first.
- Use the current session’s command port rather than creating/accessing GATT directly.
- Serialize hello, acknowledgements and set writes through `SessionWriter`, retaining 200 ms pacing and adapter operation serialization.
- Preserve one consumer of transport notifications.

Add monotonic receive stamps at `GattConnection` notification ingress and in test fakes. Propagate them to full-report observations. Confirmation requires the current session identity/generation, current wanted revision and a full report received after the final transaction write. UI wall-clock timestamps cannot establish freshness; queued old notifications must not become fresh when processed later.

Retain approximately six seconds per confirmation round and three rounds before “Not confirmed,” followed by capped reconnect recovery while foregrounded. Failures remain per light and must not block another member. Resume/reconnect replays complete persisted intent; lifecycle entry must not create duplicate workers.

Introduce a narrow normal individual HSI editing/apply path here, pulling that part of slice 5 forward so the colour fix receives visible acceptance before group controls expand.

**Callers changed:** transport events and fakes gain receipt metadata; `LightSession` exposes the generation-scoped command seam; `AppContainer` wires synchronizers and `AppVisibility`; `MainActivity` sends lifecycle events through that seam; individual HSI actions reach the control repository.

**Owner acceptance:** set visibly distinct HSI colours without first toggling CCT. Background/resume, restart and reconnect preserve the intended colour. An unreachable light does not prevent another from confirming.

#### Revised 5. Individual and named-group controls

Replace Light 1 / Light 2 / Both with all individual lights and saved named groups. Show member count and per-member connection/apply results.

Distinguish **wanted values**, **actual observations** and **apply status**:

- Equal known values show one value.
- Different known values show **Mixed**, never an average or the first member’s value.
- Missing/stale observations remain visibly unknown/stale; they cannot establish agreement.
- Group power uses explicit On/Off actions when mixed.
- Group mode-specific controls require a common selected mode/scene and complete valid per-member settings.
- Changing one field preserves every untouched per-member field.

For CCT, calculate the intersection of every selected member’s validated current hello range: greatest minimum through smallest maximum, aligned to 100 K steps. Disable CCT when any member lacks a current range or the intersection is empty, with a reason. Apply the same rule to CCT-bearing scenes. Revalidate before dispatch after reconnect; never silently clamp to different values per light.

Offline members with complete stored intent may receive edits that do not require unavailable capabilities. Keep them **Waiting for light**, not confirmed. CCT edits requiring a current intersection stay disabled.

Display results such as **“2 of 3 confirmed; Fill waiting for connection.”** Show pending, not confirmed, superseded and stale separately. A retry targets current intent on unsuccessful members; never roll back successful lights or replay an obsolete group action.

Group fan-out provides coordinated independent writes, not an atomic hardware commit. Starting multiple group actions must not create additional sessions or a global command queue that lets one unavailable light block the rest.

**Callers changed:** `LightsViewModel` maps dynamic targets to control-repository actions; `TargetStateReducer` supplies mixed values/ranges; `LightControls` and `SceneControls` render applicable controls and member outcomes.

**Owner acceptance:** individual edits leave others unchanged; mixed groups display honestly; field edits preserve untouched settings; one disconnected member produces partial success and later convergence. Validate CCT endpoints and representative scenes. Editing/deleting a group does not rewrite previously submitted per-light intent.

### A4. Tests

Use local JUnit, injected clocks, coroutine scheduling and fake transport/network/storage boundaries. No physical lights or GitHub calls belong in the fast gate.

| Tests | Required coverage |
|---|---|
| `T/update/{PhoneReleaseManifest,ReleaseVersion,ReleasePolicy}Test.kt` | Shared contract fixtures; exact fields; version rollover; higher/equal/lower codes; missing/malformed manifest; foreign tags; draft/prerelease; duplicate/missing APK; unsigned asset exclusion; wrong package/channel/repository/pin; incompatible SDK. |
| `T/update/UpdateControllerTest.kt` | Offline, timeout, rate limit, cancellation, repeated taps, rotation-equivalent reattachment, interrupted download, permission denial, installer cancellation and no success merely from handoff. |
| `T/data/remote/` tests | Bounded bodies/downloads, redirects, HTTPS enforcement, truncation, declared/actual size mismatch and digest mismatch using fake responses/streams. |
| `T/update/android/` policy/seam tests | API 26–27 versus 28+ identity handling, debug exclusion, mismatched archive metadata, ready-file revalidation and narrow installer URI construction. Platform behavior also needs device verification. |
| `T/data/local/{LightCatalogCodec,WantedStateCodec}Test.kt` | Version-1 migration, custom-name preservation, first-run seeding, empty catalog, normalized duplicates, stable IDs, invalid membership, corruption and durable-write failure. |
| `T/data/repository/LightCatalogRepositoryTest.kt` | Add/rename, group CRUD, overlapping/one-member groups, duplicate names and deletion preserving member records. |
| `T/connection/{LightDiscovery,ConnectionCoordinator}Test.kt` | Empty/all-live discovery, shared scanner, freshness/expiry, dynamic enrollment, unchanged-link preservation, serialized establishment and concurrent live links. Retired callbacks cannot revive removed/replaced sessions. |
| `T/control/{ModeCommandPlanner,TargetEditPlanner,TargetStateReducer}Test.kt` | Complete mode-first plans, unknown inactive values, all-member validation before persistence, mixed values, field preservation, overlapping groups, membership snapshots and N-way CCT intersection. |
| `T/control/LightSynchronizerTest.kt` | Colour regression first; dropped writes, full retries, obsolete revision cancellation, stale queued reports, short-reply rejection, generation fencing, resume duplication and one member failing independently. |
| `T/connection/SessionWriterTest.kt` | Hello/ack/set serialization, pacing, cancellation and stale command-port rejection. |
| `T/feature/settings/` and `T/feature/lights/` ViewModel tests | Update states, group validation, deleted-target fallback, mixed/unknown presentation and partial apply results. |
| `test/mobile/releases/update-contract.test.js` | Shared fixtures accepted/rejected consistently with the authoritative release validator. Retain existing latest-publication tests. |

Use at least three fake lights with distinct settings/ranges; two identical fixtures conceal N-light assumptions. Include persistence across reconstruction and failures during a batch write.

### A5. Verification

The implementer must run the documented fast gate after each slice, using Java 21, SDK 36 and build-tools 35.0.0:

`src\mobile\android\gradlew.bat -p src\mobile\android --offline --no-daemon --dependency-verification strict testDebugUnitTest assembleDebug lintDebug`

Then:

`src\mobile\android\gradlew.bat -p src\mobile\android --stop`

Offline dependencies must already be cached. Do not change dependency pins to bypass missing artifacts.

For shared release-fixture work, use Node 22.20.0 or newer, then run `npm ci` and `npm test` from the repository root.

Verify unsigned release packaging before installer acceptance:

`src\mobile\android\gradlew.bat -p src\mobile\android --dependency-verification strict --no-configuration-cache testReleaseUnitTest lintRelease assembleRelease "-PunsignedRelease=true" "-PphoneVersionName=<allocated>" "-PphoneVersionCode=<allocated>"`

Owner acceptance never uses adb or a debug install: the owner installs signed releases downloaded from GitHub Releases on the phone. Debug builds stay a developer-only build check and cannot establish stable-release update continuity.

Use the existing attended distribution runbook for signed owner releases. Agents remain limited to `-DryRun` for signing/publication/install owner scripts. Verify actual installer behavior on the owner’s phone, including permission denial, cancellation and a higher signed APK retaining data. Check platform adapters on API 26 and a current API level where devices/emulators are available; this need not become a broad emulator fast gate.

Complete each slice’s visible acceptance above. Record N>2 hardware acceptance as pending if only two fixtures are available. Run `dk check` before pushing and satisfy the repository review gate.

No build, test, installation or hardware verification is claimed by this planning amendment.

### A6. Docs

| Document | Update |
|---|---|
| `docs/plans/android-app.md` | Append this complete amendment and mark its precedence over fixed targets and the old updater/latest-release instructions. |
| `STRUCTURE.md` | Add enforced homes for Settings, navigation, catalog models/stores, update policy/adapters, control targeting and mirrored tests. |
| `docs/ARCHITECTURE.md` | Describe dynamic sessions/shared discovery, catalog versus wanted state, group expansion, receipt-based confirmation and installer boundaries. |
| `docs/DECISIONS.md` | Record explicit GitHub updates, direct installer plus browser fallback, manual network use, per-light group intent and serialized establishment with concurrent live links. Supersede only the updater exclusion from the earlier CD deviation. |
| `docs/GOTCHAS.md` | Document install-source permission, debug/release separation, signing continuity, missing-manifest behavior, inactive report fields, scan demand and notification freshness. |
| `docs/FEATURES.md` | Correct stale release status using the brief’s published 0.1.1 baseline; distinguish implemented, owner-tested and planned additions. |
| `docs/ROADMAP.md` | Record 2A–2D as buildable now and revised 3→4→5 dependencies. |
| `docs/mobile/android-distribution.md` | Document on-phone updates and data-retention acceptance; correct stale “no key/release” statements without claiming unverified recovery or upgrade tests passed. |
| `CLAUDE.md`, `README.md` | Update entry points and describe optional update networking while retaining offline BLE operation. |

### A7. Open questions / assumptions

- **Amendment delivery:** this is the complete proposed amendment text. The planning environment is read-only; the implementer must append it to the plan.
- **Update policy:** assume owner-initiated checks only, stable Android releases only, and the existing permanent signing key. Automatic checks, notifications, silent installation and key rotation are outside this amendment.
- **Legacy release metadata:** a missing manifest permits an explicitly manual browser handoff; it never enables the verified direct-install path.
- **Group semantics:** assume one-member and overlapping groups are useful; no automatic “Both” group, nested groups or group-owned wanted state.
- **Membership timing:** edits affect future actions. Deleting a group does not cancel already accepted per-light intent.
- **Light management scope:** add and rename are included. Forgetting lights is deferred to avoid adding deletion semantics to this request.
- **Compatibility:** discovery exposes candidates; the implemented controls remain for the validated GVM protocol/model family. Additional models require evidence before sharing scene/control assumptions.
- **N-light capacity:** there is no fixed two-light application limit, but phone BLE capacity is not guaranteed. Preserve per-light failure visibility and measure additional hardware as it becomes available.
- **Confirmation limitation:** the protocol has no transaction ID. A matching full report received after the final write is the strongest available evidence, not a correlated acknowledgement.
- **Physical controls (owner hardware fact, 2026-10-28):** the rear knobs are disabled while a light is in app mode, so they cannot change state during an app session. No acceptance step may use the knobs: set up a starting state (for example the colour-bug starting CCT) from this app, or before the session from the official app or a power cycle, then close the official app before connecting.
- **Remaining structural compromise:** platform HTTP and SharedPreferences keep dependencies small but require explicit lifecycle and persistence discipline. Revisit them only when demonstrated requirements exceed these boundaries.