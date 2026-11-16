plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.azuchan.fabula.camera"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Camera2 is part of the Android framework; coroutines will back its Flow-based API.
    implementation(libs.kotlinx.coroutines.android)
}
