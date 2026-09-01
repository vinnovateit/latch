import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
}
