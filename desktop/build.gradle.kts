import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.*

val latchVersion = providers.gradleProperty("latchVersion").get()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.desktop.native.bridge)
}

// The desktop app is a self-contained Kotlin/Native executable (SDL3 + Skia),
// not a JVM application. The JVM target is gone; the native binary is the only
// desktop distributable.
kotlin {
    linuxX64 {
        binaries.all {
            // platform.posix asks for libcrypt.so.1, which this binary never
            // calls. --as-needed drops the DT_NEEDED so it starts on hosts with
            // only libcrypt.so.2.
            freeCompilerArgs += listOf("-linker-option", "--as-needed")
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

compose.resources {
    publicResClass = false
    packageOfResClass = "com.vinnovateit.latch.desktop.resources"
    generateResClass = auto
}

compose.desktop {
    native {
        entryPoint = "com.vinnovateit.latch.desktop.main"
        icon {
            light.from("icons/latch-32.png", "icons/latch-128.png")
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
    dependsOn("linkReleaseExecutableLinuxX64", "packageReleaseComposeResourcesLinuxX64")

    archiveFileName.set("latch-native-$latchVersion-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP

    from(layout.buildDirectory.file("bin/linuxX64/releaseExecutable/desktop.kexe")) {
        into("latch-native-$latchVersion/bin")
        rename { "Latch" }
        filePermissions { unix(0b111101101) } // 0755
    }
    from(layout.buildDirectory.file("bin/linuxX64/releaseExecutable/data.kres")) {
        into("latch-native-$latchVersion/bin")
    }
}
