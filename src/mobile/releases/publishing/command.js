// Real dependencies for `node scripts/mobile/secrets.js release-sign`: the owner's gh session,
// the Android SDK build-tools, git state of this checkout and the publication lock.
// The vault client, runner and material come from scripts/mobile/secrets.js.
import fs from "node:fs";
import path from "node:path";
import { mustRun } from "../process/exec.js";
import { buildTools } from "../signing/apk-inspection.js";
import { ghSessionApi } from "./github/gh-session.js";
import { publicationLock } from "./lock.js";
import { releaseSign } from "./publish.js";

const numeric = (name) => name.split(".").map((n) => Number.parseInt(n, 10) || 0);
const newer = (a, b) => {
  const [x, y] = [numeric(a), numeric(b)];
  for (let i = 0; i < Math.max(x.length, y.length); i++)
    if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0);
  return 0;
};

/** The highest installed build-tools directory. */
export function findBuildTools(env = process.env, platform = process.platform) {
  const roots = [
    env.ANDROID_HOME,
    env.ANDROID_SDK_ROOT,
    platform === "win32" && env.LOCALAPPDATA ? path.join(env.LOCALAPPDATA, "Android", "Sdk") : null,
  ].filter(Boolean);
  const sdk = roots.find((d) => fs.existsSync(path.join(d, "build-tools")));
  if (!sdk) throw new Error("Android SDK not found; set ANDROID_HOME");
  const versions = fs
    .readdirSync(path.join(sdk, "build-tools"))
    .filter((n) => /^\d+(\.\d+)*$/.test(n))
    .sort(newer);
  if (!versions.length) throw new Error("no Android build-tools installed");
  return path.join(sdk, "build-tools", versions.at(-1));
}

export async function releaseSignCommand(deps, { tag, releaseId }) {
  const run = deps.run;
  const git = {
    head: async () =>
      (
        await mustRun(
          { cmd: "git", args: ["rev-parse", "HEAD"], cwd: deps.root },
          "git rev-parse",
          run,
        )
      ).stdout.trim(),
    clean: async () =>
      (
        await mustRun(
          { cmd: "git", args: ["status", "--porcelain"], cwd: deps.root },
          "git status",
          run,
        )
      ).stdout.trim() === "",
  };
  return releaseSign(
    {
      ...deps,
      api: ghSessionApi({ run }),
      tools: buildTools(findBuildTools(), { java: deps.java }),
      git,
      lock: publicationLock(),
    },
    { tag, releaseId },
  );
}
