"""Agent Eye camera capture for the visible proof run (the owner's LAN phone camera).

Calls `node <agent-eye>/src/camera/snapshot.mjs shot` (token comes from its own stored pairing; it is
never read, passed or printed here), copies the image into the run folder, then runs `clean`.
A failed shot is logged and returns None; it never aborts the light test.
"""
import json
import os
import shutil
import subprocess
from pathlib import Path

DEFAULT_SNAPSHOT = Path(os.environ.get("AGENT_EYE_SNAPSHOT", r"C:\Users\dirty\projects\agent-eye\src\camera\snapshot.mjs"))


def parse_shot_path(stdout: str) -> str:
    """`shot` prints `log: <path>`, then one JSON line, then `OK (exit 0) ...`; take the line starting with `{`."""
    for line in stdout.splitlines():
        if line.strip().startswith("{"):
            return json.loads(line)["path"]
    raise ValueError("no JSON line in shot output")


class Camera:
    def __init__(self, say, run_dir: Path, run_name: str, snapshot: Path = DEFAULT_SNAPSHOT, dry=False):
        self.say, self.run_dir, self.run_name, self.snapshot, self.dry = say, run_dir, run_name, Path(snapshot), dry

    def target(self, step: int) -> Path:
        return self.run_dir / f"{self.run_name}-step{step:02d}.jpg"

    def _node(self, command: str):
        return subprocess.run(["node", str(self.snapshot), command], cwd=self.snapshot.parents[2],
                              capture_output=True, text=True, timeout=45)

    def _scrub(self, text: str) -> str:
        token = os.environ.get("AGENT_EYE_TOKEN", "")
        return (text.replace(token, "[REDACTED]") if len(token) >= 4 else text).strip()[:300]

    def shoot(self, step: int):
        """Take one frame for `step`; returns the saved image path or None."""
        dest = self.target(step)
        if self.dry:
            self.say(f"CAMERA (dry run) would shoot step {step:02d} -> {dest}")
            return None
        try:
            res = self._node("shot")
            if res.returncode != 0:
                raise RuntimeError(f"shot exit {res.returncode}: {self._scrub(res.stderr or res.stdout)}")
            src = Path(parse_shot_path(res.stdout))
            self.run_dir.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dest)
            self.say(f"CAMERA step {step:02d} image: {dest}")
            return dest
        except Exception as e:  # noqa - the light test must continue
            self.say(f"CAMERA step {step:02d} FAILED ({type(e).__name__}: {self._scrub(str(e))}); continuing")
            return None
        finally:
            try:
                self._node("clean")
            except Exception:
                pass
