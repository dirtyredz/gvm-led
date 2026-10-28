import test from "node:test";
import assert from "node:assert/strict";
import { CONTRACT, PHONE_IDENTITY } from "../../../src/mobile/releases/release-contract.js";

test("the contract fixes the permanent identity, tags and assets", () => {
  assert.equal(CONTRACT.repository, "dirtyredz/gvm-led");
  assert.equal(CONTRACT.branch, "main");
  assert.equal(CONTRACT.packageName, "com.dirtyredz.gvmled");
  assert.notEqual(CONTRACT.packageName, CONTRACT.developmentPackageName);
  assert.equal(CONTRACT.tags.android, "android-v");
  assert.equal(CONTRACT.assets.manifest, "phone-release.json");
  assert.equal(CONTRACT.assets.apkPrefix, "GVM-LED-");
  assert.notEqual(CONTRACT.assets.apkSuffix, CONTRACT.assets.unsignedApkSuffix);
  assert.ok(Object.isFrozen(CONTRACT.limits));
  assert.equal(PHONE_IDENTITY.packageName, CONTRACT.packageName);
  assert.equal(PHONE_IDENTITY.alias, "gvm-led-release");
});

test("no operative field of the contract or signer record names Command Deck", () => {
  const text = JSON.stringify({ CONTRACT, PHONE_IDENTITY }, (key, value) =>
    key === "$comment" ? undefined : value,
  );
  assert.doesNotMatch(text, /command[-_ ]?deck|commanddeck/i);
});
