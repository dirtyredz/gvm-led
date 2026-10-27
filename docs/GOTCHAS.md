# GOTCHAS

- **Windows connect fails with "device not found"** -> bleak/WinRT needs the device seen in a scan first -> scan, then connect (the scripts do).
- **Connect refused or characteristic missing** -> a light accepts one connection; the phone app (or a previous connection) holds it -> close the GVM app; connect to lights sequentially; retry once after a disconnect ("Characteristic not found").
- **Packet ignored** -> the app's `\r\n` becomes a trailing `FF` byte on the wire (hex parsing maps the invalid chars to -1) -> always append `FF`; the no-FF variant is untested.
- **Writes error or are dropped** -> the characteristic is write-without-response only -> write with response=False.
- **Mesh opcodes do nothing** -> sections 2-3 of protocol.md (A1/A2/C1/5B...) are the mesh path; these lights use the legacy set -> use CMD 00-08 in protocol.md.
- **Resolution** -> legacy brightness is 1 % steps, hue 5 degree steps.
- **Ack format is inferred** -> `ack_packet` (kind 48 + echoed 0x03 payload) follows `ControlUtil.responseDev`; the exact bytes are unconfirmed on the wire -> check the first real run's log.
- **Never send sets before a valid hello** -> the old dry run assumed type 30, the lights are type 33 -> `client.py` refuses sets and queries until hello parsed.

_Living doc — refresh with /project-docs when it drifts._
