// Public claims about the CI artifact a draft was made from, never proof of CI authority:
// release-resume (publishing/ci/evidence.js) verifies them against the Actions API.
import path from "node:path";
import { mkdir, writeFile } from "node:fs/promises";
import { CONTRACT, isSafeId, isHex } from "../release-contract.js";
import { exactKeys } from "../manifest.js";
import { WORKFLOW, validateProvenance } from "./policy.js";
import { describeAsset } from "./assets.js";

const fail = () => new Error("Invalid Android CI reference");
export function unsignedArtifactName(runId, runAttempt) {
  if (!isSafeId(runId) || !isSafeId(runAttempt)) throw fail();
  return `phone-unsigned-${runId}-${runAttempt}`;
}

export function validateCiReference(value, provenance) {
  if (
    !exactKeys(value, CONTRACT.ciReferenceFields) ||
    value.schema !== 1 ||
    value.repository !== CONTRACT.repository ||
    value.workflow !== WORKFLOW ||
    !isSafeId(value.runId) ||
    !isSafeId(value.runAttempt) ||
    !isSafeId(value.draftRunAttempt) ||
    value.draftRunAttempt < value.runAttempt ||
    !isSafeId(value.artifactId) ||
    value.artifactName !== unsignedArtifactName(value.runId, value.runAttempt) ||
    !isHex(value.artifactSha256, 64) ||
    !isHex(value.commit, 40)
  )
    throw fail();
  if (provenance) {
    const p = validateProvenance(provenance);
    if (
      value.repository !== p.repository ||
      value.workflow !== p.workflow ||
      value.runId !== p.runId ||
      value.runAttempt !== p.runAttempt ||
      value.commit !== p.commit
    )
      throw fail();
  }
  return Object.fromEntries(CONTRACT.ciReferenceFields.map((field) => [field, value[field]]));
}

export function buildCiReference(provenance, { artifactId, artifactSha256, draftRunAttempt }) {
  const p = validateProvenance(provenance);
  return validateCiReference(
    {
      schema: 1,
      repository: p.repository,
      workflow: p.workflow,
      runId: p.runId,
      runAttempt: p.runAttempt,
      draftRunAttempt,
      artifactId,
      artifactName: unsignedArtifactName(p.runId, p.runAttempt),
      artifactSha256:
        typeof artifactSha256 === "string"
          ? artifactSha256.replace(/^sha256:/, "")
          : artifactSha256,
      commit: p.commit,
    },
    p,
  );
}

export async function writeCiReference(out, provenance, uploaded) {
  const ciReference = buildCiReference(provenance, uploaded);
  await mkdir(out, { recursive: true });
  const file = path.join(out, CONTRACT.assets.ciReference);
  await writeFile(file, JSON.stringify(ciReference, null, 2) + "\n", { flag: "wx" });
  return {
    ciReference,
    asset: { ...(await describeAsset(file)), file, contentType: "application/json" },
  };
}
