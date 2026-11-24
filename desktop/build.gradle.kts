import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.*
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

val latchVersion = providers.gradleProperty("latchVersion").get()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.desktop.native.bridge)
}

// Native desktop app (SDL3 + Skia) for both supported hosts. There is no JVM
// in either distributable; Windows uses the same compose-desktop-native bridge
// as Linux, on the mingwX64 target.
kotlin {
    linuxX64 {
        binaries.all {
            // platform.posix asks for libcrypt.so.1, which this binary never
            // calls. --as-needed drops the DT_NEEDED so it starts on hosts with
            // only libcrypt.so.2.
            freeCompilerArgs += listOf("-linker-option", "--as-needed")
        }
    }

    mingwX64 {
        binaries.all {
            // GUI subsystem: no black console window behind the app.
            freeCompilerArgs += listOf("-linker-option", "-Wl,--subsystem,windows")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))

            // Official coordinates. The bridge plugin substitutes the port's
            // Kotlin/Native klibs on the linuxX64 target only.
            implementation("org.jetbrains.compose.runtime:runtime:${composeDesktopNative.composeRuntime}")
            implementation("org.jetbrains.compose.ui:ui:${composeDesktopNative.compose}")
            implementation("org.jetbrains.compose.foundation:foundation:${composeDesktopNative.compose}")
            implementation("org.jetbrains.compose.animation:animation:${composeDesktopNative.compose}")
            implementation("org.jetbrains.compose.material3:material3:${composeDesktopNative.composeMaterial3}")
            implementation("org.jetbrains.compose.components:components-resources:${composeDesktopNative.compose}")
            // The SDL window shell is the port's own API, no substitution.
            implementation("com.bitsycore.compose:desktop-native-window:${composeDesktopNative.version}")

            // material-kolor ships no linuxX64 klib; the native source set
            // provides the two symbols Latch's theme calls.
            implementation(libs.lifecycle.viewmodel.mp)
            implementation(libs.lifecycle.viewmodel.compose.mp)
            implementation(libs.lifecycle.runtime.compose.mp)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

configurations.matching { it.name.contains("mingwX64", ignoreCase = true) }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.room:room-runtime")).using(project(":roomstub"))
    }
    exclude(group = "androidx.sqlite", module = "sqlite-bundled")
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.vinnovateit.latch.desktop.resources"
    generateResClass = auto
}

// One source of truth for the app icon: icons/latch.svg. GenerateIcon.java
// rasterises it with the build JDK alone -- Windows has no rsvg/ImageMagick.
val generatedIconDir = layout.buildDirectory.dir("generated/desktop-icons")
val generateDesktopIcons = tasks.register("generateDesktopIcons") {
    val svg = layout.projectDirectory.file("icons/latch.svg")
    val generator = layout.projectDirectory.file("icons/GenerateIcon.java")
    inputs.files(svg, generator)
    outputs.dir(generatedIconDir)
    doLast {
        val java = listOf(System.getProperty("java.home"), "bin", "java").joinToString(File.separator)
        val command = listOf(
            java, generator.asFile.absolutePath,
            svg.asFile.absolutePath, generatedIconDir.get().asFile.absolutePath,
        )
        val process = ProcessBuilder(command).inheritIO().start()
        if (process.waitFor() != 0) error("Failed to generate desktop icons from latch.svg")
    }
}
tasks.configureEach {
    if (name in setOf(
            "generateComposeNativeIconBlobs",
            "generateComposeNativeIco",
            "compileComposeNativeIconResource",
        )
    ) dependsOn(generateDesktopIcons)
}

compose.desktop {
    native {
        entryPoint = "com.vinnovateit.latch.desktop.main"
        icon {
            light.from(
                generatedIconDir.map { it.file("latch-32.png").asFile.absolutePath }.get(),
                generatedIconDir.map { it.file("latch-128.png").asFile.absolutePath }.get(),
            )
        }
        // The app sets Satoshi/MODERNIZ explicitly on every Text style. Skip the
        // port's ~6MB bundled Noto fonts; any unspecified family falls back to
        // the OS sans-serif via Skia's system font manager.
        fonts {
            bundleNotoSans.set(false)
            bundleNotoSansItalic.set(false)
            bundleNotoSansMono.set(false)
        }
    }
}

