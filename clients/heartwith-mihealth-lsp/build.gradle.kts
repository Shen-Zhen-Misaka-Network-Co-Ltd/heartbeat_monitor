plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.heartwith.mihealth.lsp"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.heartwith.mihealth.lsp"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            // Xposed/NPatch discovers entry points and hooks through reflection.
            // Keep release bytecode unminified; verbose logs are still gated by BuildConfig.DEBUG.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.0")
    compileOnly(project(":xposed-api-stub"))
}
