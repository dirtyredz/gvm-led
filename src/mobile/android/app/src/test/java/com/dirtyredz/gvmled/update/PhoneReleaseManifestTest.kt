package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.data.json.Json
import com.dirtyredz.gvmled.update.UpdateFixtures.contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneReleaseManifestTest {
    private fun parse(json: String, with: ReleaseContract = contract) =
        PhoneReleaseManifest.parseOrNull(json.toByteArray(), with)

    @Test fun sharedFixturesAgreeWithTheNodeValidator() {
        assertEquals(UpdateFixtures.PIN, UpdateFixtures.sharedFixtures().string("certificateSha256"))
        for ((name, manifest) in UpdateFixtures.sharedCases("valid")) {
            assertNotNull("valid: $name", parse(Json.write(manifest)))
        }
        for ((name, manifest) in UpdateFixtures.sharedCases("invalid")) {
            assertNull("invalid: $name", parse(Json.write(manifest)))
        }
    }

    @Test fun theBundledContractIsTheCommittedOne() {
        val real = ReleaseContract.parse(UpdateFixtures.contractJson(), UpdateFixtures.certificateJson())
        assertEquals("dirtyredz/gvm-led", real.repository)
        assertEquals("com.dirtyredz.gvmled", real.packageName)
        assertEquals("com.dirtyredz.gvmled.debug", real.developmentPackageName)
        assertEquals("phone-release.json", real.manifestAsset)
        assertEquals("GVM-LED-0.1.2-android.apk", real.apkName("0.1.2"))
        assertEquals(104_857_600L, real.maxApkBytes)
        assertEquals(8_192L, real.maxManifestBytes)
        assertEquals(20, real.maxAssetsPerRelease)
        assertTrue(ReleaseContract.isHex(real.certificateSha256, 64))
    }

    @Test fun aPinForAnotherPackageIsNoPin() {
        val foreign = UpdateFixtures.certificateJson().replace("\"com.dirtyredz.gvmled\"", "\"com.example\"")
        assertNull(ReleaseContract.parse(UpdateFixtures.contractJson(), foreign).certificateSha256)
    }

    @Test fun withoutAPinNothingValidates() {
        assertNull(parse(UpdateFixtures.manifestJson(), contract.copy(certificateSha256 = null)))
    }

    @Test fun manifestsAreBoundedJsonWithoutDuplicateKeys() {
        val valid = UpdateFixtures.manifestJson()
        assertNotNull(parse(valid))
        assertNull(parse(valid.dropLast(1)))
        assertNull(parse(valid.replace("{\"schema\":1,", "{\"schema\":1,\"schema\":1,")))
        assertNull(parse(valid + " ".repeat(8_192)))
        assertNull(parse(valid.replace(UpdateFixtures.PIN, UpdateFixtures.OTHER_PIN)))
    }
}
