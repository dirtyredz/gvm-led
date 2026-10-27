# GVM LED lights: control protocol from the official app (static analysis, 2026-10-25)

Source: official Android app "GVM LED" `com.gvm.cloudphone` v1.8.0 (APKPure, sha256 `3a5aa2fa...e6509b6`), decompiled with jadx 1.5.6 (never executed). Decompiled tree: `.tmp/decompiled/sources/`, manifest in `.tmp/res/`. Not obfuscated beyond use: GVM's own code (`com.gvm.cloudphone`) is readable; the Tuya SDK is partly renamed but workable. Everything below is read from code, not yet confirmed on a real light.

## Summary

- **Two transports live in one app.** (1) **Tuya BLE SIG-mesh** (the main path, all current screens: HSI, CCT, effects, groups). (2) A **legacy plain-GATT path** for old lights whose BLE name starts with `BT_LED`. The 800D3X-RGB is a "Bluetooth mesh" product, so it is almost certainly path 1. **Unconfirmed**: check on the real light (see Open questions).
- **Feasibility: harder than plain GATT, but doable.** Mesh traffic is encrypted with a per-home network key + app key that the **Tuya cloud generates** (`thing.m.device.sig.mesh.create`) and stores; they are not hard-coded in the app. The lights hold the keys the owner's phone account provisioned. Two ways in: (a) factory-reset both lights and **provision them into our own SIG mesh** with our own random keys (standard SIG provisioning; the app's Start PDU uses no-OOB unless the node advertises Output OOB), then talk the vendor model ourselves; (b) pull the existing keys for the owner's Tuya account (anonymous account: `loginOrRegisterWithUid(country, android_id, "123456")`) from Tuya's cloud. (a) needs no cloud and no vendor credentials; (b) uses the vendor's OEM app key/secret and is not recommended.
- **Application payload is simple and un-encrypted above the mesh layer**: one Tuya data point, DP `"101"` (raw, hex string), carrying a tiny command: `opcode, little-endian fields`. No header, no CRC on mesh commands. Hue 0-360 in 1 deg, saturation 0-100, intensity 0-1000 (0.1 % steps), CCT in kelvin (16-bit).
- **Unknowns that block a custom app**: the Tuya vendor-model opcode/framing that wraps DP 101 on the mesh (inside the Tuya SDK, not read in full) and whether provisioning needs Tuya-specific steps. A phone BLE capture would not help (mesh PDUs are encrypted); reading `com.thingclips.sdk.sigmesh` / `...sdk.bluetooth` further and testing on a reset light will.

## 1. Transport details

| Item | Value | Evidence |
|---|---|---|
| Mesh unprovisioned scan | service UUID `00001827-0000-1000-8000-00805f9b34fb` (Mesh Provisioning); proxy would be `0x1828` | `ThingUtil.searchDevice()` `SearchBuilder().setServiceUUIDs(...1827...)` |
| Mesh setup | Tuya cloud: create home, `createSigMesh`, `getSigMesh`; `SigMeshBean` carries netKey/appKey/ivIndex | `ThingUtil.getRoomList/createSigMesh/getSigMesh`; `SigMeshBean`; API names `thing.m.device.sig.mesh.create`, `...node.alloc.batch`, `thing.m.sig.mesh.source.id.alloc` |
| Provisioning | Nordic-style SIG provisioner; Start PDU `03 | alg | pubkeyType 0 | auth 0 (no OOB) ...` | `com.thingclips.sdk.bluetooth.bpbdqdp.bdpdqbp()`, `sigmesh/util/SecureUtils` |
| Account | anonymous Tuya user, uid = Android ID, password literal `"123456"` | `ThingUtil.init()` |
| Command channel | DP `"101"` = hex string; `mTuyaBlueMeshDevice.publishDps(nodeId, category, {"101":"<hex>"})` | `ThingUtil.publishDpsDevice()` |
| Legacy GATT (`BT_LED*` only) | service `00010203-0405-0607-0809-0a0b0c0d1910`, char `...2b10` (write w/ response + notify, CCCD `01 00`), MTU 100, ASCII-hex strings converted to bytes, split in 20-byte chunks, 200 ms apart, `\r\n` appended | `Service/BLEService.java`; name filter `startsWith("BT")` / `contains("BT_LED")` in `DeviceActivity` |
| Wi-Fi | **none**: no `DatagramSocket`/`Socket` use in `com.gvm` | grep |

## 2. Command table (mesh path, DP 101 payload, all hex, multi-byte = little-endian)