/**
 * Packages the native executable plus its data.kres resource bundle into a
 * single .tar.gz, mirroring the old JVM release's "latch-<version>-linux-x64"
 * wrapper layout so install.sh keeps working.
 */
tasks.register<Tar>("packageNativeReleaseTarGz") {
    group = "compose desktop"
    description = "Packages the native Linux distributable into a .tar.gz archive"
    dependsOn("linkReleaseExecutableLinuxX64", "packageReleaseComposeResourcesLinuxX64", "generateDesktopIcons")

    archiveFileName.set("latch-$latchVersion-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP

    from(layout.buildDirectory.file("bin/linuxX64/releaseExecutable/desktop.kexe")) {
        into("latch-$latchVersion/bin")
        rename { "Latch" }
        filePermissions { unix(0b111101101) } // 0755
    }
    from(layout.buildDirectory.file("bin/linuxX64/releaseExecutable/data.kres")) {
        into("latch-$latchVersion/bin")
    }
    // install.sh writes a desktop entry pointing at lib/Latch.png; ship it.
    from(generatedIconDir.map { it.file("latch-128.png") }) {
        into("latch-$latchVersion/lib")
        rename { "Latch.png" }
    }

    // Gradle's Tar always uses the default gzip level. Recompress at the
    // strongest level so the download is as small as possible; the archive
    // name and layout stay identical, so install.sh and CI need no changes.
    doLast {
        val archive = destinationDirectory.get().asFile.resolve(archiveFileName.get())
        val recompressed = File.createTempFile("latch-gzip-", ".tar.gz", archive.parentFile)
        GZIPInputStream(archive.inputStream()).use { input ->
            GZIPOutputStream(recompressed.outputStream(), Deflater.BEST_COMPRESSION).use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        check(archive.delete() && recompressed.renameTo(archive)) { "Cannot recompress $archive" }
        println("Linux archive: ${archive.name} (${archive.length()} bytes, gzip level 9)")
    }
}

/**
 * Compressed per-user MSI for the native Windows executable. WiX embeds a
 * high-compression cabinet in the package, so the installer is a single file.
 */
tasks.register("packageNativeMsiMingwX64") {
    group = "compose desktop"
    description = "Builds a compressed native Windows MSI with WiX"
    dependsOn(
        "linkReleaseExecutableMingwX64",
        "provisionSkikoDllReleaseMingwX64",
        "provisionSkikoIcuReleaseMingwX64",
        "packageReleaseComposeResourcesMingwX64",
        "generateDesktopIcons",
    )

    val stageDir = layout.buildDirectory.dir("msi-stage")
    val nativeDir = layout.buildDirectory.dir("bin/mingwX64/releaseExecutable")
    val wxs = layout.projectDirectory.file("packaging/windows/LatchNative.wxs")
    val output = layout.buildDirectory.file("distributions/latch-native-$latchVersion-windows-x64.msi")
    inputs.files(wxs, nativeDir, generatedIconDir)
    outputs.file(output)

    doLast {
        val stage = stageDir.get().asFile
        stage.mkdirs()
        listOf("data.kres", "icudtl.dat", "skiko-windows-x64.dll").forEach { name ->
            nativeDir.get().asFile.resolve(name).copyTo(stage.resolve(name), overwrite = true)
        }
        nativeDir.get().asFile.resolve("desktop.exe").copyTo(stage.resolve("Latch.exe"), overwrite = true)
        generatedIconDir.get().asFile.resolve("latch.ico").copyTo(stage.resolve("latch.ico"), overwrite = true)

        output.get().asFile.parentFile.mkdirs()
        val command = listOf(
            "wix", "build", "-arch", "x64", "-dcl", "high", "-ext", "WixToolset.UI.wixext",
            "-d", "Version=$latchVersion",
            "-d", "SourceDir=${stage.absolutePath}",
            "-o", output.get().asFile.absolutePath,
            wxs.asFile.absolutePath,
        )
        val process = ProcessBuilder(command).inheritIO().start()
        if (process.waitFor() != 0) error("WiX failed to build the native MSI")
        println("Windows MSI: ${output.get().asFile.name} (${output.get().asFile.length()} bytes, high compression)")
    }
}
