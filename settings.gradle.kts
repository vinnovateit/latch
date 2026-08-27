System.setProperty("java.version", "21.0.1")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Latch"
include(":core")
include(":app")
include(":desktop")
include(":cli")
