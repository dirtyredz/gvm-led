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
    implementation(libs.kotlinx.coroutines.android)
    constraints { implementation(libs.kotlinx.serialization.core) }
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
