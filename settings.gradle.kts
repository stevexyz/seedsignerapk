// SeedSigner for Android
//
// spec.md §1.3: the app declares zero third-party *runtime* dependencies.
// The repositories below exist so the build toolchain (Gradle plugins,
// Kotlin compiler/stdlib, Android Gradle Plugin) can be resolved. No
// non-platform artifact may ever be added to a `dependencies { }` block
// of an application/runtime module.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "seedsigner-android"

include(":core")
include(":app")
