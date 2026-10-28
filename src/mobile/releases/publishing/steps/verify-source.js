// Publication steps 1-3: the source revision (before the lock), then, under the lock,
// the draft, its CI provenance and versionCode monotonicity over published history.
import path from "node:path";
import { mkdir, readFile } from "node:fs/promises";
import {
  CONTRACT,
  androidTagVersion,
  apkName,
  isSafeId,
  trustedCertificate,
  unsignedApkName,
} from "../../release-contract.js";
import { fail, history, readDraft, tagCommit } from "../draft-state.js";
import { requireMonotonic, validateProvenance } from "../policy.js";
import { validateCiReference } from "../ci-reference.js";
import { describeAsset } from "../assets.js";
import { extractActionsArtifact } from "../ci/artifact.js";
import { verifyCiEvidence } from "../ci/evidence.js";

async function downloadDraftAsset(api, asset, file, limit) {
  if (!asset || !isSafeId(asset.id) || !isSafeId(asset.size) || asset.size > limit)
    throw fail(`${asset?.name ?? "draft asset"} is missing or too large`);
  await api.downloadAsset(asset.id, file, limit);
  const actual = await describeAsset(file);
  if (actual.size !== asset.size) throw fail(`draft asset ${asset.name} changed while downloading`);
  return actual;
}

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
  const { api, material, tag, commit, versionName, names } = tx;
  const state = await readDraft(api, tx);
  if (!state.ciReference) throw fail("the draft CI reference is missing");
  const provenanceFile = material.file("provenance.json");
  const draftProvenance = await downloadDraftAsset(
    api,
    state.provenance,
    provenanceFile,
    CONTRACT.limits.maxProvenanceBytes,
  );
  const provenance = validateProvenance(JSON.parse(await readFile(provenanceFile, "utf8")));
  if (provenance.commit !== commit || provenance.tag !== tag)
    throw fail("provenance names another commit or tag");
  const ciReferenceFile = material.file("ci-reference.json");
  await downloadDraftAsset(
    api,
    state.ciReference,
    ciReferenceFile,
    CONTRACT.limits.maxCiReferenceBytes,
  );
  const ciReference = validateCiReference(
    JSON.parse(await readFile(ciReferenceFile, "utf8")),
    provenance,
  );
  const evidence = await verifyCiEvidence(api, provenance, ciReference);
  const archive = material.file("actions-artifact.zip");
  await api.downloadArtifact(evidence.artifactId, archive, evidence.artifactSize);
  const downloadedArtifact = await describeAsset(archive);
  if (
    downloadedArtifact.size !== evidence.artifactSize ||
    downloadedArtifact.sha256 !== evidence.artifactSha256
  )
    throw fail("the downloaded Actions artifact differs from its authenticated digest");
  const extracted = material.file("actions-artifact");
  await mkdir(extracted);
  await extractActionsArtifact(archive, extracted, provenance);
  const actionsProvenance = await describeAsset(path.join(extracted, CONTRACT.assets.provenance));
  if (
    actionsProvenance.size !== draftProvenance.size ||
    actionsProvenance.sha256 !== draftProvenance.sha256
  )
    throw fail("the draft provenance differs from Actions");
  const actionsApk = await describeAsset(path.join(extracted, names.unsigned));
  if (
    actionsApk.size !== provenance.unsignedApk.size ||
    actionsApk.sha256 !== provenance.unsignedApk.sha256
  )
    throw fail("the Actions APK differs from its provenance");
  let unsignedFile;
  if (state.unsigned) {
    unsignedFile = material.file(names.unsigned);
    const draftApk = await downloadDraftAsset(
      api,
      state.unsigned,
      unsignedFile,
      CONTRACT.limits.maxApkBytes,
    );
    if (draftApk.size !== actionsApk.size || draftApk.sha256 !== actionsApk.sha256)
      throw fail("the draft APK differs from Actions");
  }
  const expected = {
    packageName: CONTRACT.packageName,
    versionName,
    versionCode: provenance.versionCode,
  };
  requireMonotonic(provenance.versionCode, await history(api, material.unique));
  return { state, provenance, expected, ciReference, unsignedFile };
}
