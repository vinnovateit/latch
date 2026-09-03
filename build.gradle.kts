// Top-level build file where you can add configuration options common to all sub-projects/modules.

// AGP declares httpclient 4.5.6, which is vulnerable to XSS in its error
// responses (GHSA-7r82-7xv7-xcpj, fixed in 4.5.13). Conflict resolution
// already lands the build classpath on 4.5.14, so this constraint changes
// nothing today -- it states the safe floor rather than leaving it incidental
// to whichever AGP version happens to win.
buildscript {
    dependencies {
        constraints {
            classpath("org.apache.httpcomponents:httpclient:4.5.14") {
                because("GHSA-7r82-7xv7-xcpj: cross-site scripting in httpclient < 4.5.13")
            }
        }
    }
}
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