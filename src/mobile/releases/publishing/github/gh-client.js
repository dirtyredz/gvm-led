// GitHub REST access for Android releases in CI: the job's GITHUB_TOKEN (from the
// environment, never a child's output) for allocation, drafts and resume evidence. JSON
// requests and asset uploads with deadlines, bounded downloads and bounded pagination.
// The owner's local signing session goes through gh-session.js instead, which never holds
// a token. `fetch` is injectable so tests run against a fake API.
// Ported from command-deck src/mobile/releases/publishing/gh-client.js with its
// src/process/releases/publishing/github.js transport folded in.
import { createReadStream, createWriteStream } from "node:fs";
import { createHash } from "node:crypto";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { CONTRACT } from "../../release-contract.js";

const API = "https://api.github.com";
const UPLOADS = "https://uploads.github.com";
const API_VERSION = "2022-11-28";
const MAX_PAGES = 20;

const fail = (message, status) =>
  Object.assign(new Error(message), { code: "RELEASE_GITHUB", status });

/** Every release (drafts included for a write token), newest first; exhausting the bound is an error. */
export async function listAllReleases(request, pageSize = 100) {
  const out = [];
  for (let page = 1; page <= MAX_PAGES; page++) {
    const list = await request("GET", `/releases?per_page=${pageSize}&page=${page}`);
    if (!Array.isArray(list)) throw new Error("GitHub release list is malformed");
    out.push(...list);
    if (list.length < pageSize) return out;
  }
  throw new Error("GitHub release history exceeds the pagination bound");
}

/** Streams `source` to a new `file` (at most maxBytes); returns {size, sha256}. */
export async function meterToFile(source, file, assetId, maxBytes) {
  const hash = createHash("sha256");
  let size = 0;
  const meter = new Transform({
    transform(chunk, _encoding, done) {
      size += chunk.length;
      if (size > maxBytes) return done(new Error(`asset ${assetId} exceeds ${maxBytes} bytes`));
      hash.update(chunk);
      done(null, chunk);
    },
  });
  await pipeline(source, meter, createWriteStream(file, { flags: "wx" }));
  return { size, sha256: hash.digest("hex") };
}

export function releaseApi({
  token,
  fetch = globalThis.fetch,
  repository = CONTRACT.repository,
  timeoutMs = 60000,
  uploadTimeoutMs = 30 * 60000,
}) {
  if (!token) throw fail("no GitHub token");
  const headers = (extra = {}) => ({
    authorization: `Bearer ${token}`,
    "x-github-api-version": API_VERSION,
    "user-agent": "gvm-led-release",
    ...extra,
  });

  /** JSON request; returns the parsed body, or null for 404 when `allow404`. */
  async function request(method, route, { body, allow404 = false } = {}) {
    const response = await fetch(`${API}/repos/${repository}${route}`, {
      method,
      headers: headers({
        accept: "application/vnd.github+json",
        ...(body ? { "content-type": "application/json" } : {}),
      }),
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (response.status === 404 && allow404) return null;
    if (!response.ok)
      throw fail(`GitHub ${method} ${route} answered ${response.status}`, response.status);
    return response.status === 204 ? null : response.json();
  }

  /** Upload `file` (exactly `size` bytes) as release asset `name`. */
  async function upload(releaseId, { name, file, size, contentType }) {
    const url = `${UPLOADS}/repos/${repository}/releases/${releaseId}/assets?name=${encodeURIComponent(name)}`;
    const response = await fetch(url, {
      method: "POST",
      headers: headers({
        accept: "application/vnd.github+json",
        "content-type": contentType,
        "content-length": String(size),
      }),
      body: createReadStream(file),
      duplex: "half",
      signal: AbortSignal.timeout(uploadTimeoutMs),
    });
    if (!response.ok) throw fail(`uploading ${name} answered ${response.status}`, response.status);
    return response.json();
  }

  /** Streams asset bytes to `file` (at most maxBytes); returns {size, sha256}. */
  async function downloadAsset(assetId, file, maxBytes = CONTRACT.limits.maxApkBytes) {
    return download(
      `/releases/assets/${assetId}`,
      assetId,
      file,
      maxBytes,
      "application/octet-stream",
    );
  }

  async function download(route, id, file, maxBytes, accept) {
    const response = await fetch(`${API}/repos/${repository}${route}`, {
      headers: headers({ accept }),
      redirect: "follow",
      signal: AbortSignal.timeout(uploadTimeoutMs),
    });
    if (!response.ok || !response.body)
      throw fail(`downloading asset ${id} answered ${response.status}`, response.status);
    return meterToFile(Readable.fromWeb(response.body), file, id, maxBytes);
  }

  return {
    repository,
    request,
    upload,
    downloadAsset,
    downloadArtifact: (id, file, maxBytes) =>
      download(`/actions/artifacts/${id}/zip`, id, file, maxBytes, "application/vnd.github+json"),
    allReleases: (pageSize) => listAllReleases(request, pageSize),
  };
}
