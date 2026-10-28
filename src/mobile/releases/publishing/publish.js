// Local Android publication: sign one CI draft and publish it, as one transaction.
// This module only sequences the steps (publishing/steps/):
//
//   1. verify-source: clean checkout at the tag's commit; approved contract and pin
//   2-3. verify-source: draft state, CI provenance, unsigned hash; versionCode above
//      every published Android release (errors are never "no history")
//   4-5. sign: signing material through the signing source (keystore pin checked
//      first), zipalign + apksigner (env: passwords), full signed inspection, manifest
//      from the final signed bytes
//   6. upload-and-verify: upload APK + manifest; re-download BOTH
//   7. finalize: re-check draft identity, asset set and monotonicity; delete only the
//      exact unsigned asset; publish last as GitHub's latest; read back
//
// Retries reconcile exact asset IDs and hashes: matching completed work resumes (verified
// signed assets after a failure past upload or past unsigned deletion); conflicting or
// unknown assets are never clobbered. Every failure before step 7's publish leaves the
// release a draft. Every side effect arrives through `deps` (fakes in unit tests).
import { verifyDraft, verifyRevision } from "./steps/verify-source.js";
import { signRelease } from "./steps/sign.js";
import { uploadAndVerify } from "./steps/upload-and-verify.js";
import { finalize } from "./steps/finalize.js";
import { describeAsset } from "./assets.js";

// Shared effects, after each caller's own admission. The protected worker supplies
// durable checkpoints; the attended caller keeps its existing lock and cleanup.
export async function publishVerified(tx, draft) {
  const stage = tx.stage ?? (async (_name, action) => action());
  const signed = await stage(
    "sign",
    () => signRelease(tx, draft),
    (result) => ({
      signedApkSha256: result.manifest.apk.sha256,
      signedApkSize: result.manifest.apk.size,
    }),
  );
  const uploaded = await stage(
    "upload",
    () => uploadAndVerify(tx, { ...signed, state: draft.state }),
    async (state) => ({
      signedAssetId: state.signed.id,
      manifestAssetId: state.manifest.id,
      manifestSha256: (await describeAsset(tx.material.file("phone-release.local.json"))).sha256,
    }),
  );
  await stage(
    "verify",
    async () => uploaded,
    async () => ({
      signedApkSha256: signed.manifest.apk.sha256,
      manifestSha256: (await describeAsset(tx.material.file("phone-release.local.json"))).sha256,
    }),
  );
  await stage(
    "finalize",
    () => finalize(tx, draft),
    () => ({ releaseId: tx.id }),
  );
  return signed.manifest;
}

/**
 * deps: {api, run, signing, material, tools, keytool, git, lock, say, pin?}; `material()`
 * yields {file(name), unique(name), cleanup()}. Returns the published manifest.
 */
export async function releaseSign(deps, { tag, releaseId }) {
  const revision = await verifyRevision(deps, { tag, releaseId });
  await deps.lock.acquire();
  let material;
  try {
    material = await deps.material();
    const tx = { ...deps, ...revision, material };
    const draft = await verifyDraft(tx);
    return await publishVerified(tx, draft);
  } finally {
    await material?.cleanup();
    await deps.lock.release();
  }
}
