import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.*

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Single JVM target. Windows/Linux/macOS differences are handled at RUNTIME via
// the OsBindings interface, not with separate Kotlin targets -- Compose Desktop
// produces one JVM artifact and jpackage runs per host OS.
kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":core"))
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
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Required: Compose Desktop dispatches on the AWT event thread.
            implementation(libs.kotlinx.coroutines.swing)
            // Room, sqlite, OSHI and slf4j moved to :core along with the
            // engine/platform code that uses them (Room is exposed as `api`
            // there, so LatchDatabase is still visible here transitively).
            // jna/jna-platform stay declared directly: WindowsBalloonNotifier
            // and LinuxAppIndicatorTray (tray-icon integration, GUI-only,
            // stayed in :desktop) call JNA APIs directly.
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
    }
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

val generateComposePropertiesBin by tasks.registering {
    val outputFile = project.layout.buildDirectory.file("compose/tmp/checkRuntime/properties.bin")
    outputs.file(outputFile)
    doLast {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        val clazz = Class.forName("org.jetbrains.compose.desktop.application.internal.JvmRuntimeProperties")
        val ctor = clazz.getDeclaredConstructor(Int::class.javaPrimitiveType, Class.forName("java.util.List"))
        ctor.isAccessible = true
        val modules = listOf("java.base", "java.desktop", "java.logging", "jdk.crypto.ec", "java.management", "java.naming", "jdk.unsupported", "java.instrument")
        val instance = ctor.newInstance(21, modules)
        val fos = FileOutputStream(file)
        val oos = ObjectOutputStream(fos)
        try {
            oos.writeObject(instance)
        } finally {
            oos.close()
            fos.close()
        }
    }
}

tasks.matching { it.name == "checkRuntime" }.configureEach {
    enabled = false
}

tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    dependsOn(generateComposePropertiesBin)
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.vinnovateit.latch.desktop.resources"
    generateResClass = auto
}

compose.desktop {
    application {
        mainClass = "com.vinnovateit.latch.desktop.MainKt"

        jvmArgs += listOf(
            "-Xms8m",
            "-Xmx64m",
            "-XX:MaxMetaspaceSize=48m",
            "-XX:CompressedClassSpaceSize=16m",
            "-XX:ReservedCodeCacheSize=16m",
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=20",
            "-Dfile.encoding=UTF-8",
        )


        // `packageRelease*` runs the jars through ProGuard: dead-code shrinking,
        // optimisation, and name obfuscation. SourceFile is kept so crash line
        // numbers survive; class/method names are scrambled.
        buildTypes.release.proguard {
            version.set("7.8.0")
            obfuscate.set(true)
            optimize.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Rpm)
            packageName = "Latch"
            // jpackage REQUIRES MAJOR.MINOR.PATCH with MAJOR >= 1. The Android
            // versionName "1.3" has only two components and would be rejected.
            packageVersion = "1.3.8"
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

            linux {
                iconFile.set(project.file("icons/latch.png"))
                menuGroup = "Latch"
                appCategory = "Network"
                shortcut = true
            }
        }
    }
}

tasks.register<Tar>("packageReleaseTarGz") {
    group = "compose desktop"
    description = "Packages release distributable directory into a .tar.gz archive"
    dependsOn("createReleaseDistributable")

    archiveFileName.set("latch-1.3.8-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP

    // Compose Desktop places the app image one level deeper than the "app"
    // directory, under a subdirectory named after packageName ("Latch") -
    // package that directly so the tar has a single latch-1.3.8/ wrapper
    // around bin/ and lib/, matching what install.sh's --strip-components=1
    // expects (the previous "app" path produced an extra Latch/ nesting
    // level that broke the installer).
    from(layout.buildDirectory.dir("compose/binaries/main-release/app/Latch")) {
        into("latch-1.3.8")
    }
}
