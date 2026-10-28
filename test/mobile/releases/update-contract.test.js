// The shared phone-manifest fixtures the Android updater is also tested against
// (src/mobile/android/.../update/PhoneReleaseManifestTest.kt). Both validators must agree on
// every case, so a contract change that breaks the app's update check fails here too.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { validatePhoneManifest } from "../../../src/mobile/releases/manifest.js";
import { FIXTURE_SIGNER } from "./helpers/manifest-fixture.js";

const fixtures = JSON.parse(readFileSync(new URL("./fixtures/update-contract.json", import.meta.url), "utf8"));
const options = { certificateSha256: fixtures.certificateSha256 };

test("the shared fixtures use the test signer, never the committed pin", () => {
  assert.equal(fixtures.certificateSha256, FIXTURE_SIGNER);
  assert.ok(fixtures.valid.length > 0 && fixtures.invalid.length > 0);
});

for (const { name, manifest } of fixtures.valid) {
  test(`accepted by the release validator: ${name}`, () => {
    assert.deepEqual(validatePhoneManifest(manifest, options), manifest);
  });
}

for (const { name, manifest } of fixtures.invalid) {
  test(`rejected by the release validator: ${name}`, () => {
    assert.throws(() => validatePhoneManifest(manifest, options));
  });
}
