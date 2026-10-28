// Draft recovery from a retained build artifact (release-resume.yml). Explicit deviation
// from Command Deck, whose resume republishes Windows candidates (docs/DECISIONS.md,
// 2026-10-28): here it validates the original Android run, attempt, artifact ID and
// digest (evidence.js), then restores the draft with the SAME bytes and version. It
// never rebuilds and never signs. An expired artifact needs a new normal release run.
import { readFile } from "node:fs/promises";
import path from "node:path";
import { CONTRACT } from "../../release-contract.js";
import { readPreparedUnsigned } from "../../prepare.js";
import { WORKFLOW } from "../policy.js";
import { writeCiReference } from "../ci-reference.js";
import { nextSequence, publishedTags, sequenceForVersion } from "../../version.js";
import { createAndroidDraft } from "./draft.js";

const fail = (message) => Object.assign(new Error(message), { code: "ANDROID_RESUME" });

/** The release identity the source run was built as, from verified evidence plus its version. */
export function sourceRelease(source, version) {
  return Object.freeze({
    repository: CONTRACT.repository,
    channel: CONTRACT.channel,
    version,
    commit: source.commit,
    build: Object.freeze({
      workflow: WORKFLOW,
      runId: source.runId,
      runNumber: source.runNumber,
      runAttempt: source.runAttempt,
    }),
  });
}

/**
 * `source` is verifyBuildEvidence's record; `prepared` the directory download-artifact
 * filled from exactly `source.artifactId`. Returns createAndroidDraft's result.
 */
export async function resumeDraft({ api, source, prepared, out, scratch, resumeRunId, log }) {
  // The version is read from the artifact's provenance, then everything else in it is
  // checked against the verified run (readPreparedUnsigned) and the contract.
  let claimed;
  try {
    claimed = JSON.parse(await readFile(path.join(prepared, CONTRACT.assets.provenance), "utf8"));
  } catch {
    throw fail("the retained artifact has no readable provenance");
  }
  const version = claimed?.versionName;
  const sequence = sequenceForVersion(version);
  if (sequence === null) throw fail("the retained artifact names no release version");
  const release = sourceRelease(source, version);
  const unsigned = await readPreparedUnsigned({
    directory: prepared,
    release,
    artifactAttempt: source.runAttempt,
  });
  // A restored draft must still be the next release: nothing at or above it is published.
  if (nextSequence(await publishedTags(api.request)) > sequence)
    throw fail(`android-v${version} or a later release is already published; run a new release`);
  const reference = await writeCiReference(out, unsigned.provenance, {
    artifactId: source.artifactId,
    artifactSha256: source.artifactSha256,
    draftRunAttempt: source.runAttempt,
  });
  return createAndroidDraft({
    api,
    provenance: unsigned.provenance,
    ciReference: reference.ciReference,
    assets: [...unsigned.assets, reference.asset],
    scratch,
    note: ` Restored from the retained artifact by release-resume run ${resumeRunId}.`,
    log,
  });
}