Source: `InstructData.java` (templates) plus the Activities that fill them. `I` = intensity u16.

| Function | Bytes | Range / notes |
|---|---|---|
| Power / FX flag | `0F  on  fx` | on 0/1; fx 0/1/2 (set by effect screens; keep what the light reports) (`ThingUtil.power1`) |
| Intensity (CCT mode) | `C1  I_lo I_hi` | 0-1000 = 0.0-100.0 % (UI shows `/10`) (`CctActivity`) |
| Colour temp | `C2  K_lo K_hi  flag` | kelvin; `flag` 0/1 = `cctFlag` UI variant; usable range is read from the light (min/max, default fallback 2700-7500) |
| GM / CUR | `C3  gm  cur` | one byte each |
| Intensity (HSI) | `A1  I_lo I_hi` | 0-1000 (`HsiActivity.hsiOne`) |
| **Hue / saturation (HSI)** | `A2  H_lo H_hi  S` | **H 0-360, 1 deg**; **S 0-100, 1 %** (`HsiActivity.hsiTwo`). `A2 00 00 00` is never sent (dropped in `publishDpsDevice`) |
| Gel | `B1 I_lo I_hi` / `B2 gelType gelNum cct` | |
| RGB | `D1 I_lo I_hi` / `D2 R G B` / `D3 ...16 bytes` (extended 6-ch, `RgbActivity`) | 0-255 each |
| Light source | `E1 I` / `E2 K` / `E3 slType gm` | |
| Effects | `81 I` `82 efType rate` `83 K` `91 I` `92 efType sat rate` `93 H` | scene/effect id = efType, rate byte |
| XY, zone, effect params | `61 ...`, `F2/F3/F4/F1`, `71-79` | not decoded |
| Connect/hello | `4C 54 00 00 AA 53 01 lang sum` | lang `43`(zh)/`45`(en); `sum` = sum of previous bytes mod 256 (`checkSumming`) |
| Query state | `5B` (mode+values) and `5A` (power/fx) | each sent as a bare DP |

**No separate "set mode" opcode exists.** Mode is implied by the opcode family (`A*` HSI, `C*` CCT, `B*` gel, `D*` RGB, `E*` source, `8*/9*` effects). Mode numbers used in replies: 0 CCT, 1 HSI, 2 gel, 3 RGB, 4 source, 5/6 effects, 7 zone.

Throttle: UI sends at most one packet per 200 ms per family (`sendCommand1/2`, `isRunning`).

## 3. State read-back (yes, on request)

Replies come back as DP 101 via `IMeshDevListener.onDpUpdate` (`ThingUtil.initTuyaBlueMeshDevice`).
- `5B mode I_lo I_hi ...` e.g. HSI: `5B 01 I_lo I_hi H_lo H_hi S`; CCT: `5B 00 I.. K_lo K_hi gm cur`.
- `5A on fx`.
- `4C 54 00 00 FC 53 05 ? min_lo min_hi max_lo max_hi` = CCT range reply to the hello (`HsiActivity`/`ControlActivity.mainData`).
- Mesh node online/offline via `onStatusChanged`; app calls `queryAllOnLineStatusByLocal` and sets `OnlineMode.RESPONSE_ONLINE`.

## 4. Connect / resume behaviour

1. App start: Tuya login, find/create home + SigMesh, `startClient` (proxy connection to the nearest provisioned node), scan for new nodes.
2. Tapping a light opens `ControlActivity`, which after 100 ms sends `5B` then (200 ms later) `5A`, parses `5B`'s mode byte and **jumps to the screen for that mode** (HSI/CCT/...). So on a fresh open the app trusts the light.
3. `HsiActivity.onResume()` and `CctActivity.initView()` / `HsiActivity.initData()` send **only the intensity packet** (`A1` / `C1`); hue/sat (`A2`) is only sent when the user moves a control. `HsiActivity.onResume` is empty: there is no re-sync or re-send of the full colour state when the app returns from background.
4. Legacy path only: `ControlUtil.controlDev` suppresses a command equal to the last one (`flag`), and a 1 s timer re-sends the hello until answered.

## 5. The "must toggle to standard and back" quirk (hypotheses from code, not proven)

