// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    // Declared here, applied by :desktop. kotlin-android above already puts the
    // Kotlin plugin on the buildscript classpath "with an unknown version", so a
    // subproject asking for kotlin-multiplatform 2.2.10 on its own is rejected as
    // uncheckable. Pinning it at the root makes the version known and resolvable.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false

    // Same reason as kotlin-multiplatform above: pin the version at the root
    // so :cli's plain kotlin("jvm") application can resolve it.
    alias(libs.plugins.kotlin.jvm) apply false
}