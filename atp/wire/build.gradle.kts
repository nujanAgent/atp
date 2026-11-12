// SPDX-License-Identifier: Apache-2.0
// :atp-wire is pure Kotlin/JVM: framing, the ATP codecs and the logic both sides share, with no Android
// dependency, so the same files compile in the provider, the host and these tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // The fixtures sit beside the module in every copy of it.
    systemProperty("atp.fixtures", file("../fixtures").absolutePath)
    maxHeapSize = "1g"
}
