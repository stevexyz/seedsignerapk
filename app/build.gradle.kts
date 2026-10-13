// `app` — thin Android shell, classic framework Views only (spec.md §1.4).
// No AndroidX, no Google first-party runtime artifacts.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seedsigner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seedsigner"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
}
