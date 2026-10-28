import test from "node:test";
import assert from "node:assert/strict";
import { releaseSign } from "../../../../src/mobile/releases/publishing/publish.js";
import { fixtureManifest, FIXTURE_SIGNER } from "../helpers/manifest-fixture.js";
import { badgingText, signerText, stepOf } from "../helpers/android-fakes.js";
import {
  SIGNED, UNSIGNED, commit, password, pin, provenanceFor, referenceFor, scenario, tag, unsignedBytes, untouched,
} from "../helpers/publish-scenario.js";

test("happy path: sign, upload both, re-download both, delete only the unsigned asset, publish last", async (t) => {
  const s = await scenario(t);
  const manifest = await s.sign();
  assert.equal(manifest.versionCode, 43);
  assert.equal(manifest.certificateSha256, pin);
  assert.equal(manifest.apk.name, SIGNED);
  assert.equal(manifest.commit, commit);

  assert.deepEqual(s.mutations(), [
    `upload:${SIGNED}`,
    "upload:phone-release.json",
    `delete:${s.unsigned.id}`,
    `patch:${JSON.stringify({ draft: false, make_latest: "true" })}`,
  ]);
  const events = s.github.events;
  const deleteAt = events.indexOf(`delete:${s.unsigned.id}`);
  const uploadedIds = [...s.github.releases.get(s.draft.id).assets]
    .filter((a) => [SIGNED, "phone-release.json"].includes(a.name))
    .map((a) => `download:${a.id}`);
  assert.equal(uploadedIds.length, 2);
  for (const download of uploadedIds)
    assert.ok(events.lastIndexOf(download) !== -1 && events.indexOf(download) < deleteAt, download);
  assert.equal(events.at(-1).startsWith("patch:"), true);

  const final = s.github.releases.get(s.draft.id);
  assert.equal(final.draft, false);
  assert.deepEqual(final.assets.map((a) => a.name).sort(), [
    SIGNED,
    "android-ci-reference.json",
    "android-provenance.json",
    "phone-release.json",
  ]);
  assert.equal(s.github.state.latest.tag_name, tag);
  assert.equal(s.lock.acquired, 1);
  assert.equal(s.lock.released, 1);
  assert.equal(s.cleaned(), 1);
});

test("the password reaches children only through env and never appears in args or output", async (t) => {
  const s = await scenario(t);
  await s.sign();
  for (const spec of s.specs) assert.equal(JSON.stringify(spec.args).includes(password), false);
  const withEnv = s.specs.filter((spec) => spec.env);
  assert.deepEqual(withEnv.map(stepOf), ["keytool", "sign"]);
  for (const spec of withEnv)
    assert.equal(spec.env.GVM_ANDROID_KEYSTORE_PASSWORD, password);
  assert.equal(s.said.length > 0, true);
  for (const line of s.said) assert.equal(line.includes(password), false);
  assert.equal(JSON.stringify(s.github.events).includes(password), false);
});

// Regression: history is read twice (steps 3 and 7) with "wx" downloads; scratch names must not repeat.
test("a published older Android release is read for history and does not block publication", async (t) => {
  const s = await scenario(t, {
    history: [{ tag: "android-v0.1.40", manifest: fixtureManifest(40, FIXTURE_SIGNER) }],
  });
  assert.equal((await s.sign()).versionCode, 43);
});

test("every refusal before upload leaves the release untouched", async (t) => {
  const published = (tagName, manifest) => ({ tag: tagName, manifest });
  const cases = {
    "dirty checkout": { options: { dirty: true }, error: /uncommitted/ },
    "HEAD is not the tag commit": { options: { head: "b".repeat(40) }, error: /check out/ },
    "release already published": {
      options: { release: { draft: false } },
      error: /is not the draft/,
    },
    "missing CI reference": {
      options: { noCiReference: true },
      error: /CI reference is missing/,
    },
    "Actions run names another commit": {
      options: { actionsCommit: "b".repeat(40) },
      error: /CI evidence refused/,
    },
    "draft APK and provenance were replaced after CI": {
      options: {
        draftApk: Buffer.alloc(unsignedBytes.length, 0x78),
        artifactApk: unsignedBytes,
        artifactProvenance: Buffer.from(JSON.stringify(provenanceFor(unsignedBytes))),
      },
      error: /draft provenance differs from Actions/,
    },
    prerelease: { options: { release: { prerelease: true } }, error: /is not the draft/ },
    "wrong tag": {
      options: { release: { tag_name: "android-v0.1.43" } },
      error: /is not the draft/,
    },
    "wrong target commit": {
      options: { release: { target_commitish: "b".repeat(40) } },
      error: /is not the draft/,
    },
    "unsigned APK differs from provenance": {
      options: {
        provenance: provenanceFor(Buffer.from("other bytes")),
        artifactApk: Buffer.from("other bytes"),
      },
      error: /draft APK differs from Actions/,
    },
    "unsigned APK already signed": {
      options: { tools: { unsignedVerifies: true } },
      error: /already signed/,
    },
    "wrong package": {
      options: {
        tools: { unsignedBadging: badgingText({ pkg: "com.dirtyredz.gvmled.debug" }) },
      },
      error: /unsigned APK/,
    },
    "wrong version code": {
      options: { tools: { unsignedBadging: badgingText({ code: 44 }) } },
      error: /unsigned APK/,
    },
    debuggable: {
      options: { tools: { unsignedBadging: `${badgingText()}application-debuggable\n` } },
      error: /unsigned APK/,
    },
    "versionCode equal to a published release": {
      options: { history: [published("android-v0.1.42", fixtureManifest(42, FIXTURE_SIGNER))] },
      error: /not above/,
    },
    "versionCode below a published release": {
      options: { history: [published("android-v0.1.50", fixtureManifest(50, FIXTURE_SIGNER))] },
      error: /not above/,
    },
    "unreadable history": {
      options: { history: [published("android-v0.1.40", null)] },
      error: /no readable manifest/,
    },
    "unexpected asset": {
      options: { extraAssets: [["stray.txt", "x"]] },
      error: /unexpected asset stray\.txt/,
    },
    "incomplete asset": {
      options: { extraAssets: [["phone-release.json", "x", "starter"]] },
      error: /incomplete/,
    },
    "neither unsigned nor signed APK": {
      options: { noUnsigned: true },
      error: /neither the unsigned nor a signed/,
    },
  };
  for (const [name, { options, error }] of Object.entries(cases)) {
    await t.test(name, async (t) => {
      const s = await scenario(t, options);
      await assert.rejects(s.sign(), error);
      untouched(s);
      assert.equal(
        s.vault.calls.some(([m]) => m === "readDocument"),
        false,
        "keystore never read",
      );
      // Lock and material never leak, even when nothing was acquired yet or refusal came early.
      assert.equal(s.lock.released, s.lock.acquired);
      assert.equal(s.cleaned(), s.lock.acquired);
    });
  }
});

