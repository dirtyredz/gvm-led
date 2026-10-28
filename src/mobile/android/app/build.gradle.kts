plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release identity comes from CI's allocation (scripts/mobile/release.js version), as in
// Command Deck: versionName 0.1.<n>, versionCode n + 1. Local debug builds keep the defaults.
val phoneCode = providers.gradleProperty("phoneVersionCode").orNull?.toIntOrNull()
val phoneName = providers.gradleProperty("phoneVersionName").orNull
if (phoneCode != null) require(phoneCode in 2..2100000000) { "Invalid phoneVersionCode" }
if (phoneName != null) require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(phoneName)) { "Invalid phoneVersionName" }

android {
    namespace = "com.dirtyredz.gvmled"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.dirtyredz.gvmled"
        minSdk = 26
        targetSdk = 36
        versionCode = phoneCode ?: 1
        versionName = phoneName ?: "0.1.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isDebuggable = false
            signingConfig = null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        // Pinned deliberately to CD's SDK 36 / AGP 8.13 line.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        abortOnError = true
    }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// The release contract and signer pin stay authoritative in src/mobile/releases/; the app bundles
// copies as assets (release/...) so the updater validates GitHub releases against the same rules.
abstract class BundleReleaseContract : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile.resolve("release")
        target.deleteRecursively()
        target.mkdirs()
        sources.files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}
val releaseToolingDir = layout.projectDirectory.dir("../../releases")
val bundleReleaseContract = tasks.register<BundleReleaseContract>("bundleReleaseContract") {
    sources.from(releaseToolingDir.file("release-contract.json"), releaseToolingDir.file("signing-certificate.json"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleReleaseContract, BundleReleaseContract::outputDir)
    }
}
// Unit tests read the shared manifest fixtures (also checked by the Node validator) and the contract.
android.sourceSets.getByName("test").resources.srcDirs(
    layout.projectDirectory.dir("../../../../test/mobile/releases/fixtures"),
)
tasks.withType<Test>().configureEach {
    systemProperty("gvm.releaseDir", releaseToolingDir.asFile.absolutePath)
    inputs.files(releaseToolingDir.file("release-contract.json"), releaseToolingDir.file("signing-certificate.json"))
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
}

// No signing material in Gradle: CI builds unsigned and the owner signs locally
// (docs/mobile/android-distribution.md).
val unsignedRelease = providers.gradleProperty("unsignedRelease").orNull == "true"
tasks.configureEach {
    if (name == "packageRelease") {
        val missing = listOfNotNull(
            "-PunsignedRelease=true".takeIf { !unsignedRelease },
            "-PphoneVersionCode".takeIf { phoneCode == null },
            "-PphoneVersionName".takeIf { phoneName == null },
        )
        doFirst {
            if (missing.isNotEmpty())
                throw GradleException("Release packaging requires ${missing.joinToString()} (docs/mobile/android-distribution.md).")
        }
    }
}
dependencies {
    // Keep the Compose/activity transitive runtime on the same supporting-library pins as CD.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    constraints { implementation(libs.kotlinx.serialization.core) }
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
