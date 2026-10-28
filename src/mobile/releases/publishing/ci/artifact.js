// The retained Actions archive contains exactly the unsigned APK and provenance as
// flat regular files. Refuse aliases, links, unknown entries and inflated sizes.
import path from "node:path";
import { createWriteStream } from "node:fs";
import { open } from "node:fs/promises";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import yauzl from "yauzl";
import { CONTRACT } from "../../release-contract.js";

export async function extractActionsArtifact(archive, directory, provenance) {
  const expected = new Map([
    [provenance.unsignedApk.name, provenance.unsignedApk.size],
    [CONTRACT.assets.provenance, CONTRACT.limits.maxProvenanceBytes],
  ]);
  const zip = await yauzl.openPromise(archive, {
    lazyEntries: true,
    autoClose: false,
    strictFileNames: true,
    validateEntrySizes: true,
  });
  try {
    if (zip.entryCount !== 2) throw new Error("Actions artifact must contain exactly two files");
    for await (const entry of zip.eachEntry()) {
      const limit = expected.get(entry.fileName);
      const mode = (entry.externalFileAttributes >>> 16) & 0o170000;
      if (
        !limit ||
        /[/\\:]/.test(entry.fileName) ||
        entry.isEncrypted() ||
        ![0, 8].includes(entry.compressionMethod) ||
        (mode !== 0 && mode !== 0o100000) ||
        entry.externalFileAttributes & 0x10 ||
        entry.uncompressedSize < 1 ||
        entry.uncompressedSize > limit
      )
        throw new Error("Actions artifact entry refused");
      expected.delete(entry.fileName);
      let size = 0;
      const file = path.join(directory, entry.fileName);
      await pipeline(
        await zip.openReadStreamPromise(entry),
        new Transform({
          transform(chunk, _encoding, done) {
            size += chunk.length;
            done(size > limit ? new Error("Actions artifact entry exceeds its bound") : null, chunk);
          },
        }),
        createWriteStream(file, { flags: "wx" }),
      );
      if (size !== entry.uncompressedSize) throw new Error("Actions artifact entry size mismatch");
      const handle = await open(file, "r+");
      try {
        await handle.sync();
      } finally {
        await handle.close();
      }
    }
    if (expected.size) throw new Error("Actions artifact is incomplete");
  } finally {
    zip.close();
  }
}
