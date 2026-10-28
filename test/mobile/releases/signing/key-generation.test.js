import test from "node:test";
import assert from "node:assert/strict";
import { generateKey, verifyKey } from "../../../../src/mobile/releases/signing/key-generation.js";
import { PASS_ENV } from "../../../../src/mobile/releases/signing/keystore.js";
import { fakeKeytool, fakeVault, memoryFs, memoryMaterial, RECORD } from "../helpers/fakes.js";
import { inspectCertificate } from "../../../../src/mobile/releases/signing/certificate.js";

const CERT = "/repo/signing-certificate.json";
const fixedRandom = () => Buffer.alloc(32, 7);

function setup(overrides = {}) {
  const vault = overrides.vault ?? fakeVault();
  const keytool = overrides.keytool ?? fakeKeytool();
  const fs = memoryFs({ [CERT]: overrides.record ?? RECORD });
  const material = memoryMaterial();
  const said = [];
  const deps = {
    vault,
    run: keytool.run,
    fs,
    material: material.factory,
    certificateFile: CERT,
    keytool: "keytool",
    say: (line) => said.push(line),
    random: fixedRandom,
  };
  return { deps, vault, keytool, fs, material, said };
}

test("generation stores, reads back by ID, verifies and records only public identity", async () => {
  const { deps, vault, keytool, fs, material, said } = setup();
  const identity = await generateKey(deps);
  const expected = inspectCertificate(keytool.pem);
  assert.equal(identity.certificateSha256, expected.sha256);
  assert.equal(identity.key, "RSA-4096");
  const record = JSON.parse(fs.map.get(CERT));
  assert.equal(record.certificateSha256, expected.sha256);
  assert.equal(record.notAfter, expected.notAfter);
  assert.deepEqual(
    vault.calls.map((c) => c[0]),
    ["findItems", "createPassword", "createDocument", "readPasswordById", "readDocumentById"],
  );
  assert.equal(material.state.cleaned, 1);
  const password = fixedRandom().toString("base64url");
  for (const spec of keytool.specs) {
    assert.equal(spec.env[PASS_ENV], password);
    assert.ok(!spec.args.some((arg) => arg.includes(password)), "password never in argv");
  }
  assert.ok(!said.join("\n").includes(password));
  assert.ok(!JSON.stringify(identity).includes(password));
});

test("generation refuses an existing pin or existing destination items before creating anything", async () => {
  const pinned = setup({
    record: JSON.stringify({ ...JSON.parse(RECORD), certificateSha256: "a".repeat(64) }),
  });
  await assert.rejects(generateKey(pinned.deps), /already pinned/);
  assert.equal(pinned.keytool.specs.length, 0);

  const duplicate = setup({
    vault: fakeVault({ items: [{ id: "old1", title: "gvm-led-android-release-password" }] }),
  });
  await assert.rejects(
    generateKey(duplicate.deps),
    /already holds gvm-led-android-release-password \(old1\)/,
  );
  assert.equal(duplicate.keytool.specs.length, 0);
  assert.equal(duplicate.material.state.cleaned, 0);
});

test("partial provisioning reports public item IDs, never deletes and leaves the pin unset", async () => {
  for (const [options, stage] of [
    [{ failCreate: "document" }, /vault storage.*gvm-led-android-release-password \(item1\)/],
    [{ tamper: "document" }, /read-back verification/],
    [{ tamper: "password" }, /read-back verification/],
  ]) {
    const { deps, vault, fs, material } = setup({ vault: fakeVault(options) });
    await assert.rejects(generateKey(deps), (error) => {
      assert.equal(error.code, "PARTIAL_PROVISIONING");
      assert.match(error.message, stage);
      return true;
    });
    assert.equal(JSON.parse(fs.map.get(CERT)).certificateSha256, null);
    assert.ok(vault.store.size >= 1, "created items are kept for explicit recovery");
    assert.equal(material.state.cleaned, 1);
  }
  const { deps } = setup({ vault: fakeVault({ failCreate: "password" }) });
  await assert.rejects(generateKey(deps), /nothing was created/);
});

test("generation refuses a wrong key shape and cleans up", async () => {
  const { testCertificate } = await import("../helpers/x509.js");
  for (const keytool of [
    fakeKeytool({ pem: testCertificate({ bits: 2048 }) }),
    fakeKeytool({ entries: 2 }),
    fakeKeytool({ failOn: "-genkeypair" }),
  ]) {
    const { deps, vault, material } = setup({ keytool });
    await assert.rejects(generateKey(deps));
    assert.ok(!vault.calls.some((c) => c[0].startsWith("create")));
    assert.equal(material.state.cleaned, 1);
  }
});

test("recovery verification compares the vault copy with the pin and refuses padded passwords", async () => {
  const keytool = fakeKeytool();
  const sha = inspectCertificate(keytool.pem).sha256;
  const record = JSON.stringify({ ...JSON.parse(RECORD), certificateSha256: sha });
  const items = [{ id: "k", title: "gvm-led-android-release-keystore", key: "keystore", bytes: "ks" }];
  const ok = setup({
    keytool,
    record,
    vault: fakeVault({ items, fields: { password: "pw-123456" } }),
  });
  assert.equal((await verifyKey(ok.deps)).certificateSha256, sha);
  assert.equal(ok.material.state.cleaned, 1);

  const padded = setup({
    keytool,
    record,
    vault: fakeVault({ items, fields: { password: " pw-123456" } }),
  });
  await assert.rejects(verifyKey(padded.deps), /padded/);

  const other = setup({
    record: JSON.stringify({ ...JSON.parse(RECORD), certificateSha256: "f".repeat(64) }),
    vault: fakeVault({ items, fields: { password: "pw-123456" } }),
  });
  await assert.rejects(verifyKey(other.deps), /does not match/);
});
