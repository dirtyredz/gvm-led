import test from "node:test";
import assert from "node:assert/strict";
import {
  MAX_SEQUENCE,
  allocateVersion,
  nextSequence,
  publishedTags,
  releaseFromEnv,
  sequenceForVersion,
  versionForSequence,
} from "../../../src/mobile/releases/version.js";

test("versionForSequence and sequenceForVersion round trip, including across minor rollover", () => {
  for (const n of [1, 2, 41, 59999, 60000, 60001, 3599999999, MAX_SEQUENCE]) {
    const version = versionForSequence(n);
    assert.equal(sequenceForVersion(version), n, version);
  }
  assert.equal(versionForSequence(1), "0.1.1");
  assert.equal(versionForSequence(59999), "0.1.59999");
  assert.equal(versionForSequence(60000), "0.2.0");
  for (const bad of [0, -1, 1.5, NaN, MAX_SEQUENCE + 1, "3"])
    assert.throws(() => versionForSequence(bad), { code: "RELEASE_VERSION" });
});

test("sequenceForVersion rejects anything but a canonical version above the bootstrap", () => {
  for (const bad of ["0.1.0", "0.0.5", "01.1.1", "0.1", "0.1.1-rc1", "v0.1.1", "0.60000.1", "", null, undefined])
    assert.equal(sequenceForVersion(bad), null, String(bad));
});

test("nextSequence counts only android-v tags and ignores Windows-style and foreign tags", () => {
  assert.equal(nextSequence([]), 1);
  assert.equal(nextSequence(["android-v0.1.3"]), 4);
  assert.equal(nextSequence(["android-v0.1.3", "android-v0.1.2"]), 4);
  assert.equal(nextSequence(["v0.1.9", "v0.1.99", "desktop-v0.1.50", "latest", "android-vjunk"]), 1);
  assert.equal(nextSequence(["v0.1.9", "android-v0.1.3", "android-v0.1.0", null, 7]), 4);
});

const full = () => ({
  GVM_RELEASE_CHANNEL: "stable",
  GVM_RELEASE_VERSION: "0.1.42",
  GITHUB_ACTIONS: "true",
  GITHUB_REPOSITORY: "dirtyredz/gvm-led",
  GITHUB_SHA: "a".repeat(40),
  GITHUB_WORKFLOW_REF: "dirtyredz/gvm-led/.github/workflows/release.yml@refs/heads/main",
  GITHUB_RUN_ID: "9001",
  GITHUB_RUN_NUMBER: "42",
  GITHUB_RUN_ATTEMPT: "2",
});

test("releaseFromEnv is null without GVM_RELEASE_CHANNEL and ignores other variables", () => {
  assert.equal(releaseFromEnv({}), null);
  assert.equal(releaseFromEnv({ COMMAND_DECK_RELEASE_CHANNEL: "stable" }), null);
});

test("releaseFromEnv throws outside GitHub Actions", () => {
  const env = { ...full(), GITHUB_ACTIONS: undefined };
  assert.throws(() => releaseFromEnv(env), { code: "RELEASE_VERSION", message: /only in GitHub Actions/ });
  assert.throws(() => releaseFromEnv({ ...full(), GITHUB_ACTIONS: "false" }), /only in GitHub Actions/);
});

test("releaseFromEnv parses a full environment", () => {
  const release = releaseFromEnv(full());
  assert.deepEqual(release, {
    repository: "dirtyredz/gvm-led",
    channel: "stable",
    version: "0.1.42",
    commit: "a".repeat(40),
    build: {
      workflow: ".github/workflows/release.yml",
      runId: 9001,
      runNumber: 42,
      runAttempt: 2,
    },
  });
  assert.ok(Object.isFrozen(release) && Object.isFrozen(release.build));
});

test("releaseFromEnv refuses every missing or malformed fact", () => {
  const bad = {
    channel: { GVM_RELEASE_CHANNEL: "beta" },
    repository: { GITHUB_REPOSITORY: "not a repo" },
    sha: { GITHUB_SHA: "abc" },
    "workflow of another repo": { GITHUB_WORKFLOW_REF: "other/repo/.github/workflows/release.yml@main" },
    "workflow outside .github/workflows": { GITHUB_WORKFLOW_REF: "dirtyredz/gvm-led/scripts/x.yml@main" },
    version: { GVM_RELEASE_VERSION: "0.1.0" },
    "missing version": { GVM_RELEASE_VERSION: undefined },
    "run id": { GITHUB_RUN_ID: "0" },
    "run number": { GITHUB_RUN_NUMBER: "x" },
    "run attempt": { GITHUB_RUN_ATTEMPT: undefined },
  };
  for (const [name, patch] of Object.entries(bad))
    assert.throws(() => releaseFromEnv({ ...full(), ...patch }), { code: "RELEASE_VERSION" }, name);
});

const release = (tag, extra = {}) => ({ tag_name: tag, draft: false, ...extra });

test("allocateVersion skips drafts and paginates", async () => {
  const pages = [
    [
      release("android-v0.1.9", { draft: true }),
      ...Array.from({ length: 99 }, (_, i) => release(`v0.1.${i + 1}`)),
    ],
    [release("android-v0.1.7"), release("android-v0.1.5")],
  ];
  const seen = [];
  const request = async (method, route) => {
    seen.push(route);
    assert.equal(method, "GET");
    return pages[Number(/&page=(\d+)/.exec(route)[1]) - 1];
  };
  assert.equal(await allocateVersion(request), "0.1.8");
  assert.deepEqual(seen, ["/releases?per_page=100&page=1", "/releases?per_page=100&page=2"]);
  assert.equal(await allocateVersion(async () => []), "0.1.1");
});

test("publishedTags refuses a malformed list and an exhausted page bound", async () => {
  await assert.rejects(publishedTags(async () => ({ nope: true })), { code: "RELEASE_VERSION" });
  const full100 = Array.from({ length: 2 }, (_, i) => release(`android-v0.1.${i + 1}`));
  await assert.rejects(
    publishedTags(async () => full100, { pageSize: 2, maxPages: 3 }),
    /pagination bound/,
  );
  assert.deepEqual(await publishedTags(async () => [release("a"), release("b", { draft: true })]), ["a"]);
});
