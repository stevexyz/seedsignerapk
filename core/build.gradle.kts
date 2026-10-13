// `core` — pure Kotlin, no Android imports (see docs/code_structure.md).
// Everything here runs and is unit-tested on a plain JVM.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // Test-only. Nothing here may reach the APK.
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Differential-testing harness, driven by tools/crosscheck_bip39.py (spec.md §3b).
// Usage: ./gradlew :core:crosscheck --args="<input-file> <output-file>"
tasks.register<JavaExec>("crosscheck") {
    dependsOn("compileKotlin", "compileTestKotlin")
    mainClass.set("com.seedsigner.core.crypto.CrosscheckKt")
    classpath = sourceSets.main.get().output + sourceSets.test.get().output +
        configurations.getByName("testRuntimeClasspath")
}

// PSBT differential-testing harness, driven by tools/crosscheck_psbt.py (spec.md §3b).
tasks.register<JavaExec>("crosscheck-psbt") {
    dependsOn("compileKotlin", "compileTestKotlin")
    mainClass.set("com.seedsigner.core.psbt.CrosscheckPsbtKt")
    classpath = sourceSets.main.get().output + sourceSets.test.get().output +
        configurations.getByName("testRuntimeClasspath")
}

// UR2 differential-testing harness, driven by tools/crosscheck_ur2.py (spec.md §3b).
tasks.register<JavaExec>("crosscheck-ur2") {
    dependsOn("compileKotlin", "compileTestKotlin")
    mainClass.set("com.seedsigner.core.qr.ur2.CrosscheckUr2Kt")
    classpath = sourceSets.main.get().output + sourceSets.test.get().output +
        configurations.getByName("testRuntimeClasspath")
}
