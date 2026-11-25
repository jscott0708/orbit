import java.time.LocalDateTime
import java.time.ZoneOffset

plugins {
    id("com.android.application")
}

val now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
val stamp = "%02d%02d%02d%02d".format(now.year % 100, now.monthValue, now.dayOfMonth, now.hour)
val geckoviewVersion: String by project
// where finished builds are published, for Orbit's own update check (GitHub "owner/repo"): set by the GitHub build,
// or by updateRepo in gradle.properties for a build made in Android Studio
val updateRepo: String = System.getenv("GITHUB_REPOSITORY") ?: (project.findProperty("updateRepo") as String? ?: "")
// the GitHub build signs with your key (kept as GitHub secrets); Android Studio builds are signed in the wizard
val ciKeystore: String? = System.getenv("ORBIT_KEYSTORE")

android {
    namespace = "com.intentionalmovement.orbit"
    // Firefox's engine is built against the newest Android SDK (37.2); Android Studio downloads it if asked
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.intentionalmovement.orbit"
        minSdk = 29
        targetSdk = 34
        versionCode = stamp.toInt()
        versionName = "2026.11.25 ($stamp)"
        ndk { abiFilters += listOf("arm64-v8a") }   // the Kompakt's processor
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    buildFeatures { buildConfig = true }

    if (ciKeystore != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("ORBIT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORBIT_KEY_ALIAS")
                keyPassword = System.getenv("ORBIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // needed by the WireGuard library
    }

    packaging {
        jniLibs { useLegacyPackaging = true }   // smaller install on the phone
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Firefox's browser engine, 64-bit ARM build
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:$geckoviewVersion")
    // WireGuard (Proton VPN's protocol), only for this app's own traffic
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
