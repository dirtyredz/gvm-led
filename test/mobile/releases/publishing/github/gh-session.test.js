import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, readFile, writeFile, access } from "node:fs/promises";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import path from "node:path";
import { Readable } from "node:stream";
import { ghSessionApi } from "../../../../../src/mobile/releases/publishing/github/gh-session.js";

const ok = (stdout = "") => ({ code: 0, stdout, stderr: "" });
const recorder = (...answers) => {
  const calls = [];
  const run = async (spec) => {
    calls.push(spec);
    return answers.shift() ?? ok();
  };
  return { calls, run };
};
const tmp = () => mkdtemp(path.join(tmpdir(), "gh-session-"));

test("requests go through gh api with the body on stdin and no token anywhere", async () => {
  const { calls, run } = recorder(ok('{"id":7}'), ok(), ok("[]"));
  const api = ghSessionApi({ repository: "o/r", run });
  assert.deepEqual(await api.request("POST", "/releases", { body: { draft: true } }), { id: 7 });
  assert.equal(await api.request("DELETE", "/releases/assets/9"), null);
  assert.deepEqual(await api.allReleases(), []);
  const [post, del] = calls;
  assert.equal(post.cmd, "gh");
  assert.equal(post.args[0], "api");
  assert.ok(post.args.includes("--input") && post.args.includes("-"));
  assert.equal(post.args.at(-1), "repos/o/r/releases");
  assert.equal(post.input, JSON.stringify({ draft: true }));
  assert.ok(!del.args.includes("--input") && del.input === undefined);
  for (const call of calls)
    assert.ok(!/auth|token|bearer/i.test(JSON.stringify(call)), "no credential handling");
});

test("failures carry only a status; 404 is null only when allowed; signed-out is explicit", async () => {
  const notFound = { code: 1, stdout: '{"secret":"x"}', stderr: "gh: Not Found (HTTP 404)" };
  const api = (...r) => ghSessionApi({ repository: "o/r", run: recorder(...r).run });
  assert.equal(await api(notFound).request("GET", "/x", { allow404: true }), null);
  const error = await api(notFound)
    .request("GET", "/x")
    .catch((e) => e);
  assert.equal(error.status, 404);
  assert.ok(!error.message.includes("secret"));
  const out = await api({ code: 4, stdout: "", stderr: "gh auth login" })
    .request("GET", "/x")
    .catch((e) => e);
  assert.match(out.message, /not signed in/);
});

test("uploads use the uploads host, the declared content type and the file as the body", async () => {
  const dir = await tmp();
  const file = path.join(dir, "a.apk");
  await writeFile(file, "12345");
  const { calls, run } = recorder(ok('{"id":3}'));
  const api = ghSessionApi({ repository: "o/r", run });
  await api.upload(5, { name: "a b.apk", file, size: 5, contentType: "application/zip" });
  const args = calls[0].args;
  assert.equal(
    args.at(-1),
    "https://uploads.github.com/repos/o/r/releases/5/assets?name=a%20b.apk",
  );
  assert.ok(args.includes("Content-Type: application/zip"));
  assert.equal(args[args.indexOf("--input") + 1], file);
  await assert.rejects(api.upload(5, { name: "a", file, size: 6, contentType: "x" }), /size/);
});

function fakeSpawn({ bytes, code = 0, stderr = "" }) {
  const spawned = [];
  const spawnChild = (cmd, args) => {
    const child = new EventEmitter();
    child.stdout = Readable.from([bytes]);
    child.stderr = Readable.from(stderr ? [stderr] : []);
    child.kill = () => {};
    child.stdout.on("end", () => setImmediate(() => child.emit("close", code)));
    spawned.push({ cmd, args });
    return child;
  };
  return { spawnChild, spawned };
}

test("downloads stream binary to a new file with a hash, and respect the byte bound", async () => {
  const dir = await tmp();
  const bytes = Buffer.from([0, 255, 128, 3, 200, 0]);
  const { spawnChild, spawned } = fakeSpawn({ bytes });
  const api = ghSessionApi({ repository: "o/r", spawnChild });
  const file = path.join(dir, "d.bin");
  const got = await api.downloadAsset(11, file, 100);
  assert.deepEqual(got, { size: 6, sha256: createHash("sha256").update(bytes).digest("hex") });
  assert.deepEqual(await readFile(file), bytes);
  assert.ok(spawned[0].args.includes("Accept: application/octet-stream"));
  assert.equal(spawned[0].args.at(-1), "repos/o/r/releases/assets/11");

  const big = path.join(dir, "big.bin");
  await assert.rejects(ghSessionApi({ spawnChild }).downloadAsset(11, big, 3), /exceeds/);
  await assert.rejects(access(big));
});

test("a failed download is an error with the status and leaves no file", async () => {
  const dir = await tmp();
  const { spawnChild } = fakeSpawn({
    bytes: Buffer.from('{"message":"Not Found"}'),
    code: 1,
    stderr: "gh: Not Found (HTTP 404)",
  });
  const file = path.join(dir, "e.bin");
  const error = await ghSessionApi({ spawnChild })
    .downloadAsset(1, file)
    .catch((e) => e);
  assert.equal(error.status, 404);
  await assert.rejects(access(file));
});

test("every gh call pins github.com, whatever GH_HOST says", async () => {
  const saved = process.env.GH_HOST;
  process.env.GH_HOST = "evil.example.com";
  try {
    const { calls, run } = recorder(ok('{"id":1}'), ok('{"id":2}'));
    const dir = await tmp();
    const file = path.join(dir, "u.bin");
    await writeFile(file, "12345");
    const api = ghSessionApi({
      repository: "o/r",
      run,
      spawnChild: fakeSpawn({ bytes: Buffer.from("x") }).spawnChild,
    });
    await api.request("GET", "/releases/1");
    await api.upload(5, { name: "a", file, size: 5, contentType: "application/zip" });
    const { spawnChild, spawned } = fakeSpawn({ bytes: Buffer.from("x") });
    let streamEnv;
    const spy = (cmd, args, options) => ((streamEnv = options.env), spawnChild(cmd, args, options));
    await ghSessionApi({ repository: "o/r", spawnChild: spy }).downloadAsset(
      3,
      path.join(dir, "d.bin"),
      10,
    );
    for (const args of [...calls.map((c) => c.args), spawned[0].args]) {
      const at = args.indexOf("--hostname");
      assert.ok(at >= 0 && args[at + 1] === "github.com", "pinned hostname: " + args.join(" "));
    }
    assert.equal(streamEnv.GH_HOST, "github.com");
  } finally {
    if (saved === undefined) delete process.env.GH_HOST;
    else process.env.GH_HOST = saved;
  }
});
