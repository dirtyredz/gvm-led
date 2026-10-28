// One release-sign scenario: a CI draft plus every dependency of releaseSign, all fakes.
import assert from "node:assert/strict";
import path from "node:path";
import { createHash } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { releaseSign } from "../../../../src/mobile/releases/publishing/publish.js";
import { WORKFLOW, buildProvenance } from "../../../../src/mobile/releases/publishing/policy.js";
import { buildCiReference } from "../../../../src/mobile/releases/publishing/ci-reference.js";
import { inspectCertificate } from "../../../../src/mobile/releases/signing/certificate.js";
import { workspace } from "./workspace.js";
import { vaultSigningMaterial } from "../../../../src/mobile/releases/secrets/vault.js";
import { fakeVault } from "./fakes.js";
import { testCertificate } from "./x509.js";
import { TOOLS, androidRun, fakeGithub, stepOf } from "./android-fakes.js";


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
  const provenance = options.provenance ?? provenanceFor(unsignedBytes);
  const prov = github.addAsset(draft, "android-provenance.json", JSON.stringify(provenance));
  const unsigned = options.noUnsigned ? null : github.addAsset(draft, UNSIGNED, unsignedBytes);
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
  const sign = () => releaseSign(deps, { tag, releaseId: draft.id });
  return {
    deps,
    github,
    draft,
    prov,
    unsigned,
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

