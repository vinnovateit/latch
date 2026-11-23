System.setProperty("java.version", "21.0.1")

pluginManagement {
    repositories {
        maven("https://maven.bitsycore.com/releases")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven.bitsycore.com/releases")
        google()
        mavenCentral()
    }
}

rootProject.name = "Latch"
include(":core")
include(":app")
include(":desktop")
include(":cli")
include(":roomstub")
