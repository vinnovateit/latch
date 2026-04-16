plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.vinnovateit.latch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vinnovateit.latch"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Core and Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (using BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.ui2)
    implementation(libs.androidx.compose.ui.ui.graphics)
    implementation(libs.androidx.material3.v150alpha01)
    implementation(libs.androidx.compose.material.material.icons.extended)
    implementation(libs.material.kolor)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.animation)

    implementation(libs.accompanist.pager)
    implementation(libs.accompanist.pager.indicators)
    implementation(libs.accompanist.permissions)
    implementation(libs.kotlinx.collections.immutable)

    // Widget
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.serialization.json)

    // Data
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.androidx.security.crypto)

    // Compose Core UI modules
    implementation(libs.androidx.compose.foundation.foundation)
    implementation(libs.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation.core)

    // Tooling and Preview
    debugImplementation(libs.androidx.compose.ui.ui.tooling3)
    implementation(libs.androidx.compose.ui.ui.tooling.preview2)

    // Testing
    implementation(libs.protolite.well.known.types)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.ui.test.junit42)
    debugImplementation(libs.androidx.compose.ui.ui.test.manifest4)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Settings
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.navigation.compose)

    // JSON
    implementation(libs.gson.v2110)

    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // AppCompat (deduplicate)
    implementation(libs.androidx.appcompat.v161)
    implementation(libs.androidx.compose.material.material.icons.extended2)

    // In app updates
    implementation(libs.app.update)
    implementation(libs.app.update.ktx)
}
