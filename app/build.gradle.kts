import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Release signing comes from keystore.properties at the project root (git-ignored):
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds stay unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "com.azuchan.fabula"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.azuchan.fabula"
        minSdk = 34
        // Android 17 (API 37) is still documented as a preview; target the latest stable API.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Whole-app R8: scoping it to library packages produced classes extending
            // inaccessible renamed Kotlin classes (IllegalAccessError at startup). JNI entry
            // points stay intact through the default keep rules for native methods.
            optimization {
                enable = true
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

    // Native RAW alignment, 2x reconstruction, JPEG encoding and colour looks (src/main/cpp).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// The signed release is also delivered as app/build/outputs/fabula/fabula.apk.
val fabulaReleaseApk = tasks.register<Copy>("fabulaReleaseApk") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    rename { "fabula.apk" }
    into(layout.buildDirectory.dir("outputs/fabula"))
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(fabulaReleaseApk) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":camera"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
