// Fakes for release-credential tests: an in-memory vault, a scripted child runner
// and in-memory material. No 1Password, no child processes, no temp files.
import { testCertificate } from "./x509.js";

export function fakeVault({ items = [], fields = {}, failCreate = null, tamper = null } = {}) {
  const store = new Map(items.map((i) => [i.id, i]));
  let next = 1;
  const calls = [];
  return {
    calls,
    store,
    async findItems(titles) {
      calls.push(["findItems", titles]);
      return [...store.values()]
        .filter((i) => titles.includes(i.title))
        .map(({ id, title }) => ({ id, title }));
    },
    async readField(key) {
      calls.push(["readField", key]);
      if (!(key in fields)) throw new Error("1Password field read failed");
      return fields[key];
    },
    async readDocument(key) {
      calls.push(["readDocument", key]);
      const item = [...store.values()].find((i) => i.key === key);
      if (!item) throw new Error("1Password document read failed");
      return Buffer.from(item.bytes);
    },
    async readDocumentById(id) {
      calls.push(["readDocumentById", id]);
      const bytes = Buffer.from(store.get(id).bytes);
      return tamper === "document" ? Buffer.concat([bytes, Buffer.from("x")]) : bytes;
    },
    async readPasswordById(id) {
      calls.push(["readPasswordById", id]);
      const value = store.get(id).value;
      return tamper === "password" ? value + "x" : value;
    },
    async createPassword(title, value) {
      calls.push(["createPassword", title]);
      if (failCreate === "password") throw new Error("1Password password create failed");
      const id = `item${next++}`;
      store.set(id, { id, title, value, key: "password" });
      return id;
    },
    async createDocument(title, name, bytes) {
      calls.push(["createDocument", title, name]);
      if (failCreate === "document") throw new Error("1Password document create failed");
      const id = `item${next++}`;
      store.set(id, { id, title, bytes: Buffer.from(bytes), key: "keystore" });
      return id;
    },
  };
}

/** A runner that answers keytool commands; records every spec (env and input included). */
export function fakeKeytool({
  pem = testCertificate({ bits: 4096 }),
  entries = 1,
  failOn = null,
} = {}) {
  const specs = [];
  const run = async (spec) => {
    specs.push(spec);
    const verb = spec.args[0];
    if (failOn === verb) return { code: 1, stdout: "", stderr: "" };
    if (verb === "-list")
      return {
        code: 0,
        stdout: `Keystore type: PKCS12\n\nYour keystore contains ${entries} entry\n\ngvm-led-release, Oct 18, 2026, PrivateKeyEntry, \nCertificate fingerprint (SHA-256): AA\n`,
        stderr: "",
      };
    if (verb === "-exportcert") return { code: 0, stdout: pem, stderr: "" };
    return { code: 0, stdout: "", stderr: "" };
  };
  return { run, specs, pem };
}

export function memoryFs(files = {}) {
  const map = new Map(Object.entries(files));
  return {
    map,
    async readFile(file, encoding) {
      if (!map.has(file)) {
        // keytool "creates" the keystore: model it as present bytes.
        if (String(file).endsWith(".p12")) return Buffer.from("PKCS12 test bytes");
        throw Object.assign(new Error("ENOENT"), { code: "ENOENT" });
      }
      const value = map.get(file);
      return encoding ? value.toString() : Buffer.from(value);
    },
    async writeFile(file, data) {
      map.set(file, typeof data === "string" ? data : Buffer.from(data));
    },
  };
}

export function memoryMaterial() {
  const state = { cleaned: 0, dir: "/tmp/material" };
  return {
    state,
    factory: async () => ({
      dir: state.dir,
      file: (name) => `${state.dir}/${name}`,
      cleanup: async () => {
        state.cleaned++;
      },
    }),
  };
}

export const RECORD = JSON.stringify({
  packageName: "com.dirtyredz.gvmled",
  alias: "gvm-led-release",
  certificateSha256: null,
  notAfter: null,
  bootstrapVersionCode: 1,
});
