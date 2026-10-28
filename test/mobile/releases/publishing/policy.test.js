import test from "node:test";
import assert from "node:assert/strict";
import {
  WORKFLOW,
  buildProvenance,
  contractDigest,
  publishedVersionCodes,
  requireMonotonic,
  validateProvenance,
} from "../../../../src/mobile/releases/publishing/policy.js";
import { CONTRACT, PHONE_IDENTITY } from "../../../../src/mobile/releases/release-contract.js";
import { FIXTURE_SIGNER, fixtureManifest } from "../helpers/manifest-fixture.js";

const release = {
  repository: "dirtyredz/gvm-led",
  channel: "stable",
  version: "0.1.42",
  commit: "a".repeat(40),
  build: { workflow: WORKFLOW, runId: 9001, runNumber: 42, runAttempt: 1 },
};
const unsignedApk = {
  name: "GVM-LED-0.1.42-android-unsigned.apk",
  size: 1234,
  sha256: "c".repeat(64),
};

test("contractDigest is stable and tracks the contract and signer record", () => {
  assert.equal(contractDigest(), contractDigest());
  assert.match(contractDigest(), /^[a-f0-9]{64}$/);
  const changed = { ...CONTRACT, schema: 2 };
  assert.notEqual(contractDigest(changed), contractDigest());
  assert.notEqual(
    contractDigest(CONTRACT, { ...PHONE_IDENTITY, alias: "other" }),
    contractDigest(),
  );
});

test("buildProvenance binds run, commit, versions, contract and the unsigned bytes", () => {
  const p = buildProvenance(release, unsignedApk);
  assert.deepEqual(p, {
    schema: 1,
    repository: "dirtyredz/gvm-led",
    workflow: WORKFLOW,
    runId: 9001,
    runAttempt: 1,
    runNumber: 42,
    commit: "a".repeat(40),
    tag: "android-v0.1.42",
    packageName: "com.dirtyredz.gvmled",
    versionName: "0.1.42",
    versionCode: 43,
    contractSha256: contractDigest(),
    unsignedApk,
  });
  assert.deepEqual(validateProvenance(p), p);
});

test("buildProvenance refuses a wrong workflow, repository or unsigned name", () => {
  assert.throws(() =>
    buildProvenance({ ...release, build: { ...release.build, workflow: "x.yml" } }, unsignedApk),
  );
  assert.throws(() => buildProvenance({ ...release, repository: "other/repo" }, unsignedApk));
  assert.throws(() => buildProvenance(release, { ...unsignedApk, name: "app.apk" }), /disagree/);
});

test("validateProvenance rejects every tampered binding", () => {
  const good = buildProvenance(release, unsignedApk);
  const bad = (patch) => ({ ...good, ...patch });
  const cases = {
    workflow: bad({ workflow: ".github/workflows/other.yml" }),
    repository: bad({ repository: "other/repo" }),
    "contract digest": bad({ contractSha256: "0".repeat(64) }),
    "version name": bad({ versionName: "0.1.43" }),
    "version code": bad({ versionCode: 44 }),
    "version code equal to the sequence": bad({ versionCode: 42 }),

    tag: bad({ tag: "android-v0.1.43" }),
    "unsigned name": bad({
      unsignedApk: { ...unsignedApk, name: "GVM-LED-0.1.42-android.apk" },
    }),
    "unsigned hash": bad({ unsignedApk: { ...unsignedApk, sha256: "C".repeat(64) } }),
    "unsigned size": bad({ unsignedApk: { ...unsignedApk, size: 0 } }),
    "oversized unsigned": bad({
      unsignedApk: { ...unsignedApk, size: CONTRACT.limits.maxApkBytes + 1 },
    }),
    commit: bad({ commit: "abc" }),
    package: bad({ packageName: "com.dirtyredz.gvmled.debug" }),
    "excess field": { ...good, token: "x" },
    "unsigned excess field": bad({ unsignedApk: { ...unsignedApk, url: "x" } }),
  };
  for (const [name, value] of Object.entries(cases))
    assert.throws(() => validateProvenance(value), { code: "ANDROID_RELEASE" }, name);
  const missing = { ...good };
  delete missing.tag;
  assert.throws(() => validateProvenance(missing));
});

let releaseIds = 0;
const published = (tag, extra = {}) => ({
  id: ++releaseIds,
  tag_name: tag,
  draft: false,
  prerelease: false,
  ...extra,
});

test("publishedVersionCodes counts only published Android releases", async () => {
  const asked = [];
  const codes = await publishedVersionCodes(
    [
      published("android-v0.1.40"),
      published("android-v0.1.41", { draft: true }),
      published("android-v0.1.42", { prerelease: true }),
      published("v0.1.50"),
      published("android-v0.1.39"),
      published("android-vnot-a-version"),
    ],
    async (r) => {
      asked.push(r.tag_name);
      return fixtureManifest(Number(r.tag_name.split(".").pop()), FIXTURE_SIGNER);
    },
  );
  assert.deepEqual(codes, [41, 40]);
  assert.deepEqual(asked, ["android-v0.1.40", "android-v0.1.39"]);
});

test("publishedVersionCodes refuses to guess when a published manifest is unreadable or invalid", async () => {
  await assert.rejects(
    publishedVersionCodes([published("android-v0.1.40")], async () => {
      throw new Error("no manifest");
    }),
    { code: "ANDROID_RELEASE", message: /no readable manifest/ },
  );
  await assert.rejects(
    publishedVersionCodes([published("android-v0.1.40")], async () => ({ nonsense: true })),
  );
  assert.deepEqual(await publishedVersionCodes([], async () => assert.fail("not called")), []);
});

test("requireMonotonic rejects equal or lower and accepts higher", () => {
  assert.throws(() => requireMonotonic(41, [41]), { code: "ANDROID_RELEASE" });
  assert.throws(() => requireMonotonic(40, [41, 30]), /not above/);
  assert.throws(() => requireMonotonic(PHONE_IDENTITY.bootstrapVersionCode, []), /not above/);
  assert.doesNotThrow(() => requireMonotonic(42, [41, 30]));
  assert.doesNotThrow(() => requireMonotonic(2, []));
});
