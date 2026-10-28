// Publication step 6: upload the signed APK (unless resuming) and the manifest, then
// re-download BOTH and compare size, SHA-256 and content. An already-uploaded manifest
// must equal ours exactly; a conflict is never overwritten. The draft is re-read
// before every write so a concurrent signer stops this run.
import { writeFile } from "node:fs/promises";
import { describeAsset } from "../assets.js";
import { CONTRACT } from "../../release-contract.js";
import { validatePhoneManifest } from "../../manifest.js";
import { fail, readDraft, readJson } from "../draft-state.js";

const MAX_MANIFEST = CONTRACT.limits.maxManifestBytes;

/** Returns the draft state after both assets read back exactly. */
export async function uploadAndVerify(tx, { manifest, signedFile, resumed, state }) {
  const { api, material, id, names, pin } = tx;
  if (!resumed) {
    state = await readDraft(api, tx);
    if (state.signed) throw fail("another signer uploaded concurrently; stopping");
    await api.upload(id, {
      name: names.signed,
      file: signedFile,
      size: manifest.apk.size,
      contentType: "application/vnd.android.package-archive",
    });
    state = await readDraft(api, tx);
  }

  const manifestFile = material.file("phone-release.local.json");
  await writeFile(manifestFile, JSON.stringify(manifest, null, 2) + "\n");
  const local = await describeAsset(manifestFile);
  if (state.manifest) {
    const remote = await readJson(
      api,
      state.manifest,
      material.file("manifest-remote.json"),
      MAX_MANIFEST,
    );
    if (JSON.stringify(remote) !== JSON.stringify(manifest))
      throw fail("the uploaded manifest conflicts; resolve by hand");
  } else {
    await api.upload(id, {
      name: CONTRACT.assets.manifest,
      file: manifestFile,
      size: local.size,
      contentType: "application/json",
    });
  }

  state = await readDraft(api, tx);
  const apkBack = await api.downloadAsset(state.signed.id, material.file("readback.apk"));
  if (apkBack.size !== manifest.apk.size || apkBack.sha256 !== manifest.apk.sha256)
    throw fail("the uploaded APK read back different bytes; release left as a draft");
  const manifestBack = await readJson(
    api,
    state.manifest,
    material.file("readback.json"),
    MAX_MANIFEST,
  );
  if (
    JSON.stringify(validatePhoneManifest(manifestBack, { certificateSha256: pin })) !==
    JSON.stringify(manifest)
  )
    throw fail("the uploaded manifest read back different content; release left as a draft");
  tx.say("uploaded APK and manifest read back exactly");
  return state;
}
