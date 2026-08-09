import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Single JVM target. Windows/Linux/macOS differences are handled at RUNTIME via
// the OsBindings interface, not with separate Kotlin targets -- Compose Desktop
// produces one JVM artifact and jpackage runs per host OS.
kotlin {
    // JDK 17 is what is installed and is the Gradle daemon JVM; it satisfies
    // both Compose Desktop and jpackage. Bumping this requires a toolchain
    // download to be configured.
    jvmToolchain(17)

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)

            // Explicit coordinates -- see libs.versions.toml for why not compose.material3
            implementation(libs.compose.material3)

            // NOTE: material-icons-extended is deliberately NOT declared. It is a
            // 36 MB jar -- a third of the entire installer -- and only ~31 of its
            // icons are ever used. When the remaining screens are ported, vendor
            // those icons as ImageVector (the pattern already used by
            // ThemedDrawables/ExportNotes in the Android app) rather than adding
            // the dependency back.

            implementation(libs.material.kolor)
            implementation(libs.lifecycle.viewmodel.mp)
            implementation(libs.lifecycle.viewmodel.compose.mp)
            implementation(libs.lifecycle.runtime.compose.mp)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)

            implementation(libs.room.runtime.desktop)
            implementation(libs.sqlite.bundled)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Required: Compose Desktop dispatches on the AWT event thread.
            implementation(libs.kotlinx.coroutines.swing)
            // Per-interface byte counters. Brings jna transitively; jna-platform
            // is explicit for DPAPI (credentials) + registry (autostart).
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

/**
 * Headless verification of the Windows platform layer, so the risky native seams
 * (SSID detection, OSHI, DPAPI, Room) can be checked without launching the GUI.
 */
val smoke by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the headless platform smoke test."
    mainClass.set("com.vinnovateit.latch.desktop.SmokeMainKt")
    // For a jvm("desktop") target the runtime classpath configuration is named
    // after the target.
    classpath = files(
        layout.buildDirectory.dir("classes/kotlin/desktop/main"),
        layout.buildDirectory.dir("processedResources/desktop/main"),
        configurations.named("desktopRuntimeClasspath"),
    )
    dependsOn("desktopMainClasses", "assembleDesktopMainResources")
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.vinnovateit.latch.desktop.resources"
    generateResClass = auto
}

compose.desktop {
    application {
        mainClass = "com.vinnovateit.latch.desktop.MainKt"

        // What Task Manager reports for a JVM is not the heap -- it is heap +
        // metaspace + JIT code cache + GC bookkeeping + Skia's surfaces. -Xmx
        // alone only bounds the first of those, which is why a 256 MB cap still
        // showed a few hundred MB of RSS.
        jvmArgs += listOf(
            "-Xmx256m",

            // A tray app that idles most of the day cares about footprint, not
            // pause times. G1 (the default) reserves per-region remembered sets
            // and starts several worker threads for a heap this small; the
            // serial collector has neither and gives back tens of MB of native
            // overhead for GC pauses nobody will see at this heap size.
            "-XX:+UseSerialGC",
            // Serial GC is also the collector that will actually *shrink* the
            // committed heap: after a full GC it resizes to keep free space
            // within these ratios and hands the rest back to the OS. Without
            // them the heap ratchets up to its high-water mark and stays there
            // for the rest of the session.
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",

            // Both default to reserving far more than this app uses (the code
            // cache alone reserves 240 MB), and both grow committed memory
            // monotonically.
            "-XX:MaxMetaspaceSize=128m",
            "-XX:ReservedCodeCacheSize=64m",

            "-Dfile.encoding=UTF-8",
        )

        // `packageReleaseMsi` runs the jars through ProGuard. Obfuscation is off:
        // it saves little here and makes stack traces in the support log useless.
        buildTypes.release.proguard {
            obfuscate.set(false)
            optimize.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Latch"
            // jpackage REQUIRES MAJOR.MINOR.PATCH with MAJOR >= 1. The Android
            // versionName "1.3" has only two components and would be rejected.
            packageVersion = "1.3.5"
            description = "Auto-login for VIT hostel Wi-Fi"
            vendor = "VinnovateIT"
            copyright = "(c) 2026 VinnovateIT"

            // jpackage bundles a JRE via jlink, so this list is load-bearing.
            // java.management -> OSHI, jdk.unsupported -> JNA's Unsafe.
            // sqlite-bundled ships its own JNI lib, so java.sql is not needed.
            modules("java.management", "java.naming", "jdk.unsupported", "java.instrument")

            windows {
                // Without this jpackage stamps its own stock Java icon onto
                // Latch.exe, which is then what the Start menu, the desktop
                // shortcut and Explorer show. Regenerate with
                // `java GenerateIcon.java` in that directory -- it is built from
                // the same path geometry as the tray and window icons.
                iconFile.set(project.file("icons/latch.ico"))

                menu = true
                menuGroup = "Latch"
                shortcut = true
                // Per-user install: no admin prompt, and pairs correctly with an
                // HKCU Run key and %LOCALAPPDATA% data paths.
                perUserInstall = true
                dirChooser = true
                // MUST stay fixed forever. Change it and every MSI becomes a
                // distinct product that installs side-by-side instead of upgrading.
                upgradeUuid = "6f3b9c84-1d52-4e7a-9b06-2a8f5c14d7e3"
                console = false
            }
        }
    }
}
