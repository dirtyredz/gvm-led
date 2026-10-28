// A schema-1 phone manifest for the GVM LED contract and a fixed fake signer pin.
// Tests inject the pin; the committed signing-certificate.json is never relied on.
import { versionForSequence } from "../../../../src/mobile/releases/version.js";
import { apkName } from "../../../../src/mobile/releases/release-contract.js";

export const FIXTURE_SIGNER = "5".repeat(64);

export function fixtureManifest(sequence = 41, signer = FIXTURE_SIGNER) {
  const versionName = versionForSequence(sequence);
  return {
    schema: 1,
    repository: "dirtyredz/gvm-led",
    channel: "stable",
    packageName: "com.dirtyredz.gvmled",
    versionName,
    versionCode: sequence + 1,
    minSdk: 26,
    commit: "a".repeat(40),
    certificateSha256: signer,
    apk: { name: apkName(versionName), size: 1048576, sha256: "b".repeat(64) },
  };
}
