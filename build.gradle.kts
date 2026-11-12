// Root build file: the plugins the modules apply, at the versions in gradle/libs.versions.toml (AGP 9 with its
// built-in Kotlin support, JDK 17).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
