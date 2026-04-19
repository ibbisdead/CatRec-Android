import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    // Must be applied (no apply false) so google-services.json is processed and Firebase SDKs initialize.
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.ibbie.catrec_screenrecorcer"
    // compileSdk 37: build-time SDK for current CameraX / Media3 / Navigation AAR metadata.
    // targetSdk 36 (Android 16): runtime behavior under test (Google Play). Platform APIs @since 37+ must be gated with
    // Build.VERSION.SDK_INT >= 37 (see ObsoleteSdkInt / NewApi in lint).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ibbie.catrec_screenrecorder"
        minSdk = 27
        targetSdk = 36
        versionCode = 16
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Native libs (FFmpeg Kit, CameraX, etc.) are duplicated per ABI. Omit 32-bit arm/x86 to cut download size.
        // Add "armeabi-v7a" and/or "x86" here if you must support legacy 32-bit-only devices or older emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Upload full native debug symbols to both Google Play and Firebase Crashlytics.
            ndk {
                debugSymbolLevel = "FULL"
            }
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
    // Play App Bundle: one upload; users receive ABI/language/density splits automatically.
    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    lint {
        // Keep API/version checks visible in CI and IDE; NewApi flags calls not valid on minSdk without guards.
        checkReleaseBuilds = true
        checkDependencies = true
        enable += setOf(
            "NewApi",
            "InlinedApi",
            "ObsoleteSdkInt",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
}

tasks.named("check") {
    dependsOn(tasks.named("detekt"), tasks.named("ktlintCheck"))
}

ktlint {
    // Align with Kotlin 2.2 (embedded parser in default ktlint 1.3.x fails on some sources).
    version.set("1.7.1")
    android.set(true)
    outputToConsole.set(true)
}

dependencies {
    // Firebase (BoM manages all Firebase library versions)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // Google Mobile Ads
    implementation(libs.play.services.ads)

    // Google Play Billing
    implementation(libs.android.billing.ktx)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3.window.size)

    // Navigation & Icons
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)

    // Persistence
    implementation(libs.androidx.datastore.preferences)

    // AppCompat — required for per-app locale switching (AppCompatDelegate)
    implementation(libs.androidx.appcompat)

    // Play Feature Delivery — request language splits when user changes in-app locale (bundle language splits enabled).
    implementation(libs.play.feature.delivery)

    // Coil — image loading for ScreenshotsScreen
    implementation(libs.coil.compose)

    // Views (Required for OverlayService XML)
    implementation(libs.androidx.cardview)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // FFmpeg (LGPL): GIF export uses palettegen + paletteuse. Official arthenica binaries were
    // retired; this fork ships 16KB page-size libs for current Play / API 35 targets.
    implementation(libs.ffmpeg.kit.x6kb)

    // Media3 / ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Debugging (Only for debug builds ideally, but kept for dev)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
