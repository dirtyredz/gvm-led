// Publication steps 4-5: the signed APK and the manifest of its final bytes. A fresh
// run inspects the unsigned CI build, resolves signing material through the signing
// source and signs; a resumed run (signed APK already uploaded) verifies that
// APK is a valid pinned signing of this build and never re-signs over it.
import { writeFile } from "node:fs/promises";
import { describeAsset } from "../assets.js";
import { CONTRACT, apkName } from "../../release-contract.js";
import { validatePhoneManifest } from "../../manifest.js";
import { mustRun } from "../../process/exec.js";
import { KEYSTORE_FILE } from "../../signing/keystore.js";
import {
  badgingCommand,
  parseBadging,
  requireUnsignedBuild,
  verifyCommand,
} from "../../signing/apk-inspection.js";
import { inspectSigned, signApk } from "../../signing/sign.js";
import { fail } from "../draft-state.js";

/** Returns {manifest, signedFile, resumed}; the signed APK is local at `signedFile`. */
export async function signRelease(tx, { state, provenance, expected }) {
  const { api, material, names, pin } = tx;
  const signedFile = material.file(names.signed);
  if (state.signed) {
    tx.say(`resuming: verifying the uploaded ${names.signed}`);
    await api.downloadAsset(state.signed.id, signedFile);
    let badging;
    try {
      badging = await inspectSigned({
        run: tx.run,
        tools: tx.tools,
        apk: signedFile,
        pin,
        expected: { ...expected, minSdk: undefined },
      });
    } catch {
      throw fail(
        `the uploaded ${names.signed} is not a valid pinned signing of this build; resolve by hand`,
      );
    }
    const manifest = buildManifest(provenance, badging, await describeAsset(signedFile), pin);
    return { manifest, signedFile, resumed: true };
  }

  if (!state.unsigned) throw fail("draft has neither the unsigned nor a signed APK");
  const unsignedFile = tx.preparedCandidate?.apk ?? material.file(names.unsigned);
  const got = tx.preparedCandidate
    ? await describeAsset(unsignedFile)
    : await api.downloadAsset(state.unsigned.id, unsignedFile);
  if (got.size !== provenance.unsignedApk.size || got.sha256 !== provenance.unsignedApk.sha256)
    throw fail("the unsigned APK differs from its CI provenance");
  const badging = requireUnsignedBuild(
    parseBadging(
      (await mustRun(badgingCommand(tx.tools, unsignedFile), "aapt2 badging", tx.run)).stdout,
    ),
    expected,
  );
  if ((await tx.run(verifyCommand(tx.tools, unsignedFile))).code === 0)
    throw fail("the CI APK is already signed");

  // The unattended caller repeats CURRENT registry/durable/source admission here,
  // immediately before lazy token decryption and SDK creation.
  await tx.beforeSecret?.();
  const password = await tx.signing.password();
  const keystore = material.file(KEYSTORE_FILE);
  await writeFile(keystore, await tx.signing.keystore(), { flag: "wx" });
  tx.say("resolved the release key from 1Password; signing");
  const signed = await signApk({
    run: tx.run,
    tools: tx.tools,
    keytool: tx.keytool,
    keystore,
    password,
    pin,
    unsigned: unsignedFile,
    aligned: material.file("aligned.apk"),
    output: signedFile,
    expected: { ...expected, minSdk: badging.minSdk },
  });
  const manifest = buildManifest(provenance, signed, await describeAsset(signedFile), pin);
  return { manifest, signedFile, resumed: false };
}

function buildManifest(provenance, badging, asset, pin) {
  if (asset.name !== apkName(provenance.versionName)) throw fail("signed APK name mismatch");
  return validatePhoneManifest(
    {
      schema: CONTRACT.schema,
      repository: CONTRACT.repository,
      channel: CONTRACT.channel,
      packageName: CONTRACT.packageName,
      versionName: provenance.versionName,
      versionCode: provenance.versionCode,
      minSdk: badging.minSdk,
      commit: provenance.commit,
      certificateSha256: pin,
      apk: { name: asset.name, size: asset.size, sha256: asset.sha256 },
    },
    { certificateSha256: pin },
  );
}
