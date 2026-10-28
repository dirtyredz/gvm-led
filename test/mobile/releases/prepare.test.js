import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { createHash } from "node:crypto";
import { readFile, readdir, writeFile } from "node:fs/promises";
import { workspace } from "./helpers/workspace.js";
import { prepareUnsigned, readPreparedUnsigned } from "../../../src/mobile/releases/prepare.js";
import { WORKFLOW, validateProvenance } from "../../../src/mobile/releases/publishing/policy.js";

const pin = "5".repeat(64);
const release = {
  repository: "dirtyredz/gvm-led",
  channel: "stable",
  version: "0.1.42",
  commit: "a".repeat(40),
  build: { workflow: WORKFLOW, runId: 9001, runNumber: 42, runAttempt: 1 },
};
const badging = {
  packageName: "com.dirtyredz.gvmled",
  versionCode: 43,
  versionName: "0.1.42",
  minSdk: 26,
  debuggable: false,
};

async function stage(t) {
  const { dir } = await workspace(t);
  const apk = path.join(dir, "app-release-unsigned.apk");
  const bytes = Buffer.from("unsigned APK bytes");
  await writeFile(apk, bytes);
  return { dir, apk, bytes, out: path.join(dir, "out") };
}

test("prepareUnsigned stages the unsigned APK and provenance with matching hash and size", async (t) => {
  const { apk, bytes, out } = await stage(t);
  const result = await prepareUnsigned({ pin, apk, out, release, badging, signed: false });
  const name = "GVM-LED-0.1.42-android-unsigned.apk";
  assert.deepEqual((await readdir(out)).sort(), [name, "android-provenance.json"]);
  assert.deepEqual(await readFile(path.join(out, name)), bytes);
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  assert.deepEqual(result.provenance.unsignedApk, { name, size: bytes.length, sha256 });
  const written = JSON.parse(await readFile(path.join(out, "android-provenance.json"), "utf8"));
  assert.deepEqual(written, result.provenance);
  assert.deepEqual(validateProvenance(written), written);
  assert.equal(written.tag, "android-v0.1.42");
  assert.equal(written.commit, release.commit);
  assert.deepEqual(
    result.assets.map((a) => [a.name, a.contentType, a.file]),
    [
      [name, "application/vnd.android.package-archive", path.join(out, name)],
      ["android-provenance.json", "application/json", path.join(out, "android-provenance.json")],
    ],
  );
  assert.equal(result.assets[0].sha256, sha256);
  assert.equal(result.assets[0].size, bytes.length);
});

test("prepareUnsigned refuses a signed APK before staging anything", async (t) => {
  const { apk, out } = await stage(t);
  for (const signed of [true, undefined, "false"])
    await assert.rejects(prepareUnsigned({ pin, apk, out, release, badging, signed }), /unsigned APK/);
  await assert.rejects(readdir(out), /ENOENT/);
});

test("prepareUnsigned refuses the wrong package, version, debug build or identity", async (t) => {
  const { apk, out } = await stage(t);
  const attempt = (patch, rel = release) =>
    prepareUnsigned({ pin, apk, out, release: rel, badging: { ...badging, ...patch }, signed: false });
  for (const patch of [
    { packageName: "com.dirtyredz.gvmled.debug" },
    { versionCode: 44 },
    { versionName: "0.1.43" },
    { debuggable: true },
    { minSdk: 25 },
  ])
    await assert.rejects(attempt(patch), /unsigned APK/);
  await assert.rejects(attempt({}, { ...release, repository: "other/repo" }), /identity/);
  await assert.rejects(attempt({}, { ...release, channel: "beta" }), /identity/);
  await assert.rejects(readdir(out), /ENOENT/);
});

test("prepareUnsigned never overwrites an APK already bound to existing provenance", async (t) => {
  const { apk, out, bytes } = await stage(t);
  await prepareUnsigned({ pin, apk, out, release, badging, signed: false });
  await writeFile(apk, "replacement bytes");
  await assert.rejects(prepareUnsigned({ pin, apk, out, release, badging, signed: false }), /EEXIST/);
  assert.deepEqual(
    await readFile(path.join(out, "GVM-LED-0.1.42-android-unsigned.apk")),
    bytes,
  );
});

test("partial-rerun drafts preserve producing provenance and verify the exact two-file artifact", async (t) => {
  const { apk, out } = await stage(t);
  await prepareUnsigned({ pin, apk, out, release, badging, signed: false });
  const before = await readFile(path.join(out, "android-provenance.json"));
  const rerun = { ...release, build: { ...release.build, runAttempt: 3 } };
  const prepared = await readPreparedUnsigned({
    directory: out,
    release: rerun,
    artifactAttempt: 1,
  });
  assert.equal(prepared.provenance.runAttempt, 1);
  assert.deepEqual(await readFile(path.join(out, "android-provenance.json")), before);
  await assert.rejects(
    readPreparedUnsigned({ directory: out, release: rerun, artifactAttempt: 3 }),
    /producing attempt/,
  );
  await writeFile(
    path.join(out, "android-ci-reference.json"),
    "must not be inside its own artifact",
  );
  await assert.rejects(
    readPreparedUnsigned({ directory: out, release: rerun, artifactAttempt: 1 }),
    /exactly APK and provenance/,
  );
});

test("prepared artifact admission rejects changed APK bytes and foreign release identity", async (t) => {
  const { apk, out } = await stage(t);
  await prepareUnsigned({ pin, apk, out, release, badging, signed: false });
  await assert.rejects(
    readPreparedUnsigned({
      directory: out,
      release: { ...release, commit: "b".repeat(40) },
      artifactAttempt: 1,
    }),
    /producing attempt/,
  );
  await writeFile(path.join(out, "GVM-LED-0.1.42-android-unsigned.apk"), "tampered");
  await assert.rejects(
    readPreparedUnsigned({ directory: out, release, artifactAttempt: 1 }),
    /differs from provenance/,
  );
});

test("prepareUnsigned refuses while no certificate is pinned, staging nothing", async (t) => {
  const { apk, out } = await stage(t);
  await assert.rejects(
    prepareUnsigned({ pin: null, apk, out, release, badging, signed: false }),
    /No signing certificate is pinned/,
  );
  await assert.rejects(readdir(out), /ENOENT/);
});
