// CI preparation of an unsigned Android release: stage the unsigned APK under its
// contract name beside public provenance binding repository, workflow, run, commit,
// allocated versions, contract digest and the unsigned bytes. Signing happens later,
// locally (publishing/publish.js); nothing here ever touches a key.
import path from "node:path";
import { constants } from "node:fs";
import { mkdir, copyFile, writeFile, readFile, readdir, lstat } from "node:fs/promises";
import { describeAsset } from "./publishing/assets.js";
import { CONTRACT, trustedCertificate, unsignedApkName } from "./release-contract.js";
import { phoneVersionOf } from "./manifest.js";
import { buildProvenance, validateProvenance } from "./publishing/policy.js";
import { requireUnsignedBuild } from "./signing/apk-inspection.js";

/**
 * `badging` and `signed` come from aapt2 and apksigner against `apk`. The APK must be the
 * permanent package at the allocated versions, a release build, and actually unsigned.
 */
export async function prepareUnsigned({
  apk,
  out,
  release,
  badging,
  signed,
  pin = trustedCertificate(),
}) {
  const version = phoneVersionOf(release.version);
  if (release.repository !== CONTRACT.repository || release.channel !== CONTRACT.channel)
    throw new Error("Wrong phone release identity");
  // The provenance binds the contract digest, which includes the pin: a draft prepared
  // before the key exists could never be signed, so refuse it here.
  if (!pin)
    throw new Error("No signing certificate is pinned; run scripts/owner/new-signing-key.ps1 first");
  requireUnsignedBuild(badging, { ...version, packageName: CONTRACT.packageName });
  if (signed !== false) throw new Error("CI must produce an unsigned APK");
  await mkdir(out, { recursive: true });
  const file = path.join(out, unsignedApkName(version.versionName));
  await copyFile(apk, file, constants.COPYFILE_EXCL);
  const unsignedApk = await describeAsset(file);
  const provenance = buildProvenance(release, unsignedApk);
  const provenanceFile = path.join(out, CONTRACT.assets.provenance);
  await writeFile(provenanceFile, JSON.stringify(provenance, null, 2) + "\n", { flag: "wx" });
  return {
    provenance,
    assets: [
      { ...unsignedApk, file, contentType: "application/vnd.android.package-archive" },
      {
        ...(await describeAsset(provenanceFile)),
        file: provenanceFile,
        contentType: "application/json",
      },
    ],
  };
}

/** Consume the already prepared exact build artifact without rewriting provenance
 * under the draft job's (possibly newer) run attempt. Only two ordinary files. */
export async function readPreparedUnsigned({ directory, release, artifactAttempt }) {
  const version = phoneVersionOf(release.version);
  const apkName = unsignedApkName(version.versionName);
  const expected = [apkName, CONTRACT.assets.provenance].sort();
  if ((await readdir(directory)).sort().join("\n") !== expected.join("\n"))
    throw new Error("Prepared Android artifact must contain exactly APK and provenance");
  for (const name of expected) {
    const stat = await lstat(path.join(directory, name));
    const limit =
      name === apkName ? CONTRACT.limits.maxApkBytes : CONTRACT.limits.maxProvenanceBytes;
    if (
      !stat.isFile() ||
      stat.isSymbolicLink() ||
      stat.nlink !== 1 ||
      stat.size < 1 ||
      stat.size > limit
    )
      throw new Error("Prepared Android artifact file refused");
  }
  const provenanceFile = path.join(directory, CONTRACT.assets.provenance);
  const provenance = validateProvenance(JSON.parse(await readFile(provenanceFile, "utf8")));
  if (
    release.repository !== provenance.repository ||
    release.channel !== CONTRACT.channel ||
    release.commit !== provenance.commit ||
    release.version !== provenance.versionName ||
    release.build.workflow !== provenance.workflow ||
    release.build.runId !== provenance.runId ||
    release.build.runNumber !== provenance.runNumber ||
    provenance.runAttempt !== artifactAttempt ||
    artifactAttempt > release.build.runAttempt
  )
    throw new Error("Prepared Android artifact does not match its producing attempt");
  const file = path.join(directory, apkName),
    apk = await describeAsset(file);
  if (apk.size !== provenance.unsignedApk.size || apk.sha256 !== provenance.unsignedApk.sha256)
    throw new Error("Prepared Android APK differs from provenance");
  return {
    provenance,
    assets: [
      { ...apk, file, contentType: "application/vnd.android.package-archive" },
      {
        ...(await describeAsset(provenanceFile)),
        file: provenanceFile,
        contentType: "application/json",
      },
    ],
  };
}
