import test from "node:test";
import assert from "node:assert/strict";
import {
  buildTools,
  parseBadging,
  parseSigners,
  requireSingleSigner,
  requireUnsignedBuild,
} from "../../../../src/mobile/releases/signing/apk-inspection.js";

const BADGING = `package: name='com.dirtyredz.gvmled' versionCode='42' versionName='0.1.41' platformBuildVersionName='15' compileSdkVersion='35'
sdkVersion:'26'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
application-label:'Command Deck'
`;
const HEAD = BADGING.split("\n")[0];
const PIN = "a".repeat(64);
const SIGNED = `Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
Signer #1 certificate DN: CN=Command Deck
Signer #1 certificate SHA-256 digest: ${PIN}
`;

test("parseBadging reads aapt2 package, versions and minSdk", () => {
  assert.deepEqual(parseBadging(BADGING), {
    packageName: "com.dirtyredz.gvmled",
    versionCode: 42,
    versionName: "0.1.41",
    minSdk: 26,
    debuggable: false,
  });
});

test("parseBadging accepts legacy sdkVersion and lets minSdkVersion win", () => {
  assert.equal(parseBadging(`${HEAD}\nsdkVersion:'26'\n`).minSdk, 26);
  assert.equal(parseBadging(`${HEAD}\nsdkVersion:'24'\nminSdkVersion:'30'\n`).minSdk, 30);
  assert.equal(parseBadging(`${HEAD}\nminSdkVersion:'31'\nsdkVersion:'24'\n`).minSdk, 31);
});

test("parseBadging reports debuggable and throws on missing lines", () => {
  assert.equal(parseBadging(`${BADGING}application-debuggable\n`).debuggable, true);
  assert.throws(() => parseBadging("sdkVersion:'26'\n"), /no package line/);
  assert.throws(() => parseBadging(HEAD), /no minSdkVersion or sdkVersion/);
  assert.throws(() => parseBadging(""), /no package line/);
});

test("parseSigners counts signers, digests and schemes", () => {
  assert.deepEqual(parseSigners(SIGNED), { signers: 1, sha256: [PIN], schemes: ["v2", "v3"] });
  const two = `Number of signers: 2\nSigner #1 certificate SHA-256 digest: ${PIN}\nSigner #2 certificate SHA-256 digest: ${"b".repeat(64)}\n`;
  assert.deepEqual(parseSigners(two), { signers: 2, sha256: [PIN, "b".repeat(64)], schemes: [] });
  assert.deepEqual(parseSigners("DOES NOT VERIFY\n"), { signers: 0, sha256: [], schemes: [] });
  assert.deepEqual(
    parseSigners("Verified using v3.1 scheme (APK Signature Scheme v3.1): true\n").schemes,
    ["v3.1"],
  );
});

const expected = {
  packageName: "com.dirtyredz.gvmled",
  versionName: "0.1.41",
  versionCode: 42,
};

test("requireUnsignedBuild accepts the allocated release build and refuses drift", () => {
  const ok = parseBadging(BADGING);
  assert.equal(requireUnsignedBuild(ok, expected), ok);
  for (const patch of [
    { packageName: "com.dirtyredz.gvmled.debug" },
    { versionName: "0.1.42" },
    { versionCode: 43 },
    { debuggable: true },
    { minSdk: 25 },
  ])
    assert.throws(() => requireUnsignedBuild({ ...ok, ...patch }, expected), /unsigned APK/);
});

test("requireSingleSigner demands one pinned signer with v2+ signing", () => {
  const ok = parseSigners(SIGNED);
  assert.equal(requireSingleSigner(ok, PIN), ok);
  assert.doesNotThrow(() => requireSingleSigner({ ...ok, schemes: ["v3.1"] }, PIN));
  for (const bad of [
    { ...ok, signers: 2, sha256: [PIN, PIN] },
    { ...ok, sha256: ["b".repeat(64)] },
    { ...ok, sha256: [] },
    { ...ok, schemes: ["v1"] },
    { ...ok, schemes: [] },
    { signers: 0, sha256: [], schemes: [] },
  ])
    assert.throws(() => requireSingleSigner(bad, PIN), /exactly one signer/);
});

test("buildTools names the executables per platform", () => {
  const win = buildTools("C:\\bt", { platform: "win32", java: "j" });
  assert.match(win.zipalign, /zipalign\.exe$/);
  assert.match(win.aapt2, /aapt2\.exe$/);
  assert.equal(win.java, "j");
  assert.match(win.apksignerJar, /apksigner\.jar$/);
  assert.match(buildTools("/bt", { platform: "linux" }).zipalign, /zipalign$/);
});
