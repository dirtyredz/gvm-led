// Bounded child execution for release-credential tools. Secrets travel on stdin or
// in the child's environment only (never argv); output is captured whole (bounded),
// redacted after the stream ends so a secret split across chunks is still caught,
// and never echoed raw. Owned children are tracked so cleanup can terminate them
// before temporary material is deleted.
import { spawn } from "node:child_process";
import { PASS_ENV } from "../signing/keystore.js";

export const MAX_OUTPUT_BYTES = 1024 * 1024;
export const DEFAULT_TIMEOUT_MS = 10 * 60 * 1000;
const MIN_SECRET = 6;

/** Replaces every secret (and its base64 form) in text. Shorter strings are not secrets here. */
export function redact(text, secrets = []) {
  let out = String(text);
  for (const secret of secrets) {
    if (typeof secret !== "string" || secret.length < MIN_SECRET) continue;
    for (const form of new Set([secret, Buffer.from(secret).toString("base64")]))
      out = out.split(form).join("***");
  }
  return out;
}

/** Collects at most `max` bytes; later bytes are counted, not kept. */
export function boundedSink(max = MAX_OUTPUT_BYTES) {
  const chunks = [];
  let kept = 0;
  let total = 0;
  return {
    push(chunk) {
      total += chunk.length;
      if (kept >= max) return;
      const part = chunk.subarray(0, max - kept);
      chunks.push(part);
      kept += part.length;
    },
    text: () => Buffer.concat(chunks).toString("utf8"),
    truncated: () => total > kept,
  };
}

/** The signing password variable: never inherited, only passed explicitly (keytool, apksigner). */
const SIGNING_ENV = [PASS_ENV];
const GITHUB_HOST = "github.com";

/**
 * The environment for one child: the process environment WITHOUT the signing
 * password variable, plus `extra`. gh always targets github.com whatever GH_HOST says.
 */
export function childEnv(cmd, extra = {}) {
  const base = { ...process.env };
  for (const name of SIGNING_ENV) delete base[name];
  if (/^gh(.exe)?$/i.test(label(cmd))) base.GH_HOST = GITHUB_HOST;
  return { ...base, ...extra };
}

const owned = new Set();

/** Terminates every child this process still owns (cleanup and signal paths). */
export function terminateChildren() {
  for (const child of owned) {
    try {
      child.kill("SIGKILL");
    } catch {
      // Already gone.
    }
  }
  owned.clear();
}

/**
 * Runs one child. Returns {code, stdout, stderr, truncated} with secrets redacted.
 * `env` entries are added to the inherited environment minus the signing variables (childEnv); `input` is written to stdin.
 */
export function runChild(
  { cmd, args = [], cwd, env, input, secrets = [], timeoutMs = DEFAULT_TIMEOUT_MS, maxOutput },
  { spawnChild = spawn } = {},
) {
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawnChild(cmd, args, {
        cwd,
        env: childEnv(cmd, env),
        stdio: ["pipe", "pipe", "pipe"],
        windowsHide: true,
        shell: false,
      });
    } catch {
      reject(new Error(`could not start ${label(cmd)}`));
      return;
    }
    owned.add(child);
    const out = boundedSink(maxOutput);
    const err = boundedSink(maxOutput);
    child.stdout.on("data", (d) => out.push(d));
    child.stderr.on("data", (d) => err.push(d));
    const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
    child.once("error", () => {
      clearTimeout(timer);
      owned.delete(child);
      reject(new Error(`could not start ${label(cmd)}`));
    });
    child.once("close", (code) => {
      clearTimeout(timer);
      owned.delete(child);
      resolve({
        code: code ?? 1,
        stdout: redact(out.text(), secrets),
        stderr: redact(err.text(), secrets),
        truncated: out.truncated() || err.truncated(),
      });
    });
    child.stdin.on("error", () => {});
    child.stdin.end(input ?? "");
  });
}

/** The executable's base name: safe to print (paths may be user-specific, values never appear). */
export const label = (cmd) => String(cmd).split(/[\\/]/).pop();

/** Runs a child and throws a sanitized error on a non-zero exit. */
export async function mustRun(spec, what, run = runChild) {
  const result = await run(spec);
  if (result.code !== 0) throw new Error(`${what} failed with exit code ${result.code}`);
  return result;
}
