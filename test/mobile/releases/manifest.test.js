import test from "node:test";
import assert from "node:assert/strict";
import {
  androidTag,
  androidTagVersion,
  trustedCertificate,
  PHONE_IDENTITY,
} from "../../../src/mobile/releases/release-contract.js";
import {
  phoneVersion,
  validatePhoneManifest,
} from "../../../src/mobile/releases/manifest.js";
import { fixtureManifest, FIXTURE_SIGNER } from "./helpers/manifest-fixture.js";

const options = { certificateSha256: FIXTURE_SIGNER };

test("phone versions preserve reruns and reject regression, bootstrap and Android overflow", () => {
  assert.deepEqual(phoneVersion(10), phoneVersion(10));
  assert.deepEqual(phoneVersion(1), { versionName: "0.1.1", versionCode: 2 });
  assert.deepEqual(phoneVersion(60000), { versionName: "0.2.0", versionCode: 60001 });
  for (const value of [0, -1, 1.2, 2100000000, NaN]) assert.throws(() => phoneVersion(value));
  assert.throws(() => phoneVersion(10, 11));
  assert.throws(() => phoneVersion(10, 0));
  assert.equal(phoneVersion(11, 11).versionCode, 12);
});

test("Android tags are distinct from other release tags", () => {
  assert.equal(androidTag("0.1.42"), "android-v0.1.42");
  assert.equal(androidTagVersion("android-v0.1.42"), "0.1.42");
  for (const tag of ["v0.1.42", "android-v0.1", "android-v01.1.1", "android-v0.1.42-rc1", 7])
    assert.equal(androidTagVersion(tag), null);
});

test("the permanent identity has no trusted signer until a verified key is recorded", () => {
  assert.equal(PHONE_IDENTITY.packageName, "com.dirtyredz.gvmled");
  if (PHONE_IDENTITY.certificateSha256 === null) {
    assert.equal(trustedCertificate(), null);
    // With no pin, even a manifest naming any signer is refused: a manifest never selects its signer.
    assert.throws(() => validatePhoneManifest(fixtureManifest()));
  } else {
    assert.match(trustedCertificate(), /^[a-f0-9]{64}$/);
  }
});

test("phone manifests pin package, certificate, version, repository and bounded exact asset", () => {
  assert.deepEqual(validatePhoneManifest(fixtureManifest(), options), fixtureManifest());
  assert.throws(() => validatePhoneManifest(fixtureManifest(), { certificateSha256: "bad" }));
  assert.throws(() => validatePhoneManifest(fixtureManifest(), { certificateSha256: null }));
});
