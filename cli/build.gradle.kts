import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

val latchVersion = providers.gradleProperty("latchVersion").get()
val hostIsWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val hostIsLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Deliberately plain kotlin("jvm"), not kotlin.multiplatform / compose.multiplatform.
// This module must stay free of Compose Desktop, Room, and JNA at its own
// compile time -- it only talks to :core through the platform-agnostic
// interfaces in com.vinnovateit.latch.core.platform. Gradle's KMP metadata
// resolves the project(":core") dependency to :core's single jvm("desktop")
// target automatically, the same way :app already resolves it to androidTarget().
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The `application` plugin's compileJava task otherwise defaults to the
// toolchain JDK version (21 here), which Kotlin then rejects as
// inconsistent with compileKotlin's JVM_17 target above.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.vinnovateit.latch.cli.MainKt")
    applicationName = "latch-cli"
    applicationDefaultJvmArgs = listOf(
        "-Xms8m",
        "-Xmx64m",
        "-XX:+UseSerialGC",
        "-Dfile.encoding=UTF-8",
    )
}

val cliInstallDir = layout.buildDirectory.dir("install/latch-cli")
val cliPackageDir = layout.buildDirectory.dir("cli-package")
val cliImageDir = cliPackageDir.map { it.dir("image/latch-cli") }
val cliDistributionsDir = layout.buildDirectory.dir("distributions")
val linuxPackagingResources = layout.projectDirectory.dir("packaging/linux")

val packageCliAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a standalone latch-cli app image with a bundled runtime."
    dependsOn(tasks.installDist)
    onlyIf { hostIsWindows || hostIsLinux }
    outputs.dir(cliImageDir)

    doFirst {
        delete(cliPackageDir.get().dir("image"))
    }

    executable = File(System.getProperty("java.home"), "bin/jpackage").absolutePath
    args(
        "--type", "app-image",
        "--dest", cliPackageDir.get().dir("image").asFile.absolutePath,
        "--input", cliInstallDir.get().dir("lib").asFile.absolutePath,
        "--main-jar", "cli.jar",
        "--main-class", "com.vinnovateit.latch.cli.MainKt",
        "--name", "latch-cli",
        "--app-version", latchVersion,
        "--vendor", "VinnovateIT",
        "--description", "Automatic VIT Wi-Fi login from the terminal",
        "--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,jdk.unsupported,java.instrument",
        "--java-options", "-Xms8m",
        "--java-options", "-Xmx64m",
        "--java-options", "-XX:+UseSerialGC",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Djava.awt.headless=true",
    )
    if (hostIsWindows) args("--win-console")
}

tasks.register<Tar>("packageCliTarGz") {
    group = "distribution"
    description = "Packages the Linux CLI app image as a portable tarball."
    dependsOn(packageCliAppImage)
    onlyIf { hostIsLinux }
    archiveFileName.set("latch-cli-$latchVersion-linux-$hostArch.tar.gz")
    destinationDirectory.set(cliDistributionsDir)
    compression = Compression.GZIP
    from(cliImageDir) {
        into("latch-cli-$latchVersion-linux-$hostArch")
    }
}

tasks.register<Zip>("packageCliZip") {
    group = "distribution"
    description = "Packages the Windows CLI app image as a portable ZIP."
    dependsOn(packageCliAppImage)
    onlyIf { hostIsWindows }
    archiveFileName.set("latch-cli-$latchVersion-windows-$hostArch.zip")
    destinationDirectory.set(cliDistributionsDir)
    from(cliImageDir) {
        into("latch-cli-$latchVersion-windows-$hostArch")
    }
}

fun registerLinuxPackageTask(taskName: String, packageType: String) = tasks.register<Exec>(taskName) {
    group = "distribution"
    description = "Builds the standalone CLI .$packageType package with jpackage."
    dependsOn(tasks.installDist)
    onlyIf { hostIsLinux }
    outputs.dir(cliDistributionsDir)

    doFirst {
        cliDistributionsDir.get().asFile.mkdirs()
        cliDistributionsDir.get().asFile.listFiles()
            ?.filter { it.name.startsWith("latch-cli_$latchVersion") && it.extension == packageType }
            ?.forEach(File::delete)
    }

    executable = File(System.getProperty("java.home"), "bin/jpackage").absolutePath
    args(
        "--type", packageType,
        "--dest", cliDistributionsDir.get().asFile.absolutePath,
        "--input", cliInstallDir.get().dir("lib").asFile.absolutePath,
        "--main-jar", "cli.jar",
        "--main-class", "com.vinnovateit.latch.cli.MainKt",
        "--name", "latch-cli",
        "--linux-package-name", "latch-cli",
        "--app-version", latchVersion,
        "--vendor", "VinnovateIT",
        "--description", "Automatic VIT Wi-Fi login from the terminal",
        "--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,jdk.unsupported,java.instrument",
        "--java-options", "-Xms8m",
        "--java-options", "-Xmx64m",
        "--java-options", "-XX:+UseSerialGC",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Djava.awt.headless=true",
        "--linux-app-category", "Network",
        "--resource-dir", linuxPackagingResources.asFile.absolutePath,
    )
    if (packageType == "deb") {
        args(
            "--linux-deb-maintainer", "VinnovateIT",
            "--linux-package-deps", "network-manager",
        )
    }
    if (packageType == "rpm") {
        args(
            "--linux-rpm-license-type", "MIT",
            "--linux-package-deps", "NetworkManager",
        )
    }
}

registerLinuxPackageTask("packageCliDeb", "deb")
registerLinuxPackageTask("packageCliRpm", "rpm")
