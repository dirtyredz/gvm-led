import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { EventEmitter } from "node:events";
import { existsSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import { createMaterial } from "../../../../src/mobile/releases/signing/material.js";
import { workspace } from "../helpers/workspace.js";

test("material lives outside the checkout and cleanup removes it and terminates children", async (t) => {
  const { dir } = await workspace(t);
  const tmpRoot = path.join(dir, "tmp");
  await mkdir(tmpRoot);
  let terminated = 0;
  const m = await createMaterial({
    root: path.join(dir, "checkout"),
    tmpRoot,
    terminate: () => terminated++,
  });
  assert.ok(existsSync(m.dir));
  assert.equal(path.dirname(m.dir), tmpRoot);
  await m.cleanup();
  await m.cleanup();
  assert.equal(existsSync(m.dir), false);
  assert.equal(terminated, 1);
});

test("unique scratch names never repeat within one material directory", async (t) => {
  const { dir } = await workspace(t);
  const tmpRoot = path.join(dir, "tmp");
  await mkdir(tmpRoot);
  const m = await createMaterial({ root: path.join(dir, "checkout"), tmpRoot });
  try {
    const names = [m.unique("published.json"), m.unique("published.json")];
    assert.notEqual(names[0], names[1]);
    for (const name of names) assert.equal(path.dirname(name), m.dir);
    assert.equal(m.file("a.json"), path.join(m.dir, "a.json"));
  } finally {
    await m.cleanup();
  }
});

test("material inside the checkout is refused and removed", async (t) => {
  const { dir } = await workspace(t);
  await assert.rejects(createMaterial({ root: dir, tmpRoot: dir }), /outside the checkout/);
  const { readdir } = await import("node:fs/promises");
  assert.deepEqual(
    (await readdir(dir)).filter((n) => n.startsWith("gvm-led-signing-")),
    [],
  );
});

test("a catchable signal terminates children, deletes material and exits 130", async (t) => {
  const { dir } = await workspace(t);
  const signals = new EventEmitter();
  const order = [];
  let exited;
  const material = await createMaterial({
    root: path.join(dir, "checkout"),
    tmpRoot: dir,
    signals,
    terminate: () => order.push("terminate"),
    exit: (code) => {
      exited = code;
    },
  });
  signals.emit("SIGINT");
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(existsSync(material.dir), false);
  assert.deepEqual(order, ["terminate"]);
  assert.equal(exited, 130);
  assert.equal(signals.listenerCount("SIGTERM"), 0);
});
