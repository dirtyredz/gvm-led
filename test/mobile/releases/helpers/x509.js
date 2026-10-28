// Disposable self-signed test certificates built in memory (DER via a tiny encoder,
// signature via node:crypto). Test-only identities: never written to disk, never a
// release key, and created without spawning keytool.
import { generateKeyPairSync, sign } from "node:crypto";

const length = (n) => {
  if (n < 0x80) return Buffer.from([n]);
  const bytes = [];
  for (let v = n; v > 0; v >>= 8) bytes.unshift(v & 0xff);
  return Buffer.from([0x80 | bytes.length, ...bytes]);
};
const tlv = (tag, body) => Buffer.concat([Buffer.from([tag]), length(body.length), body]);
const seq = (...items) => tlv(0x30, Buffer.concat(items));
const int = (n) => tlv(0x02, Buffer.from([n]));
const oid = (text) => {
  const [a, b, ...rest] = text.split(".").map(Number);
  const out = [40 * a + b];
  for (const arc of rest) {
    const part = [arc & 0x7f];
    for (let v = arc >> 7; v > 0; v >>= 7) part.unshift(0x80 | (v & 0x7f));
    out.push(...part);
  }
  return tlv(0x06, Buffer.from(out));
};
const time = (date) =>
  tlv(0x18, Buffer.from(date.toISOString().replace(/[-:T]/g, "").slice(0, 14) + "Z"));
const name = (cn) => seq(tlv(0x31, seq(oid("2.5.4.3"), tlv(0x0c, Buffer.from(cn)))));

const cache = new Map();
/** PEM of a self-signed RSA certificate (memoised per options: 4096-bit keygen is slow). */
export function testCertificate(options = {}) {
  const key = JSON.stringify(options);
  if (!cache.has(key)) cache.set(key, buildCertificate(options));
  return cache.get(key);
}

function buildCertificate({ bits = 2048, years = 30, cn = "GVM LED TEST ONLY" } = {}) {
  const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: bits });
  const algorithm = seq(oid("1.2.840.113549.1.1.11"), Buffer.from([0x05, 0x00]));
  const now = new Date();
  const until = new Date(now.getTime() + years * 365.25 * 24 * 3600 * 1000);
  const tbs = seq(
    tlv(0xa0, int(2)),
    int(1),
    algorithm,
    name(cn),
    seq(time(now), time(until)),
    name(cn),
    publicKey.export({ type: "spki", format: "der" }),
  );
  const signature = sign("sha256", tbs, privateKey);
  const der = seq(tbs, algorithm, tlv(0x03, Buffer.concat([Buffer.from([0]), signature])));
  const body = der
    .toString("base64")
    .match(/.{1,64}/g)
    .join("\n");
  return `-----BEGIN CERTIFICATE-----\n${body}\n-----END CERTIFICATE-----\n`;
}
