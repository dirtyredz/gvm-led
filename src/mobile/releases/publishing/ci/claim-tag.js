// Claiming a release tag for a new draft attempt (CI draft and resume; ported from
// command-deck src/process/releases/publishing/publication/claim-tag.js). A version number is reused when an earlier attempt
// failed before publishing, so the tag and drafts it left behind may be
// replaced; a published release never is. The one operation:
//
//   1. refuses if a PUBLISHED release uses the tag (the guard lives here, so no
//      caller can forget it);
//   2. asks `draftPolicy(draft, allDraftsOfTheTag)` about every draft holding the tag, before touching
//      anything: true = replace it, false = keep it (the caller reuses it),
//      throw = refuse the whole claim;
//   3. creates the tag at `commit`, reuses it when it already names that commit,
//      or force-moves a leftover one;
//   4. deletes the drafts the policy marked replaceable.
//
// `client.request(method, route, {allow404, body})` is gh-client.js's releaseApi
// (a fake in tests). Dependency-free.

const fail = (code, message) => Object.assign(new Error(message), { code });

/**
 * Returns `{tag: "created" | "reused" | "moved", kept: [draft], removed: [id]}`.
 * `draftPolicy(draft, drafts)` defaults to replacing every draft.
 */
export async function claimReleaseTag({
  client,
  tag,
  commit,
  draftPolicy = () => true,
  log = () => {},
}) {
  if (await client.request("GET", `/releases/tags/${tag}`, { allow404: true }))
    throw fail("RELEASE_TAG_CONFLICT", `${tag} is already published; refusing to touch its tag`);
  const releases = await client.request("GET", "/releases?per_page=100");
  const drafts = releases.filter((r) => r.draft && r.tag_name === tag);
  const replace = [];
  const kept = [];
  for (const draft of drafts) (draftPolicy(draft, drafts) ? replace : kept).push(draft);

  const state = await pinTag(client, tag, commit);
  log(`tag ${tag}: ${state} at ${commit.slice(0, 12)}`);
  for (const stale of replace) {
    log(`removing stale draft ${stale.id}`);
    await client.request("DELETE", `/releases/${stale.id}`);
  }
  return { tag: state, kept, removed: replace.map((d) => d.id) };
}

async function pinTag(client, tag, commit) {
  const ref = await client.request("GET", `/git/ref/tags/${tag}`, { allow404: true });
  if (!ref) {
    await client.request("POST", "/git/refs", { body: { ref: `refs/tags/${tag}`, sha: commit } });
    return "created";
  }
  if (ref.object?.type === "commit" && ref.object.sha === commit) return "reused";
  await client.request("PATCH", `/git/refs/tags/${tag}`, { body: { sha: commit, force: true } });
  return "moved";
}
