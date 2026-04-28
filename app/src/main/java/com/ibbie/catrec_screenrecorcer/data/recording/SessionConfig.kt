package com.ibbie.catrec_screenrecorcer.data.recording

import android.content.Intent
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

/** Defensive copy for projection token hand-off (Android 15-safe: avoid mutating the activity result). */
fun cloneProjectionIntent(source: Intent): Intent = Intent(source)
