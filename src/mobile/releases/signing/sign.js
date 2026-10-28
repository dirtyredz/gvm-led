// Signing orchestration for one APK, inside temporary material: check the keystore's
// certificate against the pin BEFORE signing, zipalign, apksigner with `env:` password
// references, then verify alignment, exactly one pinned signer and the badging identity.
import { mustRun } from "../process/exec.js";
import { KEY_ALIAS, PASS_ENV, keytoolExport } from "./keystore.js";
import { inspectCertificate } from "./certificate.js";
import {
  badgingCommand,
  parseBadging,
  parseSigners,
  requireSingleSigner,
  verifyCommand,
} from "./apk-inspection.js";

export const zipalignCommand = (tools, input, output) => ({
  cmd: tools.zipalign,
  args: ["-p", "-f", "4", input, output],
});
export const zipalignCheck = (tools, apk) => ({
  cmd: tools.zipalign,
  args: ["-c", "-p", "4", apk],
});
export const signCommand = (tools, keystore, input, output) => ({
  cmd: tools.java,
  args: [
    "-jar",
    tools.apksignerJar,
    "sign",
    "--ks",
    keystore,
    "--ks-type",
    "PKCS12",
    "--ks-key-alias",
    KEY_ALIAS,
    "--ks-pass",
    `env:${PASS_ENV}`,
    "--key-pass",
    `env:${PASS_ENV}`,
    "--out",
    output,
    input,
  ],
});

/** The pinned certificate must be the keystore's before anything is signed. */
export async function requireKeystorePin({ run, keytool, keystore, password, pin }) {
  const exported = await mustRun(
    { ...keytoolExport({ keytool, keystore }), env: { [PASS_ENV]: password }, secrets: [password] },
    "keytool -exportcert",
    run,
  );
  if (inspectCertificate(exported.stdout).sha256 !== pin)
    throw new Error("the 1Password keystore is not the pinned release certificate");
}

/** Full inspection of a signed APK; returns its badging. `expected.minSdk` undefined accepts any minSdk >= 26. */
export async function inspectSigned({ run, tools, apk, pin, expected }) {
  await mustRun(zipalignCheck(tools, apk), "zipalign -c", run);
  const verified = await mustRun(verifyCommand(tools, apk), "apksigner verify", run);
  requireSingleSigner(parseSigners(verified.stdout), pin);
  const badging = parseBadging(
    (await mustRun(badgingCommand(tools, apk), "aapt2 badging", run)).stdout,
  );
  if (
    badging.packageName !== expected.packageName ||
    badging.versionCode !== expected.versionCode ||
    badging.versionName !== expected.versionName ||
    (expected.minSdk !== undefined && badging.minSdk !== expected.minSdk) ||
    badging.minSdk < 26 ||
    badging.debuggable
  )
    throw new Error("signed APK identity differs from the unsigned build");
  return badging;
}

/** Signs `unsigned` into `output`. The password exists only in the child's environment. */
export async function signApk({
  run,
  tools,
  keytool,
  keystore,
  password,
  pin,
  unsigned,
  aligned,
  output,
  expected,
}) {
  await requireKeystorePin({ run, keytool, keystore, password, pin });
  await mustRun(zipalignCommand(tools, unsigned, aligned), "zipalign", run);
  await mustRun(
    {
      ...signCommand(tools, keystore, aligned, output),
      env: { [PASS_ENV]: password },
      secrets: [password],
    },
    "apksigner sign",
    run,
  );
  return inspectSigned({ run, tools, apk: output, pin, expected });
}
