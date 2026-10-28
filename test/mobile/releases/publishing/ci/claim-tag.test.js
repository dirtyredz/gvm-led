import test from "node:test";
import assert from "node:assert/strict";
import { claimReleaseTag } from "../../../../../src/mobile/releases/publishing/ci/claim-tag.js";

test("claims against the bounded paginated allReleases snapshot", async () => {
  const tag = "android-v0.1.42";
  const commit = "a".repeat(40);
  const calls = [];
  const stale = { id: 250, draft: true, tag_name: tag };
  const client = {
    async allReleases() {
      calls.push("allReleases");
      return [stale];
    },
    async request(method, route) {
      calls.push(`${method} ${route}`);
      if (route === `/releases/tags/${tag}`) return null;
      if (route === `/git/ref/tags/${tag}`) return null;
      if (method === "POST" && route === "/git/refs") return {};
      if (method === "DELETE" && route === "/releases/250") return null;
      if (route.startsWith("/releases?")) throw new Error("unpaginated release read");
      throw new Error(`unscripted ${method} ${route}`);
    },
  };

  assert.deepEqual(await claimReleaseTag({ client, tag, commit }), {
    tag: "created",
    kept: [],
    removed: [250],
  });
  assert.equal(calls.filter((call) => call === "allReleases").length, 1);
});
