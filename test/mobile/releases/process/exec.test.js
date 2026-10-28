// INTEGRATION: real child processes (Node doubles only; no keytool, gh, wrangler or 1Password).
import test from "node:test";
import assert from "node:assert/strict";
import {
  runChild,
  childEnv,
  terminateChildren,
} from "../../../../src/mobile/releases/process/exec.js";

const SECRET = "fixture-secret-value-123";
const node = (source, extra = {}) => ({ cmd: process.execPath, args: ["-e", source], ...extra });

test("secrets reach the child through env and stdin, never argv, and come back redacted", async () => {
  const result = await runChild(
    node(
      `let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{process.stdout.write(JSON.stringify({argv:process.argv.slice(1),env:process.env.PASS===${JSON.stringify(SECRET)},stdin:s}));process.stdout.write(" echo:"+process.env.PASS)})`,
      { env: { PASS: SECRET }, input: "stdin-only", secrets: [SECRET] },
    ),
  );
  assert.equal(result.code, 0);
  const parsed = JSON.parse(result.stdout.split(" echo:")[0]);
  assert.equal(parsed.env, true);
  assert.equal(parsed.stdin, "stdin-only");
  assert.ok(!JSON.stringify(parsed.argv).includes(SECRET));
  assert.ok(result.stdout.endsWith(" echo:***"));
});

test("a secret split across output chunks is redacted", async () => {
  const half = SECRET.length / 2;
  const result = await runChild(
    node(
      `process.stdout.write(${JSON.stringify(SECRET.slice(0, half))});setTimeout(()=>{process.stdout.write(${JSON.stringify(SECRET.slice(half))});process.stderr.write(${JSON.stringify(SECRET)})},50)`,
      { secrets: [SECRET] },
    ),
  );
  assert.equal(result.stdout, "***");
  assert.equal(result.stderr, "***");
});

test("captured output is bounded", async () => {
  const result = await runChild(
    node(`process.stdout.write("x".repeat(300000))`, { maxOutput: 1000 }),
  );
  assert.equal(result.stdout.length, 1000);
  assert.equal(result.truncated, true);
});

test("owned children are terminated by cleanup and by the timeout", async () => {
  const started = Date.now();
  const pending = runChild(node("setTimeout(()=>{},60000)"));
  setTimeout(terminateChildren, 200);
  const result = await pending;
  assert.notEqual(result.code, 0);
  assert.ok(Date.now() - started < 20000);
  const timed = await runChild(node("setTimeout(()=>{},60000)", { timeoutMs: 300 }));
  assert.notEqual(timed.code, 0);
});

test("an unknown executable fails with its name only", async () => {
  await assert.rejects(
    runChild({ cmd: "gvm-led-missing-tool-xyz", args: [SECRET] }),
    (error) => {
      assert.equal(error.message, "could not start gvm-led-missing-tool-xyz");
      return true;
    },
  );
});

test("non-signing children never inherit the injected signing variables; signers get the password explicitly", async () => {
  const { PASS_ENV } =
    await import("../../../../src/mobile/releases/signing/keystore.js");
  const saved = [process.env[PASS_ENV], process.env.GH_HOST];
  process.env[PASS_ENV] = "injected-password-value";
  process.env.GH_HOST = "evil.example.com";
  try {
    for (const cmd of ["gh", "zipalign", "aapt2"]) {
      const env = childEnv(cmd);
      assert.ok(!(PASS_ENV in env), cmd);
    }
    for (const cmd of ["keytool", "apksigner"])
      assert.equal(childEnv(cmd, { [PASS_ENV]: "explicit" })[PASS_ENV], "explicit");
    assert.equal(childEnv("gh").GH_HOST, "github.com", "hostile GH_HOST is overridden for gh");
    const real = await runChild(
      node("process.stdout.write(String(process.env.GVM_ANDROID_KEYSTORE_PASSWORD))"),
    );
    assert.equal(real.stdout, "undefined");
  } finally {
    for (const [name, v] of [
      [PASS_ENV, saved[0]],
      ["GH_HOST", saved[1]],
    ])
      if (v === undefined) delete process.env[name];
      else process.env[name] = v;
  }
});
