import test from "node:test";
import assert from "node:assert/strict";
import { verifyBuildEvidence } from "../../../../../src/mobile/releases/publishing/ci/evidence.js";

const RUN_ID = 5001;
const SHA = "a".repeat(40);
const DIGEST = "c".repeat(64);
const NOW = Date.parse("2026-10-28T12:00:00Z");
const at = (minute, second = 0) =>
  new Date(Date.parse("2026-10-28T10:00:00Z") + minute * 60000 + second * 1000).toISOString();

const baseRun = () => ({
  id: RUN_ID,
  repository: { full_name: "dirtyredz/gvm-led" },
  head_repository: { full_name: "dirtyredz/gvm-led" },
  path: ".github/workflows/release.yml@refs/heads/main",
  event: "workflow_dispatch",
  head_branch: "main",
  head_sha: SHA,
  run_number: 12,
  run_attempt: 1,
  workflow_id: 99,
  status: "completed",
});
const job = (name, attempt, startMin, endMin, extra = {}) => ({
  id: 1000 * attempt + (name === "android-build" ? 2 : 1),
  name,
  run_id: RUN_ID,
  run_attempt: attempt,
  head_sha: SHA,
  status: "completed",
  conclusion: "success",
  started_at: at(startMin),
  completed_at: at(endMin),
  ...extra,
});
const baseJobs = () => [job("android-verify", 1, 0, 2), job("android-build", 1, 3, 8)];
const artifact = (attempt = 1, extra = {}) => ({
  id: 7000 + attempt,
  name: `phone-unsigned-${RUN_ID}-${attempt}`,
  digest: `sha256:${DIGEST}`,
  expired: false,
  size_in_bytes: 5000,
  created_at: at(7),
  expires_at: "2026-11-20T00:00:00Z",
  workflow_run: { id: RUN_ID, head_sha: SHA, head_branch: "main" },
  ...extra,
});

function fakeActions({ run = {}, jobs = baseJobs(), artifacts, workflow = {} } = {}) {
  const runValue = { ...baseRun(), ...run };
  const list = artifacts ?? [artifact(runValue.run_attempt)];
  return {
    repository: "dirtyredz/gvm-led",
    async request(method, route) {
      assert.equal(method, "GET");
      if (route === `/actions/runs/${RUN_ID}`) return runValue;
      if (route === "/actions/workflows/release.yml")
        return { id: 99, path: ".github/workflows/release.yml", ...workflow };
      if (route.startsWith(`/actions/runs/${RUN_ID}/jobs`)) return { jobs };
      const m = /^\/actions\/runs\/\d+\/artifacts\?name=([^&]+)/.exec(route);
      if (m) return { artifacts: list.filter((a) => a.name === decodeURIComponent(m[1])) };
      throw new Error(`unscripted ${route}`);
    },
  };
}
const verify = (api, attempt = 1) =>
  verifyBuildEvidence(api, { runId: RUN_ID, attempt }, { now: NOW });
const refused = /Android CI evidence refused/;

test("a finished dispatch run on main with one live artifact yields the source record", async () => {
  assert.deepEqual(await verify(fakeActions()), {
    runId: RUN_ID,
    runNumber: 12,
    runAttempt: 1,
    commit: SHA,
    artifactId: 7001,
    artifactName: `phone-unsigned-${RUN_ID}-1`,
    artifactSha256: DIGEST,
    artifactSize: 5000,
  });
});

test("run-level refusals: branch, event, workflow, repository, status, wrong client", async () => {
  const cases = {
    "master branch": { run: { head_branch: "master" } },
    "push event": { run: { event: "push" } },
    "wrong workflow path": { run: { path: ".github/workflows/other.yml@refs/heads/main" } },
    "fork head repository": { run: { head_repository: { full_name: "evil/gvm-led" } } },
    "in progress": { run: { status: "in_progress" } },
    "attempt beyond the run": { run: { run_attempt: 1 }, attempt: 2 },
    "workflow id mismatch": { workflow: { id: 5 } },
    "workflow path mismatch": { workflow: { path: ".github/workflows/x.yml" } },
  };
  for (const [name, { attempt = 1, ...options }] of Object.entries(cases))
    await assert.rejects(verify(fakeActions(options), attempt), refused, name);
  const foreign = fakeActions();
  foreign.repository = "dirtyredz/command-deck";
  await assert.rejects(verify(foreign), refused);
  await assert.rejects(verifyBuildEvidence(fakeActions(), { runId: "5001", attempt: 1 }), refused);
});

test("an android-build that failed in that attempt is refused", async () => {
  const jobs = [
    job("android-verify", 1, 0, 2),
    job("android-build", 1, 3, 8, { conclusion: "failure" }),
  ];
  await assert.rejects(verify(fakeActions({ jobs })), /did not succeed in attempt 1/);
  const missing = fakeActions({ jobs: [job("android-verify", 1, 0, 2)] });
  await assert.rejects(verify(missing), refused);
});

test("an attempt-2 rerun whose android-build row copies attempt 1 is refused; attempt 1 stands", async () => {
  const jobs = [
    job("android-verify", 1, 0, 2),
    job("android-build", 1, 3, 8),
    job("android-verify", 2, 20, 22),
    job("android-build", 2, 3, 8),
  ];
  const api = fakeActions({
    run: { run_attempt: 2 },
    jobs,
    artifacts: [artifact(1), artifact(2)],
  });
  await assert.rejects(verify(api, 2), /not that attempt's own execution/);
  assert.equal((await verify(api, 1)).artifactName, `phone-unsigned-${RUN_ID}-1`);
});

test("a genuinely rerun attempt 2 is accepted with its own artifact", async () => {
  const jobs = [
    job("android-verify", 1, 0, 2),
    job("android-build", 1, 3, 8),
    job("android-verify", 2, 20, 22),
    job("android-build", 2, 23, 28),
  ];
  const api = fakeActions({
    run: { run_attempt: 2 },
    jobs,
    artifacts: [artifact(1), artifact(2, { created_at: at(27) })],
  });
  const source = await verify(api, 2);
  assert.equal(source.runAttempt, 2);
  assert.equal(source.artifactId, 7002);
});

test("artifact refusals: expired, duplicate, foreign run, missing digest, stale or early", async () => {
  const cases = {
    expired: [artifact(1, { expired: true })],
    "expiry in the past": [artifact(1, { expires_at: "2026-10-01T00:00:00Z" })],
    "two artifacts": [artifact(1), artifact(1, { id: 7777 })],
    none: [],
    "other run": [artifact(1, { workflow_run: { id: 1, head_sha: SHA, head_branch: "main" } })],
    "other commit": [
      artifact(1, { workflow_run: { id: RUN_ID, head_sha: "b".repeat(40), head_branch: "main" } }),
    ],
    "digest missing": [artifact(1, { digest: undefined })],
    "digest malformed": [artifact(1, { digest: "md5:abc" })],
    "created before the build": [artifact(1, { created_at: at(1) })],
    "created long after the build": [artifact(1, { created_at: at(30) })],
    oversized: [artifact(1, { size_in_bytes: 500 * 1024 * 1024 })],
  };
  for (const [name, artifacts] of Object.entries(cases))
    await assert.rejects(verify(fakeActions({ artifacts })), refused, name);
});
