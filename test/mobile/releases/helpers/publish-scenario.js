// One release-sign scenario: a CI draft plus every dependency of releaseSign, all fakes.
import assert from "node:assert/strict";
import path from "node:path";
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { releaseSign } from "../../../../src/mobile/releases/publishing/publish.js";
import { WORKFLOW, buildProvenance } from "../../../../src/mobile/releases/publishing/policy.js";
import { buildCiReference } from "../../../../src/mobile/releases/publishing/ci-reference.js";
import { inspectCertificate } from "../../../../src/mobile/releases/signing/certificate.js";
import { workspace } from "./workspace.js";
import { vaultSigningMaterial } from "../../../../src/mobile/releases/secrets/vault.js";
import { fakeVault } from "./fakes.js";
import { testCertificate } from "./x509.js";
import { TOOLS, androidRun, fakeGithub, stepOf } from "./android-fakes.js";
import { buildZip } from "./zip.js";


export const pem = testCertificate();
export const pin = inspectCertificate(pem).sha256;
export const password = "pw-123456";
export const commit = "a".repeat(40);
export const tag = "android-v0.1.42";
export const UNSIGNED = "GVM-LED-0.1.42-android-unsigned.apk";
export const SIGNED = "GVM-LED-0.1.42-android.apk";
export const unsignedBytes = Buffer.from("unsigned apk bytes from CI");
export const MUTATIONS = /^(upload|delete|patch):/;

export const provenanceFor = (bytes) => {
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  return buildProvenance(
    {
      repository: "dirtyredz/gvm-led",
      channel: "stable",
      version: "0.1.42",
      commit,
      build: { workflow: WORKFLOW, runId: 9001, runNumber: 42, runAttempt: 1 },
    },
    { name: UNSIGNED, size: bytes.length, sha256 },
  );
};

