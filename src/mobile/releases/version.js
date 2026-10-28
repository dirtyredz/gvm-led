// Release versions and the CI release identity. A release has a SEQUENCE number n (one
// above the highest android-v<version> ever published) and the version 0.1.<n>; the
// patch rolls into the minor every PATCH_INTERVAL releases (Command Deck's scheme, kept so
// both projects order versions alike). versionCode is n + 1 (manifest.js). The workflow
// run number is provenance only. releaseFromEnv reads only the environment object it is
// given (CI: GVM_RELEASE_CHANNEL + GVM_RELEASE_VERSION + GitHub's own variables).
// Ported from command-deck src/api/contracts/releases/{identity,version}.js,
// src/process/releases/publishing/version.js and publication/allocation.js; desktop parts omitted.

export const CHANNELS = Object.freeze(["stable"]);
export const FIRST_MINOR = 1;
export const PATCH_INTERVAL = 60000;
export const MAX_SEQUENCE =
  PATCH_INTERVAL * PATCH_INTERVAL * PATCH_INTERVAL - 1 - FIRST_MINOR * PATCH_INTERVAL;
/** GitHub `owner/name`. */
export const GITHUB_REPOSITORY = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})\/[A-Za-z0-9._-]{1,100}$/;
export const WORKFLOW_PATH = /^\.github\/workflows\/[A-Za-z0-9._-]{1,100}\.ya?ml$/;

const fail = (message) => Object.assign(new Error(message), { code: "RELEASE_VERSION" });

/** The release version of sequence `sequence` (a positive integer): 1 -> 0.1.1. */
export function versionForSequence(sequence) {
  if (!Number.isSafeInteger(sequence) || sequence < 1 || sequence > MAX_SEQUENCE)
    throw fail(`sequence out of range: ${sequence}`);
  const total = FIRST_MINOR * PATCH_INTERVAL + sequence;
  const patch = total % PATCH_INTERVAL;
  const minors = Math.floor(total / PATCH_INTERVAL);
  return `${Math.floor(minors / PATCH_INTERVAL)}.${minors % PATCH_INTERVAL}.${patch}`;
}

/** The sequence a canonical version stands for, or null for anything else. */
export function sequenceForVersion(version) {
  const match = /^(0|[1-9]\d{0,4})\.(0|[1-9]\d{0,4})\.(0|[1-9]\d{0,4})$/.exec(version ?? "");
  if (!match) return null;
  const [major, minor, patch] = match.slice(1).map(Number);
  if (minor >= PATCH_INTERVAL || patch >= PATCH_INTERVAL) return null;
  const total = (major * PATCH_INTERVAL + minor) * PATCH_INTERVAL + patch;
  const sequence = total - FIRST_MINOR * PATCH_INTERVAL;
  return sequence >= 1 && sequence <= MAX_SEQUENCE ? sequence : null;
}

/**
 * The next sequence: one above the highest published `android-v<version>` tag. `tags` are
 * the tag names of published (non-draft) releases; foreign tags are ignored.
 */
export function nextSequence(tags) {
  let highest = 0;
  for (const tag of tags) {
    const match = /^android-v(.+)$/.exec(typeof tag === "string" ? tag : "");
    const sequence = match ? sequenceForVersion(match[1]) : null;
    if (sequence !== null && sequence > highest) highest = sequence;
  }
  return highest + 1;
}

const runInteger = (env, name) => {
  const text = env[name];
  if (typeof text !== "string" || !/^[1-9]\d{0,15}$/.test(text)) throw fail(`${name} is not set`);
  const value = Number(text);
  if (!Number.isSafeInteger(value)) throw fail(`${name} is out of range`);
  return value;
};

/** `.github/workflows/<file>` from GITHUB_WORKFLOW_REF (`owner/repo/.github/workflows/x.yml@ref`). */
function workflowPath(ref, repository) {
  const prefix = `${repository}/`;
  if (typeof ref !== "string" || !ref.startsWith(prefix)) return null;
  const at = ref.indexOf("@", prefix.length);
  const workflow = ref.slice(prefix.length, at === -1 ? undefined : at);
  return WORKFLOW_PATH.test(workflow) ? workflow : null;
}

/**
 * The release identity of this CI build, or null when it is not a release (no
 * GVM_RELEASE_CHANNEL). A release build never falls back to an unversioned one: every
 * GitHub fact must be present and well formed, or this throws RELEASE_VERSION.
 * Returns `{repository, channel, version, commit, build: {workflow, runId, runNumber, runAttempt}}`.
 */
export function releaseFromEnv(env = process.env) {
  const channel = env.GVM_RELEASE_CHANNEL;
  if (!channel) return null;
  if (!CHANNELS.includes(channel)) throw fail(`unknown release channel: ${channel}`);
  if (env.GITHUB_ACTIONS !== "true") throw fail("release builds run only in GitHub Actions");
  const repository = env.GITHUB_REPOSITORY;
  if (typeof repository !== "string" || !GITHUB_REPOSITORY.test(repository))
    throw fail("GITHUB_REPOSITORY is not set");
  const commit = env.GITHUB_SHA;
  if (typeof commit !== "string" || !/^[a-f0-9]{40}$/.test(commit))
    throw fail("GITHUB_SHA is not set");
  const workflow = workflowPath(env.GITHUB_WORKFLOW_REF, repository);
  if (!workflow) throw fail("GITHUB_WORKFLOW_REF does not name a workflow of this repository");
  const version = env.GVM_RELEASE_VERSION;
  if (sequenceForVersion(version) === null) throw fail("GVM_RELEASE_VERSION is not set");
  return Object.freeze({
    repository,
    channel,
    version,
    commit,
    build: Object.freeze({
      workflow,
      runId: runInteger(env, "GITHUB_RUN_ID"),
      runNumber: runInteger(env, "GITHUB_RUN_NUMBER"),
      runAttempt: runInteger(env, "GITHUB_RUN_ATTEMPT"),
    }),
  });
}

/** Tag names of every published (non-draft) release; exhausting the page bound is an error. */
export async function publishedTags(request, { pageSize = 100, maxPages = 50 } = {}) {
  const tags = [];
  for (let page = 1; page <= maxPages; page++) {
    const list = await request("GET", `/releases?per_page=${pageSize}&page=${page}`);
    if (!Array.isArray(list)) throw fail("GitHub release list is malformed");
    for (const release of list) if (release && release.draft !== true) tags.push(release.tag_name);
    if (list.length < pageSize) return tags;
  }
  throw fail("GitHub release history exceeds the pagination bound");
}

/** The version the next release gets (`0.1.4` after a published `android-v0.1.3`). */
export async function allocateVersion(request) {
  return versionForSequence(nextSequence(await publishedTags(request)));
}
