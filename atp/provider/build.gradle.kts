// SPDX-License-Identifier: Apache-2.0
// :atp-provider, the provider kit's engine: everything an app needs to offer tools over ATP (SPEC.md 4-8) except the
// tools themselves. It compiles to Kotlin 2.1's language and API so apps on older toolchains can use it.
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nujan_agent.atp.provider"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ATP's provider and host interfaces sit beside the module in every copy of the drop.
androidComponents {
    onVariants { variant -> variant.sources.aidl?.addStaticSourceDirectory(file("../aidl").absolutePath) }
}

kotlin {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

tasks.withType<Test>().configureEach {
    // The fixtures sit beside the module in every copy of it.
    systemProperty("atp.fixtures", file("../fixtures").absolutePath)
}

dependencies {
    api(project(":atp-wire"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
