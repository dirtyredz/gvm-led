// A disposable temp directory per test. `defer(close, label)` registers a disposer the
// moment a resource is acquired; at teardown disposers run in reverse order (all attempted),
// then the directory is removed.
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

export async function workspace(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), "gvm-led-test-"));
  const disposers = [];
  t.after(async () => {
    const errors = [];
    for (const { close } of disposers.reverse()) {
      try {
        await close();
      } catch (error) {
        errors.push(error);
      }
    }
    await rm(dir, { recursive: true, force: true, maxRetries: 3 });
    if (errors.length) throw errors[0];
  });
  return { dir, defer: (close, label) => disposers.push({ close, label }) };
}
