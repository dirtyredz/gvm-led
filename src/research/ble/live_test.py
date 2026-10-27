"""Visible proof run for two GVM-800D3X-RGB lights over the legacy plain-GATT (BT_LED) protocol.

Modes (exactly one):
  --dry    print every planned packet (and camera shot point) as hex; no Bluetooth, no camera
  --check  connect to both, hello + state reports only (no visible change)
  --run    paced visible sequence; the observer is the camera (--camera) or you (typed answers)

Each step logs: WANTED values, packets SENT, the fresh REPORT(s) with a field-by-field compare, and
either the saved camera image path or what you typed you saw. Framing: protocol.py. Connections: client.py.
Runs from any directory; logs to .tmp/owner-runs/live-test-<timestamp>.log (+ a same-named folder of images).
Exit codes: 0 all steps matched, 1 error / hello failed (nothing guessed was sent), 2 --check got no replies,
3 run finished but a non-experiment step did not match its report.
"""
import argparse
import asyncio
import datetime
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import protocol as p
from camera import DEFAULT_SNAPSHOT, Camera
from client import HelloFailed, Light

ROOT = Path(__file__).resolve().parents[3]
LOG_DIR = ROOT / ".tmp" / "owner-runs"
LIGHTS = {"light1": "94:C9:60:E5:D7:6F", "light2": "94:C9:60:EB:67:E6"}
SETTLE = 1.5  # seconds after the last write before the camera shot / fresh-report wait
GAP = 0.2     # seconds between writes inside a step

_log = None


def say(msg=""):
    line = f"{datetime.datetime.now().strftime('%H:%M:%S.%f')[:-3]} {msg}"
    print(line, flush=True)
    if _log:
        _log.write(line + "\n")
        _log.flush()


@dataclass
class Step:
    text: str                  # what to look for
    lights: list               # names the step addresses
    plan: object               # fn(light) -> (actions [(cmd, value, why)], wanted dict)
    experiment: bool = False   # a mismatch is a FINDING about firmware behaviour, not a failure
    reconnect: bool = False    # disconnect, wait, reconnect + hello first (colour-bug test)
    results: list = field(default_factory=list)


