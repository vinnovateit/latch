// Top-level build file where you can add configuration options common to all sub-projects/modules.

// AGP and the Kotlin Gradle plugin pull these in transitively, so the versions
// are theirs rather than ours and a version-catalog bump cannot reach them.
// Every one of them is on the build classpath only -- none is packaged into the
// APK, the desktop image or the CLI bundle -- but each patched release is a
// drop-in, so there is no reason to keep a flagged version around.
buildscript {
    dependencies {
        constraints {
            // 2.0.6 parses XML with external entities enabled. Reached through
            // AGP -> jetifier-processor; nothing here feeds it untrusted XML.
            classpath("org.jdom:jdom2:2.0.6.1") {
                because("GHSA-2363-cqg2-863c: XXE injection in jdom2 < 2.0.6.1")
            }
            // 0.9.5 decompresses JWE payloads without an output bound, so a
            // crafted token can exhaust the heap. Reached through AGP's Google
            // auth stack; nothing here processes JWEs.
            classpath("org.bitbucket.b_c:jose4j:0.9.6") {
                because("GHSA-3677-xxcr-wjqv: DoS via compressed JWE content in jose4j < 0.9.6")
            }
            // 1.80.2 does not escape values used to build LDAP filters. Reached
            // through both AGP and the Kotlin Gradle plugin; nothing here does LDAP.
            classpath("org.bouncycastle:bcprov-jdk18on:1.84") {
                because("GHSA-c3fc-8qff-9hwx: LDAP injection in bcprov-jdk18on < 1.84")
            }
            // Modules in 1.80.2 still use a broken cryptographic algorithm. Ships
            // alongside bcprov, pulled by AGP and the Kotlin Gradle plugin.
            classpath("org.bouncycastle:bcpkix-jdk18on:1.84") {
                because("GHSA-wg6q-6289-32hp: broken cryptographic algorithm in bcpkix-jdk18on < 1.84")
            }
            // 3.16.0 recurses without a depth bound on long inputs, so a large
            // string can overflow the stack. Reached through AGP.
            classpath("org.apache.commons:commons-lang3:3.18.0") {
                because("GHSA-j288-q9x7-2f5v: uncontrolled recursion in commons-lang3 < 3.18.0")
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