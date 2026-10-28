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
    const signed = await signRelease(tx, draft);
    await uploadAndVerify(tx, { ...signed, state: draft.state });
    await finalize(tx, draft);
    return signed.manifest;
  } finally {
    await material?.cleanup();
    await deps.lock.release();
  }
}
