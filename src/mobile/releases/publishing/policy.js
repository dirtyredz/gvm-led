// Android-only release policy shared by the CI draft job and the local signer:
// the contract digest, CI provenance (what was built, where, from which commit)
// and versionCode monotonicity over published Android history. Node only.
import { createHash } from "node:crypto";
import {
  CONTRACT,
  PHONE_IDENTITY,
  androidTag,
  isHex,
  isPublishedAndroid,
  isSafeId,
  unsignedApkName,
} from "../release-contract.js";
import { sequenceForVersion } from "../version.js";
import { exactKeys, phoneVersionOf, validatePhoneManifest } from "../manifest.js";

export const WORKFLOW = ".github/workflows/release.yml";
const L = CONTRACT.limits;

const fail = (message) => Object.assign(new Error(message), { code: "ANDROID_RELEASE" });

/** SHA-256 of the canonical contract plus signer record (line-ending independent). */
export function contractDigest(contract = CONTRACT, identity = PHONE_IDENTITY) {
  return createHash("sha256").update(JSON.stringify({ contract, identity })).digest("hex");
}

/** The CI provenance for an unsigned build of `release` (version.js releaseFromEnv). */
export function buildProvenance(release, unsignedApk) {
  const { versionName, versionCode } = phoneVersionOf(release.version);
  return validateProvenance({
    schema: 1,
    repository: release.repository,
    workflow: release.build.workflow,
    runId: release.build.runId,
    runAttempt: release.build.runAttempt,
    runNumber: release.build.runNumber,
    commit: release.commit,
    tag: androidTag(versionName),
    packageName: CONTRACT.packageName,
    versionName,
    versionCode,
    contractSha256: contractDigest(),
    unsignedApk,
  });
}

/** Validated provenance; binds repository, workflow, run, commit, versions, contract and unsigned bytes. */
export function validateProvenance(value) {
  if (
    !exactKeys(value, CONTRACT.provenanceFields) ||
    value.schema !== 1 ||
    value.repository !== CONTRACT.repository ||
    value.workflow !== WORKFLOW ||
    !isSafeId(value.runId) ||
    !isSafeId(value.runAttempt) ||
    !isSafeId(value.runNumber) ||
    !isHex(value.commit, 40) ||
    value.packageName !== CONTRACT.packageName ||
    value.contractSha256 !== contractDigest() ||
    !exactKeys(value.unsignedApk, ["name", "size", "sha256"]) ||
    !Number.isSafeInteger(value.unsignedApk.size) ||
    value.unsignedApk.size < 1 ||
    value.unsignedApk.size > L.maxApkBytes ||
    !isHex(value.unsignedApk.sha256, 64)
  )
    throw fail("CI provenance is malformed or from another contract");
  // versionCode is the release sequence + 1, checked against the sequence the
  // versionName itself stands for (the phone manifest's pairing, manifest.js).
  const sequence = sequenceForVersion(value.versionName);
  const version = phoneVersionOf(value.versionName);
  if (
    sequence === null ||
    value.versionCode !== sequence + 1 ||
    value.versionName !== version.versionName ||
    value.tag !== androidTag(version.versionName) ||
    value.unsignedApk.name !== unsignedApkName(version.versionName)
  )
    throw fail("CI provenance versions disagree with its release sequence");
  return structuredClone(value);
}

/**
 * The highest versionCode among published Android releases. `manifests` maps a release to
 * its parsed manifest (or throws); unreadable published Android releases are errors, never absent.
 */
export async function publishedVersionCodes(releases, readManifest) {
  const codes = [];
  for (const release of releases) {
    if (!isPublishedAndroid(release)) continue;
    let manifest;
    try {
      manifest = await readManifest(release);
    } catch {
      throw fail(
        `published ${release.tag_name} has no readable manifest; refusing to guess history`,
      );
    }
    // Validated against its own signer: history counts even across a key change.
    validatePhoneManifest(manifest, { certificateSha256: manifest?.certificateSha256 });
    codes.push(manifest.versionCode);
  }
  return codes;
}

export function requireMonotonic(versionCode, published) {
  const highest = Math.max(PHONE_IDENTITY.bootstrapVersionCode, ...published);
  if (!(versionCode > highest))
    throw fail(`versionCode ${versionCode} is not above the published Android maximum ${highest}`);
}