- There is no explicit mode-select command; the light switches mode when it receives a colour-family command. When the app comes back, the existing HSI screen's UI state (hue, sat) is unchanged and nothing is re-sent except `A1`. If `A1` alone does not switch the light to HSI (likely: it only carries intensity), the light stays in its previous mode until `A2` is sent. Toggling to CCT and back fires a fresh `C*`/`A*` sequence, which is why it "sticks".
- Contributing: while backgrounded the Tuya client may drop the mesh proxy link; commands sent during reconnect are fire-and-forget (`publishDps`, errors only logged), so the first colour packet can be lost silently. `HsiActivity` also rewrites hue 0 to 10 and sat 0 to 10 when parsing `5B`.
- **For our app**: on every connect/resume send the complete set for the wanted mode (`A2` then `A1`, or `C2` then `C1`), re-query with `5B`, and retry until a `5B` reply shows the expected mode.

## 6. Two lights

- Per light: `publishDps(nodeId, category, ...)` to a unicast mesh node.
- Group: Tuya group = SIG group address; `multicastDps(group.localId, category, {"101":hex})` (`ThingUtil.sendExDps`, `CreateGroupActivity`). One packet reaches all members, so two lights can be set to identical colours at once; per-light differences use two unicasts.
- Group membership/subscriptions are set at provisioning/config time (`thing.m.device.ble.mesh.*` APIs, vendor subscription list).

## 7. Open questions to confirm on the real lights

