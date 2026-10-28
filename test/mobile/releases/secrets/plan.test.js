import test from "node:test";
import assert from "node:assert/strict";
import {
  COMMANDS,
  dryRunLines,
  ITEMS,
  VAULT,
  describeSource,
} from "../../../../src/mobile/releases/secrets/plan.js";
import {
  keytoolExport,
  keytoolGenerate,
  PASS_ENV,
} from "../../../../src/mobile/releases/signing/keystore.js";

test("the vault plan names GVM-LED's two durable items", () => {
  assert.equal(VAULT, "GVM-LED");
  assert.deepEqual(COMMANDS, ["generate-key", "verify-key", "release-sign"]);
  assert.equal(ITEMS.keystore.title, "gvm-led-android-release-keystore");
  assert.equal(ITEMS.keystore.file, "gvm-led-release.p12");
  assert.equal(ITEMS.password.ref, "op://GVM-LED/gvm-led-android-release-password/password");
  assert.deepEqual(Object.keys(ITEMS).sort(), ["keystore", "password"]);
  assert.match(describeSource("keystore"), /^document GVM-LED\//);
});

test("keytool passwords are environment references, never literal argv", () => {
  assert.equal(PASS_ENV, "GVM_ANDROID_KEYSTORE_PASSWORD");
  for (const spec of [
    keytoolGenerate({ keytool: "keytool", keystore: "k.p12" }),
    keytoolExport({ keytool: "keytool", keystore: "k.p12" }),
  ]) {
    assert.ok(spec.args.includes(`-storepass:env`));
    assert.equal(spec.args[spec.args.indexOf("-storepass:env") + 1], PASS_ENV);
    assert.ok(!spec.args.includes("-storepass"));
  }
  const generate = keytoolGenerate({ keytool: "keytool", keystore: "k.p12" }).args;
  assert.equal(generate[generate.indexOf("-keysize") + 1], "4096");
  assert.equal(generate[generate.indexOf("-storetype") + 1], "PKCS12");
  assert.equal(generate[generate.indexOf("-alias") + 1], "gvm-led-release");
});

test("dry runs describe every command without values", () => {
  for (const [command, options] of [
    ["generate-key", {}],
    ["verify-key", {}],
    ["release-sign", { tag: "android-v0.1.5", releaseId: "9" }],
  ]) {
    const lines = dryRunLines(command, options);
    assert.ok(lines.length > 1);
    assert.ok(lines.every((line) => line.startsWith("would ")));
  }
  assert.throws(() => dryRunLines("push-github"), /unknown/);
  assert.throws(() => dryRunLines("nope"), /unknown/);
});