/** One scenario: a draft from CI plus every dependency of releaseSign, all fakes. */
export async function scenario(t, options = {}) {
  const { dir } = await workspace(t);
  const materialDir = path.join(dir, "material");
  await mkdir(materialDir);
  const github = fakeGithub({ commit, tag });
  const draft = github.addRelease({
    tag_name: tag,
    target_commitish: commit,
    ...options.release,
  });
  const draftApk = Buffer.from(options.draftApk ?? unsignedBytes);
  const provenance = options.provenance ?? provenanceFor(draftApk);
  const provenanceBytes = Buffer.from(JSON.stringify(provenance));
  const artifactApk = Buffer.from(options.artifactApk ?? draftApk);
  const artifactProvenance = Buffer.from(options.artifactProvenance ?? provenanceBytes);
  const artifact = buildZip([
    { name: UNSIGNED, data: artifactApk },
    { name: "android-provenance.json", data: artifactProvenance },
  ]);
  let reference = options.ciReference;
  if (!reference) {
    const uploaded = {
      artifactId: 7001,
      artifactSha256: createHash("sha256").update(artifact).digest("hex"),
      draftRunAttempt: 2,
    };
    try {
      reference = buildCiReference(provenance, uploaded);
    } catch {
      reference = buildCiReference(provenanceFor(unsignedBytes), uploaded);
    }
  }
  const prov = github.addAsset(draft, "android-provenance.json", provenanceBytes);
  const unsigned = options.noUnsigned ? null : github.addAsset(draft, UNSIGNED, draftApk);
  if (!options.noCiReference)
    github.addAsset(draft, "android-ci-reference.json", JSON.stringify(reference));
  for (const [name, data, state] of options.extraAssets ?? [])
    github.addAsset(draft, name, data, state);
  for (const published of options.history ?? []) {
    const release = github.addRelease({
      tag_name: published.tag,
      draft: false,
      target_commitish: commit,
    });
    if (published.manifest)
      github.addAsset(release, "phone-release.json", JSON.stringify(published.manifest));
  }
  const fake = androidRun({ pin, pem, ...options.tools });
  const vault = fakeVault({
    fields: { password },
    items: [{ id: "k", key: "keystore", bytes: "p12 keystore bytes" }],
  });
  const lock = { acquired: 0, released: 0 };
  const said = [];
  let cleaned = 0;
  const deps = {
    api: github.api,
    run: fake.run,
    signing: vaultSigningMaterial(vault),
    material: async () => {
      let n = 0;
      return {
        dir: materialDir,
        file: (name) => path.join(materialDir, name),
        unique: (name) => path.join(materialDir, `${++n}-${name}`),
        cleanup: async () => void cleaned++,
      };
    },
    tools: TOOLS,
    keytool: "keytool",
    git: {
      clean: async () => options.dirty !== true,
      head: async () => options.head ?? commit,
    },
    lock: {
      acquire: async () => void lock.acquired++,
      release: async () => void lock.released++,
    },
    say: (line) => said.push(line),
    pin,
  };
  github.api.repository = "dirtyredz/gvm-led";
  const request = github.api.request;
  github.api.request = async (method, route, requestOptions) => {
    if (method === "GET" && route === "/actions/runs/9001")
      return {
        id: 9001,
        workflow_id: 40,
        repository: { full_name: "dirtyredz/gvm-led" },
        head_repository: { full_name: "dirtyredz/gvm-led" },
        path: WORKFLOW,
        event: "workflow_dispatch",
        head_branch: "main",
        head_sha: options.actionsCommit ?? commit,
        run_number: 42,
        run_attempt: 2,
        status: "completed",
      };
    if (method === "GET" && route === "/actions/workflows/release.yml")
      return { id: 40, path: WORKFLOW };
    if (method === "GET" && route.startsWith("/actions/runs/9001/jobs?")) {
      const job = (name, id, attempt, start, completed) => ({
        name,
        id,
        run_id: 9001,
        head_sha: commit,
        run_attempt: attempt,
        status: "completed",
        conclusion: "success",
        started_at: start,
        completed_at: completed,
      });
      return {
        jobs: [
          job("android-verify", 11, 1, "2026-10-28T10:00:00Z", "2026-10-28T10:01:00Z"),
          job("android-build", 12, 1, "2026-10-28T10:02:00Z", "2026-10-28T10:03:00Z"),
          job("android-verify", 13, 2, "2026-10-28T10:00:00Z", "2026-10-28T10:01:00Z"),
          job("android-build", 14, 2, "2026-10-28T10:02:00Z", "2026-10-28T10:03:00Z"),
          job("android-draft", 15, 2, "2026-10-28T10:04:00Z", "2026-10-28T10:05:00Z"),
        ],
      };
    }
    if (method === "GET" && route === "/actions/artifacts/7001")
      return {
        id: 7001,
        name: reference.artifactName,
        digest: `sha256:${reference.artifactSha256}`,
        size_in_bytes: artifact.length,
        expired: false,
        created_at: "2026-10-28T10:02:30Z",
        expires_at: "2027-01-01T00:00:00Z",
        workflow_run: { id: 9001, head_sha: commit, head_branch: "main" },
      };
    return request(method, route, requestOptions);
  };
  github.api.downloadArtifact = async (id, file, max = Infinity) => {
    assert.equal(id, 7001);
    assert.ok(artifact.length <= max);
    github.events.push(`artifact:${id}`);
    await writeFile(file, artifact, { flag: "wx" });
    return { size: artifact.length, sha256: createHash("sha256").update(artifact).digest("hex") };
  };
  const sign = () => releaseSign(deps, { tag, releaseId: draft.id });
  return {
    deps,
    github,
    draft,
    prov,
    unsigned,
    reference,
    vault,
    lock,
    said,
    sign,
    specs: fake.specs,
    cleaned: () => cleaned,
    mutations: () => github.events.filter((e) => MUTATIONS.test(e)),
    steps: () => fake.specs.map(stepOf),
  };
}

export const untouched = (s) => {
  assert.deepEqual(s.mutations(), []);
  assert.equal(s.steps().includes("sign"), false);
};


export const referenceFor = () =>
  buildCiReference(provenanceFor(unsignedBytes), {
    artifactId: 7001,
    artifactSha256: "e".repeat(64),
    draftRunAttempt: 2,
  });
