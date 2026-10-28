import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { readFile } from "node:fs/promises";
import { workspace } from "../helpers/workspace.js";
import { buildProvenance, WORKFLOW } from "../../../../src/mobile/releases/publishing/policy.js";
import {
  buildCiReference,
  validateCiReference,
  unsignedArtifactName,
  writeCiReference,
} from "../../../../src/mobile/releases/publishing/ci-reference.js";

const provenance = buildProvenance(
  {
    repository: "dirtyredz/gvm-led",
    channel: "stable",
    version: "0.1.42",
    commit: "a".repeat(40),
    build: { workflow: WORKFLOW, runId: 9001, runAttempt: 1, runNumber: 42 },
  },
  { name: "GVM-LED-0.1.42-android-unsigned.apk", size: 100, sha256: "b".repeat(64) },
);
const uploaded = { artifactId: 7001, artifactSha256: "c".repeat(64), draftRunAttempt: 3 };

test("CI reference binds exact upload identity while preserving the original producing attempt", () => {
  const reference = buildCiReference(provenance, uploaded);
  assert.equal(reference.runAttempt, 1);
  assert.equal(reference.draftRunAttempt, 3);
  assert.equal(reference.artifactId, 7001);
  assert.equal(reference.artifactName, "phone-unsigned-9001-1");
  assert.equal(unsignedArtifactName(9001, 2), "phone-unsigned-9001-2");
  assert.deepEqual(
    buildCiReference(provenance, {
      ...uploaded,
      artifactSha256: `sha256:${uploaded.artifactSha256}`,
    }),
    reference,
  );
  assert.deepEqual(validateCiReference(reference, provenance), reference);
});

test("foreign, malformed, extra and mismatched CI claims fail closed", () => {
  const reference = buildCiReference(provenance, uploaded);
  for (const patch of [
    { schema: 2 },
    { repository: "other/repo" },
    { workflow: ".github/workflows/other.yml" },
    { runId: 2 },
    { runAttempt: 2 },
    { draftRunAttempt: 0 },
    { artifactId: "7001" },
    { artifactName: "phone-unsigned" },
    { artifactSha256: "bad" },
    { commit: "d".repeat(40) },
    { extra: "claim" },
  ])
    assert.throws(
      () => validateCiReference({ ...reference, ...patch }, provenance),
      /Invalid Android CI reference/,
    );
  assert.throws(() =>
    buildCiReference(provenance, { ...uploaded, artifactId: Number.MAX_SAFE_INTEGER + 1 }),
  );
});

test("reference is written only after upload, outside the prepared artifact, and never overwritten", async (t) => {
  const { dir } = await workspace(t);
  const { ciReference, asset } = await writeCiReference(
    path.join(dir, "draft"),
    provenance,
    uploaded,
  );
  assert.equal(asset.name, "android-ci-reference.json");
  assert.equal(asset.contentType, "application/json");
  assert.deepEqual(JSON.parse(await readFile(asset.file, "utf8")), ciReference);
  await assert.rejects(writeCiReference(path.join(dir, "draft"), provenance, uploaded), /EEXIST/);
});
