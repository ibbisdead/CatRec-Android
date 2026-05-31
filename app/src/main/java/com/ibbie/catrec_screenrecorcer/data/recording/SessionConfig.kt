package com.ibbie.catrec_screenrecorcer.data.recording

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Which audio paths are active for this capture session (maps to mic / internal flags in
 * [com.ibbie.catrec_screenrecorcer.service.ScreenRecordService]).
 */
enum class SessionAudioSource {
    NONE,
    MICROPHONE,
    INTERNAL,
    MICROPHONE_AND_INTERNAL,
}

/**
 * Parcelable capture/encode parameters + MediaProjection **result code** (consent line).
 *
 * The MediaProjection **grant [android.content.Intent]** is **not** nested here: parceling
 * a consent `Intent` (extras may include framework internals) has caused deserialization failures
 * on some OEM builds. The grant is sent only as [com.ibbie.catrec_screenrecorcer.service.ScreenRecordService.EXTRA_DATA]
 * on the start intent, beside this extra.
 *
 * @see com.ibbie.catrec_screenrecorcer.data.recording.cloneProjectionIntent
 */
@Parcelize
data class SessionConfig(
    val widthPx: Int,
    val heightPx: Int,
    val bitrateBitsPerSecond: Int,
    val frameRate: Int,
    val audioSource: SessionAudioSource,
    val mediaProjectionResultCode: Int,
    /** Single-app / partial screen capture when supported. */
    val recordSingleApp: Boolean = false,
) : Parcelable {
    companion object {
        /** Sentinel dimensions: service keeps using its usual [calculateDimensions] path. */
        const val USE_SERVICE_DEFAULT_DIMENSIONS = 0
    }
}

fun SessionAudioSource.toMicAndInternalFlags(): Pair<Boolean, Boolean> =
    when (this) {
        SessionAudioSource.NONE -> false to false
        SessionAudioSource.MICROPHONE -> true to false
        SessionAudioSource.INTERNAL -> false to true
        SessionAudioSource.MICROPHONE_AND_INTERNAL -> true to true
    }

/**
 * Serialise to a Bundle of primitives so the intent extra can be unparcelled on any Android
 * version without triggering [android.os.Parcel.readParcelableCreatorInternal] — which
 * requires a correct class-loader on the receiving Bundle and NPEs on certain OEM Android 13
 * builds when the lazy-parcel class-loader is null.
 *
 * @see SessionConfig.fromBundle
 */
fun SessionConfig.toBundle(): Bundle = Bundle(7).apply {
    putInt("widthPx", widthPx)
    putInt("heightPx", heightPx)
    putInt("bitrateBitsPerSecond", bitrateBitsPerSecond)
    putInt("frameRate", frameRate)
    putString("audioSource", audioSource.name)
    putInt("mediaProjectionResultCode", mediaProjectionResultCode)
    putBoolean("recordSingleApp", recordSingleApp)
}

/** Reconstruct a [SessionConfig] from a bundle written by [toBundle]. */
fun Bundle.toSessionConfig(): SessionConfig = SessionConfig(
    widthPx = getInt("widthPx"),
    heightPx = getInt("heightPx"),
    bitrateBitsPerSecond = getInt("bitrateBitsPerSecond"),
    frameRate = getInt("frameRate"),
    audioSource = try {
        SessionAudioSource.valueOf(getString("audioSource") ?: "")
    } catch (_: IllegalArgumentException) {
        SessionAudioSource.NONE
    },
    mediaProjectionResultCode = getInt("mediaProjectionResultCode"),
    recordSingleApp = getBoolean("recordSingleApp"),
)

/** Defensive copy for projection token hand-off (Android 15-safe: avoid mutating the activity result). */
fun cloneProjectionIntent(source: Intent): Intent = Intent(source)
