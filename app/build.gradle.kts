import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is supplied either by a local, git-ignored keystore.properties or by
// environment variables in CI. When neither is present the release build still runs and
// simply produces an unsigned artifact, so a fork without secrets is not broken by this.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)

// Play rejects any upload whose versionCode is not strictly higher than the last one, which is
// the most common publishing failure. CI supplies one derived from the workflow run number so
// repeat publishes cannot collide; local and unattended builds fall back to the constants.
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull()
    ?: System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: 1
val appVersionName = (findProperty("appVersionName") as String?)
    ?: System.getenv("VERSION_NAME")
    ?: "1.0"

val releaseStoreFile = signingValue("storeFile", "ANDROID_KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

// Names the build outputs, so they arrive as orty.starlink_guard-release.aab rather than the
// default app-release.aab. CI trims the variant suffix off the two shipping artifacts; it is
// kept locally because a debug and a release build sharing one filename is a trap.
base {
    archivesName.set("orty.starlink_guard")
}

android {
    namespace = "dev.starlinkguard"
    // Google Play requires new apps to target Android 16 (API 36) from 31 August 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.starlinkguard"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Left off for the first release on purpose. R8 breakage shows up at runtime
            // rather than at build time, and nothing here has been exercised on a physical
            // device yet. proguard-rules.pro already carries the keeps this app needs, so
            // turning this on is a one-line change once the app has been tested on hardware.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // The build should not fall over on a lint warning, but the report is still produced.
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Lets CI assert that the versionCode override actually takes effect. A silent failure here
// would only surface as a Play rejection on the first automated publish.
tasks.register("printVersionCode") {
    val resolved = appVersionCode
    doLast { println("versionCode=$resolved") }
}
