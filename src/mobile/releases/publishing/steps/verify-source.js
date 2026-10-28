// Publication steps 1-3: the source revision (before the lock), then, under the lock,
// the draft, its CI provenance and versionCode monotonicity over published history.
import {
  CONTRACT,
  androidTagVersion,
  apkName,
  isSafeId,
  trustedCertificate,
  unsignedApkName,
} from "../../release-contract.js";
import { fail, history, readDraft, readJson, tagCommit } from "../draft-state.js";
import { requireMonotonic, validateProvenance } from "../policy.js";
import { validateCiReference } from "../ci-reference.js";

/**
 * 1. Arguments, the approved pin and a clean checkout at the tag's commit.
 * Returns the run's fixed identity: {tag, id, versionName, pin, names, commit}.
 */
export async function verifyRevision(deps, { tag, releaseId }) {
  const versionName = androidTagVersion(tag);
  const id = Number(releaseId);
  if (!versionName || !/^[1-9]\d{0,15}$/.test(String(releaseId)) || !isSafeId(id))
    throw fail("release-sign needs --tag android-v<version> and --release-id <id>");
  const pin = deps.pin ?? trustedCertificate();
  if (!pin) throw fail("no approved permanent certificate is pinned; run scripts/owner/new-signing-key.ps1 first");
  const names = {
    unsigned: unsignedApkName(versionName),
    provenance: CONTRACT.assets.provenance,
    ciReference: CONTRACT.assets.ciReference,
    signed: apkName(versionName),
    manifest: CONTRACT.assets.manifest,
  };
  if (!(await deps.git.clean())) throw fail("the checkout has uncommitted changes");
  const commit = await tagCommit(deps.api, tag);
  if ((await deps.git.head()) !== commit)
    throw fail(`check out ${tag} (${commit.slice(0, 12)}) first`);
  return { tag, id, versionName, pin, names, commit };
}

/**
 * 2-3. The draft, its provenance (bound to this commit and tag) and monotonicity.
 * Returns {state, provenance, expected} where `expected` is the badging identity.
 */
export async function verifyDraft(tx) {
  const { api, material, tag, commit, versionName } = tx;
  const state = await readDraft(api, tx);
  const provenance = validateProvenance(
    await readJson(
      api,
      state.provenance,
      material.file("provenance.json"),
      CONTRACT.limits.maxProvenanceBytes,
    ),
  );
  if (provenance.commit !== commit || provenance.tag !== tag)
    throw fail("provenance names another commit or tag");
  // Legacy attended drafts can lack this claim. New drafts must retain it, and
  // unattended admission will require independent authenticated CI evidence.
  const ciReference = state.ciReference
    ? validateCiReference(
        await readJson(
          api,
          state.ciReference,
          material.file("ci-reference.json"),
          CONTRACT.limits.maxCiReferenceBytes,
        ),
        provenance,
      )
    : null;
  const expected = {
    packageName: CONTRACT.packageName,
    versionName,
    versionCode: provenance.versionCode,
  };
  requireMonotonic(provenance.versionCode, await history(api, material.unique));
  return { state, provenance, expected, ciReference };
}
