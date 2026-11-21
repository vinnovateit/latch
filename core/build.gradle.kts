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

    // Kotlin/Native desktop target for the SDL/Skia build. Kept alongside the
    // JVM target so the CLI and JVM reference build continue to work.
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                create("curl")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)

            // LatchDatabase (in commonMain -- Room's expect/actual
            // RoomDatabaseConstructor pattern requires the @Database class to
            // live here, compiled for every target) extends RoomDatabase, and
            // that type crosses the module boundary into :desktop, :cli and
            // :app -- api, not implementation, or their compile fails with a
            // missing supertype. Only the runtime artifact goes in commonMain;
            // the SQLite driver is target-specific (see desktopMain below --
            // Android needs no equivalent, it has its own framework driver).
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
    }
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
