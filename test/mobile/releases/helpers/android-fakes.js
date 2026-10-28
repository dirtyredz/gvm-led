// Fakes for Android signing and publication tests: a scripted build-tools runner and an
// in-memory GitHub. No child processes, no network; files go to a caller-provided temp dir.
import path from "node:path";
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";

export const TOOLS = {
  zipalign: "zipalign",
  aapt2: "aapt2",
  java: "java",
  apksignerJar: "apksigner.jar",
};

export const badgingText = ({
  code = 43,
  name = "0.1.42",
  pkg = "com.dirtyredz.gvmled",
} = {}) =>
  `package: name='${pkg}' versionCode='${code}' versionName='${name}' compileSdkVersion='35'\nminSdkVersion:'26'\ntargetSdkVersion:'35'\n`;

export const signerText = (pin, { signers = 1, digests = [pin], scheme = "v2" } = {}) =>
  `Verifies\nVerified using ${scheme} scheme (APK Signature Scheme ${scheme}): true\nNumber of signers: ${signers}\n` +
  digests.map((d, i) => `Signer #${i + 1} certificate SHA-256 digest: ${d}\n`).join("");

/** The step a spec represents, for ordering assertions. */
export function stepOf(spec) {
  if (spec.cmd === "keytool") return "keytool";
  if (spec.cmd === "zipalign") return spec.args[0] === "-c" ? "zipalign-check" : "zipalign";
  if (spec.cmd === "aapt2") return "badging";
  if (spec.cmd === "java") return spec.args[2];
  return `unknown:${spec.cmd}`;
}

/**
 * A runner answering keytool, zipalign, apksigner and aapt2. A file whose name contains
 * "unsigned" is the CI build; everything else is a signed output.
 */
export function androidRun({
  pin,
  pem,
  unsignedBadging = badgingText(),
  signedBadging = badgingText(),
  signed = signerText(pin),
  unsignedVerifies = false,
  alignOk = true,
  failSign = false,
  specs = [],
} = {}) {
  const isUnsigned = (file) => path.basename(file).includes("unsigned");
  const run = async (spec) => {
    specs.push(spec);
    const last = spec.args[spec.args.length - 1];
    switch (stepOf(spec)) {
      case "keytool":
        return { code: 0, stdout: pem, stderr: "" };
      case "zipalign":
        await writeFile(last, "aligned apk bytes");
        return { code: 0, stdout: "", stderr: "" };
      case "zipalign-check":
        return { code: alignOk ? 0 : 1, stdout: "", stderr: "" };
      case "sign":
        if (failSign) return { code: 1, stdout: "", stderr: "" };
        await writeFile(spec.args[spec.args.indexOf("--out") + 1], "signed apk bytes");
        return { code: 0, stdout: "", stderr: "" };
      case "verify":
        if (isUnsigned(last))
          return unsignedVerifies
            ? { code: 0, stdout: signed, stderr: "" }
            : { code: 1, stdout: "", stderr: "DOES NOT VERIFY" };
        return { code: 0, stdout: signed, stderr: "" };
      case "badging":
        return { code: 0, stdout: isUnsigned(last) ? unsignedBadging : signedBadging, stderr: "" };
      default:
        throw new Error(`unscripted command ${spec.cmd}`);
    }
  };
  return { run, specs };
}

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

/**
 * In-memory GitHub for release-sign. `releases` is a Map by id; asset bytes live in `bytes`
 * by asset id. `events` records mutating calls and downloads in order.
 */
export function fakeGithub({ commit, tag, latest = { tag_name: "v0.1.40" } } = {}) {
  const releases = new Map();
  const bytes = new Map();
  const refs = new Map(tag ? [[tag, { object: { type: "commit", sha: commit } }]] : []);
  const events = [];
  const state = { latest, nextId: 1000, corruptUpload: null, latestAfterPatch: null };

  function addAsset(release, name, data, assetState = "uploaded") {
    const asset = { id: state.nextId++, name, size: data.length, state: assetState };
    release.assets.push(asset);
    bytes.set(asset.id, Buffer.from(data));
    return asset;
  }
  function addRelease(fields) {
    const release = { id: state.nextId++, assets: [], draft: true, prerelease: false, ...fields };
    releases.set(release.id, release);
    return release;
  }
  const api = {
    async request(method, route, { allow404 = false, body } = {}) {
      let m;
      if (method === "GET" && (m = /^\/releases\/(\d+)$/.exec(route))) {
        const release = releases.get(Number(m[1]));
        return release ? structuredClone(release) : null;
      }
      if (method === "GET" && (m = /^\/git\/ref\/tags\/(.+)$/.exec(route))) {
        const ref = refs.get(m[1]);
        if (!ref && allow404) return null;
        return ref ?? Promise.reject(new Error("404"));
      }
      if (method === "GET" && route === "/releases/latest") return state.latest;
      if (method === "DELETE" && (m = /^\/releases\/assets\/(\d+)$/.exec(route))) {
        events.push(`delete:${m[1]}`);
        for (const release of releases.values())
          release.assets = release.assets.filter((a) => a.id !== Number(m[1]));
        return null;
      }
      if (method === "PATCH" && (m = /^\/releases\/(\d+)$/.exec(route))) {
        events.push(`patch:${JSON.stringify(body)}`);
        const release = releases.get(Number(m[1]));
        Object.assign(release, { draft: body.draft });
        if (body.make_latest === "true") state.latest = { tag_name: release.tag_name };
        if (state.latestAfterPatch) state.latest = state.latestAfterPatch;
        return structuredClone(release);
      }
      throw new Error(`unscripted request ${method} ${route}`);
    },
    async upload(id, { name, file, size }) {
      const data = await readFile(file);
      assertSize(data, size);
      events.push(`upload:${name}`);
      const stored = state.corruptUpload === name ? Buffer.alloc(data.length, 0x7a) : data;
      addAsset(releases.get(id), name, stored);
    },
    async allReleases() {
      return [...releases.values()].map((r) => structuredClone(r));
    },
    async downloadAsset(id, file, max = Infinity) {
      const data = bytes.get(id);
      if (!data || data.length > max) throw new Error("download refused");
      events.push(`download:${id}`);
      await writeFile(file, data, { flag: "wx" });
      return { size: data.length, sha256: sha256(data) };
    },
  };
  return { api, releases, bytes, refs, events, state, addAsset, addRelease, sha256 };
}

function assertSize(data, size) {
  if (data.length !== size) throw new Error("upload size mismatch");
}
