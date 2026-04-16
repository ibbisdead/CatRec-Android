// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// Android Studio’s Upgrade Assistant reads this literal; the app module applies the same id without a version.
plugins {
    id("com.android.application") version "9.1.1" apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}