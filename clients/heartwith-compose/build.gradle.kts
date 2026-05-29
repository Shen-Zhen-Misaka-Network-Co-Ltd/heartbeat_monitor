val heartwithVersionCode = (findProperty("heartwithClientVersionCode") as String).toInt()
val heartwithVersionName = findProperty("heartwithClientVersionName") as String

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.heartwith.app"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "com.heartwith.app"
        minSdk = 26
        targetSdk = 36
        versionCode = heartwithVersionCode
        versionName = heartwithVersionName
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            res.directories.add("src/androidMain/res")
            kotlin.directories.add("src/androidMain/kotlin")
            kotlin.directories.add("../heartwith-shared/src/commonMain/kotlin")
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.ui)
}

afterEvaluate {
    tasks.named("assembleRelease") {
        doLast {
            val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val source = releaseDir.resolve("heartwith-compose-release.apk")
            val target = releaseDir.resolve("Heartwith-v$heartwithVersionName-$heartwithVersionCode-release.apk")
            if (source.exists()) source.copyTo(target, overwrite = true)
        }
    }
}
