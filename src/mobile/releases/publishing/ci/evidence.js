// Authenticated Actions evidence for release-resume: which run, attempt and artifact
// produced an unsigned build. Draft JSON and workflow inputs are claims; only these
// fixed-repository Actions reads establish the producer. Ported from command-deck
// src/mobile/releases/publishing/ci-evidence.js (job/attempt rules unchanged), reshaped
// for recovery: the android-draft job of the source attempt need not have succeeded.
import { CONTRACT, isSafeId } from "../../release-contract.js";
import { WORKFLOW } from "../policy.js";
import { unsignedArtifactName } from "../ci-reference.js";

const refuse = (why) => {
  throw Object.assign(new Error(`Android CI evidence refused: ${why}`), { code: "ANDROID_EVIDENCE" });
};
const time = (value) => (typeof value === "string" ? Date.parse(value) : NaN);
const sameExecution = (a, b) =>
  a.started_at === b.started_at &&
  a.completed_at === b.completed_at &&
  a.conclusion === b.conclusion;

export async function jobsFor(api, runId) {
  const jobs = [];
  for (let page = 1; page <= 20; page++) {
    const body = await api.request(
      "GET",
      `/actions/runs/${runId}/jobs?filter=all&per_page=100&page=${page}`,
    );
    if (!Array.isArray(body?.jobs) || body.jobs.length > 100) refuse("job list malformed");
    jobs.push(...body.jobs);
    if (body.jobs.length < 100) return jobs;
  }
  refuse("job list exceeds the pagination bound");
}

/**
 * The effective successful execution of job `name` in `attempt`. A rerun attempt lists
 * jobs it did not rerun with their original timings; those count as the original row.
 */
export function execution(jobs, name, attempt, run) {
  const rows = jobs
    .filter((job) => job.name === name)
    .sort((a, b) => a.run_attempt - b.run_attempt);
  const seen = new Set(),
    origins = [],
    effective = new Map();
  for (const row of rows) {
    if (
      !isSafeId(row.id) ||
      !isSafeId(row.run_attempt) ||
      row.run_attempt > run.run_attempt ||
      row.run_id !== run.id ||
      row.head_sha !== run.head_sha ||
      seen.has(row.run_attempt) ||
      !(time(row.completed_at) >= time(row.started_at))
    )
      refuse(`job ${name} rows are inconsistent`);
    seen.add(row.run_attempt);
    const origin = origins.find((old) => sameExecution(old, row));
    if (!origin) origins.push(row);
    effective.set(row.run_attempt, origin ?? row);
  }
  const job = effective.get(attempt);
  if (!job || job.status !== "completed" || job.conclusion !== "success")
    refuse(`job ${name} did not succeed in attempt ${attempt}`);
  return job;
}

/**
 * Verifies run `runId` attempt `attempt` of the Release workflow on the default branch
 * built and uploaded exactly one live unsigned artifact. Returns the source record the
 * resume step trusts: {runId, runNumber, runAttempt, commit, artifactId, artifactName,
 * artifactSha256, artifactSize}.
 */
export async function verifyBuildEvidence(api, { runId, attempt }, { now = Date.now() } = {}) {
  if (!isSafeId(runId) || !isSafeId(attempt)) refuse("run ID and attempt must be positive integers");
  if (api.repository !== CONTRACT.repository) refuse("wrong repository client");
  const run = await api.request("GET", `/actions/runs/${runId}`);
  if (
    run?.id !== runId ||
    run.repository?.full_name !== CONTRACT.repository ||
    run.head_repository?.full_name !== CONTRACT.repository ||
    run.path?.split("@")[0] !== WORKFLOW ||
    run.event !== "workflow_dispatch" ||
    run.head_branch !== CONTRACT.branch ||
    !/^[a-f0-9]{40}$/.test(run.head_sha ?? "") ||
    !isSafeId(run.run_number) ||
    !isSafeId(run.run_attempt) ||
    run.run_attempt < attempt ||
    run.status !== "completed"
  )
    refuse(`run ${runId} is not a finished Release run on ${CONTRACT.branch}`);
  const workflow = await api.request("GET", "/actions/workflows/release.yml");
  if (!isSafeId(workflow?.id) || workflow.id !== run.workflow_id || workflow.path !== WORKFLOW)
    refuse("workflow identity mismatch");
  const jobs = await jobsFor(api, runId);
  const verify = execution(jobs, "android-verify", attempt, run);
  const build = execution(jobs, "android-build", attempt, run);
  if (build.run_attempt !== attempt || time(verify.completed_at) > time(build.started_at))
    refuse(`android-build of attempt ${attempt} is not that attempt's own execution`);
  const artifactName = unsignedArtifactName(runId, attempt);
  const listed = await api.request(
    "GET",
    `/actions/runs/${runId}/artifacts?name=${encodeURIComponent(artifactName)}&per_page=100`,
  );
  const artifacts = (listed?.artifacts ?? []).filter((a) => a?.name === artifactName);
  if (artifacts.length !== 1) refuse(`expected exactly one ${artifactName}`);
  const [artifact] = artifacts;
  const digest = /^sha256:([a-f0-9]{64})$/.exec(artifact.digest ?? "")?.[1];
  if (
    !isSafeId(artifact.id) ||
    artifact.expired !== false ||
    !digest ||
    artifact.workflow_run?.id !== runId ||
    artifact.workflow_run?.head_sha !== run.head_sha ||
    artifact.workflow_run?.head_branch !== CONTRACT.branch ||
    !isSafeId(artifact.size_in_bytes) ||
    artifact.size_in_bytes > CONTRACT.limits.maxApkBytes + 1024 * 1024 ||
    !(time(artifact.expires_at) > now) ||
    !(time(artifact.created_at) >= time(build.started_at)) ||
    !(time(artifact.created_at) <= time(build.completed_at) + 5000)
  )
    refuse(`${artifactName} is expired, foreign or not from that build`);
  return {
    runId,
    runNumber: run.run_number,
    runAttempt: attempt,
    commit: run.head_sha,
    artifactId: artifact.id,
    artifactName,
    artifactSha256: digest,
    artifactSize: artifact.size_in_bytes,
  };
}
