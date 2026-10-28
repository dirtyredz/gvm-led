// CI's Android draft: pin tag android-v<version> to the built commit, then create (or
// reuse an identical) draft holding APK, provenance and exact CI reference. Used by the
// release workflow's android-draft job and by release-resume (same bytes, same version).
// Ported from command-deck src/mobile/releases/publishing/draft.js.
//
// Deviation from Command Deck (docs/DECISIONS.md, 2026-10-28): CD's allocator ignores
// drafts and replaces any unsigned leftover with other bytes. Here a pending draft is
// the owner's open release: while any android-v draft from ANOTHER run exists, no new
// draft is made (sign and publish it, or delete it, first). Only a draft of this same
// tag from this same CI run (a rerun attempt) may be replaced. The check runs before
// any tag is moved or draft written.
import { claimReleaseTag } from "./claim-tag.js";
import { CONTRACT, androidTag, androidTagVersion, unsignedApkName } from "../../release-contract.js";
import { validateCiReference } from "../ci-reference.js";
import { readJson } from "../draft-state.js";

const fail = (message) => Object.assign(new Error(message), { code: "ANDROID_DRAFT" });

const sameAsset = (remote, local) =>
  remote &&
  remote.state === "uploaded" &&
  remote.size === local.size &&
  (typeof remote.digest !== "string" || remote.digest === `sha256:${local.sha256}`);

/** The run that produced an existing draft, from its CI reference asset; null if unreadable. */
async function draftRunId(api, draft, scratch) {
  const asset = draft.assets.find((a) => a.name === CONTRACT.assets.ciReference);
  if (!asset) return null;
  try {
    const reference = await readJson(
      api,
      asset,
      scratch("existing-ci-reference.json"),
      CONTRACT.limits.maxCiReferenceBytes,
    );
    return validateCiReference(reference).runId;
  } catch {
    return null;
  }
}

/**
 * Decides, before anything is written, what happens to every existing Android draft:
 * returns `"replace" | "reuse" | null` for this tag's draft (null: none), or throws.
 */
export async function pendingDraftDecision({ api, tag, ciReference, expected, identical, scratch }) {
  const drafts = (await api.allReleases()).filter(
    (r) => r?.draft === true && androidTagVersion(r.tag_name) !== null,
  );
  const other = drafts.find((r) => r.tag_name !== tag);
  if (other)
    throw fail(
      `draft ${other.tag_name} (release ${other.id}) is still pending; sign and publish it, or delete it, before a new release`,
    );
  if (drafts.length > 1) throw fail(`${tag} has several drafts; resolve them by hand`);
  const [draft] = drafts;
  if (!draft) return null;
  if (draft.assets.some((a) => !expected.has(a.name)))
    throw fail(`draft ${tag} is being signed or holds other assets; never replaced automatically`);
  if (identical(draft)) return "reuse";
  if ((await draftRunId(api, draft, scratch)) === ciReference.runId) return "replace";
  throw fail(
    `draft ${tag} (release ${draft.id}) came from another run; sign and publish it, or delete it, first`,
  );
}

/**
 * assets: [{name, file, size, sha256, contentType}] = APK, provenance, CI reference.
 * `scratch(name)` returns a fresh temporary path. Returns {id, state: "created" | "reused"}.
 */
export async function createAndroidDraft({
  api,
  provenance,
  ciReference,
  assets,
  scratch,
  note = "",
  log = () => {},
}) {
  const tag = androidTag(provenance.versionName);
  if (tag !== provenance.tag) throw fail("provenance tag mismatch");
  if (!assets.some((a) => a.name === CONTRACT.assets.provenance)) throw fail("provenance missing");
  validateCiReference(ciReference, provenance);
  const allowed = [
    unsignedApkName(provenance.versionName),
    CONTRACT.assets.provenance,
    CONTRACT.assets.ciReference,
  ];
  if (
    assets.length !== allowed.length ||
    new Set(assets.map((a) => a.name)).size !== allowed.length ||
    assets.some((a) => !allowed.includes(a.name))
  )
    throw fail("draft needs exactly APK, provenance and CI reference");
  const expected = new Set(assets.map((a) => a.name));
  const identical = (draft) =>
    draft.assets.length === assets.length &&
    assets.every((local) =>
      sameAsset(
        draft.assets.find((a) => a.name === local.name),
        local,
      ),
    );
  const decision = await pendingDraftDecision({
    api,
    tag,
    ciReference,
    expected,
    identical,
    scratch,
  });
  const claim = await claimReleaseTag({
    client: api,
    tag,
    commit: provenance.commit,
    // Decided above; a draft that appeared since is refused rather than guessed about.
    draftPolicy: (draft, all) => {
      if (all.length !== 1 || decision === null)
        throw fail(`drafts of ${tag} changed during the claim; rerun`);
      return decision === "replace";
    },
    log,
  });
  if (claim.kept.length) {
    log(`reusing identical draft ${claim.kept[0].id}`);
    return { id: claim.kept[0].id, state: "reused" };
  }
  const draft = await api.request("POST", "/releases", {
    body: {
      tag_name: tag,
      target_commitish: provenance.commit,
      name: `GVM LED Android ${provenance.versionName}`,
      body:
        `Unsigned Android build of ${provenance.commit} (run ${provenance.runNumber}).${note}` +
        ` Sign and publish locally: scripts/owner/publish-release.ps1 -Tag ${tag}.`,
      draft: true,
      prerelease: false,
      make_latest: "false",
    },
  });
  for (const asset of assets) {
    log(`uploading ${asset.name} (${asset.size} bytes)`);
    await api.upload(draft.id, asset);
  }
  const listed = await api.request("GET", `/releases/${draft.id}/assets?per_page=100`);
  if (
    listed.length !== assets.length ||
    !assets.every((a) =>
      sameAsset(
        listed.find((r) => r.name === a.name),
        a,
      ),
    )
  )
    throw fail(`draft ${tag} did not read back exactly; left as a draft for inspection`);
  return { id: draft.id, state: "created" };
}
