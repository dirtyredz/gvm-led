// Repository-scoped local publication lock: one signing job at a time on this machine.
// A leftover lock (a crashed run) is reported, never broken automatically.
import os from "node:os";
import path from "node:path";
import { open, rm } from "node:fs/promises";
import { CONTRACT } from "../release-contract.js";

export function publicationLock({ dir = os.tmpdir(), fs = { open, rm } } = {}) {
  const file = path.join(dir, `${CONTRACT.repository.replace("/", "-")}-android-publish.lock`);
  let held = false;
  return {
    file,
    async acquire() {
      try {
        const handle = await fs.open(file, "wx");
        await handle.writeFile(JSON.stringify({ pid: process.pid, at: new Date().toISOString() }));
        await handle.close();
        held = true;
      } catch (error) {
        if (error?.code === "EEXIST")
          throw new Error(
            `another Android publication holds ${file}; if none is running, delete it by hand`,
          );
        throw error;
      }
    },
    async release() {
      if (held) await fs.rm(file, { force: true });
      held = false;
    },
  };
}
