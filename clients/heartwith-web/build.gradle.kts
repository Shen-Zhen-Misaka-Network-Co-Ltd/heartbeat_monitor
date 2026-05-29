import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
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
        commonMain {
            kotlin.srcDir("../heartwith-shared/src/commonMain/kotlin")
            dependencies {
                implementation(libs.compose.foundation)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.kotlinx.serialization.cbor)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.miuix.icons)
                implementation(libs.miuix.ui)
            }
        }
        wasmJsMain.dependencies {
            implementation(libs.compose.components.resources)
            implementation(libs.ktor.client.js)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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
