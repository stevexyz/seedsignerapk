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
        // Kept in sync with VersionView (Settings → Version) and the README
        // status line — do not bump one without the other.
        versionName = "0.4.0"
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
        // Controller maps Destination.viewArgs to View constructor parameter
        // names, which requires real names in the class file.
        freeCompilerArgs = freeCompilerArgs + "-java-parameters"
    }
}

dependencies {
    implementation(project(":core"))

    // JVM unit tests for the models (Seed/Settings are pure Kotlin, no
    // Android imports). JUnit comes from the Gradle distribution's
    // mavenCentral — build-time only, not packaged into the APK.
    testImplementation("junit:junit:4.13.2")
}
