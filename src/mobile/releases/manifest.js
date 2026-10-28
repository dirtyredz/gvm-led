// Phone manifest validation over the shared release contract (release-contract.js), plus
// the release-sequence to Android version pairing. Portable (no Node built-ins).
// Ported from command-deck src/mobile/releases/manifest.js (Worker candidates omitted).
import { sequenceForVersion, versionForSequence } from "./version.js";
import { CONTRACT, PHONE_IDENTITY, apkName, isHex, trustedCertificate } from "./release-contract.js";

const L = CONTRACT.limits;

const fail = () => new Error("Invalid phone release manifest or version");
const isObject = (value) => !!value && typeof value === "object" && !Array.isArray(value);
export const exactKeys = (value, keys) =>
  isObject(value) && Object.keys(value).sort().join() === [...keys].sort().join();

export function phoneVersion(sequence, previousCode = PHONE_IDENTITY.bootstrapVersionCode) {
  if (
    !Number.isSafeInteger(sequence) ||
    sequence < 1 ||
    sequence >= L.maxVersionCode ||
    !Number.isSafeInteger(previousCode) ||
    previousCode < PHONE_IDENTITY.bootstrapVersionCode ||
    sequence + 1 <= previousCode
  )
    throw fail();
  return { versionName: versionForSequence(sequence), versionCode: sequence + 1 };
}

/**
 * The phone version of a release version string. versionCode is the release sequence + 1
 * (never the workflow run number): the sequence only rises past every published number, so
 * versionCode stays strictly monotonic even when a failed run's number is reused.
 */
export function phoneVersionOf(version) {
  const sequence = sequenceForVersion(version);
  if (sequence === null) throw fail();
  return phoneVersion(sequence);
}

/**
 * The validated copy of a schema-1 phone manifest. `certificateSha256` is the trusted
 * signer to require (default: the pinned permanent certificate); a manifest can never
 * select its own signer, and with no pinned certificate every manifest is refused.
 */
export function validatePhoneManifest(value, { certificateSha256 = trustedCertificate() } = {}) {
  if (
    !isHex(certificateSha256, 64) ||
    !exactKeys(value, CONTRACT.manifestFields) ||
    value.schema !== CONTRACT.schema ||
    value.repository !== CONTRACT.repository ||
    value.channel !== CONTRACT.channel ||
    value.packageName !== CONTRACT.packageName ||
    value.certificateSha256 !== certificateSha256 ||
    !Number.isSafeInteger(value.versionCode) ||
    value.versionCode <= PHONE_IDENTITY.bootstrapVersionCode ||
    value.versionCode > L.maxVersionCode ||
    value.versionName !== versionForSequence(value.versionCode - 1) ||
    !Number.isSafeInteger(value.minSdk) ||
    value.minSdk < L.minSdkFloor ||
    value.minSdk > L.minSdkCeiling ||
    !isHex(value.commit, 40) ||
    !exactKeys(value.apk, CONTRACT.apkFields) ||
    value.apk.name !== apkName(value.versionName) ||
    !Number.isSafeInteger(value.apk.size) ||
    value.apk.size < 1 ||
    value.apk.size > L.maxApkBytes ||
    !isHex(value.apk.sha256, 64)
  )
    throw fail();
  return structuredClone(value);
}
