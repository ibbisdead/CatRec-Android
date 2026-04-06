// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// Android Studio’s Upgrade Assistant does not resolve AGP version from version-catalog aliases alone.
// Keep this literal in sync with gradle/libs.versions.toml → [versions] → agp.
plugins {
    id("com.android.application") version "8.13.2" apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}