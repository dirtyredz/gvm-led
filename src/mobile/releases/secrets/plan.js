// Public plan for GVM LED Android release credentials: the 1Password vault and items,
// and the exact external commands. No I/O and no secret values: everything here is safe
// to print (--dry-run) and to unit test. Release-credential tooling only.
// Ported from command-deck src/mobile/releases/secrets/plan.js; its GitHub-environment,
// Worker and signing-vault plans are omitted (GVM has no build secrets, Worker or
// unattended signer).
import { CONTRACT } from "../release-contract.js";
import { KEYSTORE_FILE, PASS_ENV, keytoolExport, keytoolGenerate } from "../signing/keystore.js";

/** The owner's 1Password account (the same account as Command Deck; a separate vault). */
export const ACCOUNT_ID = "ETCB7LGCDNEP7PUD3FDNS5JXBU";
/** GVM's own vault: never Command Deck's, so neither project can read the other's key. */
export const VAULT = "GVM-LED";
export const INTEGRATION = "gvm-led-release-secrets";
export const REPOSITORY = CONTRACT.repository;
export const COMMANDS = Object.freeze(["generate-key", "verify-key", "release-sign"]);

/** The durable items. References and titles are public; values are not. */
export const ITEMS = Object.freeze({
  keystore: { title: "gvm-led-android-release-keystore", kind: "document", file: KEYSTORE_FILE },
  password: {
    title: "gvm-led-android-release-password",
    kind: "password",
    ref: `op://${VAULT}/gvm-led-android-release-password/password`,
  },
});

export const describeSource = (key) => {
  const item = ITEMS[key];
  return item.kind === "document" ? `document ${VAULT}/${item.title}` : item.ref;
};

const show = (spec) => `${spec.cmd} ${spec.args.join(" ")}`;
const refuse = (message) => Object.assign(new Error(message), { code: "SECRETS_REFUSED" });

/** Human-readable plan. Resolves nothing and never opens 1Password. */
export function dryRunLines(command, { tag, releaseId } = {}) {
  if (command === "generate-key")
    return [
      "would refuse if src/mobile/releases/signing-certificate.json already pins a certificate",
      `would open one 1Password SDK client (account ${ACCOUNT_ID}; one Windows Hello approval)`,
      `would create the vault ${VAULT} if it does not exist (never Command Deck's vault)`,
      `would refuse if ${VAULT} already holds ${ITEMS.keystore.title} or ${ITEMS.password.title}`,
      "would create an owner-only temporary directory outside the checkout",
      `would generate a random password and run: ${show(keytoolGenerate({ keytool: "keytool", keystore: `<tmp>/${KEYSTORE_FILE}` }))}`,
      `would store the password as ${ITEMS.password.ref} and the keystore as ${describeSource("keystore")}`,
      "would read both back by item ID and compare SHA-256, then inspect the public certificate",
      "would record only the public fingerprint and expiry in signing-certificate.json (commit it)",
      "would delete the temporary directory afterwards (finally and on SIGINT/SIGTERM)",
    ];
  if (command === "verify-key")
    return [
      `would read ${ITEMS.password.ref} and ${describeSource("keystore")}`,
      `would run: ${show(keytoolExport({ keytool: "keytool", keystore: `<tmp>/${KEYSTORE_FILE}` }))}`,
      "would compare the certificate fingerprint with signing-certificate.json and print public metadata only",
    ];
  if (command === "release-sign")
    return [
      `would sign draft ${tag ?? "<--tag android-v...>"} (release ${releaseId ?? "<--release-id>"}) in ${REPOSITORY}`,
      "would require a clean checkout at the tag's commit and the approved contract and pin",
      "would authenticate the Actions run and exact artifact, match its provenance and APK to the draft, then inspect the unsigned APK",
      `would read ${ITEMS.password.ref} and ${describeSource("keystore")} through one SDK client`,
      `would run zipalign -p -f 4, then apksigner sign --ks-pass env:${PASS_ENV} (never argv)`,
      "would verify alignment, exactly one pinned signer, package, versions, minSdk, non-debuggable",
      "would upload the signed APK and manifest, re-download both and compare size and SHA-256",
      "would delete only the exact unsigned asset, publish as GitHub's latest release and read back",
    ];
  throw refuse(`unknown command: ${command}`);
}
