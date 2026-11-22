import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

val latchVersion = providers.gradleProperty("latchVersion").get()

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

private val generatedVersionDir = layout.buildDirectory.dir("generated/cli-version")

// LatchCore.VERSION is desktop-only and reads a JVM classpath resource. Generate
// the same latchVersion value as a Kotlin constant so the native CLI reports the
// identical version without depending on it.
private val generateCliVersion = tasks.register("generateCliVersion") {
    inputs.property("latchVersion", latchVersion)
    outputs.dir(generatedVersionDir)
    doLast {
        generatedVersionDir.get().file("com/vinnovateit/latch/cli/CliVersion.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package com.vinnovateit.latch.cli\n\ninternal const val CLI_VERSION = \"$latchVersion\"\n")
        }
    }
}

// Native-only CLI: one linuxX64 executable, no JVM, no jpackage, no bundled
// runtime. Sources live in linuxX64Main so platform code can use posix APIs
// directly; there is deliberately no jvm() target to resolve against :core.
kotlin {
    linuxX64 {
        // platform.posix's default linker opts request libcrypt, which this CLI
        // never calls. --as-needed drops that DT_NEEDED so both the executable
        // and the native test runner start on hosts with only libcrypt.so.2.
        binaries.all {
            freeCompilerArgs += listOf("-linker-option", "--as-needed")
        }
        binaries.executable {
            entryPoint = "com.vinnovateit.latch.cli.main"
        }
    }

    sourceSets {
        linuxX64Main {
            kotlin.srcDir(generatedVersionDir)
            dependencies {
                implementation(project(":core"))
                // SDL3 cinterop for the daemon's system tray (AppIndicator on Linux).
                implementation("com.bitsycore.compose.sdl:sdl-core:${libs.versions.composeDesktopNative.get()}")
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }

        linuxX64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named("compileKotlinLinuxX64") {
    dependsOn(generateCliVersion)
}

// Keep the existing :cli:test CI invocation working on the native-only module.
tasks.register("test") {
    group = "verification"
    description = "Runs the native CLI smoke tests (alias for linuxX64Test)."
    dependsOn("linuxX64Test")
}

private val nativeExecutable = layout.buildDirectory.file("bin/linuxX64/releaseExecutable/cli.kexe")
private val distributionName = "latch-cli-$latchVersion-linux-x64"

tasks.register<Tar>("packageCliTarGz") {
    group = "distribution"
    description = "Packages the native CLI executable as a portable tarball."
    dependsOn("linkReleaseExecutableLinuxX64")
    archiveFileName.set("$distributionName.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    from(nativeExecutable) {
        rename { "latch-cli" }
        filePermissions { unix("rwxr-xr-x") }
    }
    into(distributionName)
}
