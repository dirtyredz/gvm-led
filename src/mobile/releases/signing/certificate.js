// Public certificate inspection, independent of the tool that produced it: Node's
// X509 parser reads the PEM keytool exported. Only public identity metadata leaves
// this module: fingerprint, subject, validity and key size.
import { X509Certificate } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { KEY_ALIAS, KEY_SIZE } from "./keystore.js";

const PEM = /-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g;
const MIN_VALIDITY_YEARS = 25;
const YEAR_MS = 365.25 * 24 * 3600 * 1000;

/** Exactly one certificate from keytool -exportcert -rfc output. */
export function inspectCertificate(pemText, { now = Date.now() } = {}) {
  const blocks = String(pemText).match(PEM) ?? [];
  if (blocks.length !== 1) throw new Error("expected exactly one certificate");
  let cert;
  try {
    cert = new X509Certificate(blocks[0]);
  } catch {
    throw new Error("certificate could not be parsed");
  }
  const key = cert.publicKey;
  const notAfter = new Date(cert.validTo);
  return {
    sha256: cert.fingerprint256.replaceAll(":", "").toLowerCase(),
    subject: cert.subject,
    notBefore: new Date(cert.validFrom).toISOString(),
    notAfter: notAfter.toISOString(),
    keyType: key.asymmetricKeyType,
    keyBits: key.asymmetricKeyDetails?.modulusLength ?? 0,
    validYears: (notAfter.getTime() - now) / YEAR_MS,
  };
}

/** The dedicated release key: RSA-4096 with about 30 years left. */
export function requireReleaseKey(info) {
  if (info.keyType !== "rsa" || info.keyBits !== KEY_SIZE)
    throw new Error("release certificate must be RSA-4096");
  if (info.validYears < MIN_VALIDITY_YEARS)
    throw new Error(`release certificate must remain valid for ${MIN_VALIDITY_YEARS}+ years`);
  return info;
}

/** keytool -list output must name exactly one private key entry with the release alias. */
export function requireSingleEntry(listText) {
  const count = /contains (\d+) entr/.exec(listText);
  // `<alias>, <date with commas>, <EntryType>,` — one line per entry of any type.
  const entries =
    String(listText).match(
      /^[^,\n]+, .*, (?:PrivateKeyEntry|trustedCertEntry|SecretKeyEntry),/gm,
    ) ?? [];
  if (
    count?.[1] !== "1" ||
    entries.length !== 1 ||
    !entries[0].startsWith(`${KEY_ALIAS},`) ||
    !entries[0].includes("PrivateKeyEntry")
  )
    throw new Error(`keystore must hold exactly one ${KEY_ALIAS} private key entry`);
}

/** Records the verified public identity. Refuses to replace an existing pin. */
export async function recordCertificate(file, info, { read = readFile, write = writeFile } = {}) {
  const record = JSON.parse(await read(file, "utf8"));
  if (record.certificateSha256 !== null)
    throw new Error("signing-certificate.json already pins a certificate; never replace it");
  const next = { ...record, certificateSha256: info.sha256, notAfter: info.notAfter };
  await write(file, JSON.stringify(next, null, 2) + "\n");
  return next;
}
