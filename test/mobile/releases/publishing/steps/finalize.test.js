import test from "node:test";
import assert from "node:assert/strict";
import { releaseSign } from "../../../../../src/mobile/releases/publishing/publish.js";
import { fixtureManifest, FIXTURE_SIGNER } from "../../helpers/manifest-fixture.js";
import { badgingText, signerText } from "../../helpers/android-fakes.js";
import {
  SIGNED, UNSIGNED, commit, password, pin, provenanceFor, referenceFor, scenario, tag, unsignedBytes, untouched,
} from "../../helpers/publish-scenario.js";

test("publishing fails when GitHub's latest is not this tag afterwards", async (t) => {
  const s = await scenario(t);
  s.github.state.latestAfterPatch = { tag_name: "v0.1.40" };
  await assert.rejects(s.sign(), /GitHub's latest is v0.1.40/);
  assert.equal(s.lock.released, 1);
  assert.equal(s.cleaned(), 1);
});

test("an unset latest release after publishing is reported too", async (t) => {
  const s = await scenario(t);
  s.github.state.latestAfterPatch = null;
  s.github.state.latest = null;
  const api = s.deps.api;
  const request = api.request;
  api.request = async (method, route, opts) => (route === "/releases/latest" ? null : request(method, route, opts));
  await assert.rejects(s.sign(), /latest is unset/);
});

test("publication PATCH marks the release latest and the success line says so", async (t) => {
  const s = await scenario(t);
  await s.sign();
  assert.ok(s.mutations().at(-1).includes('"make_latest":"true"'));
  assert.match(s.said.at(-1), /as GitHub's latest release/);
});

// The no-pin refusal is unreachable while signing-certificate.json pins a certificate:
// a null `deps.pin` falls back to the committed pin.

test("attended finalization preserves the validated CI reference beside provenance and signed output", async (t) => {
  const s = await scenario(t, {
    extraAssets: [["android-ci-reference.json", JSON.stringify(referenceFor())]],
  });
  await s.sign();
  assert.ok(
    s.github.releases
      .get(s.draft.id)
      .assets.some((asset) => asset.name === "android-ci-reference.json"),
  );
  assert.equal(s.github.releases.get(s.draft.id).assets.length, 4);
});

test("malformed or foreign CI references are rejected before signing", async (t) => {
  const s = await scenario(t, {
    extraAssets: [
      ["android-ci-reference.json", JSON.stringify({ ...referenceFor(), runAttempt: 2 })],
    ],
  });
  await assert.rejects(s.sign(), /Invalid Android CI reference/);
  untouched(s);
});

test("a removed CI reference stops finalization before deleting unsigned bytes or publishing", async (t) => {
  const s = await scenario(t, {
    extraAssets: [["android-ci-reference.json", JSON.stringify(referenceFor())]],
  });
  const upload = s.deps.api.upload;
  s.deps.api.upload = async (...args) => {
    await upload(...args);
    if (args[1].name === "phone-release.json") {
      const release = s.github.releases.get(s.draft.id);
      release.assets = release.assets.filter((asset) => asset.name !== "android-ci-reference.json");
    }
  };
  await assert.rejects(s.sign(), /draft CI reference changed/);
  assert.equal(
    s.mutations().some((event) => /^(delete|patch):/.test(event)),
    false,
  );
});

test("duplicate draft asset names cannot hide an unexpected CI reference", async (t) => {
  const s = await scenario(t, { extraAssets: [["android-provenance.json", "duplicate"]] });
  await assert.rejects(s.sign(), /duplicate or excessive assets/);
  untouched(s);
});
