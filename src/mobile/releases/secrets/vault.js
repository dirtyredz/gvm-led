// The 1Password SDK adapter: one DesktopAuth client per command process (one Windows
// Hello approval), reused for every read and write. Values are returned to callers in
// memory only. SDK errors are replaced by the operation name: SDK messages are never
// surfaced, so nothing they might contain can reach a log.
// Ported from command-deck src/mobile/releases/secrets/{vault,signing-source}.js; the
// signing-vault copy and the MCP env signing mode are omitted.
import { ACCOUNT_ID, INTEGRATION, ITEMS, VAULT } from "./plan.js";

export class VaultError extends Error {
  constructor(operation) {
    super(`1Password ${operation} failed`);
    this.code = "VAULT";
  }
}

/** A stored text value, exactly as stored: refuse rather than trim padding or blanks. */
export function requireStoredText(value, key) {
  if (typeof value !== "string" || !value || value.trim() !== value)
    throw new Error(
      `stored value for ${key} is empty or has surrounding whitespace; fix it in 1Password`,
    );
  return value;
}

export async function storedText(vault, key) {
  return requireStoredText(await vault.readField(key), key);
}

/** Signing material over an open vault: `{ password(), keystore() }`. */
export const vaultSigningMaterial = (vault) => ({
  password: () => storedText(vault, "password"),
  keystore: () => vault.readDocument("keystore"),
});

const guarded = async (operation, action) => {
  try {
    return await action();
  } catch (error) {
    if (error instanceof VaultError) throw error;
    throw new VaultError(operation);
  }
};

/**
 * Opens the single client and the GVM vault. `create` (generate-key only) creates the
 * vault when absent; every other command requires it to exist exactly once.
 * `load` is the SDK module loader (tests inject a fake).
 */
export async function openVault({
  load = () => import("@1password/sdk"),
  version = "1.0.0",
  create = false,
} = {}) {
  const sdk = await guarded("SDK load", async () => {
    const mod = await load();
    return mod.default ?? mod;
  });
  const client = await guarded("sign-in", () =>
    sdk.createClient({
      auth: new sdk.DesktopAuth(ACCOUNT_ID),
      integrationName: INTEGRATION,
      integrationVersion: version,
    }),
  );
  const vault = await guarded("vault lookup", async () => {
    const found = (await client.vaults.list()).filter((v) => v.title === VAULT);
    if (found.length > 1) throw new VaultError(`ambiguous vault ${VAULT} lookup`);
    if (found.length === 1) return found[0];
    if (!create) throw new VaultError(`vault ${VAULT} lookup`);
    if (typeof client.vaults.create !== "function")
      throw new Error(
        `The installed @1password/sdk cannot create vaults. Create the vault ${VAULT} in 1Password, then rerun.`,
      );
    return client.vaults.create({ title: VAULT });
  });

  const category = (name) => sdk.ItemCategory?.[name] ?? name;
  const concealed = sdk.ItemFieldType?.Concealed ?? "Concealed";
  const getItem = (id) => guarded("item read", () => client.items.get(vault.id, id));
  const documentBytes = async (item) => {
    if (!item.document) throw new VaultError("document read");
    return Buffer.from(
      await guarded("document read", () =>
        client.items.files.read(vault.id, item.id, item.document),
      ),
    );
  };
  const passwordOf = (item) => {
    const fields = (item.fields ?? []).filter((f) => f.id === "password");
    if (fields.length !== 1 || typeof fields[0].value !== "string")
      throw new VaultError("password read");
    return fields[0].value;
  };

  return {
    /** Active items whose titles are listed: public IDs, titles and categories only. */
    async findItems(titles) {
      const items = await guarded("item list", () => client.items.list(vault.id));
      return items
        .filter((i) => titles.includes(i.title))
        .map((i) => ({ id: i.id, title: i.title, category: i.category, state: i.state }));
    },
    /** A text field by secret reference (op://GVM-LED/...). */
    async readField(key) {
      const item = ITEMS[key];
      if (!item?.ref) throw new VaultError("field read");
      return guarded("field read", () => client.secrets.resolve(item.ref));
    },
    /** The single Document item with this key's title. */
    async readDocument(key) {
      const matches = await this.findItems([ITEMS[key].title]);
      if (matches.length !== 1) throw new VaultError(`${ITEMS[key].title} lookup`);
      return documentBytes(await getItem(matches[0].id));
    },
    async readDocumentById(id) {
      return documentBytes(await getItem(id));
    },
    async readPasswordById(id) {
      return passwordOf(await getItem(id));
    },
    async createPassword(title, value) {
      const item = await guarded("password create", () =>
        client.items.create({
          category: category("Password"),
          vaultId: vault.id,
          title,
          fields: [{ id: "password", title: "password", fieldType: concealed, value }],
        }),
      );
      return item.id;
    },
    async createDocument(title, name, bytes) {
      const item = await guarded("document create", () =>
        client.items.create({
          category: category("Document"),
          vaultId: vault.id,
          title,
          document: { name, content: new Uint8Array(bytes) },
        }),
      );
      return item.id;
    },
  };
}
