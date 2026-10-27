"""Connection handling for one light: connect, hello, send, receive, ack. No test orchestration here.

Rules enforced here (slice 0 spec): bad-CRC frames are dropped by the decoder; sets and state
queries refuse to go out until a valid hello reply supplied the device id/type (never a guessed type);
every valid 0x03 report is acked with 0x48.
"""
import asyncio
import time

import protocol as p


class HelloFailed(RuntimeError):
    pass


class Light:
    def __init__(self, name, addr, say, dry=False, ff=True):
        self.name, self.addr, self.say, self.dry, self.ff = name, addr, say, dry, ff
        self.devid = self.devtype = None  # unknown until hello
        self.cct_min = self.cct_max = None
        self.client = None
        self.decoder = p.FrameDecoder()
        self.state = None
        self.state_t = 0.0
        self.hello_ok = False
        self.rx_count = self.acks_sent = 0
        self.sent = []  # packets written since the last take_sent()
        if dry:  # documented values (protocol.md), used ONLY to print packets; marked in the log
            self.devid, self.devtype, self.cct_min, self.cct_max, self.hello_ok = 0, 0x33, 3200, 5600, True

    def tag(self):
        return f"[{self.name}]"

    # ---- transport
    async def connect(self):
        if self.dry:
            return
        from bleak import BleakClient, BleakScanner  # lazy: --dry works without bleak installed
        for attempt in range(1, 5):
            self.say(f"{self.tag()} connecting {self.addr} (attempt {attempt})")
            try:
                dev = await BleakScanner.find_device_by_address(self.addr, timeout=15)
                if dev is None:
                    raise RuntimeError("not seen in scan (off, out of range, or held by the phone app?)")
                self.client = BleakClient(dev, timeout=20)
                await self.client.connect()
                await self.client.start_notify(p.CHAR_UUID, self._on_notify)
                self.say(f"{self.tag()} connected, notifications on")
                return
            except Exception as e:  # service discovery flakes right after a disconnect
                self.say(f"{self.tag()} connect attempt {attempt} failed: {type(e).__name__}: {e}")
                try:
                    if self.client:
                        await self.client.disconnect()
                except Exception:
                    pass
                self.client = None
                await asyncio.sleep(2.5)
        raise RuntimeError(f"{self.name}: could not connect after 4 attempts")

    async def disconnect(self):
        if self.client and self.client.is_connected:
            try:
                await self.client.stop_notify(p.CHAR_UUID)
            except Exception:
                pass
            await self.client.disconnect()
            self.say(f"{self.tag()} disconnected")
        self.client = None
        if not self.dry:
            self.hello_ok = False
            self.devid = self.devtype = None
        self.decoder = p.FrameDecoder()

    def _on_notify(self, _h, data):
        self.rx_count += 1
        self.say(f"{self.tag()} RX {bytes(data).hex(' ')}")
        n_rej = len(self.decoder.rejected)
        frames = self.decoder.feed(bytes(data))
        for raw in self.decoder.rejected[n_rej:]:
            self.say(f"{self.tag()} REJECTED bad-CRC/invalid frame {raw.hex(' ')}")
        for fr in frames:
            self._handle(fr)

    def _handle(self, fr: p.Frame):
        hello = p.parse_hello(fr)
        if hello:
            self.devid, self.devtype, self.cct_min, self.cct_max = hello.devid, hello.devtype, hello.cct_min, hello.cct_max
            self.hello_ok = True
            self.say(f"{self.tag()} HELLO reply devid={hello.devid:02X} type={hello.devtype:02X} "
                     f"cct_range={hello.cct_min}-{hello.cct_max}K")
        elif fr.kind == p.K_STATE_REPORT:
            d = p.decode_state(fr.payload)
            if d:
                self.state, self.state_t = d, time.time()
            self.say(f"{self.tag()} REPORT {p.fmt_state(d)}  payload={fr.payload.hex(' ')}")
            if self.hello_ok and self.client:
                asyncio.get_running_loop().create_task(self._ack(fr.payload))
        else:
            self.say(f"{self.tag()} OTHER kind={fr.kind:02X} frame={fr.raw.hex(' ')}")

    async def _ack(self, payload: bytes):
        try:
            await self.send(p.ack_packet(self.devid, self.devtype, payload, self.ff), "ack report (48)", track=False)
            self.acks_sent += 1
        except Exception as e:
            self.say(f"{self.tag()} ack failed: {type(e).__name__}: {e}")

    async def send(self, pkt: bytes, why="", track=True):
        self.say(f"{self.tag()} TX {pkt.hex(' ')}  {why}")
        if track:
            self.sent.append(pkt)
        if self.dry:
            return
        await self.client.write_gatt_char(p.CHAR_UUID, pkt, response=False)

    def take_sent(self):
        out, self.sent = self.sent, []
        return out

    # ---- protocol
    async def hello(self, tries=5) -> bool:
        """Hello uses 00/00 by definition (not a guess). Returns False if no valid reply."""
        if self.dry:
            await self.send(p.hello_packet(self.ff), "hello (dry run: type 33 / range 3200-5600 assumed from protocol.md)")
            return True
        for _ in range(tries):
            await self.send(p.hello_packet(self.ff), "hello", track=False)
            for _ in range(10):
                await asyncio.sleep(0.1)
                if self.hello_ok:
                    return True
        return False

    def _require_hello(self):
        if not self.hello_ok or self.devtype is None:
            raise HelloFailed(f"{self.name}: no valid hello reply; refusing to send with a guessed device type")

    async def query_state(self):
        self._require_hello()
        await self.send(p.state_query_packet(self.devid, self.devtype, self.ff), "state query (52)", track=False)

    async def cmd(self, c, v, why=""):
        self._require_hello()
        await self.send(p.set_packet(self.devid, self.devtype, c, v, self.ff), why)

    async def fresh_report(self, since: float, timeout=9.0):
        """Wait for a 0x03 report received after `since` (lights push one about every 4 s)."""
        if self.dry:
            return None
        end = time.time() + timeout
        while time.time() < end and self.state_t <= since:
            await asyncio.sleep(0.1)
        return self.state if self.state_t > since else None
