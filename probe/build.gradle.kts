plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.bydlauncher.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bydlauncher.probe"
        minSdk = 21
        // Kept low on purpose: a diagnostic tool that reads OEM framework classes by
        // reflection, which newer target levels restrict more tightly.
        targetSdk = 27
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        // Fixed, public debug key committed to the repo so every CI build can be
        // installed over the previous one. Not a release key.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
