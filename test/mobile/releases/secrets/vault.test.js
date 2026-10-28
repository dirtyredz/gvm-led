import test from "node:test";
import assert from "node:assert/strict";
import { openVault, VaultError } from "../../../../src/mobile/releases/secrets/vault.js";

const LEAK = "op://GVM-LED/secret-detail sdk internals";

function fakeSdk({ vaults = [{ id: "v1", title: "GVM-LED" }], failOn = null } = {}) {
  const created = [];
  const createdVaults = [];
  const items = new Map();
  let clients = 0;
  const maybe = (name, value) => {
    if (failOn === name) throw new Error(LEAK);
    return value;
  };
  const sdk = {
    ItemCategory: { Password: "Password", Document: "Document" },
    ItemFieldType: { Concealed: "Concealed" },
    DesktopAuth: class {
      constructor(account) {
        this.account = account;
      }
    },
    async createClient(config) {
      clients++;
      sdk.config = config;
      return {
        vaults: {
          list: async () => maybe("vaults", vaults),
          create: async ({ title }) => (createdVaults.push(title), { id: "new", title }),
        },
        secrets: { resolve: async (ref) => maybe("resolve", `value-of:${ref}`) },
        items: {
          list: async () =>
            maybe(
              "list",
              [...items.values()].map((i) => ({ id: i.id, title: i.title, state: "active" })),
            ),
          get: async (vaultId, id) => maybe("get", items.get(id)),
          create: async (params) => {
            maybe("create", null);
            const item = { ...params, id: `id${items.size + 1}` };
            if (params.document) item.document = { name: params.document.name, id: "file" };
            items.set(item.id, item);
            created.push(params);
            return item;
          },
          files: {
            read: async (vaultId, itemId) =>
              maybe("read", items.get(itemId).documentBytes ?? Uint8Array.from([1, 2])),
          },
        },
      };
    },
  };
  return { sdk, created, createdVaults, items, clients: () => clients };
}

test("one DesktopAuth client serves every operation", async () => {
  const fake = fakeSdk();
  const vault = await openVault({ load: async () => fake.sdk });
  assert.equal(fake.sdk.config.auth.account, "ETCB7LGCDNEP7PUD3FDNS5JXBU");
  assert.equal(fake.sdk.config.integrationName, "gvm-led-release-secrets");
  const pw = await vault.createPassword("gvm-led-android-release-password", "pw-secret-value");
  const doc = await vault.createDocument(
    "gvm-led-android-release-keystore",
    "gvm-led-release.p12",
    Buffer.from([1, 2]),
  );
  assert.equal(await vault.readPasswordById(pw), "pw-secret-value");
  assert.deepEqual([...(await vault.readDocumentById(doc))], [1, 2]);
  assert.deepEqual([...(await vault.readDocument("keystore"))], [1, 2]);
  assert.equal(
    await vault.readField("password"),
    "value-of:op://GVM-LED/gvm-led-android-release-password/password",
  );
  assert.deepEqual(
    (await vault.findItems(["gvm-led-android-release-password"])).map((i) => i.id),
    [pw],
  );
  assert.equal(fake.clients(), 1);
  assert.equal(fake.created[0].category, "Password");
  assert.equal(fake.created[0].fields[0].fieldType, "Concealed");
  assert.equal(fake.created[1].category, "Document");
  assert.equal(fake.created[1].document.name, "gvm-led-release.p12");
});

test("SDK errors are replaced by the operation name", async () => {
  for (const failOn of ["vaults", "resolve", "create", "get", "list"]) {
    const fake = fakeSdk({ failOn });
    const attempt = async () => {
      const vault = await openVault({ load: async () => fake.sdk });
      await vault.readField("password");
      await vault.createPassword("t", "v");
      await vault.findItems(["t"]);
      await vault.readPasswordById("id1");
    };
    await assert.rejects(attempt, (error) => {
      assert.ok(error instanceof VaultError, failOn);
      assert.ok(!error.message.includes("op://"), error.message);
      assert.ok(!error.message.includes("sdk internals"));
      return true;
    });
  }
});

test("a missing or ambiguous vault is refused", async () => {
  for (const vaults of [
    [],
    [
      { id: "a", title: "GVM-LED" },
      { id: "b", title: "GVM-LED" },
    ],
  ])
    await assert.rejects(
      openVault({ load: async () => fakeSdk({ vaults }).sdk }),
      /vault GVM-LED/,
    );
  await assert.rejects(
    openVault({
      load: async () => {
        throw new Error(LEAK);
      },
    }),
    /SDK load failed/,
  );
});

test("unknown item keys are refused", async () => {
  const vault = await openVault({ load: async () => fakeSdk().sdk });
  await assert.rejects(vault.readField("keystore"), VaultError);
  await assert.rejects(vault.readDocument("keystore"), /lookup/);
});

test("a missing vault is created only on request, and never when ambiguous", async () => {
  const none = fakeSdk({ vaults: [] });
  await assert.rejects(openVault({ load: async () => none.sdk }), /vault GVM-LED lookup/);
  assert.deepEqual(none.createdVaults, []);
  const make = fakeSdk({ vaults: [] });
  await openVault({ load: async () => make.sdk, create: true });
  assert.deepEqual(make.createdVaults, ["GVM-LED"]);
  const two = fakeSdk({
    vaults: [
      { id: "a", title: "GVM-LED" },
      { id: "b", title: "GVM-LED" },
    ],
  });
  await assert.rejects(openVault({ load: async () => two.sdk, create: true }), /ambiguous/);
  assert.deepEqual(two.createdVaults, []);
});

test("other vaults, such as Command Deck's, are never matched", async () => {
  const fake = fakeSdk({ vaults: [{ id: "x", title: "CommandDeck" }] });
  await assert.rejects(openVault({ load: async () => fake.sdk }), /vault GVM-LED lookup/);
});
