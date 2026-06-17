// Part of the Spraak derivative. GPL-3.0-or-later.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: android/keystore.properties (gitignored — NEVER commit it) with
// storeFile/storePassword/keyAlias/keyPassword. When absent, release falls back to
// the debug key so any checkout still builds; only the keystore holder can sign
// builds that update an installed release.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.thermetery.spraak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thermetery.spraak"
        minSdk = 26          // matches PRAAT_ANDROID_API in android/androidenv.sh
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // libpraat.so staged by android/build-bridge.sh
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // the engine .so dominates size; enable R8 only after separate testing
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