1. **Which path?** nRF Connect scan on a light that is NOT connected to the app: name starting `BT_LED` plus service `...1910` = legacy plain GATT (then it is trivially scriptable: header `4C54`, CRC16/XMODEM, see `ControlUtil`). Service `0x1827`/`0x1828` or `0x1827` beacon = Tuya mesh.
2. For mesh: what exact vendor-model opcode wraps DP 101 and how its TID/framing works (read `ThingVendorModelStatus`, `VendorModelMessageStatus` in `com.thingclips.sdk.sigmesh.provisioner` next; or provision a reset light in nRF Mesh and observe).
3. Does `A1` alone switch to HSI, or only `A2`? Test by writing each in CCT mode.
4. Does a reset light accept no-OOB provisioning from a stock SIG provisioner (or require Tuya's device-authorisation step)? Resetting forgets the official app's pairing; re-pair there afterwards.
5. Real CCT range per model (reply to hello) and meaning of `fx` and `C2` flag.

## 8. Evidence references (all under `.tmp/decompiled/sources/com/`)

`gvm/cloudphone/InstructData.java` (opcode templates, `byteToString`, `checkSumming`); `Util/ThingUtil.java` (`publishDpsDevice`, `sendExDps`, `searchDevice`, `initTuyaBlueMeshDevice`, `init`); `Activity/HsiActivity.java` (`hsiOne`, `hsiTwo`, `getData` 5A/5B parse); `Activity/CctActivity.java`; `Activity/ControlActivity.java` (`SyncStatus`, `mainData`); `Service/BLEService.java` + `Util/ControlUtil.java` + `Util/CRC16.java` (legacy); `thingclips/sdk/bluetooth/bpbdqdp.java` (provisioning start PDU); `thingclips/smart/sdk/bean/SigMeshBean.java`. Vendor Tuya OEM app key/secret are in `AndroidManifest.xml` meta-data (not copied here).

## Earlier notes (pre-analysis, still valid)

Vendor pages: spec says BT control <=15 m, "wireless master-slave" <=50 m, hue 0-360, sat 0-100, CCT ~3200-5600 K (800D-RGB), 8 scenes. No public GVM protocol write-ups existed; method references: 8none1/ledble-ledlamp, Wes Bos' Amaran mesh write-up, nRF Mesh app for provisioning tests.

## PC scan results (2026-10-25)

Method: Python 3.12 + `bleak` (venv `.tmp/scan/`), Valhalla, 28 s active scan, then one read-only connect per candidate (no writes, no pairing, no CCCD writes; read only Generic Access / PnP ID). Scripts: `.tmp/scan/scan.py`, `.tmp/scan/gatt.py`; raw output `.tmp/scan/scan.json`. Scan 1 was discarded because the PC's Bluetooth had been off; this is the re-run.

**Verdict: legacy plain GATT (path a). Both lights found, both advertise as `BT_LED`. No mesh (0x1827 / 0x1828) advertisement from any device.**

Candidates (the only two `BT_LED` devices; every other device was a different product):

| Address (random static) | Name | RSSI max | Adv service UUIDs | Manufacturer data (company id : payload) | Service data |
|---|---|---|---|---|---|
| `94:C9:60:E5:D7:6F` | `BT_LED` | -71 | 0x180F Battery, 0x1812 HID | `0xD76F` : `e560c994` | none |
| `94:C9:60:EB:67:E6` | `BT_LED` | -73 | 0x180F Battery, 0x1812 HID | `0x67E6` : `eb60c994` | none |

Notes on the advertisements: the "manufacturer data" is not a registered company ID; it is the last two address bytes (little-endian, `0xD76F`/`0x67E6`) followed by the address bytes reversed (`e5 60 c9 94` = tail of `94:C9:60:E5`), so it is just the device address echoed. Per-light identity = the address. Both lights are only about 2 dB apart in RSSI, so they could not be told apart by signal.

GATT (identical on both; connect succeeded without pairing):

- 0x1800 Generic Access: `2A00` Device Name = `BT_LED` (notify, read); `2A01` Appearance = `0000`; `2A04` conn params = min 20 / max 40 (x1.25 ms), latency 0, timeout 1000 (x10 ms).
- 0x1801: `2A05` Service Changed (indicate).
- 0x180A: only `2A50` PnP ID = `02 8a24 6682 0100` (vendor source 0x02 USB-IF, VID 0x248A, PID 0x8266, ver 0x0001). 0x248A is Telink Semiconductor's USB VID, so the chip is likely a Telink BLE SoC. No Manufacturer/Model strings exposed.
- **`00010203-0405-0607-0809-0a0b0c0d1910`** (the legacy service from the static analysis), one characteristic **`...0d2b10`: properties `notify`, `write-without-response`, `read`**. Not read (not on the safe list; could be a status value).
- 0x180F and 0x1812 are in the advertisement but NOT present in the GATT table.

Conclusions:

1. Both 800D3X-RGB lights use the **legacy plain-GATT path**: name `BT_LED`, exact legacy service/characteristic UUIDs from `BLEService.java`. This contradicts the earlier "almost certainly Tuya mesh" guess in the Summary. No Tuya (0x07D0) data, 0x1827 or 0x1828 was seen from these lights. (Unrelated hit: `LK` (address redacted) had 0x07D0 + service 0xA201 at -85 dBm; this is some other Tuya product, not a candidate. Other Govee/ihoment/ELK-BLEDOM/Apple/Google-FCF1 devices are neighbours.)
2. Not provisioned/unprovisioned mesh: neither state applies.
3. Difference from the static analysis: the characteristic reports **write-without-response** (not write-with-response); use write-no-response (or whichever bleak accepts) and subscribe to notifications. Everything else (service, char UUID) matches. The 20-byte chunking, `\r\n`, CRC16 framing in `BLEService`/`ControlUtil`/`CRC16` is the thing to implement next.
4. No pairing needed to connect and read; the lights stayed connectable (one connection at a time, so close the phone app first).

## Live test prep (2026-10-26)

Script: `.tmp/scan/live_test.py` (`--dry`, `--check`, `--run`; logs to `.tmp/owner-runs/live-test-<timestamp>.log`). Re-read of the legacy path (`Util/ControlUtil.java`, `Util/CRC16.java`, `Service/BLEService.java`, `Util/Str.java`, `Activity/MainActivity.java`).

**Big correction: the legacy path does NOT carry the mesh commands (A1/A2/C1-C3/5A/5B).** It is a separate, coarser command set. Sections 2-3 above (DP 101 payloads, 0.1 % intensity, 1 deg hue) apply to the mesh path only.

### Confirmed legacy framing (all hex bytes; confirmed on both lights)

`4C 54 | LEN | DEVID | DEVTYPE | body | CRC_hi CRC_lo | FF`

- `LEN` = number of bytes from DEVID through the CRC inclusive (= len(DEVID..body) + 2).
- `CRC` = CRC16/XMODEM (poly 0x1021, init 0) over everything from `4C` to the end of body (header and LEN included), **big-endian** (high byte first). `CRC16.getCRC16` zero-pads to 4 hex digits. Verified: hello `4C 54 09 00 00 53 00 00 01 00` -> `94 74`.
- Trailing `FF`: the app appends the string `"\r\n"` to the hex text, and `Str.getHexBytes` turns that pair into one byte `0xFF` (invalid hex chars map to -1). Lights replied with it present; the no-FF variant was not needed and is untested (`--no-ff`).
- Write type: write-without-response (what the characteristic offers). One write per packet (the 20-byte chunking in `sendData` only triggers for hex strings > 120 chars, never for these). The app ticks every 200 ms and re-sends the pending packet; we send once.
- `DEVID`/`DEVTYPE` come from the hello reply; before it the app uses `00`/`30`. Both lights: ID `00`, type `33` (the `rgb33` family).
- **Hello** (sent on connect, repeated every 1 s until answered): `4C 54 09 00 00 53 00 00 01 00 94 74`. Reply: `4C 54 0A 00 33 53 00 00 02 20 38 F3 9B`; byte 9 / 10 = CCT min / max in 100 K (`20`/`38` = 3200-5600 K).
- **Set command** (`controlDev`): body = `57 00 CMD 01 VAL` (one byte value), e.g. `4C 54 09 00 33 57 00 02 01 32 CRC` = brightness 50 %.
- **State query** (`getStateOperValue`, unused by the app): body = `52 00 00 04` (`05` for type 30). Reply is `4C 54 0B ID TYPE 02 00 00 01 pick bright cct CRC` (short form).
- **State report** (kind `03`, pushed by the light right after hello and about every 4 s, the app acks with kind `48`): `4C 54 0E ID TYPE 03 | power pick bright cct/100 hue/5 sat mode scene time/100 | CRC`. mode: 1 CCT, 2 HSI, 3 scenes.

### Legacy command bytes (`CMD`, value is one byte)

| CMD | Meaning | Value |
|---|---|---|
| 00 | power | 0 / 1 |
| 01 | pick number (group/channel, 1-12) | |
| 02 | brightness | 0-100 (1 % steps, **no 0.1 %**) |
| 03 | colour temperature | K/100, within hello range |
| 04 | hue | degrees/5, 0-72 (**5 deg steps, not 1 deg**) |
| 05 | saturation (RGB screens) | 0-100 |
| 06 | **mode select** | 1 CCT, 2 HSI, 3 scenes. An explicit mode opcode DOES exist here (contradicts "no mode opcode" for mesh) |
| 07 / 08 | scene number / effect time (x100 ms) | |

Consequences: the "colour bug" sequence in legacy is `06=02` (mode) then `04`, `05`, `02`. Fine-resolution tests (20.1 %, 1 deg hue) can only show whole-percent / 5-degree behaviour on this path.

### `--check` result (queries only, no visible change)

Replies received on the first framing tried (trailing FF on) from both lights, so no variants were needed. The hello reply and the periodic 0x03 state reports decode with valid CRCs; the `52` query also answers (short `02` form).

| Light | Address | State at check time |
|---|---|---|
| light 1 | `94:C9:60:E5:D7:6F` | type 33, on, HSI, hue 355, sat 100 %, brightness 7 %, (CCT byte 4400 K, inactive), pick 11 |
| light 2 | `94:C9:60:EB:67:E6` | type 33, on, HSI, hue 240, sat 100 %, brightness 34 %, (CCT byte 3600 K, inactive), pick 11 |

Notes: Windows needs a scan hit before connect (script scans first); connects must be sequential and sometimes need a retry ("Characteristic not found" right after a disconnect). Phone app must be closed.

## Visible proof run (2026-10-26)

Run: `src/research/ble/live_test.py --run --camera` via `scripts/owner/prove-light-commands.ps1`; log `.tmp/owner-runs/live-test-20261026-204635.log`. Both lights type 33, phone app closed. Result: 0 mismatches between wanted values and fresh 0x03 reports on every step.

- **All set commands work on both lights**: power (00), mode (06), hue (04), saturation (05), brightness (02), CCT (03), scene (07), effect time (08). Each set is answered by a short kind-02 echo `4C 54 08 ID TYPE 02 00 CMD VAL CRC`; the next full 0x03 report confirms the stored values.
- **CCT endpoints** are the hello range, 3200-5600 K (hello bytes `20`/`38`); both endpoints were accepted and reported back.
- **Scenes 19 and 20** with effect times were accepted and reported (scene and time fields).
- **While off**: the light accepts and stores parameter and mode changes. A mode change does NOT turn it on. Power-on then shows the stored values. Power ordering for the app: send the full settings, then power on last; either order works.
- **Colour-bug check passed**: disconnect, 5 s, reconnect, hello, then mode-first HSI amber (06=02, hue, sat, brightness) gave a matching report on both lights with no CCT detour.
- **0x48 ack** (kind 48 + echoed 0x03 payload) provoked no visible error; the lights kept reporting.
- A camera check after the run showed both lights warm white, matching the final step.
- Tooling note: the per-step Agent Eye shots failed in this run (`snapshot.mjs shot` prints `log:`, a JSON line, then `OK ...`; camera.py now parses the `{` line).
