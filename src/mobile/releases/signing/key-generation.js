// Dedicated Android release key generation and recovery verification. Every side
// effect arrives through `deps` (vault, run, material, certificate record) so the
// unit tests drive it with fakes; the real SDK and keytool run only when the owner
// runs scripts/owner/new-signing-key.ps1 (secrets.js generate-key).
//
// Never overwrites an existing key, never silently generates a replacement and
// never deletes vault items: a partial provisioning reports public item IDs and
// requires explicit owner recovery.
import { createHash, randomBytes } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { ITEMS, VAULT } from "../secrets/plan.js";
import { mustRun } from "../process/exec.js";
import {
  KEYSTORE_FILE,
  PASS_ENV,
  keytoolExport,
  keytoolGenerate,
  keytoolList,
} from "./keystore.js";
import {
  inspectCertificate,
  recordCertificate,
  requireReleaseKey,
  requireSingleEntry,
} from "./certificate.js";

export class PartialProvisioning extends Error {
  constructor(stage, created) {
    super(
      `key provisioning stopped at ${stage}; created items need explicit recovery: ${
        created.map((c) => `${c.title} (${c.id})`).join(", ") || "none"
      }`,
    );
    this.code = "PARTIAL_PROVISIONING";
    this.created = created;
  }
}

const sha256 = (value) => createHash("sha256").update(value).digest("hex");

/** A random keystore password: 32 bytes, URL-safe, no whitespace to trim. */
export const newPassword = (random = randomBytes) => random(32).toString("base64url");

/** Refuses when a pin exists or any destination item already exists (duplicates or leftovers). */
export async function refuseExisting({ vault, certificateFile, read = readFile }) {
  const record = JSON.parse(await read(certificateFile, "utf8"));
  if (record.certificateSha256 !== null)
    throw new Error(
      "an approved permanent key is already pinned; generate-key refuses to replace it",
    );
  const existing = await vault.findItems([ITEMS.keystore.title, ITEMS.password.title]);
  if (existing.length)
    throw new Error(
      `${VAULT} already holds ${existing.map((i) => `${i.title} (${i.id})`).join(", ")}; resolve this ambiguous state by hand first`,
    );
}

async function certificateOf({ run, keytool, keystore, password }) {
  const env = { [PASS_ENV]: password };
  const secrets = [password];
  const list = await mustRun(
    { ...keytoolList({ keytool, keystore }), env, secrets },
    "keytool -list",
    run,
  );
  requireSingleEntry(list.stdout);
  const exported = await mustRun(
    { ...keytoolExport({ keytool, keystore }), env, secrets },
    "keytool -exportcert",
    run,
  );
  return requireReleaseKey(inspectCertificate(exported.stdout));
}

/**
 * deps: {vault, run, material(), keytool, certificateFile, say, fs: {readFile, writeFile}, random}
 * Returns the public identity written to the certificate record.
 */
export async function generateKey(deps) {
  const fs = deps.fs ?? { readFile, writeFile };
  await refuseExisting({
    vault: deps.vault,
    certificateFile: deps.certificateFile,
    read: fs.readFile,
  });
  const material = await deps.material();
  try {
    const keystore = material.file(KEYSTORE_FILE);
    const password = newPassword(deps.random);
    await mustRun(
      {
        ...keytoolGenerate({ keytool: deps.keytool, keystore }),
        env: { [PASS_ENV]: password },
        secrets: [password],
      },
      "keytool -genkeypair",
      deps.run,
    );
    const bytes = await fs.readFile(keystore);
    const generated = await certificateOf({
      run: deps.run,
      keytool: deps.keytool,
      keystore,
      password,
    });
    deps.say(`generated RSA-4096 key; storing it in ${VAULT}`);

    const created = [];
    try {
      created.push({
        title: ITEMS.password.title,
        id: await deps.vault.createPassword(ITEMS.password.title, password),
      });
      created.push({
        title: ITEMS.keystore.title,
        id: await deps.vault.createDocument(ITEMS.keystore.title, KEYSTORE_FILE, bytes),
      });
    } catch {
      if (created.length) throw new PartialProvisioning("vault storage", created);
      throw new Error("1Password storage failed; nothing was created");
    }

    let readBack;
    try {
      const storedPassword = await deps.vault.readPasswordById(created[0].id);
      const storedBytes = await deps.vault.readDocumentById(created[1].id);
      if (sha256(storedPassword) !== sha256(password) || sha256(storedBytes) !== sha256(bytes))
        throw new Error("mismatch");
      // Independent check: the recovered copy alone must open and yield the same certificate.
      const recovered = material.file(`recovered-${KEYSTORE_FILE}`);
      await fs.writeFile(recovered, storedBytes);
      readBack = await certificateOf({
        run: deps.run,
        keytool: deps.keytool,
        keystore: recovered,
        password: storedPassword,
      });
    } catch {
      throw new PartialProvisioning("read-back verification", created);
    }
    if (readBack.sha256 !== generated.sha256)
      throw new PartialProvisioning("certificate comparison", created);

    await recordCertificate(deps.certificateFile, readBack, {
      read: fs.readFile,
      write: fs.writeFile,
    });
    deps.say(
      `stored ${created.map((c) => `${c.title} (${c.id})`).join(", ")}; read-back SHA-256 matched`,
    );
    return publicIdentity(readBack);
  } finally {
    await material.cleanup();
  }
}

/** Recovery verification: the vault copy alone reproduces the pinned certificate. */
export async function verifyKey(deps) {
  const fs = deps.fs ?? { readFile, writeFile };
  const record = JSON.parse(await fs.readFile(deps.certificateFile, "utf8"));
  if (!record.certificateSha256)
    throw new Error("no certificate is pinned yet; run generate-key first");
  const password = await deps.vault.readField("password");
  if (!password || password.trim() !== password)
    throw new Error("stored keystore password is empty or padded");
  const bytes = await deps.vault.readDocument("keystore");
  const material = await deps.material();
  try {
    const keystore = material.file(KEYSTORE_FILE);
    await fs.writeFile(keystore, bytes);
    const info = await certificateOf({ run: deps.run, keytool: deps.keytool, keystore, password });
    if (info.sha256 !== record.certificateSha256)
      throw new Error(`the ${VAULT} keystore does not match the pinned certificate`);
    return publicIdentity(info);
  } finally {
    await material.cleanup();
  }
}

export const publicIdentity = (info) => ({
  certificateSha256: info.sha256,
  subject: info.subject,
  notBefore: info.notBefore,
  notAfter: info.notAfter,
  key: `${info.keyType.toUpperCase()}-${info.keyBits}`,
});
