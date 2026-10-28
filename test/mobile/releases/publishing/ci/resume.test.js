import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { readdir, writeFile } from "node:fs/promises";
import { resumeDraft, sourceRelease } from "../../../../../src/mobile/releases/publishing/ci/resume.js";
import { prepareUnsigned } from "../../../../../src/mobile/releases/prepare.js";
import { workspace } from "../../helpers/workspace.js";

const PIN = "5".repeat(64);
const VERSION = "0.1.42";
const TAG = `android-v${VERSION}`;
const source = {
  runId: 9001,
  runNumber: 42,
  runAttempt: 1,
  commit: "a".repeat(40),
  artifactId: 7001,
  artifactName: "phone-unsigned-9001-1",
  artifactSha256: "c".repeat(64),
  artifactSize: 1000,
};
const badging = {
  packageName: "com.dirtyredz.gvmled",
  versionCode: 43,
  versionName: VERSION,
  minSdk: 26,
  debuggable: false,
};

/** A prepared artifact directory built as CI would, from a release identity. */
async function prepared(t, { release = sourceRelease(source, VERSION) } = {}) {
  const { dir } = await workspace(t);
  const apk = path.join(dir, "in.apk");
  await writeFile(apk, "unsigned apk bytes");
  const directory = path.join(dir, "prepared");
  await prepareUnsigned({ pin: PIN, apk, out: directory, release, badging, signed: false });
  return { dir, directory, out: path.join(dir, "reference-out") };
}

function fakeApi(releases = []) {
  const calls = [];
  const uploaded = [];
  return {
    calls,
    uploaded,
    async request(method, route, opts = {}) {
      calls.push([method, route, opts.body]);
      if (method === "GET" && route.startsWith("/releases?per_page=100")) return releases;
      if (route === `/releases/tags/${TAG}`)
        return releases.find((r) => !r.draft && r.tag_name === TAG) ?? null;
      if (route === `/git/ref/tags/${TAG}`) return null;
      if (route === "/git/refs") return {};
      if (method === "POST" && route === "/releases") return { id: 77 };
      if (route.startsWith("/releases/77/assets"))
        return uploaded.map((a) => ({ name: a.name, size: a.size, state: "uploaded" }));
      throw new Error(`unscripted ${method} ${route}`);
    },
    async upload(id, asset) {
      assert.equal(id, 77);
      uploaded.push(asset);
    },
    async allReleases() {
      return releases;
    },
  };
}
const scratch = (name) => path.join("unused", name);
const writes = (api) => api.calls.filter(([m]) => ["POST", "PATCH", "DELETE"].includes(m));

test("a draft is restored from the prepared artifact with the same bytes and version", async (t) => {
  const p = await prepared(t);
  const api = fakeApi([{ id: 1, tag_name: "android-v0.1.41", draft: false, prerelease: false }]);
  const result = await resumeDraft({
    api,
    source,
    prepared: p.directory,
    out: p.out,
    scratch,
    resumeRunId: 123456,
  });
  assert.deepEqual(result, { id: 77, state: "created" });
  assert.deepEqual(api.uploaded.map((a) => a.name).sort(), [
    "GVM-LED-0.1.42-android-unsigned.apk",
    "android-ci-reference.json",
    "android-provenance.json",
  ]);
  const [, , body] = api.calls.find(([m, r]) => m === "POST" && r === "/releases");
  assert.equal(body.tag_name, TAG);
  assert.equal(body.target_commitish, source.commit);
  assert.equal(body.draft, true);
  assert.match(body.body, /release-resume run 123456/);
  assert.deepEqual(await readdir(p.out), ["android-ci-reference.json"]);
});

test("provenance from another commit, run or version than the verified source is refused", async (t) => {
  const cases = {
    commit: { ...source, commit: "b".repeat(40) },
    "run id": { ...source, runId: 1234 },
    "run number": { ...source, runNumber: 7 },
    attempt: { ...source, runAttempt: 2 },
  };
  for (const [name, wrong] of Object.entries(cases)) {
    const p = await prepared(t);
    const api = fakeApi();
    await assert.rejects(
      resumeDraft({ api, source: wrong, prepared: p.directory, out: p.out, scratch, resumeRunId: 1 }),
      /does not match its producing attempt/,
      name,
    );
    assert.deepEqual(writes(api), [], name);
  }
});

test("provenance built for another commit than the evidence is refused", async (t) => {
  const p = await prepared(t, {
    release: { ...sourceRelease(source, VERSION), commit: "d".repeat(40) },
  });
  const api = fakeApi();
  await assert.rejects(
    resumeDraft({ api, source, prepared: p.directory, out: p.out, scratch, resumeRunId: 1 }),
    /producing attempt/,
  );
  assert.deepEqual(writes(api), []);
});

test("the same or a later published android-v version refuses; an earlier one does not", async (t) => {
  const published = (tag) => [{ id: 5, tag_name: tag, draft: false, prerelease: false }];
  for (const tag of [TAG, "android-v0.1.43", "android-v0.1.99"]) {
    const p = await prepared(t);
    const api = fakeApi(published(tag));
    await assert.rejects(
      resumeDraft({ api, source, prepared: p.directory, out: p.out, scratch, resumeRunId: 1 }),
      /already published/,
      tag,
    );
    assert.deepEqual(writes(api), [], tag);
  }
  // A draft of another version is not publication, but it blocks a new draft.
  const pending = await prepared(t);
  await assert.rejects(
    resumeDraft({
      api: fakeApi([{ id: 6, tag_name: "android-v0.1.43", draft: true, assets: [] }]),
      source,
      prepared: pending.directory,
      out: pending.out,
      scratch,
      resumeRunId: 1,
    }),
    /still pending/,
  );
  // A foreign (non-Android) tag counts for nothing.
  const p = await prepared(t);
  const api = fakeApi([{ id: 7, tag_name: "v0.1.99", draft: false }]);
  const result = await resumeDraft({
    api,
    source,
    prepared: p.directory,
    out: p.out,
    scratch,
    resumeRunId: 1,
  });
  assert.equal(result.state, "created");
});

test("an artifact without readable provenance, or with a tampered APK, is refused", async (t) => {
  const none = await prepared(t);
  await writeFile(path.join(none.directory, "android-provenance.json"), "not json");
  await assert.rejects(
    resumeDraft({ api: fakeApi(), source, prepared: none.directory, out: none.out, scratch, resumeRunId: 1 }),
    /no readable provenance/,
  );
  const tampered = await prepared(t);
  await writeFile(path.join(tampered.directory, "GVM-LED-0.1.42-android-unsigned.apk"), "tampered");
  await assert.rejects(
    resumeDraft({ api: fakeApi(), source, prepared: tampered.directory, out: tampered.out, scratch, resumeRunId: 1 }),
    /differs from provenance/,
  );
});
