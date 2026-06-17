package com.ibbie.catrec_screenrecorcer.data.recording

import androidx.annotation.StringRes
import com.ibbie.catrec_screenrecorcer.R

enum class RecordingFeature {
    SCREENSHOT_WHILE_RECORDING,
    NORMAL_RECORDING,
    ROLLING_BUFFER,
    AUDIO_CAPTURE,
    SEPARATE_MIC_TRACK,
    OVERLAY_CONTROLS,
    CAMERA_OVERLAY,
    WATERMARK_OVERLAY,
    GIF_MODE,
    ADAPTIVE_PERFORMANCE,
}

enum class RecordingFeatureUnavailableReason(
    @get:StringRes val messageResId: Int,
) {
    SCREENSHOT_WHILE_RECORDING_UNAVAILABLE(
        R.string.recording_screenshot_while_recording_unavailable,
    ),
}

data class RecordingFeatureCompatibilityResult(
    val available: Boolean,
    val reason: RecordingFeatureUnavailableReason? = null,
) {
    @get:StringRes
    val reasonMessageResId: Int?
        get() = reason?.messageResId
}

object RecordingFeatureCompatibility {
    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        mode: RecordingEngineMode,
        feature: RecordingFeature,
    ): RecordingFeatureCompatibilityResult =
        when (feature) {
            RecordingFeature.SCREENSHOT_WHILE_RECORDING ->
                unavailable(RecordingFeatureUnavailableReason.SCREENSHOT_WHILE_RECORDING_UNAVAILABLE)

            RecordingFeature.ROLLING_BUFFER -> {
                // Model-level availability only. Direct-path rolling buffer still needs Stage 2
                // validation around segment rotation, keyframe timing, and cleanup.
                available()
            }

            RecordingFeature.NORMAL_RECORDING,
            RecordingFeature.AUDIO_CAPTURE,
            RecordingFeature.SEPARATE_MIC_TRACK,
            RecordingFeature.OVERLAY_CONTROLS,
            RecordingFeature.CAMERA_OVERLAY,
            RecordingFeature.WATERMARK_OVERLAY,
            RecordingFeature.GIF_MODE,
            RecordingFeature.ADAPTIVE_PERFORMANCE,
            -> available()
        }

    fun isAvailable(
        mode: RecordingEngineMode,
        feature: RecordingFeature,
    ): Boolean = evaluate(mode, feature).available

    private fun available(): RecordingFeatureCompatibilityResult = RecordingFeatureCompatibilityResult(available = true)

    private fun unavailable(reason: RecordingFeatureUnavailableReason): RecordingFeatureCompatibilityResult = RecordingFeatureCompatibilityResult(available = false, reason = reason)
}
