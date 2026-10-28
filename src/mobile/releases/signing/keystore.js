// The dedicated release keystore: its fixed identity (alias, file name, key size,
// validity, subject) and the exact keytool commands over it. Passwords travel only as
// `:env` references to PASS_ENV, never in argv. No I/O; secrets/ (vault plan and dry
// runs) and signing/ (generation and signing) both build on it.

/** Carries the keystore password to keytool (`:env`) and apksigner (`env:`); never argv. */
export const PASS_ENV = "GVM_ANDROID_KEYSTORE_PASSWORD";
export const KEY_ALIAS = "gvm-led-release";
export const KEYSTORE_FILE = "gvm-led-release.p12";
/** About 30 years. */
export const KEY_VALIDITY_DAYS = 10957;
export const KEY_SIZE = 4096;
export const KEY_DNAME = "CN=GVM LED Android Release, O=dirtyredz";

export const keytoolGenerate = ({ keytool, keystore }) => ({
  cmd: keytool,
  args: [
    "-genkeypair",
    "-noprompt",
    "-keystore",
    keystore,
    "-storetype",
    "PKCS12",
    "-alias",
    KEY_ALIAS,
    "-keyalg",
    "RSA",
    "-keysize",
    String(KEY_SIZE),
    "-validity",
    String(KEY_VALIDITY_DAYS),
    "-dname",
    KEY_DNAME,
    "-storepass:env",
    PASS_ENV,
    "-keypass:env",
    PASS_ENV,
  ],
});
export const keytoolExport = ({ keytool, keystore }) => ({
  cmd: keytool,
  args: [
    "-exportcert",
    "-rfc",
    "-keystore",
    keystore,
    "-storetype",
    "PKCS12",
    "-alias",
    KEY_ALIAS,
    "-storepass:env",
    PASS_ENV,
  ],
});
export const keytoolList = ({ keytool, keystore }) => ({
  cmd: keytool,
  args: ["-list", "-keystore", keystore, "-storetype", "PKCS12", "-storepass:env", PASS_ENV],
});
