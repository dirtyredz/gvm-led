// Publication step 7: re-check monotonicity, delete only the exact unsigned asset,
// confirm the final asset set, publish last and read back. GVM has no Windows release
// line, so (unlike Command Deck's make_latest "false") the signed Android release becomes
// GitHub's latest: install-release.ps1 and the Releases page then point at it.
import { CONTRACT } from "../../release-contract.js";
import { fail, history, readDraft, readJson, tagCommit } from "../draft-state.js";
import { requireMonotonic } from "../policy.js";
import { validateCiReference } from "../ci-reference.js";

export async function finalize(tx, { provenance, state: initialState, ciReference }) {
  const { api, material, id, tag } = tx;
  requireMonotonic(provenance.versionCode, await history(api, material.unique));
  let state = await readDraft(api, tx);
  if (state.ciReference?.id !== initialState.ciReference?.id)
    throw fail("draft CI reference changed");
  if (
    ciReference &&
    JSON.stringify(
      validateCiReference(
        await readJson(
          api,
          state.ciReference,
          material.file("ci-reference-final.json"),
          CONTRACT.limits.maxCiReferenceBytes,
        ),
        provenance,
      ),
    ) !== JSON.stringify(ciReference)
  )
    throw fail("draft CI reference changed");
  if (state.unsigned) await api.request("DELETE", `/releases/assets/${state.unsigned.id}`);
  state = await readDraft(api, tx);
  if (
    state.unsigned ||
    !state.signed ||
    !state.manifest ||
    !state.provenance ||
    state.ciReference?.id !== initialState.ciReference?.id
  )
    throw fail("draft asset set changed");
  if ((await tagCommit(api, tag)) !== tx.commit) throw fail("tag changed before publication");
  await tx.beforePublish?.(state);
  await api.request("PATCH", `/releases/${id}`, { body: { draft: false, make_latest: "true" } });
  const published = await api.request("GET", `/releases/${id}`);
  if (published.draft !== false || published.tag_name !== tag)
    throw fail(`GitHub did not publish ${tag}`);
  const latest = await api.request("GET", "/releases/latest", { allow404: true });
  if (latest?.tag_name !== tag)
    throw fail(`${tag} is published but GitHub's latest is ${latest?.tag_name ?? "unset"}; mark it latest by hand`);
  tx.say(`published ${tag} as GitHub's latest release`);
}
