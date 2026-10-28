import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { ReadableStream } from "node:stream/web";
import { releaseApi } from "../../../../../src/mobile/releases/publishing/github/gh-client.js";

const tmp = () => mkdtemp(path.join(tmpdir(), "gh-client-"));

test("downloads send the Accept header required by each GitHub route", async () => {
  const calls = [];
  const fetch = async (url, options) => {
    calls.push({ url, options });
    return {
      ok: true,
      status: 200,
      body: ReadableStream.from([Buffer.from("zip")]),
    };
  };
  const api = releaseApi({ token: "test", repository: "o/r", fetch });
  const dir = await tmp();

  await api.downloadAsset(11, path.join(dir, "asset.apk"), 100);
  await api.downloadArtifact(22, path.join(dir, "artifact.zip"), 100);

  assert.equal(calls[0].options.headers.accept, "application/octet-stream");
  assert.equal(calls[0].url, "https://api.github.com/repos/o/r/releases/assets/11");
  assert.equal(calls[1].options.headers.accept, "application/vnd.github+json");
  assert.equal(calls[1].url, "https://api.github.com/repos/o/r/actions/artifacts/22/zip");
});
