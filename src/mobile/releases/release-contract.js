// The phone release contract as code: the frozen contract and signer records, the
// trusted signer, release naming and the shape of a published Android release.
// A dependency-free leaf (JSON imports only) under CI, the local signer and manifest
// validation. Ported from command-deck src/mobile/releases/release-contract.js.
import contract from "./release-contract.json" with { type: "json" };
import identity from "./signing-certificate.json" with { type: "json" };

function deepFreeze(value) {
  if (value && typeof value === "object") {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}

export const CONTRACT = deepFreeze(structuredClone(contract));
export const PHONE_IDENTITY = deepFreeze(structuredClone(identity));

export const isHex = (value, length) =>
  typeof value === "string" && value.length === length && /^[a-f0-9]+$/.test(value);
/** A GitHub numeric ID that survives JSON, Kotlin Long and URL paths unchanged. */
export const isSafeId = (value) =>
  Number.isSafeInteger(value) && value >= 1 && value <= CONTRACT.limits.maxId;

/** The pinned permanent signer, or null while no verified key has been recorded. */
export function trustedCertificate() {
  if (PHONE_IDENTITY.packageName !== CONTRACT.packageName) return null;
  return isHex(PHONE_IDENTITY.certificateSha256, 64) ? PHONE_IDENTITY.certificateSha256 : null;
}

export const androidTag = (versionName) => `${CONTRACT.tags.android}${versionName}`;
export const apkName = (versionName) =>
  `${CONTRACT.assets.apkPrefix}${versionName}${CONTRACT.assets.apkSuffix}`;
export const unsignedApkName = (versionName) =>
  `${CONTRACT.assets.apkPrefix}${versionName}${CONTRACT.assets.unsignedApkSuffix}`;

/** The versionName of an exact Android tag, or null (only `android-v<semver>` tags count). */
export function androidTagVersion(tag) {
  if (typeof tag !== "string" || !tag.startsWith(CONTRACT.tags.android)) return null;
  const version = tag.slice(CONTRACT.tags.android.length);
  return /^(0|[1-9]\d{0,9})\.(0|[1-9]\d{0,4})\.(0|[1-9]\d{0,4})$/.test(version) ? version : null;
}

/**
 * A published (non-draft, non-prerelease) Android release by shape alone, no network:
 * the one eligibility rule for the Worker's selection and the signer's history.
 */
export function isPublishedAndroid(release) {
  return (
    !!release &&
    typeof release === "object" &&
    release.draft === false &&
    release.prerelease === false &&
    isSafeId(release.id) &&
    androidTagVersion(release.tag_name) !== null
  );
}