def hsi(h, s, b, power=False):
    def plan(_l):
        acts = ([(p.C_POWER, 1, "power on")] if power else []) + [
            (p.C_MODE, p.MODE_HSI, "mode=HSI (first)"), (p.C_HUE, h // 5, f"hue {h} deg (/5)"),
            (p.C_SAT, s, f"sat {s}%"), (p.C_BRIGHT, b, f"brightness {b}%")]
        wanted = dict(mode=p.MODE_HSI, hue=h, sat=s, bright=b)
        return acts, ({**wanted, "power": 1} if power else wanted)
    return plan


def cct(which, b=50):
    def plan(l):
        k = l.cct_min if which == "min" else l.cct_max if which == "max" else which
        return ([(p.C_MODE, p.MODE_CCT, "mode=CCT (first)"), (p.C_CCT, k // 100, f"cct {k}K"),
                 (p.C_BRIGHT, b, f"brightness {b}%")], dict(mode=p.MODE_CCT, cct=k, bright=b))
    return plan


def scene(n, t_ms, b=50):
    def plan(_l):
        return ([(p.C_MODE, p.MODE_SCENE, "mode=SCENE (first)"), (p.C_SCENE, n, f"scene {n}"),
                 (p.C_TIME, t_ms // 100, f"effect time {t_ms} ms (/100)"), (p.C_BRIGHT, b, f"brightness {b}%")],
                dict(mode=p.MODE_SCENE, scene=n, time_ms=t_ms, bright=b))
    return plan


def power(on):
    return lambda _l: ([(p.C_POWER, 1 if on else 0, f"power {'on' if on else 'off'}")], dict(power=1 if on else 0))


def only(acts, wanted):
    return lambda _l: (acts, wanted)


L1, L2, BOTH = ["light1"], ["light2"], ["light1", "light2"]
STEPS = [
    Step("Light 1 only: RED at 50% (light 2 must not change)", L1, hsi(0, 100, 50, power=True)),
    Step("Light 1: BLUE (hue 240)", L1, hsi(240, 100, 50)),
    Step("Light 1: saturation 50% (paler blue)", L1, hsi(240, 50, 50)),
    Step("Light 2 only: GREEN (hue 120) at 50%", L2, hsi(120, 100, 50, power=True)),
    Step("BOTH: MAGENTA (hue 300)", BOTH, hsi(300, 100, 50)),
    Step("BOTH: CCT at the LOW end of the hello range", BOTH, cct("min")),
    Step("BOTH: CCT at the HIGH end of the hello range", BOTH, cct("max")),
    Step("BOTH: scene 19, effect time 2.0 s", BOTH, scene(19, 2000)),
    Step("BOTH: scene 20, effect time 0.5 s", BOTH, scene(20, 500)),
    Step("BOTH: power OFF (dark)", BOTH, power(False)),
    Step("Light 1 EXPERIMENT: set HSI red 30% sat 20 while OFF (should stay dark; are values stored?)", L1,
         only([(p.C_MODE, p.MODE_HSI, "mode=HSI while off"), (p.C_HUE, 0, "hue 0 while off"),
               (p.C_SAT, 20, "sat 20%"), (p.C_BRIGHT, 30, "brightness 30% while off")],
              dict(power=0, mode=p.MODE_HSI, hue=0, sat=20, bright=30)), experiment=True),
    Step("Light 1 EXPERIMENT: mode change only (CCT) while OFF - does it turn the light on?", L1,
         only([(p.C_MODE, p.MODE_CCT, "mode=CCT while off")], dict(power=0, mode=p.MODE_CCT)), experiment=True),
    Step("Light 1 EXPERIMENT: power ON - which stored values appear?", L1,
         only([(p.C_POWER, 1, "power on")], dict(power=1, mode=p.MODE_CCT)), experiment=True),
    Step("BOTH: power ON (light 2 was left off)", BOTH, power(True)),
    Step("COLOUR-BUG TEST: disconnect, 5 s, reconnect, mode-first HSI AMBER (hue 35)", BOTH,
         hsi(35, 100, 50), reconnect=True),
    Step("BOTH: warm white at the LOW end of the CCT range, 40% (final state)", BOTH, cct("min", 40)),
]


async def both(lights, fn):
    await asyncio.gather(*(fn(l) for l in lights))


async def connect_all(lights):
    for l in lights:  # sequential: concurrent connects fail on Windows
        await l.connect()
    for l in lights:
        if not await l.hello():
            raise HelloFailed(f"{l.name}: hello got no valid reply; stopping before any set is sent")


async def run_steps(lights, dry, camera):
    by_name = {l.name: l for l in lights}
    await connect_all(lights)
    if camera:
        say("")
        say("STEP 00: baseline (before any change)")
        await asyncio.sleep(0 if dry else 2.0)
        camera.shoot(0)
    failures = 0
    for n, st in enumerate(STEPS, 1):
        targets = [by_name[x] for x in st.lights]
        say("")
        say(f"STEP {n:02d}{' (EXPERIMENT)' if st.experiment else ''}: LOOK -> {st.text}")
        if st.reconnect:
            await both(lights, lambda l: l.disconnect())
            await asyncio.sleep(0 if dry else 5.0)
            await connect_all(lights)
        wanted = {}
        for l in targets:
            acts, wanted[l.name] = st.plan(l)
            say(f"{l.tag()} WANTED {wanted[l.name]}")
            l.take_sent()
            for cmd, val, why in acts:
                await l.cmd(cmd, val, why)
                await asyncio.sleep(0 if dry else GAP)
        wrote_at = time.time()
        await asyncio.sleep(0 if dry else SETTLE)
        if camera:
            camera.shoot(n)
        else:
            say(f"OBSERVE: look at the lights now (step {n:02d}).")
        for l in targets:
            sent = l.take_sent()
            say(f"{l.tag()} SENT {len(sent)} packet(s): " + " | ".join(x.hex(' ') for x in sent))
            if dry:
                continue
            await l.query_state()
            rep = await l.fresh_report(wrote_at + SETTLE)
            diffs = p.compare_state(wanted[l.name], rep)
            say(f"{l.tag()} FRESH REPORT {p.fmt_state(rep)}")
            if diffs:
                say(f"{l.tag()} {'FINDING' if st.experiment else 'MISMATCH'}: " + "; ".join(diffs))
                failures += 0 if st.experiment else 1
            else:
                say(f"{l.tag()} MATCH: report agrees with wanted values")
        if not camera and not dry:
            seen = await asyncio.get_running_loop().run_in_executor(None, _ask_owner)
            say(f"OBSERVED (typed by owner, step {n:02d}): {seen}")
    await both(lights, lambda l: l.disconnect())
    return failures


def _ask_owner():
    try:
        return input("Type what you actually saw on each light, then press Enter: ").strip() or "(nothing typed)"
    except EOFError:
        return "(no input available)"


async def check(lights):
    await connect_all(lights)
    try:
        say("waiting 6 s for pushed 0x03 reports")
        t0 = time.time()
        for l in lights:
            await l.query_state()
            await l.fresh_report(t0, timeout=8.0)
        for l in lights:
            say(f"{l.tag()} notifications={l.rx_count} acks_sent={l.acks_sent} {p.fmt_state(l.state)}")
    finally:
        await both(lights, lambda l: l.disconnect())
    return 0 if all(l.rx_count > 0 and l.state for l in lights) else 2


async def amain(args, run_dir, run_name):
    lights = [Light(n, a, say, dry=args.dry, ff=not args.no_ff) for n, a in LIGHTS.items()]
    camera = Camera(say, run_dir, run_name, Path(args.snapshot), dry=args.dry) if args.camera else None
    if args.dry:
        say("DRY RUN: no Bluetooth, no camera. Device type 33 and CCT range 3200-5600 K are assumed ONLY to print packets.")
        say(f"observer: {'camera -> ' + str(run_dir) if camera else 'typed observations (owner)'}")
    try:
        if args.check:
            return await check(lights)
        failures = await run_steps(lights, args.dry, camera)
        say("")
        say(f"SUMMARY: {failures} non-experiment step/light mismatch(es)")
        return 3 if failures else 0
    except HelloFailed as e:
        say(f"STOPPED: {e}")
        return 1
    finally:
        await both(lights, lambda l: l.disconnect())


def main():
    global _log
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--dry", action="store_true")
    g.add_argument("--check", action="store_true")
    g.add_argument("--run", action="store_true")
    ap.add_argument("--no-ff", action="store_true", help="omit the trailing FF byte (default: append, as the app does)")
    ap.add_argument("--camera", action="store_true", help="capture a frame per step through Agent Eye instead of asking you")
    ap.add_argument("--snapshot", default=str(DEFAULT_SNAPSHOT), help="path to Agent Eye snapshot.mjs")
    args = ap.parse_args()
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    run_name = "live-test-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = LOG_DIR / f"{run_name}.log"
    _log = open(log_path, "w", encoding="utf-8")
    code = 1
    try:
        code = asyncio.run(amain(args, LOG_DIR / run_name, run_name))
    except Exception as e:  # noqa
        say(f"EXCEPTION {type(e).__name__}: {e}")
        code = 1
    finally:
        say(f"{'OK' if code == 0 else 'FAILED'} exit={code} log={log_path}")
        _log.close()
    sys.exit(code)


if __name__ == "__main__":
    main()