test("an unreadable provenance asset or a provenance for another commit is refused", async (t) => {
  const s = await scenario(t, { provenance: { nonsense: true } });
  await assert.rejects(s.sign(), /CI provenance is malformed/);
  untouched(s);
  const other = await scenario(t, {
    provenance: { ...provenanceFor(unsignedBytes), commit: "c".repeat(40) },
  });
  await assert.rejects(other.sign(), /names another commit or tag/);
  untouched(other);
});

test("a keystore certificate that is not the pin refuses before apksigner sign runs", async (t) => {
  const s = await scenario(t);
  s.deps.pin = "b".repeat(64);
  await assert.rejects(s.sign(), /not the pinned release certificate/);
  untouched(s);
  assert.equal(s.cleaned(), 1);
  assert.equal(s.lock.released, 1);
});

test("a read-back mismatch of the uploaded APK leaves the draft unpublished and intact", async (t) => {
  const s = await scenario(t);
  s.github.state.corruptUpload = SIGNED;
  await assert.rejects(s.sign(), /read back different bytes/);
  assert.equal(
    s.mutations().some((e) => e.startsWith("delete:") || e.startsWith("patch:")),
    false,
  );
  const release = s.github.releases.get(s.draft.id);
  assert.equal(release.draft, true);
  assert.ok(release.assets.some((a) => a.id === s.unsigned.id));
  assert.equal(s.lock.released, 1);
  assert.equal(s.cleaned(), 1);
});

test("a failing signer still releases the lock and cleans the material", async (t) => {
  const s = await scenario(t, { tools: { failSign: true } });
  await assert.rejects(s.sign(), /apksigner sign failed/);
  assert.deepEqual(s.mutations(), []);
  assert.equal(s.lock.released, 1);
  assert.equal(s.cleaned(), 1);
});

test("a bad signed output (two signers) is never uploaded", async (t) => {
  const s = await scenario(t, {
    tools: { signed: signerText(pin, { signers: 2, digests: [pin, pin] }) },
  });
  await assert.rejects(s.sign(), /exactly one signer/);
  assert.deepEqual(s.mutations(), []);
});

test("resume: a draft with valid signed APK and manifest but no unsigned asset publishes without re-signing", async (t) => {
  const first = await scenario(t);
  const manifest = await first.sign();
  // Rebuild the post-upload, post-unsigned-deletion state in a new scenario.
  const s = await scenario(t, { noUnsigned: true });
  const release = s.github.releases.get(s.draft.id);
  s.github.addAsset(release, SIGNED, "signed apk bytes");
  s.github.addAsset(release, "phone-release.json", JSON.stringify(manifest, null, 2) + "\n");

  const result = await s.sign();
  assert.deepEqual(result, manifest);
  assert.equal(s.steps().includes("sign"), false);
  assert.equal(s.steps().includes("keytool"), false);
  assert.equal(s.vault.calls.length, 0);
  assert.deepEqual(s.mutations(), [
    `patch:${JSON.stringify({ draft: false, make_latest: "true" })}`,
  ]);
  assert.equal(s.github.releases.get(s.draft.id).draft, false);
});

test("resume: a signed APK that is not verifiably ours is refused", async (t) => {
  const s = await scenario(t, { noUnsigned: true, tools: { signed: signerText("c".repeat(64)) } });
  s.github.addAsset(s.github.releases.get(s.draft.id), SIGNED, "signed apk bytes");
  await assert.rejects(s.sign(), /not a valid pinned signing of this build/);
  untouched(s);
});

test("resume: a conflicting remote manifest is refused", async (t) => {
  const s = await scenario(t, { noUnsigned: true });
  const release = s.github.releases.get(s.draft.id);
  s.github.addAsset(release, SIGNED, "signed apk bytes");
  s.github.addAsset(release, "phone-release.json", JSON.stringify({ other: true }));
  await assert.rejects(s.sign(), /manifest conflicts/);
  untouched(s);
});


test("release-sign needs a valid Android tag and a numeric release id", async (t) => {
  const s = await scenario(t);
  for (const args of [
    { tag: "v0.1.42", releaseId: s.draft.id },
    { tag, releaseId: "abc" },
    { tag, releaseId: "0" },
    { tag, releaseId: "1.5" },
  ])
    await assert.rejects(releaseSign(s.deps, args), /needs --tag/);
  assert.equal(s.lock.acquired, 0);
});
