plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.heartwith.uploader"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
