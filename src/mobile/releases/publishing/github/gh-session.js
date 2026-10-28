// GitHub access for the owner's local signing: every call is a `gh api` child, so the
// write token stays inside gh (keyring) and never crosses this process, a child's stdout
// or an argument list. Exposes the same surface as gh-client.js's releaseApi
// (request, upload, downloadAsset, allReleases). gh's own error text is
// not echoed: failures carry a status and our own message, never the child's output.
//
// Behaviour proven against gh 2.98: `gh api` attaches the session token on
// uploads.github.com URLs as well as the API host, follows the asset redirect with
// `Accept: application/octet-stream` (binary on stdout), and reports failures as
// "gh: <message> (HTTP <status>)" on stderr with a non-zero exit (4 when signed out).
import { spawn } from "node:child_process";
import { rm, stat } from "node:fs/promises";
import { CONTRACT } from "../../release-contract.js";
import { runChild, childEnv } from "../../process/exec.js";
import { listAllReleases, meterToFile } from "./gh-client.js";

const API_VERSION = "2022-11-28";
const UPLOADS = "https://uploads.github.com";
const JSON_ACCEPT = "application/vnd.github+json";
const SIGNED_OUT_EXIT = 4;

const fail = (message, status) =>
  Object.assign(new Error(message), { code: "RELEASE_GITHUB", status });

const statusOf = (stderr) => Number(/\(HTTP (\d{3})\)/.exec(stderr ?? "")?.[1]) || undefined;

function failure(what, { code, stderr }) {
  if (code === SIGNED_OUT_EXIT) return fail("gh is not signed in; run gh auth login");
  const status = statusOf(stderr);
  return fail(status ? `GitHub ${what} answered ${status}` : `gh api ${what} failed`, status);
}

// Every call pins github.com: GH_HOST must never redirect release authority or assets.
const base = (accept, extra = []) => [
  "api",
  "--hostname",
  "github.com",
  "-H",
  `Accept: ${accept}`,
  "-H",
  `X-GitHub-Api-Version: ${API_VERSION}`,
  ...extra,
];

/**
 * @param {{repository?: string, run?: Function, spawnChild?: Function}} options
 * `run` executes one bounded child (exec.js runChild); `spawnChild` streams binary stdout.
 */
export function ghSessionApi({
  repository = CONTRACT.repository,
  run = runChild,
  spawnChild = spawn,
} = {}) {
  /** JSON request; the parsed body, or null for 404 when `allow404` (and for empty bodies). */
  async function request(method, route, { body, allow404 = false } = {}) {
    const result = await run({
      cmd: "gh",
      args: [
        ...base(JSON_ACCEPT, [
          "--method",
          method,
          ...(body ? ["-H", "Content-Type: application/json", "--input", "-"] : []),
        ]),
        `repos/${repository}${route}`,
      ],
      input: body ? JSON.stringify(body) : undefined,
      timeoutMs: 60000,
    });
    if (result.code !== 0) {
      if (allow404 && statusOf(result.stderr) === 404) return null;
      throw failure(`${method} ${route}`, result);
    }
    return result.stdout.trim() ? JSON.parse(result.stdout) : null;
  }

  /** Upload `file` (exactly `size` bytes) as release asset `name`. */
  async function upload(releaseId, { name, file, size, contentType }) {
    if ((await stat(file)).size !== size) throw fail(`${name} does not match its declared size`);
    const result = await run({
      cmd: "gh",
      args: [
        ...base(JSON_ACCEPT, [
          "--method",
          "POST",
          "-H",
          `Content-Type: ${contentType}`,
          "--input",
          file,
        ]),
        `${UPLOADS}/repos/${repository}/releases/${releaseId}/assets?name=${encodeURIComponent(name)}`,
      ],
      timeoutMs: 30 * 60000,
    });
    if (result.code !== 0) throw failure(`uploading ${name}`, result);
    return JSON.parse(result.stdout);
  }

  /** Streams asset bytes to a new `file` (at most maxBytes); returns {size, sha256}. */
  async function download(route, assetId, file, maxBytes) {
    const child = spawnChild(
      "gh",
      [
        ...base("application/octet-stream", ["--method", "GET"]),
        `repos/${repository}${route}`,
      ],
      { env: childEnv("gh"), stdio: ["ignore", "pipe", "pipe"], windowsHide: true, shell: false },
    );
    let stderr = "";
    child.stderr.on("data", (d) => {
      if (stderr.length < 4096) stderr += d;
    });
    const timer = setTimeout(() => child.kill("SIGKILL"), 30 * 60000);
    const closed = new Promise((resolve) => {
      child.once("error", () => resolve(1));
      child.once("close", (code) => resolve(code ?? 1));
    });
    try {
      const result = await meterToFile(child.stdout, file, assetId, maxBytes);
      const code = await closed;
      if (code !== 0) throw failure(`downloading asset ${assetId}`, { code, stderr });
      return result;
    } catch (error) {
      child.kill("SIGKILL");
      await rm(file, { force: true });
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    repository,
    request,
    upload,
    downloadAsset: (id, file, maxBytes = CONTRACT.limits.maxApkBytes) =>
      download(`/releases/assets/${id}`, id, file, maxBytes),
    downloadArtifact: (id, file, maxBytes) =>
      download(`/actions/artifacts/${id}/zip`, id, file, maxBytes),
    allReleases: (pageSize) => listAllReleases(request, pageSize),
  };
}
