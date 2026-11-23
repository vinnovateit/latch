import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val latchVersion = providers.gradleProperty("latchVersion").get()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    android {
        namespace = "com.vinnovateit.latch.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Kotlin/Native desktop targets for the SDL/Skia build. Kept alongside the
    // JVM target so the CLI and JVM reference build continue to work.
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                create("curl")
            }
        }
    }

    // Windows native desktop target. No cinterop: HTTP goes through curl.exe
    // from the mingwX64 JRuntime implementation instead of libcurl.
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)

            // Room has no mingwX64 klib. The shared metadata compile keeps the
            // real API; the Windows platform compile substitutes source stubs.
            api(libs.room.runtime.desktop)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.security.crypto)
        }

        val desktopMain by getting
        val desktopTest by getting

        desktopMain.dependencies {
            // JVM has no built-in SQLite; Android does, so this stays desktop-only.
            api(libs.sqlite.bundled)

            implementation(libs.oshi.core)
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.slf4j.simple)
        }

        desktopTest.dependencies {
            implementation(kotlin("test"))
        }

        val linuxX64Main by getting
        linuxX64Main.dependencies {
            // Bundled SQLite ships a linuxX64 klib, so Room needs no JNI here.
            api(libs.sqlite.bundled)
        }

        // Neither Room nor bundled SQLite ships a mingwX64 klib.
        val mingwX64Main by getting
    }
}

// The shared compile uses the real Room API; Windows platform configurations
// substitute a tiny module with the same androidx.* declarations (see :roomstub).
configurations.matching { it.name.contains("mingwX64", ignoreCase = true) }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.room:room-runtime")).using(project(":roomstub"))
    }
    exclude(group = "androidx.sqlite", module = "sqlite-bundled")
}

// KSP config names derive from the target name: jvm("desktop") -> kspDesktop,
// androidTarget -> kspAndroid. Room's expect/actual RoomDatabaseConstructor
// needs an actual generated for every target that compiles commonMain.
dependencies {
    add("kspDesktop", libs.room.compiler.desktop)
    add("kspAndroid", libs.room.compiler.desktop)
    add("kspLinuxX64", libs.room.compiler.desktop)
}

// latch-version.properties is checked in as `version=${latchVersion}` and is the
// only place :cli's --version, DesktopPlatformServices.versionName and the
// GithubUpdater's "am I out of date" comparison get the version from. Nothing
// substituted that token, so every desktop and CLI build shipped the literal
// string. Scoped by filesMatching because expand() runs a Groovy template over
// whatever it matches, and declared as an input so a latchVersion bump
// invalidates the task instead of reusing a stale processed resource.
tasks.named<ProcessResources>("desktopProcessResources") {
    inputs.property("latchVersion", latchVersion)
    filesMatching("latch-version.properties") {
        expand("latchVersion" to latchVersion)
    }
}

// Lets the desktopTest source set assert the packaged resource against the
// gradle.properties value rather than a hardcoded copy of it.
tasks.named<Test>("desktopTest") {
    systemProperty("latchVersion", latchVersion)
}
