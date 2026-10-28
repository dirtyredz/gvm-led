import test from "node:test";
import assert from "node:assert/strict";
import { main } from "../../../../scripts/mobile/secrets.js";

const run = async (argv) => {
  const lines = [];
  const code = await main(argv, (line) => lines.push(line));
  return { code, text: lines.join("\n"), lines };
};

test("each command's --dry-run exits 0 with GVM-LED wording and never names the CommandDeck vault or env", async () => {
  for (const argv of [
    ["generate-key", "--dry-run"],
    ["verify-key", "--dry-run"],
    ["release-sign", "--dry-run", "--tag", "android-v0.1.5", "--release-id", "9"],
    ["release-sign", "--dry-run"],
  ]) {
    const { code, text, lines } = await run(argv);
    assert.equal(code, 0, argv.join(" "));
    assert.ok(lines.length > 1);
    assert.match(text, /GVM-LED|gvm-led/, argv.join(" "));
    assert.doesNotMatch(text, /CommandDeck|COMMAND_DECK|command-deck/, argv.join(" "));
  }
});

test("a dry run names the GVM vault and the supplied draft, and opens nothing", async () => {
  const keys = await run(["generate-key", "--dry-run"]);
  assert.match(keys.text, /vault GVM-LED/);
  const sign = await run(["release-sign", "--dry-run", "--tag", "android-v0.1.5", "--release-id", "9"]);
  assert.match(sign.text, /android-v0\.1\.5.*release 9.*dirtyredz\/gvm-led/);
  assert.match(sign.text, /authenticate the Actions run and exact artifact/);
  // Reaching here without a 1Password SDK, Windows Hello or a child process is the point:
  // dry-run returns before openVault, so nothing could have been opened.
});

test("unknown commands and extra arguments print usage and exit 2", async () => {
  for (const argv of [["nope"], [], ["verify-key", "extra"], ["push-github", "--dry-run"]]) {
    const { code, text } = await run(argv);
    assert.equal(code, 2, argv.join(" "));
    assert.match(text, /usage: secrets <generate-key\|verify-key\|release-sign>/);
  }
});
