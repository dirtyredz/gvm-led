import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { signApk, inspectSigned } from "../../../../src/mobile/releases/signing/sign.js";
import { inspectCertificate } from "../../../../src/mobile/releases/signing/certificate.js";
import { PASS_ENV } from "../../../../src/mobile/releases/signing/keystore.js";
import { workspace } from "../helpers/workspace.js";
import { testCertificate } from "../helpers/x509.js";
import { TOOLS, androidRun, badgingText, signerText, stepOf } from "../helpers/android-fakes.js";

const pem = testCertificate();
const pin = inspectCertificate(pem).sha256;
const password = "pw-123456";
const expected = {
  packageName: "com.dirtyredz.gvmled",
  versionName: "0.1.42",
  versionCode: 43,
  minSdk: 26,
};

async function setup(t, options = {}) {
  const { dir } = await workspace(t);
  const fake = androidRun({ pin, pem, ...options });
  const args = {
    run: fake.run,
    tools: TOOLS,
    keytool: "keytool",
    keystore: path.join(dir, "release.p12"),
    password,
    pin,
    unsigned: path.join(dir, "GVM-LED-0.1.42-android-unsigned.apk"),
    aligned: path.join(dir, "aligned.apk"),
    output: path.join(dir, "GVM-LED-0.1.42-android.apk"),
    expected,
  };
  return { fake, args, steps: () => fake.specs.map(stepOf) };
}

test("signApk checks the keystore pin first, then aligns, signs and inspects", async (t) => {
  const { fake, args, steps } = await setup(t);
  const badging = await signApk(args);
  assert.equal(badging.versionCode, 43);
  assert.deepEqual(steps(), ["keytool", "zipalign", "sign", "zipalign-check", "verify", "badging"]);
  assert.equal(fake.specs[0].args.includes("-exportcert"), true);
});

test("the password travels only in the child environment, via env: references", async (t) => {
  const { fake, args } = await setup(t);
  await signApk(args);
  const sign = fake.specs.find((s) => stepOf(s) === "sign");
  assert.deepEqual(sign.env, { [PASS_ENV]: password });
  const at = (flag) => sign.args[sign.args.indexOf(flag) + 1];
  assert.equal(at("--ks-pass"), `env:${PASS_ENV}`);
  assert.equal(at("--key-pass"), `env:${PASS_ENV}`);
  assert.deepEqual(sign.secrets, [password]);
  for (const spec of fake.specs) assert.equal(JSON.stringify(spec.args).includes(password), false);
  const keytool = fake.specs[0];
  assert.deepEqual(keytool.env, { [PASS_ENV]: password });
});

test("a keystore whose certificate is not the pin is refused before anything is signed", async (t) => {
  const { fake, args, steps } = await setup(t);
  await assert.rejects(
    signApk({ ...args, pin: "b".repeat(64) }),
    /not the pinned release certificate/,
  );
  assert.deepEqual(steps(), ["keytool"]);
  assert.equal(
    fake.specs.some((s) => stepOf(s) === "sign"),
    false,
  );
});

test("a failing apksigner sign surfaces a sanitized error without the password", async (t) => {
  const { args } = await setup(t, { failSign: true });
  await assert.rejects(signApk(args), (error) => {
    assert.match(error.message, /apksigner sign failed with exit code 1/);
    assert.equal(error.message.includes(password), false);
    return true;
  });
});

test("inspectSigned refuses wrong signer count, digest, scheme, alignment and identity", async (t) => {
  const apk = path.join((await workspace(t)).dir, "GVM-LED-0.1.42-android.apk");
  const inspect = (options, wanted = expected) => {
    const { run } = androidRun({ pin, pem, ...options });
    return inspectSigned({ run, tools: TOOLS, apk, pin, expected: wanted });
  };
  assert.equal((await inspect({})).minSdk, 26);
  await assert.rejects(
    inspect({ signed: signerText(pin, { signers: 2, digests: [pin, pin] }) }),
    /exactly one signer/,
  );
  await assert.rejects(inspect({ signed: signerText("c".repeat(64)) }), /exactly one signer/);
  await assert.rejects(
    inspect({ signed: signerText(pin, { scheme: "v1" }) }),
    /exactly one signer/,
  );
  await assert.rejects(inspect({ alignOk: false }), /zipalign -c failed/);
  await assert.rejects(inspect({ signedBadging: badgingText({ code: 44 }) }), /identity differs/);
  await assert.rejects(
    inspect({ signedBadging: badgingText({ name: "0.1.43" }) }),
    /identity differs/,
  );
  await assert.rejects(
    inspect({ signedBadging: badgingText({ pkg: "com.dirtyredz.gvmled.debug" }) }),
    /identity differs/,
  );
  await assert.rejects(
    inspect({ signedBadging: `${badgingText()}application-debuggable\n` }),
    /identity differs/,
  );
  await assert.rejects(
    inspect({ signedBadging: badgingText().replace("'26'", "'27'") }),
    /identity differs/,
  );
  // An undefined expected minSdk accepts any minSdk >= 26.
  assert.equal(
    (
      await inspect(
        { signedBadging: badgingText().replace("'26'", "'30'") },
        { ...expected, minSdk: undefined },
      )
    ).minSdk,
    30,
  );
});
