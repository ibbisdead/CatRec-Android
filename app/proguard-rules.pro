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
# Do not use -keep on com.google.firebase.** / com.google.android.gms.** (hundreds of classes).
# firebase-bom artifacts and play-services-ads ship consumer ProGuard/R8 rules; merge those instead.
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

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

# ── Parcelable (e.g. SessionConfig @Parcelize) — R8 must not strip CREATOR; otherwise
# unmarshalling from Intent/Bundle can fail in release (Class resolution / readParcelable NPEs).
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep @kotlinx.parcelize.Parcelize class * { *; }

# ── AndroidX / Compose ────────────────────────────────────────────────────────
# lifecycle-* artifacts include their own consumer rules; avoid -keep androidx.lifecycle.**.
-dontwarn androidx.compose.**

# ── FFmpeg Kit (GIF transcode JNI) ───────────────────────────────────────────
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# ── Media (MediaProjection, MediaRecorder, MediaMuxer) ────────────────────────
# These are Android platform classes — no keep rules needed, but suppress any
# warnings R8 might emit about internal implementation classes.
-dontwarn android.media.**

