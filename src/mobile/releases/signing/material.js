// Temporary signing material: an owner-only directory outside the checkout that
// exists only for one command. Cleanup terminates owned children first, then
// deletes the directory; it runs from `finally` and from catchable signals.
import os from "node:os";
import path from "node:path";
import { mkdtemp, rm, chmod } from "node:fs/promises";

const outside = (dir, root) => {
  const relative = path.relative(path.resolve(root), path.resolve(dir));
  return relative.startsWith("..") || path.isAbsolute(relative);
};

/**
 * Creates the material directory. `root` is the checkout (the directory must lie outside it).
 * Returns {dir, file, unique, cleanup}. Tests inject fs, process and the child terminator.
 */
export async function createMaterial({
  root,
  tmpRoot = os.tmpdir(),
  prefix = "gvm-led-signing-",
  fs = { mkdtemp, rm, chmod },
  signals = process,
  terminate = () => {},
  exit = (code) => process.exit(code),
} = {}) {
  const dir = await fs.mkdtemp(path.join(tmpRoot, prefix));
  let removed = false;
  const cleanup = async () => {
    if (removed) return;
    removed = true;
    for (const [signal, handler] of handlers) signals.removeListener?.(signal, handler);
    terminate();
    await fs.rm(dir, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  };
  const handlers = ["SIGINT", "SIGTERM", "SIGHUP"].map((signal) => {
    const handler = () => {
      cleanup().finally(() => exit(130));
    };
    signals.once?.(signal, handler);
    return [signal, handler];
  });
  try {
    if (root && !outside(dir, root))
      throw new Error("temporary signing material must live outside the checkout");
    await fs.chmod(dir, 0o700);
  } catch (error) {
    await cleanup();
    throw error;
  }
  let scratch = 0;
  return {
    dir,
    file: (name) => path.join(dir, name),
    /** A fresh path per call (`<n>-<name>`), for files a run writes more than once. */
    unique: (name) => path.join(dir, `${++scratch}-${name}`),
    cleanup,
  };
}
