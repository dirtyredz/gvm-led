import test from "node:test";
import assert from "node:assert/strict";
import {
  inspectCertificate,
  recordCertificate,
  requireReleaseKey,
  requireSingleEntry,
} from "../../../../src/mobile/releases/signing/certificate.js";
import { testCertificate } from "../helpers/x509.js";

test("inspection reads only public identity from exactly one certificate", () => {
  const pem = testCertificate({ bits: 2048, years: 30 });
  const info = inspectCertificate(`keytool banner\n${pem}`);
  assert.match(info.sha256, /^[a-f0-9]{64}$/);
  assert.equal(info.keyType, "rsa");
  assert.equal(info.keyBits, 2048);
  assert.throws(() => inspectCertificate(pem + pem), /exactly one/);
  assert.throws(() => inspectCertificate("no certificate"), /exactly one/);
  assert.throws(
    () => inspectCertificate("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"),
    /parsed/,
  );
});

test("the release key must be RSA-4096 with about 30 years of validity", () => {
  const short = inspectCertificate(testCertificate({ bits: 2048, years: 30 }));
  assert.throws(() => requireReleaseKey(short), /RSA-4096/);
  assert.throws(
    () => requireReleaseKey({ keyType: "rsa", keyBits: 4096, validYears: 2 }),
    /25\+ years/,
  );
  assert.ok(requireReleaseKey({ keyType: "rsa", keyBits: 4096, validYears: 29.9 }));
});

test("the keystore must hold exactly one release-alias private key", () => {
  const one =
    "Your keystore contains 1 entry\n\ngvm-led-release, Oct 18, 2026, PrivateKeyEntry, \n";
  requireSingleEntry(one);
  assert.throws(() => requireSingleEntry(one.replace("1 entry", "2 entries")));
  assert.throws(() => requireSingleEntry(one.replace("gvm-led-release", "other")));
});

test("recording refuses to replace an existing pin", async () => {
  const files = new Map([["c.json", JSON.stringify({ certificateSha256: null, notAfter: null })]]);
  const io = {
    read: async (f) => files.get(f),
    write: async (f, v) => files.set(f, v),
  };
  const info = { sha256: "a".repeat(64), notAfter: "2056-01-01T00:00:00.000Z" };
  await recordCertificate("c.json", info, io);
  assert.equal(JSON.parse(files.get("c.json")).certificateSha256, "a".repeat(64));
  await assert.rejects(recordCertificate("c.json", info, io), /never replace/);
});
