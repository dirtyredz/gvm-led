import test from "node:test";
import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import { mkdtempSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { createAndroidDraft } from "../../../../../src/mobile/releases/publishing/ci/draft.js";
import { WORKFLOW, buildProvenance } from "../../../../../src/mobile/releases/publishing/policy.js";
import { buildCiReference } from "../../../../../src/mobile/releases/publishing/ci-reference.js";

const commit = "a".repeat(40);
const tag = "android-v0.1.42";
const apk = {
  id: 1,
  name: "GVM-LED-0.1.42-android-unsigned.apk",
  file: "/x/apk",
  size: 100,
  sha256: "d".repeat(64),
  contentType: "application/vnd.android.package-archive",
};
const provenance = buildProvenance(
  {
    repository: "dirtyredz/gvm-led",
    channel: "stable",
    version: "0.1.42",
    commit,
    build: { workflow: WORKFLOW, runId: 7, runNumber: 42, runAttempt: 1 },
  },
  { name: apk.name, size: apk.size, sha256: apk.sha256 },
);
const prov = {
  id: 2,
  name: "android-provenance.json",
  file: "/x/prov",
  size: 300,
  sha256: "e".repeat(64),
  contentType: "application/json",
};
const reference = {
  id: 3,
  name: "android-ci-reference.json",
  file: "/x/reference",
  size: 500,
  sha256: "f".repeat(64),
  contentType: "application/json",
};
const ciReference = buildCiReference(provenance, {
  artifactId: 88,
  artifactSha256: "9".repeat(64),
  draftRunAttempt: 1,
});
const otherRun = buildProvenance(
  {
    repository: "dirtyredz/gvm-led",
    channel: "stable",
    version: "0.1.42",
    commit,
    build: { workflow: WORKFLOW, runId: 8, runNumber: 43, runAttempt: 1 },
  },
  { name: apk.name, size: apk.size, sha256: apk.sha256 },
);
const otherReference = buildCiReference(otherRun, {
  artifactId: 89,
  artifactSha256: "9".repeat(64),
  draftRunAttempt: 1,
});
const scratchDir = mkdtempSync(path.join(os.tmpdir(), "gvm-led-draft-"));
let scratchN = 0;
const scratch = (name) => path.join(scratchDir, `${++scratchN}-${name}`);
const assets = [apk, prov, reference];
const remote = (a, extra = {}) => ({
  id: a.id,
  name: a.name,
  size: a.size,
  state: "uploaded",
  digest: `sha256:${a.sha256}`,
  ...extra,
});

/** A recording fake `api`; `refs` maps tag -> ref object, `releases` is the existing list. */
function fakeApi({ ref = null, releases = [], listed = null, downloads = {} } = {}) {
  const calls = [];
  const uploaded = [];
  const api = {
    calls,
    uploaded,
    async request(method, route, opts = {}) {
      calls.push([method, route, opts.body]);
      if (route === `/releases/tags/${tag}`)
        return releases.find((r) => !r.draft && r.tag_name === tag) ?? null;
      if (route === "/releases?per_page=100") return releases;
      if (route === `/git/ref/tags/${tag}`) return ref;
      if (route === "/git/refs") return {};
      if (method === "PATCH" && route === `/git/refs/tags/${tag}`) return {};
      if (method === "DELETE" && /^\/releases\/\d+$/.test(route)) return null;
      if (method === "POST" && route === "/releases") return { id: 77 };
      if (route.startsWith("/releases/77/assets")) return listed ?? uploaded.map((a) => remote(a));
      throw new Error(`unscripted ${method} ${route}`);
    },
    async upload(id, asset) {
      assert.equal(id, 77);
      uploaded.push(asset);
    },
    async allReleases() {
      return releases;
    },
    async downloadAsset(id, file, max) {
      if (!(id in downloads)) throw new Error("download refused");
      const text = JSON.stringify(downloads[id]);
      assert.ok(text.length <= max);
      await writeFile(file, text, { flag: "wx" });
      return { size: text.length };
    },
  };
  return api;
}
const run = (api) => createAndroidDraft({ api, provenance, ciReference, assets, scratch });
const sameRun = { [reference.id]: ciReference };
const never = (api) =>
  assert.equal(
    api.calls.some(([m]) => m === "DELETE"),
    false,
  );
const deleted = (api) => api.calls.filter(([m]) => m === "DELETE").map(([, route]) => route);

test("a fresh draft is created non-latest and holds exactly APK, provenance and CI reference", async () => {
  const api = fakeApi();
  assert.deepEqual(await run(api), { id: 77, state: "created" });
  const [, , body] = api.calls.find(([m, r]) => m === "POST" && r === "/releases");
  assert.equal(body.draft, true);
  assert.equal(body.prerelease, false);
  assert.equal(body.make_latest, "false");
  assert.equal(body.tag_name, tag);
  assert.equal(body.target_commitish, commit);
  assert.deepEqual(
    api.uploaded.map((a) => a.name),
    [apk.name, prov.name, reference.name],
  );
  never(api);
});

test("a read-back that differs from what was uploaded throws and leaves the draft", async () => {
  for (const listed of [
    [remote(apk), remote(prov)],
    [remote(apk), remote(prov), remote(reference, { digest: `sha256:${"0".repeat(64)}` })],
    [remote(apk), remote(prov, { size: 1 })],
    [remote(apk), remote(prov, { digest: `sha256:${"0".repeat(64)}` })],
    [remote(apk)],
    [remote(apk), remote(prov, { state: "starter" })],
    [remote(apk), remote(prov), remote({ name: "extra", size: 1, sha256: "f".repeat(64) })],
  ]) {
    const api = fakeApi({ listed });
    await assert.rejects(run(api), { code: "ANDROID_DRAFT", message: /did not read back/ });
    never(api);
  }
});

test("a rerun with an identical draft reuses it without uploading", async () => {
  const draft = {
    id: 55,
    tag_name: tag,
    draft: true,
    assets: [remote(prov), remote(apk), remote(reference)],
  };
  const api = fakeApi({
    releases: [draft, { id: 1, tag_name: "v0.1.1", draft: false, assets: [] }],
  });
  assert.deepEqual(await run(api), { id: 55, state: "reused" });
  assert.deepEqual(api.uploaded, []);
  assert.equal(
    api.calls.some(([m, r]) => m === "POST" && r === "/releases"),
    false,
  );
});

test("a stale unsigned draft of this same run is replaced; one being signed is refused, never deleted", async () => {
  const withAssets = (list) => [{ id: 55, tag_name: tag, draft: true, assets: list }];
  const stale = {
    "different bytes": [
      remote(apk),
      remote(prov, { digest: `sha256:${"0".repeat(64)}` }),
      remote(reference),
    ],
    "different size": [remote(apk, { size: 5 }), remote(prov), remote(reference)],
    "incomplete asset": [remote(apk, { state: "starter" }), remote(prov), remote(reference)],
    "only the reference": [remote(reference)],
  };
  for (const [name, list] of Object.entries(stale)) {
    const api = fakeApi({ releases: withAssets(list), downloads: sameRun });
    assert.deepEqual(await run(api), { id: 77, state: "created" }, name);
    assert.deepEqual(deleted(api), ["/releases/55"], name);
    assert.deepEqual(
      api.uploaded.map((a) => a.name),
      [apk.name, prov.name, reference.name],
      name,
    );
  }
  const cases = {
    "being signed (signed APK)": [
      remote(apk),
      remote(prov),
      { name: "GVM-LED-0.1.42-android.apk", size: 9, state: "uploaded" },
    ],
    "being signed (manifest)": [
      remote(apk),
      remote(prov),
      { name: "phone-release.json", size: 9, state: "uploaded" },
    ],
  };
  for (const [name, list] of Object.entries(cases)) {
    const api = fakeApi({ releases: withAssets(list) });
    await assert.rejects(run(api), { code: "ANDROID_DRAFT" }, name);
    assert.deepEqual(api.uploaded, [], name);
    never(api);
  }
});

test("a published release with the tag, or several drafts, is refused", async () => {
  const published = fakeApi({ releases: [{ id: 9, tag_name: tag, draft: false, assets: [] }] });
  await assert.rejects(run(published), /already published/);
  const two = fakeApi({
    releases: [
      { id: 1, tag_name: tag, draft: true, assets: [] },
      { id: 2, tag_name: tag, draft: true, assets: [] },
    ],
  });
  await assert.rejects(run(two), /several drafts/);
  for (const api of [published, two]) {
    assert.deepEqual(api.uploaded, []);
    never(api);
  }
});

test("a leftover tag on another commit is moved, but never while a release is published under it", async () => {
  const ref = { object: { type: "commit", sha: "b".repeat(40) } };
  const reused = fakeApi({ ref });
  assert.deepEqual(await run(reused), { id: 77, state: "created" });
  assert.ok(reused.calls.some(([m, r]) => m === "PATCH" && r === `/git/refs/tags/${tag}`));
  const published = fakeApi({
    ref,
    releases: [{ id: 9, tag_name: tag, draft: false, assets: [] }],
  });
  await assert.rejects(run(published), /already published/);
  assert.equal(
    published.calls.some(([m, r]) => m === "PATCH" || r === "/releases"),
    false,
  );
  never(published);
});

test("missing provenance asset or mismatched tag is refused up front", async () => {
  const api = fakeApi();
  await assert.rejects(
    createAndroidDraft({ api, provenance, ciReference, assets: [apk], scratch }),
    /provenance missing/,
  );
  await assert.rejects(
    createAndroidDraft({
      api,
      provenance: { ...provenance, tag: "android-v0.1.43" },
      ciReference,
      assets,
      scratch,
    }),
    /tag mismatch/,
  );
  assert.deepEqual(api.calls, []);
});

test("new drafts require the separate reference and refuse duplicate or unrelated local assets", async () => {
  const api = fakeApi();
  for (const wrong of [
    [apk, prov],
    [apk, prov, prov],
    [...assets, { ...reference, name: "unexpected.json" }],
  ])
    await assert.rejects(
      createAndroidDraft({ api, provenance, ciReference, assets: wrong, scratch }),
      /exactly APK, provenance and CI reference/,
    );
  await assert.rejects(
    createAndroidDraft({ api, provenance, assets, scratch }),
    /Invalid Android CI reference/,
  );
  assert.deepEqual(api.calls, []);
});

const writes = (api) => api.calls.filter(([m]) => ["POST", "PATCH", "DELETE"].includes(m));
const stale = (id = 55, extra = {}) => ({
  id,
  tag_name: tag,
  draft: true,
  assets: [remote(apk), remote(prov, { digest: `sha256:${"0".repeat(64)}` }), remote(reference)],
  ...extra,
});

test("another android-v draft pending refuses, naming it, before any write", async () => {
  const api = fakeApi({
    releases: [{ id: 31, tag_name: "android-v0.1.41", draft: true, assets: [] }],
    ref: { object: { type: "commit", sha: "b".repeat(40) } },
  });
  await assert.rejects(run(api), (error) => {
    assert.equal(error.code, "ANDROID_DRAFT");
    assert.match(error.message, /android-v0\.1\.41/);
    assert.match(error.message, /still pending/);
    return true;
  });
  assert.deepEqual(writes(api), []);
  assert.deepEqual(api.uploaded, []);
});

test("a Windows-style v draft does not block a new Android draft", async () => {
  const api = fakeApi({ releases: [{ id: 31, tag_name: "v0.1.41", draft: true, assets: [] }] });
  assert.deepEqual(await run(api), { id: 77, state: "created" });
});

test("a same-tag draft with different bytes from the same run is deleted and recreated", async () => {
  const api = fakeApi({ releases: [stale()], downloads: sameRun });
  assert.deepEqual(await run(api), { id: 77, state: "created" });
  assert.deepEqual(deleted(api), ["/releases/55"]);
  const order = api.calls.map(([m, r]) => `${m} ${r}`);
  assert.ok(order.indexOf("DELETE /releases/55") < order.indexOf("POST /releases"));
});

test("a same-tag draft with different bytes from another run is refused, no writes", async () => {
  const api = fakeApi({ releases: [stale()], downloads: { [reference.id]: otherReference } });
  await assert.rejects(run(api), { code: "ANDROID_DRAFT", message: /another run/ });
  assert.deepEqual(writes(api), []);
  assert.deepEqual(api.uploaded, []);
});

test("a same-tag draft whose reference is unreadable or invalid is refused, no writes", async () => {
  for (const downloads of [{}, { [reference.id]: { nonsense: true } }]) {
    const api = fakeApi({ releases: [stale()], downloads });
    await assert.rejects(run(api), { code: "ANDROID_DRAFT", message: /another run/ });
    assert.deepEqual(writes(api), []);
  }
  const missing = fakeApi({
    releases: [stale(55, { assets: [remote(apk), remote(prov, { size: 1 })] })],
  });
  await assert.rejects(run(missing), { code: "ANDROID_DRAFT" });
  assert.deepEqual(writes(missing), []);
});

test("a same-tag draft holding a signed APK is refused even from the same run", async () => {
  const signed = {
    id: 9,
    name: "GVM-LED-0.1.42-android.apk",
    size: 9,
    state: "uploaded",
  };
  const api = fakeApi({
    releases: [stale(55, { assets: [remote(apk), remote(reference), signed] })],
    downloads: sameRun,
  });
  await assert.rejects(run(api), { code: "ANDROID_DRAFT", message: /being signed/ });
  assert.deepEqual(writes(api), []);
});
