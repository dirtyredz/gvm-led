// Owner-PC entry for the GVM LED Android release key and local signing:
//   node scripts/mobile/secrets.js <command> [--dry-run] [options]
//     generate-key                                     create the key (vault created if absent)
//     verify-key                                       recover the key and check it against the pin
//     release-sign --tag android-v<version> --release-id <id>   sign + publish one CI draft
// The owner runs these through scripts/owner/{new-signing-key,publish-release}.ps1.
// Opens one 1Password SDK client per process (one Windows Hello approval). --dry-run
// resolves nothing and never opens 1Password; agents run --dry-run only. Errors carry
// names, stages and exit codes only, never values.
// Ported from command-deck scripts/mobile/secrets.js (GitHub/Worker pushes, signing-vault
// copy and the MCP env signing mode omitted: GVM has none of them).
import path from "node:path";
import { existsSync } from "node:fs";
import { parseArgs } from "node:util";
import { fileURLToPath } from "node:url";
import { COMMANDS, dryRunLines } from "../../src/mobile/releases/secrets/plan.js";
import { openVault, vaultSigningMaterial } from "../../src/mobile/releases/secrets/vault.js";
import { runChild, terminateChildren } from "../../src/mobile/releases/process/exec.js";
import { generateKey, verifyKey } from "../../src/mobile/releases/signing/key-generation.js";
import { createMaterial } from "../../src/mobile/releases/signing/material.js";
import { releaseSignCommand } from "../../src/mobile/releases/publishing/command.js";

const root = fileURLToPath(new URL("../../", import.meta.url));

function javaTool(name) {
  const exe = process.platform === "win32" ? `${name}.exe` : name;
  const home = process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, "bin", exe);
  return home && existsSync(home) ? home : exe;
}

export async function main(argv = process.argv.slice(2), say = (line) => console.log(line)) {
  const { values, positionals } = parseArgs({
    args: argv,
    allowPositionals: true,
    strict: true,
    options: {
      "dry-run": { type: "boolean" },
      tag: { type: "string" },
      "release-id": { type: "string" },
    },
  });
  const [command, ...extra] = positionals;
  if (!COMMANDS.includes(command) || extra.length) {
    say(`usage: secrets <${COMMANDS.join("|")}> [--dry-run] (see scripts/mobile/secrets.js)`);
    return 2;
  }
  const releaseId = values["release-id"];
  if (values["dry-run"]) {
    for (const line of dryRunLines(command, { tag: values.tag, releaseId })) say(line);
    return 0;
  }
  // release-sign opens 1Password lazily, at the sign step, so a wrong checkout or draft
  // stops before any Windows Hello prompt; the key commands need it immediately.
  const vault = command === "release-sign" ? null : await openVault({ create: command === "generate-key" });
  let signingVault;
  const lazySigning = {
    password: async () => vaultSigningMaterial((signingVault ??= await openVault())).password(),
    keystore: async () => vaultSigningMaterial((signingVault ??= await openVault())).keystore(),
  };
  const material = () => createMaterial({ root, terminate: terminateChildren });
  const certificateFile = path.join(root, "src/mobile/releases/signing-certificate.json");
  const deps = {
    vault,
    run: runChild,
    say,
    material,
    certificateFile,
    keytool: javaTool("keytool"),
  };
  if (command === "generate-key") {
    const identity = await generateKey(deps);
    say(JSON.stringify(identity, null, 2));
    say("Commit src/mobile/releases/signing-certificate.json (public pin only).");
  } else if (command === "verify-key") say(JSON.stringify(await verifyKey(deps), null, 2));
  else
    await releaseSignCommand(
      { ...deps, signing: lazySigning, root, java: javaTool("java") },
      { tag: values.tag, releaseId },
    );
  return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(
    (code) => {
      process.exitCode = code;
    },
    (error) => {
      terminateChildren();
      // Messages are built from item titles, public IDs, stages and exit codes, never values.
      console.error(`error: ${error?.message ?? "secrets command failed"}`);
      process.exitCode = 1;
    },
  );
}
