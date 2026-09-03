// Top-level build file where you can add configuration options common to all sub-projects/modules.

<<<<<<< HEAD
// AGP pulls jose4j in transitively through its Google auth stack. 0.9.5
// decompresses JWE payloads without an output bound, so a crafted token can
// exhaust the heap (GHSA-3677-xxcr-wjqv). Build classpath only -- it never
// reaches a shipped artifact -- but the patched release is a drop-in.
buildscript {
    dependencies {
        constraints {
            classpath("org.bitbucket.b_c:jose4j:0.9.6") {
                because("GHSA-3677-xxcr-wjqv: DoS via compressed JWE content in jose4j < 0.9.6")
=======
// AGP pulls jdom2 in transitively through jetifier-processor. 2.0.6 parses XML
// with external entities enabled (GHSA-2363-cqg2-863c, XXE). Nothing here feeds
// it untrusted XML, and it never reaches a shipped artifact -- it is on the
// build classpath only -- but the patched release is a drop-in, so there is no
// reason to keep the vulnerable one around.
buildscript {
    dependencies {
        constraints {
            classpath("org.jdom:jdom2:2.0.6.1") {
                because("GHSA-2363-cqg2-863c: XXE injection in jdom2 < 2.0.6.1")
>>>>>>> origin/main
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