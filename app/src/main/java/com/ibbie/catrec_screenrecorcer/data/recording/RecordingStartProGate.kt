package com.ibbie.catrec_screenrecorcer.data.recording

import android.util.Log
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import kotlinx.coroutines.flow.first

private const val LOG_TAG = "RecordingStartProGate"

/** Minimum FPS that requires Pro (90 and 120 in the UI). */
const val PRO_RECORDING_FPS_THRESHOLD = 90

/** Maximum video bitrate (Mbps) on the free tier; strictly greater requires Pro. */
const val FREE_VIDEO_BITRATE_MBPS = 16f

enum class ProRecordingFeature(val logName: String) {
    RECORDING_HIGH_FPS("pro_high_fps"),
    HIGH_BITRATE("pro_high_bitrate"),
    SEPARATE_AUDIO_TRACKS("pro_separate_audio_tracks"),
    CAMERA_OVERLAY("pro_camera_overlay"),
    WATERMARK("pro_watermark"),
}

sealed interface RecordingStartProGateResult {
    data object Allowed : RecordingStartProGateResult
    data class BlockedNeedsPro(val features: List<ProRecordingFeature>) : RecordingStartProGateResult
}

object RecordingStartProGate {
    fun featuresForFullRecording(
        fps: Int,
        videoBitrateMbps: Float,
        separateMicRecording: Boolean,
        cameraOverlay: Boolean,
        showWatermark: Boolean,
    ): List<ProRecordingFeature> =
        buildList {
            if (fps >= PRO_RECORDING_FPS_THRESHOLD) add(ProRecordingFeature.RECORDING_HIGH_FPS)
            if (videoBitrateMbps > FREE_VIDEO_BITRATE_MBPS) add(ProRecordingFeature.HIGH_BITRATE)
            if (separateMicRecording) add(ProRecordingFeature.SEPARATE_AUDIO_TRACKS)
            if (cameraOverlay) add(ProRecordingFeature.CAMERA_OVERLAY)
            if (showWatermark) add(ProRecordingFeature.WATERMARK)
        }

    fun featuresForBuffer(
        fps: Int,
        videoBitrateMbps: Float,
    ): List<ProRecordingFeature> =
        buildList {
            if (fps >= PRO_RECORDING_FPS_THRESHOLD) add(ProRecordingFeature.RECORDING_HIGH_FPS)
            if (videoBitrateMbps > FREE_VIDEO_BITRATE_MBPS) add(ProRecordingFeature.HIGH_BITRATE)
        }

    fun check(
        source: String,
        features: List<ProRecordingFeature>,
        adsDisabled: Boolean,
        proUnlockedUntilMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): RecordingStartProGateResult {
        val featureNames = features.joinToString(",") { it.logName }.ifBlank { "none" }
        Log.d(LOG_TAG, "recording start requested source=$source pro_features=$featureNames")
        return when {
            features.isEmpty() -> {
                Log.d(LOG_TAG, "Pro gate allowed source=$source reason=no_pro_features")
                RecordingStartProGateResult.Allowed
            }
            adsDisabled -> {
                Log.d(LOG_TAG, "Pro gate allowed source=$source reason=remove_ads features=$featureNames")
                RecordingStartProGateResult.Allowed
            }
            proUnlockedUntilMillis > nowMillis -> {
                Log.d(
                    LOG_TAG,
                    "Pro gate allowed source=$source reason=timed_unlock until=$proUnlockedUntilMillis features=$featureNames",
                )
                RecordingStartProGateResult.Allowed
            }
            else -> {
                Log.d(LOG_TAG, "Pro gate blocked source=$source features=$featureNames")
                RecordingStartProGateResult.BlockedNeedsPro(features)
            }
        }
    }

    suspend fun checkFullRecording(
        settingsRepository: SettingsRepository,
        source: String,
    ): RecordingStartProGateResult {
        val captureMode = settingsRepository.captureMode.first()
        val fps =
            if (captureMode == CaptureMode.GIF) {
                GifRecordingPresets.byId(settingsRepository.gifRecorderPresetId.first()).recordingFps
            } else {
                settingsRepository.fps.first().toInt()
            }
        val videoBitrateMbps =
            if (captureMode == CaptureMode.GIF) {
                GifRecordingPresets.byId(settingsRepository.gifRecorderPresetId.first()).bitrateMbps
            } else {
                settingsRepository.bitrate.first()
            }
        return check(
            source = source,
            features =
                featuresForFullRecording(
                    fps = fps,
                    videoBitrateMbps = videoBitrateMbps,
                    separateMicRecording = settingsRepository.separateMicRecording.first(),
                    cameraOverlay = settingsRepository.cameraOverlay.first(),
                    showWatermark = settingsRepository.showWatermark.first(),
                ),
            adsDisabled = settingsRepository.adsDisabled.first(),
            proUnlockedUntilMillis = settingsRepository.proFeaturesUnlockedUntilMillis.first(),
        )
    }

    suspend fun checkBuffer(
        settingsRepository: SettingsRepository,
        source: String,
    ): RecordingStartProGateResult =
        check(
            source = source,
            features =
                featuresForBuffer(
                    settingsRepository.fps.first().toInt(),
                    settingsRepository.bitrate.first(),
                ),
            adsDisabled = settingsRepository.adsDisabled.first(),
            proUnlockedUntilMillis = settingsRepository.proFeaturesUnlockedUntilMillis.first(),
        )
}
