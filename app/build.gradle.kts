import java.io.FileInputStream
import java.util.Properties

// TASK 2: release signing. Reads secrets from keystore.properties (project
// root, gitignored) rather than hardcoding them here -- see the generated
// keystore.properties.template for the exact keys expected. Debug builds
// are unaffected; if this file is absent, the release signingConfig simply
// doesn't get applied (see buildTypes.release below), so a missing keystore
// never breaks `./gradlew assembleDebug`.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") // Required for Room annotation processing
}

android {
    namespace = "com.example.intervaltimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.intervaltimer"
        minSdk = 26          // Section 2: minimum API level 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.5"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // composeOptions { kotlinCompilerExtensionVersion = ... } removed:
    // the org.jetbrains.kotlin.plugin.compose plugin (added above) now
    // derives the correct Compose compiler version from the Kotlin
    // version automatically. Setting both would conflict.

    // Renames every build's output APK from the generic "app-release.apk"
    // to "<project name>-v<versionName>-<buildType>.apk", e.g.
    // "HIIT_app-v1.0-release.apk", so old builds don't get silently
    // overwritten if you copy one aside before running assembleRelease
    // again. Runs for all variants (debug and release), not just release.
    //
    // NOTE: this does NOT indicate signing status in the filename (an
    // earlier version tried to via variant.buildType.signingConfig, but
    // that property doesn't resolve on this AGP's classic variant model --
    // not worth chasing further for what's a purely cosmetic feature).
    // To confirm a release build is actually signed, check that
    // keystore.properties exists and is filled in BEFORE building, or just
    // try `adb install` -- an unsigned APK fails to install outright.
    //
    // Uses the classic applicationVariants API, which depends on this
    // project's android.newDsl=false opt-out (gradle.properties) staying
    // active. If that opt-out is ever removed -- required no later than
    // AGP 10.0 -- this block needs to migrate to the new Variant API:
    // androidComponents { onVariants { variant -> variant.outputs.forEach
    // { it.outputFileName.set(...) } } }. Not needed while the opt-out holds.
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName =
                    "${rootProject.name}-v${variant.versionName}-${variant.buildType.name}.apk"
            }
    }
}

// android.kotlinOptions { jvmTarget = "17" } removed -- that setter is a
// hard error as of this Kotlin version, not just deprecated. Replacement
// is this separate top-level kotlin{} block using the compilerOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Core AndroidX ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // --- Room (local persistence) ---
    // Room bumped 2.6.1 -> 2.7.2: 2.6.1 predates Room's KSP2/Kotlin 2.0+
    // support and crashes ("unexpected jvm signature V" in
    // JvmDescriptorUtilsKt) when processing against our current KSP
    // 2.3.10 / Kotlin 2.4.10 toolchain. Stays on the same androidx.room
    // package -- NOT the breaking Room 3.0 (androidx.room3) rename, which
    // would require changing every @Entity/@Dao import across the project.
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // --- Kotlin Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Location Services (GPS distance tracking, Session 3) ---
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // --- Lifecycle / ViewModel (MVVM) ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // --- Jetpack Compose (UI, wired up in later sessions) ---
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // --- Testing (used in Session 9) ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
}
