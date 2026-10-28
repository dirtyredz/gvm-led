package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.data.json.Json
import com.dirtyredz.gvmled.data.json.JsonArray
import com.dirtyredz.gvmled.data.json.JsonObject
import java.io.File

/** Test inputs for the updater: the real bundled contract (with a fake pin) and typed release listings. */
internal object UpdateFixtures {
    const val PIN = "5555555555555555555555555555555555555555555555555555555555555555"
    const val OTHER_PIN = "6666666666666666666666666666666666666666666666666666666666666666"

    private val releaseDir = File(checkNotNull(System.getProperty("gvm.releaseDir")) { "gvm.releaseDir not set" })

    fun contractJson(): String = releaseDir.resolve("release-contract.json").readText()

    fun certificateJson(): String = releaseDir.resolve("signing-certificate.json").readText()

    /** The committed contract, with the fixture pin instead of the real one. */
    val contract: ReleaseContract = ReleaseContract.parse(contractJson(), certificateJson()).copy(certificateSha256 = PIN)

    /** `test/mobile/releases/fixtures/update-contract.json`, shared with the Node validator. */
    fun sharedFixtures(): JsonObject {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("update-contract.json")) {
            "Shared fixtures are not on the test classpath"
        }
        return Json.parseObject(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    fun sharedCases(kind: String): List<Pair<String, JsonObject>> =
        (sharedFixtures()[kind] as JsonArray).items.map { case ->
            case as JsonObject
            case.string("name")!! to case.obj("manifest")!!
        }

    /** Release name for [code]; code 1 is the debug/bootstrap build, which has no release name. */
    fun versionName(code: Long) = ReleaseVersion.versionNameForCode(code) ?: "0.1.0"

    fun manifestJson(
        versionCode: Long = 3,
        minSdk: Int = 26,
        size: Long = 4,
        sha256: String = "b".repeat(64),
        pin: String = PIN,
    ): String {
        val name = versionName(versionCode)
        return """{"schema":1,"repository":"dirtyredz/gvm-led","channel":"stable","packageName":"com.dirtyredz.gvmled",""" +
            """"versionName":"$name","versionCode":$versionCode,"minSdk":$minSdk,"commit":"${"a".repeat(40)}",""" +
            """"certificateSha256":"$pin","apk":{"name":"GVM-LED-$name-android.apk","size":$size,"sha256":"$sha256"}}"""
    }

    fun asset(tag: String, name: String, size: Long) =
        ReleaseAsset(name, size, "https://github.com/dirtyredz/gvm-led/releases/download/$tag/$name")

    /** A typed `releases/latest` response. Assets default to the manifest plus the signed APK. */
    fun releaseListing(
        versionCode: Long = 3,
        apkSize: Long = 4,
        tag: String = "android-v${versionName(versionCode)}",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<ReleaseAsset>? = null,
    ): ReleaseListing {
        val name = versionName(versionCode)
        val listed = assets ?: listOf(asset(tag, "phone-release.json", 400), asset(tag, "GVM-LED-$name-android.apk", apkSize))
        return ReleaseListing(tag, "https://github.com/dirtyredz/gvm-led/releases/tag/$tag", draft, prerelease, listed)
    }

    fun installed(versionCode: Long = 2, packageName: String = "com.dirtyredz.gvmled", signers: Set<String> = setOf(PIN)) =
        InstalledApp(packageName, versionName(versionCode), versionCode, signers)

    fun archive(versionCode: Long = 3, packageName: String = "com.dirtyredz.gvmled", signers: Set<String> = setOf(PIN), minSdk: Int = 26) =
        ArchiveIdentity(packageName, versionName(versionCode), versionCode, minSdk, signers)
}
