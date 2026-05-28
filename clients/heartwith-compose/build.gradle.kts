import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

val heartwithVersionCode = 2
val heartwithVersionName = "0.1.1"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidTarget()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "heartwith.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.icons)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.compose.components.resources)
            implementation(libs.ktor.client.js)
        }
    }
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
    namespace = "com.heartwith.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.heartwith.app"
        minSdk = 26
        targetSdk = 36
        versionCode = heartwithVersionCode
        versionName = heartwithVersionName
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

tasks.withType<KotlinWebpack>().configureEach {
    doLast {
        outputDirectory.get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "map" }
            .forEach { it.delete() }
    }
}
