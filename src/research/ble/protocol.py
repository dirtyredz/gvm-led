"""Legacy plain-GATT (BT_LED) framing for the GVM 800D3X-RGB lights. Pure functions, no Bluetooth.

Wire format (docs/research/protocol.md, "Confirmed legacy framing"):
  4C 54 | LEN | DEVID | DEVTYPE | KIND/body... | CRC16-XMODEM big-endian | [FF]
  LEN = len(DEVID..body) + 2.  The trailing FF is what the app's "\\r\\n" turns into.
"""
from dataclasses import dataclass, field

HEADER = b"\x4c\x54"
CHAR_UUID = "00010203-0405-0607-0809-0a0b0c0d2b10"

# Set-command bytes (CMD in body 57 00 CMD 01 VAL).
C_POWER, C_PICK, C_BRIGHT, C_CCT, C_HUE, C_SAT, C_MODE, C_SCENE, C_TIME = range(9)
MODE_CCT, MODE_HSI, MODE_SCENE = 1, 2, 3
MODE_NAMES = {0: "?", MODE_CCT: "CCT", MODE_HSI: "HSI", MODE_SCENE: "SCENE"}

K_STATE_QUERY_REPLY, K_STATE_REPORT, K_ACK, K_HELLO = 0x02, 0x03, 0x48, 0x53

# State fields that mean something in each mode (power and mode are always compared).
APPLICABLE = {
    MODE_CCT: ("bright", "cct"),
    MODE_HSI: ("bright", "hue", "sat"),
    MODE_SCENE: ("bright", "scene", "time_ms"),
}


def crc16_xmodem(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def frame(devid: int, devtype: int, body: bytes, ff: bool = True) -> bytes:
    inner = bytes([devid, devtype]) + body
    pkt = HEADER + bytes([len(inner) + 2]) + inner
    pkt += crc16_xmodem(pkt).to_bytes(2, "big")
    return pkt + (b"\xff" if ff else b"")


def hello_packet(ff: bool = True) -> bytes:
    return frame(0, 0, bytes.fromhex("5300000100"), ff)


def state_query_packet(devid: int, devtype: int, ff: bool = True) -> bytes:
    return frame(devid, devtype, bytes([0x52, 0x00, 0x00, 0x05 if devtype == 0x30 else 0x04]), ff)


def set_packet(devid: int, devtype: int, cmd: int, value: int, ff: bool = True) -> bytes:
    return frame(devid, devtype, bytes([0x57, 0x00, cmd, 0x01, value & 0xFF]), ff)


def ack_packet(devid: int, devtype: int, report_payload: bytes, ff: bool = True) -> bytes:
    """Ack for a 0x03 report: kind 48 followed by the report payload echoed back (ControlUtil.responseDev)."""
    return frame(devid, devtype, bytes([K_ACK]) + report_payload, ff)


@dataclass
class Frame:
    devid: int
    devtype: int
    kind: int
    payload: bytes
    raw: bytes


@dataclass
class FrameDecoder:
    """Reassembles frames from notification fragments. Frames with a bad CRC are rejected, never returned."""
    buf: bytes = b""
    rejected: list = field(default_factory=list)  # raw bytes of rejected frames, for the log

    def feed(self, data: bytes) -> list:
        self.buf += data
        out = []
        while True:
            i = self.buf.find(HEADER)
            if i < 0:
                self.buf = self.buf[-1:] if self.buf.endswith(HEADER[:1]) else b""
                break
            self.buf = self.buf[i:]
            if len(self.buf) < 3:
                break
            if self.buf[2] < 5:  # smallest legal LEN: devid + type + kind + CRC
                self.rejected.append(self.buf[:3])
                self.buf = self.buf[2:]
                continue
            end = 3 + self.buf[2]
            if len(self.buf) < end:
                break
            raw = self.buf[:end]
            if crc16_xmodem(raw[:-2]) != int.from_bytes(raw[-2:], "big"):
                self.rejected.append(raw)
                self.buf = self.buf[2:]  # resynchronise on the next header
                continue
            self.buf = self.buf[end:].lstrip(b"\xff")
            out.append(Frame(raw[3], raw[4], raw[5], raw[6:-2], raw))
        if len(self.buf) > 200:
            self.buf = b""
        return out


@dataclass
class Hello:
    devid: int
    devtype: int
    cct_min: int  # kelvin
    cct_max: int


def parse_hello(fr: Frame):
    """Hello reply payload: 00 00 02 MIN MAX (CCT range in 100 K). None if not a hello reply."""
    if fr.kind != K_HELLO or len(fr.payload) < 5:
        return None
    return Hello(fr.devid, fr.devtype, fr.payload[3] * 100, fr.payload[4] * 100)


def decode_state(p: bytes):
    """Payload of a 0x03 report (MainActivity.resloveState). 9 bytes is the full form."""
    if len(p) == 9:
        return dict(power=p[0], pick=p[1], bright=p[2], cct=p[3] * 100, hue=p[4] * 5, sat=p[5],
                    mode=p[6], scene=p[7], time_ms=p[8] * 100)
    if len(p) == 6:
        return dict(power=p[0], pick=p[1], bright=p[2], cct=p[3] * 100, mode=p[4], scene=p[5])
    if len(p) == 4:
        return dict(power=p[0], pick=p[1], bright=p[2], cct=p[3] * 100)
    return None


def fmt_state(d) -> str:
    if not d:
        return "no state"
    parts = [f"power={'on' if d['power'] == 1 else 'off'}"]
    if "mode" in d:
        parts.append(f"mode={MODE_NAMES.get(d['mode'], d['mode'])}")
    parts.append(f"bright={d['bright']}%")
    parts.append(f"cct={d['cct']}K")
    if "hue" in d:
        parts.append(f"hue={d['hue']} sat={d['sat']}%")
    if "scene" in d:
        parts.append(f"scene={d['scene']}")
    if "time_ms" in d:
        parts.append(f"time={d['time_ms']}ms")
    return " ".join(parts)


def compare_state(wanted: dict, got):
    """Mismatch strings for the fields that apply to the wanted mode (empty list = match).

    power and mode are always compared when wanted; other fields only if they apply to the mode.
    A short-form report that lacks a wanted field counts as a mismatch, never as a pass.
    """
    if not got:
        return ["no report"]
    mode = wanted.get("mode", got.get("mode"))
    keys = [k for k in wanted if k in ("power", "mode") or k in APPLICABLE.get(mode, ())]
    return [f"{k}: wanted {wanted[k]}, got {got.get(k, 'missing')}" for k in keys if got.get(k) != wanted[k]]
