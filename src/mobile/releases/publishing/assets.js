// Shared streaming asset identity for desktop and phone release preparation.
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import path from "node:path";

/** `{name, size, sha256}` of a release asset file, streamed. */
export async function describeAsset(file) {
  const info = await stat(file);
  if (!info.isFile()) throw new Error(`not a file: ${path.basename(file)}`);
  const hash = createHash("sha256");
  let size = 0;
  for await (const chunk of createReadStream(file)) {
    hash.update(chunk);
    size += chunk.length;
  }
  if (size !== info.size) throw new Error(`${path.basename(file)} changed while it was hashed`);
  return { name: path.basename(file), size, sha256: hash.digest("hex") };
}
