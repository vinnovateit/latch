import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
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
            implementation(libs.androidx.preference.ktx)
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
    }
}

// KSP config names derive from the target name: jvm("desktop") -> kspDesktop,
// androidTarget -> kspAndroid. Room's expect/actual RoomDatabaseConstructor
// needs an actual generated for every target that compiles commonMain.
dependencies {
    add("kspDesktop", libs.room.compiler.desktop)
    add("kspAndroid", libs.room.compiler.desktop)
}

