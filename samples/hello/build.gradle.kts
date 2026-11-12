// SPDX-License-Identifier: Apache-2.0
// An app that offers one tool to agents on the same phone: the provider kit, one tool source and its XML.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nujan_agent.atp.sample.hello"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nujan_agent.atp.sample.hello"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":atp-provider"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}
