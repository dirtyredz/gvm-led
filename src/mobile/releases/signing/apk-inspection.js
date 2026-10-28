// APK inspection from Android build-tools output (extracted from scripts/mobile/release.js):
// aapt2 badging for package, versions, minSdk and debuggable state; apksigner verify for
// signers. Parsers are pure; the command specs run through process/exec.js.
import path from "node:path";

/** Build-tools executables. apksigner runs as `java -jar` (no .bat shell on Windows). */
export function buildTools(dir, { java = "java", platform = process.platform } = {}) {
  const exe = (name) => path.join(dir, platform === "win32" ? `${name}.exe` : name);
  return {
    zipalign: exe("zipalign"),
    aapt2: exe("aapt2"),
    java,
    apksignerJar: path.join(dir, "lib", "apksigner.jar"),
  };
}

export const badgingCommand = (tools, apk) => ({
  cmd: tools.aapt2,
  args: ["dump", "badging", apk],
});
export const verifyCommand = (tools, apk) => ({
  cmd: tools.java,
  args: ["-jar", tools.apksignerJar, "verify", "--verbose", "--print-certs", apk],
});

/**
 * `{packageName, versionCode, versionName, minSdk, debuggable}` from aapt2 (`minSdkVersion:'N'`)
 * or legacy aapt (`sdkVersion:'N'`) badging; minSdkVersion wins when both appear.
 */
export function parseBadging(text) {
  const pkg = /^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m.exec(text);
  if (!pkg) throw new Error("badging output has no package line");
  const sdk = /^minSdkVersion:'(\d+)'/m.exec(text) ?? /^sdkVersion:'(\d+)'/m.exec(text);
  if (!sdk) throw new Error("badging output has no minSdkVersion or sdkVersion line");
  return {
    packageName: pkg[1],
    versionCode: Number(pkg[2]),
    versionName: pkg[3],
    minSdk: Number(sdk[1]),
    debuggable: /^application-debuggable/m.test(text),
  };
}

/** `{signers, sha256: [..], schemes}` from apksigner verify --verbose --print-certs. */
export function parseSigners(text) {
  const count = /^Number of signers: (\d+)$/m.exec(text);
  const sha256 = [
    ...text.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$/gm),
  ].map((m) => m[1]);
  const schemes = [
    ...text.matchAll(/^Verified using (v[\d.]+) scheme \(APK Signature Scheme v[\d.]+\): true$/gm),
  ].map((m) => m[1]);
  return { signers: count ? Number(count[1]) : 0, sha256, schemes };
}

/** The unsigned APK CI produced: permanent package, allocated versions, release build. */
export function requireUnsignedBuild(badging, { versionName, versionCode, packageName }) {
  if (
    badging.packageName !== packageName ||
    badging.versionCode !== versionCode ||
    badging.versionName !== versionName ||
    badging.debuggable ||
    badging.minSdk < 26
  )
    throw new Error("unsigned APK package, version, minSdk or debuggable state is wrong");
  return badging;
}

/** Exactly one signer, equal to the pinned certificate, with a modern signature scheme. */
export function requireSingleSigner(signers, pin) {
  if (
    signers.signers !== 1 ||
    signers.sha256.length !== 1 ||
    signers.sha256[0] !== pin ||
    !signers.schemes.some((s) => s === "v2" || s === "v3" || s === "v3.1")
  )
    throw new Error(
      "signed APK must have exactly one signer, the pinned certificate, with v2+ signing",
    );
  return signers;
}
