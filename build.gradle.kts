// Top-level build file. Declares plugin versions once for the whole project;
// individual modules (e.g. app/build.gradle.kts) apply these WITHOUT a
// version number, since it's resolved here.
//
// Kotlin bumped from 1.9.24 -> 2.4.10: the 1.9.24 compiler crashes
// (JavaVersion.parse IllegalArgumentException) against JDK 25, which is
// what Android Studio's bundled JBR now ships. 2.4.10 is also the version
// that explicitly declares Gradle 9.5.0 as its max fully-supported target.
// KSP version must match exactly per Kotlin's own KSP quickstart docs.
// AGP bumped 8.5.2 -> 9.0.1: 8.5.2 predates the addKspConfigurations()
// API that KSP 2.3.10's Gradle integration calls, causing a
// "method not found" failure. AGP 9.0 also makes built-in Kotlin
// compilation the default, which conflicts with our separately-applied
// org.jetbrains.kotlin.android plugin below -- see the
// android.builtInKotlin=false flag in gradle.properties, which opts back
// out of that and keeps this project's plugin structure unchanged. Google
// documents that opt-out as temporary (removed in AGP 10.0), so this may
// need revisiting later, but it's the lowest-risk fix for now.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
