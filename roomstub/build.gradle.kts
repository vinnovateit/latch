plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "androidx.room"
version = "2.8.5"

// Room publishes no mingwX64 klib. This module is substituted for
// androidx.room:room-runtime on Windows platform configurations only; the
// shared metadata compile still sees the real Room API.
kotlin {
    mingwX64()
}
