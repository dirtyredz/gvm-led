// The read side of local Android publication: what GitHub says about the tag, the
// draft and published history. Every read is exact and refuses to guess; nothing
// here writes to GitHub. Failures carry code ANDROID_PUBLISH.
import { readFile } from "node:fs/promises";
import { CONTRACT } from "../release-contract.js";
import { publishedVersionCodes } from "./policy.js";

export const fail = (message) => Object.assign(new Error(message), { code: "ANDROID_PUBLISH" });

/** The commit a lightweight or annotated tag points at. */
export async function tagCommit(api, tag) {
  const ref = await api.request("GET", `/git/ref/tags/${tag}`, { allow404: true });
  if (!ref) throw fail(`tag ${tag} does not exist`);
  if (ref.object?.type === "commit") return ref.object.sha;
  if (ref.object?.type === "tag") {
    const annotated = await api.request("GET", `/git/tags/${ref.object.sha}`);
    if (annotated.object?.type === "commit") return annotated.object.sha;
  }
  throw fail(`tag ${tag} does not resolve to a commit`);
}

/** Downloads a bounded JSON asset to `file` and parses it. */
export async function readJson(api, asset, file, maxBytes) {
  if (!asset || asset.size > maxBytes)
    throw fail(`${asset?.name ?? "asset"} is missing or too large`);
  await api.downloadAsset(asset.id, file, maxBytes);
  return JSON.parse(await readFile(file, "utf8"));
}

/**
 * versionCodes of every published Android release. `scratch(name)` must return a fresh
 * path per call: downloads open with "wx" and history is read more than once per run.
 */
export async function history(api, scratch) {
  const releases = await api.allReleases();
  return publishedVersionCodes(releases, async (release) => {
    const manifests = release.assets.filter((a) => a.name === CONTRACT.assets.manifest);
    if (manifests.length !== 1) throw new Error("no manifest");
    return readJson(api, manifests[0], scratch("published.json"), CONTRACT.limits.maxManifestBytes);
  });
}

/** The draft's assets by role; any unexpected or incomplete asset stops the run. */
export function draftState(release, names) {
  const assets = new Map(release.assets.map((a) => [a.name, a]));
  if (assets.size !== release.assets.length || assets.size > CONTRACT.limits.maxAssetsPerRelease)
    throw fail("draft holds duplicate or excessive assets");
  for (const name of assets.keys())
    if (!Object.values(names).includes(name))
      throw fail(`draft holds an unexpected asset ${name}; resolve it by hand`);
  for (const asset of assets.values())
    if (asset.state !== "uploaded")
      throw fail(`asset ${asset.name} is incomplete; delete that exact asset by hand`);
  return {
    unsigned: assets.get(names.unsigned),
    provenance: assets.get(names.provenance),
    ciReference: assets.get(names.ciReference),
    signed: assets.get(names.signed),
    manifest: assets.get(names.manifest),
  };
}

/** Re-reads release `id` and requires it to still be the expected draft. */
export async function readDraft(api, { id, tag, commit, names }) {
  const release = await api.request("GET", `/releases/${id}`);
  if (
    release?.id !== id ||
    release.draft !== true ||
    release.prerelease !== false ||
    release.tag_name !== tag ||
    release.target_commitish !== commit
  )
    throw fail(`release ${id} is not the draft ${tag} of ${commit.slice(0, 12)}`);
  return draftState(release, names);
}
