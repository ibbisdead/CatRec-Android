# ── Stack trace readability ────────────────────────────────────────────────────
# Keep source file names and line numbers so Crashlytics stack traces are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep generic signatures and annotations (required by Gson, Retrofit, Firebase, etc.)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── Firebase & Google Play Services ───────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Crashlytics needs to inspect exception classes at runtime.
-keep class com.google.firebase.crashlytics.** { *; }

# ── Native / JNI ──────────────────────────────────────────────────────────────
# Keep all classes that declare native methods so R8 does not remove them before
# Crashlytics uploads the .so debug symbols.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── AndroidX / Compose ────────────────────────────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.compose.**

# ── Media (MediaProjection, MediaRecorder, MediaMuxer) ────────────────────────
# These are Android platform classes — no keep rules needed, but suppress any
# warnings R8 might emit about internal implementation classes.
-dontwarn android.media.**

