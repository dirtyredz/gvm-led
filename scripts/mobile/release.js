// CI entry for Android releases (.github/workflows/release.yml, release-resume.yml).
// No signing happens in CI; the owner signs locally (scripts/owner/publish-release.ps1).
//
//   next-version    (version job) one above the highest published android-v<version>;
//                   writes version= to GITHUB_OUTPUT, exported as GVM_RELEASE_VERSION
//   version         (android-build) writes name= and code= for the Gradle properties
//   prepare --apk <unsigned.apk> --tools <build-tools dir> --out <dir>
//                   stage the unsigned APK and provenance before the artifact upload
//   draft --prepared <dir> --out <dir> --artifact-id N --artifact-sha256 H --artifact-attempt N
//                   consume that exact artifact, add the CI reference, create the draft
//   resume-source --run-id N --attempt N --out <source.json>   (release-resume)
//                   verify the run/attempt/artifact through the Actions API
//   resume --source <source.json> --prepared <dir> --out <dir>  (release-resume)
//                   restore the draft from the retained artifact's exact bytes
//
// Ported from command-deck scripts/mobile/release.js and adapted to Android-only releases.
import path from "node:path";
import os from "node:os";
import { parseArgs } from "node:util";
import { appendFile, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { allocateVersion, releaseFromEnv } from "../../src/mobile/releases/version.js";
import { phoneVersionOf } from "../../src/mobile/releases/manifest.js";
import { prepareUnsigned, readPreparedUnsigned } from "../../src/mobile/releases/prepare.js";
import { runChild } from "../../src/mobile/releases/process/exec.js";
import {
  badgingCommand,
  buildTools,
  parseBadging,
  verifyCommand,
} from "../../src/mobile/releases/signing/apk-inspection.js";
import { releaseApi } from "../../src/mobile/releases/publishing/github/gh-client.js";
import { createAndroidDraft } from "../../src/mobile/releases/publishing/ci/draft.js";
import { writeCiReference } from "../../src/mobile/releases/publishing/ci-reference.js";
import { verifyBuildEvidence } from "../../src/mobile/releases/publishing/ci/evidence.js";
import { resumeDraft } from "../../src/mobile/releases/publishing/ci/resume.js";

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: {
    apk: { type: "string" },
    out: { type: "string" },
    tools: { type: "string" },
    prepared: { type: "string" },
    source: { type: "string" },
    "run-id": { type: "string" },
    attempt: { type: "string" },
    "artifact-id": { type: "string" },
    "artifact-sha256": { type: "string" },
    "artifact-attempt": { type: "string" },
  },
});
const required = (name) => {
  if (!values[name]) throw new Error(`--${name} required`);
  return path.resolve(values[name]);
};
const integer = (name) => {
  const value = values[name];
  if (
    typeof value !== "string" ||
    !/^[1-9]\d{0,15}$/.test(value) ||
    !Number.isSafeInteger(Number(value))
  )
    throw Object.assign(new Error(`--${name} must be a positive safe integer`), { code: "ARGS" });
  return Number(value);
};
const output = (text) =>
  process.env.GITHUB_OUTPUT ? appendFile(process.env.GITHUB_OUTPUT, text) : process.stdout.write(text);
const ciRelease = () => {
  const release = releaseFromEnv();
  if (!release) throw Object.assign(new Error("CI release identity required"), { code: "ARGS" });
  return release;
};
const api = () => releaseApi({ token: process.env.GITHUB_TOKEN });
const scratchDir = async () => {
  const dir = await mkdtemp(path.join(process.env.RUNNER_TEMP || os.tmpdir(), "gvm-draft-"));
  let n = 0;
  return (name) => path.join(dir, `${++n}-${name}`);
};

const commands = {
  async "next-version"() {
    const version = await allocateVersion(api().request);
    await output(`version=${version}\n`);
    console.log(`next Android release version ${version}`);
  },
  async version() {
    const v = phoneVersionOf(ciRelease().version);
    await output(`name=${v.versionName}\ncode=${v.versionCode}\n`);
  },
  async prepare() {
    const release = ciRelease();
    const apk = required("apk");
    const tools = buildTools(required("tools"));
    const badging = parseBadging((await runChild(badgingCommand(tools, apk))).stdout);
    const signed = (await runChild(verifyCommand(tools, apk))).code === 0;
    await prepareUnsigned({ apk, out: required("out"), release, badging, signed });
    await output(`run-attempt=${release.build.runAttempt}\n`);
  },
  async draft() {
    const release = ciRelease();
    const prepared = await readPreparedUnsigned({
      directory: required("prepared"),
      release,
      artifactAttempt: integer("artifact-attempt"),
    });
    const reference = await writeCiReference(required("out"), prepared.provenance, {
      artifactId: integer("artifact-id"),
      artifactSha256: values["artifact-sha256"],
      draftRunAttempt: release.build.runAttempt,
    });
    const result = await createAndroidDraft({
      api: api(),
      ...prepared,
      ciReference: reference.ciReference,
      assets: [...prepared.assets, reference.asset],
      scratch: await scratchDir(),
      log: console.log,
    });
    console.log(`Android draft ${prepared.provenance.tag}: ${result.state} (release ${result.id})`);
    await output(`release-id=${result.id}\ntag=${prepared.provenance.tag}\n`);
  },
  async "resume-source"() {
    const source = await verifyBuildEvidence(api(), {
      runId: integer("run-id"),
      attempt: integer("attempt"),
    });
    await writeFile(required("out"), JSON.stringify(source, null, 2) + "\n", { flag: "wx" });
    await output(`artifact-id=${source.artifactId}\nrun-id=${source.runId}\n`);
    console.log(
      `verified run ${source.runId} attempt ${source.runAttempt} (${source.commit.slice(0, 12)}), artifact ${source.artifactId}`,
    );
  },
  async resume() {
    const source = JSON.parse(await readFile(required("source"), "utf8"));
    const result = await resumeDraft({
      api: api(),
      source,
      prepared: required("prepared"),
      out: required("out"),
      scratch: await scratchDir(),
      resumeRunId: process.env.GITHUB_RUN_ID ?? "local",
      log: console.log,
    });
    console.log(`Android draft restored: ${result.state} (release ${result.id})`);
  },
};

try {
  if (!Object.hasOwn(commands, positionals[0]))
    throw Object.assign(new Error(`Expected one of: ${Object.keys(commands).join(", ")}`), {
      code: "ARGS",
    });
  await commands[positionals[0]]();
} catch (error) {
  // Child process errors can contain environment/arguments. Print only our own messages.
  console.error(
    `Android release step failed: ${error?.code ? error.message : "check CI identity and artifacts"}`,
  );
  process.exitCode = 1;
}
