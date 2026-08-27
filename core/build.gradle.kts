import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget {
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
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        val desktopMain by getting

        desktopMain.dependencies {
            // LatchDatabase extends RoomDatabase, so this type crosses the
            // module boundary into :desktop and :cli -- api, not
            // implementation, or their compile fails with a missing
            // supertype.
            api(libs.room.runtime.desktop)
            api(libs.sqlite.bundled)

            implementation(libs.oshi.core)
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.slf4j.simple)
        }
    }
}

// KSP config name derives from the target name: jvm("desktop") -> kspDesktop,
// NOT kspJvm.
dependencies {
    add("kspDesktop", libs.room.compiler.desktop)
}

android {
    namespace = "com.vinnovateit.latch.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
