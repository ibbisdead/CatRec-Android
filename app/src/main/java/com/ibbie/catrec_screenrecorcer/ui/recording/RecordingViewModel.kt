package com.ibbie.catrec_screenrecorcer.ui.recording

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.service.ScreenRecordService
import com.ibbie.catrec_screenrecorcer.utils.applyAnalyticsCollectionEnabled
import com.ibbie.catrec_screenrecorcer.utils.applyCrashlyticsCollectionEnabled
import com.ibbie.catrec_screenrecorcer.utils.applyPersonalizedAdsEnabled
import com.ibbie.catrec_screenrecorcer.utils.refreshCrashlyticsSessionKeys
import com.ibbie.catrec_screenrecorcer.utils.syncFirebaseUserIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    val isRecording: StateFlow<Boolean>  = RecordingState.isRecording
    val isBuffering: StateFlow<Boolean>  = RecordingState.isBuffering
    val isPrepared:  StateFlow<Boolean>  = RecordingState.isPrepared

    // Video
    val fps: StateFlow<Float> = repository.fps.stateIn(viewModelScope, SharingStarted.Lazily, 30f)
    val bitrate: StateFlow<Float> = repository.bitrate.stateIn(viewModelScope, SharingStarted.Lazily, 10f)
    val videoEncoder: StateFlow<String> = repository.videoEncoder.stateIn(viewModelScope, SharingStarted.Lazily, "H.264")
    val resolution: StateFlow<String> = repository.resolution.stateIn(viewModelScope, SharingStarted.Lazily, "Native")
    val recordingOrientation: StateFlow<String> = repository.recordingOrientation.stateIn(viewModelScope, SharingStarted.Lazily, "Auto")

    // Audio
    val recordAudio: StateFlow<Boolean> = repository.recordAudio.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val internalAudio: StateFlow<Boolean> = repository.internalAudio.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val audioBitrate: StateFlow<Int> = repository.audioBitrate.stateIn(viewModelScope, SharingStarted.Lazily, 128)
    val audioSampleRate: StateFlow<Int> = repository.audioSampleRate.stateIn(viewModelScope, SharingStarted.Lazily, 48000)
    val audioChannels: StateFlow<String> = repository.audioChannels.stateIn(viewModelScope, SharingStarted.Lazily, "Mono")
    val audioEncoder: StateFlow<String> = repository.audioEncoder.stateIn(viewModelScope, SharingStarted.Lazily, "AAC-LC")
    val separateMicRecording: StateFlow<Boolean> = repository.separateMicRecording.stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Controls
    val floatingControls: StateFlow<Boolean> = repository.floatingControls.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val touchOverlay: StateFlow<Boolean> = repository.touchOverlay.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val countdown: StateFlow<Int> = repository.countdown.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    /** Rolling Clipper buffer length (1–5 minutes). */
    val clipperDurationMinutes: StateFlow<Int> =
        repository.clipperDurationMinutes.stateIn(viewModelScope, SharingStarted.Lazily, 1)
    val stopBehavior: StateFlow<Set<String>> = repository.stopBehavior.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        setOf(StopBehaviorKeys.NOTIFICATION),
    )

    // Camera Overlay
    val cameraOverlay: StateFlow<Boolean> = repository.cameraOverlay.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val cameraOverlaySize: StateFlow<Int> = repository.cameraOverlaySize.stateIn(viewModelScope, SharingStarted.Lazily, 120)
    val cameraXFraction: StateFlow<Float> = repository.cameraXFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.05f)
    val cameraYFraction: StateFlow<Float> = repository.cameraYFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.1f)
    val cameraLockPosition: StateFlow<Boolean> = repository.cameraLockPosition.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val cameraFacing: StateFlow<String> = repository.cameraFacing.stateIn(viewModelScope, SharingStarted.Lazily, "Front")
    val cameraAspectRatio: StateFlow<String> = repository.cameraAspectRatio.stateIn(viewModelScope, SharingStarted.Lazily, "Circle")
    val cameraOrientation: StateFlow<String> = repository.cameraOrientation.stateIn(viewModelScope, SharingStarted.Lazily, "Auto")
    val cameraOpacity: StateFlow<Int> = repository.cameraOpacity.stateIn(viewModelScope, SharingStarted.Lazily, 100)

    // Watermark
    val showWatermark: StateFlow<Boolean> = repository.showWatermark.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val watermarkLocation: StateFlow<String> = repository.watermarkLocation.stateIn(viewModelScope, SharingStarted.Lazily, "Top Left")
    val watermarkImageUri: StateFlow<String?> = repository.watermarkImageUri.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val watermarkShape: StateFlow<String> = repository.watermarkShape.stateIn(viewModelScope, SharingStarted.Lazily, "Square")
    val watermarkOpacity: StateFlow<Int> = repository.watermarkOpacity.stateIn(viewModelScope, SharingStarted.Lazily, 100)
    val watermarkSize: StateFlow<Int> = repository.watermarkSize.stateIn(viewModelScope, SharingStarted.Lazily, 80)
    val watermarkXFraction: StateFlow<Float> = repository.watermarkXFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.05f)
    val watermarkYFraction: StateFlow<Float> = repository.watermarkYFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.05f)

    // Screenshots
    val screenshotFormat: StateFlow<String> = repository.screenshotFormat.stateIn(viewModelScope, SharingStarted.Lazily, "JPEG")
    val screenshotQuality: StateFlow<Int> = repository.screenshotQuality.stateIn(viewModelScope, SharingStarted.Lazily, 90)

    // Theme & Language
    val appTheme: StateFlow<String> = repository.appTheme.stateIn(viewModelScope, SharingStarted.Lazily, "System")
    val appLanguage: StateFlow<String> = repository.appLanguage.stateIn(viewModelScope, SharingStarted.Lazily, "system")

    // Storage
    val filenamePattern: StateFlow<String> = repository.filenamePattern.stateIn(viewModelScope, SharingStarted.Lazily, "yyyyMMdd_HHmmss")
    val saveLocationUri: StateFlow<String?> = repository.saveLocationUri.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val autoDelete: StateFlow<Boolean> = repository.autoDelete.stateIn(viewModelScope, SharingStarted.Lazily, false)

    // General
    val keepScreenOn: StateFlow<Boolean> = repository.keepScreenOn.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val hapticFeedback: StateFlow<Boolean> = repository.hapticFeedback.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val soundFeedback: StateFlow<Boolean> = repository.soundFeedback.stateIn(viewModelScope, SharingStarted.Lazily, false)

    // UI Mode
    val performanceMode: StateFlow<Boolean> = repository.performanceMode.stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** Remove-ads entitlement: disables all rewarded-ad gating when true ([com.ibbie.catrec_screenrecorcer.data.AdGate]). */
    val adsDisabled: StateFlow<Boolean> =
        repository.adsDisabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Privacy
    val analyticsEnabled: StateFlow<Boolean> = repository.analyticsEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val personalizedAdsEnabled: StateFlow<Boolean> =
        repository.personalizedAdsEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)

    // Onboarding (StateFlow defaults false before DataStore loads; use [betaNoticePersistedValue] for gating.)
    val betaNoticeShown: StateFlow<Boolean> =
        repository.betaNoticeShown.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Reads the persisted flag so the beta dialog is not shown on every cold start (avoids stateIn initial false). */
    suspend fun betaNoticePersistedValue(): Boolean = repository.betaNoticeShown.first()

    // Accent Color
    val accentColor:       StateFlow<String>  = repository.accentColor.stateIn(viewModelScope, SharingStarted.Lazily, "FF0033")
    val accentColor2:      StateFlow<String>  = repository.accentColor2.stateIn(viewModelScope, SharingStarted.Lazily, "FF6600")
    val accentUseGradient: StateFlow<Boolean> = repository.accentUseGradient.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val showDebugInfo: StateFlow<Boolean> = MutableStateFlow(false)

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted

    fun updatePermissionsState(granted: Boolean) { _permissionsGranted.value = granted }

    // Setters — Video
    fun setFps(value: Float) = viewModelScope.launch { repository.setFps(value) }
    fun setBitrate(value: Float) = viewModelScope.launch { repository.setBitrate(value) }
    fun setVideoEncoder(value: String) = viewModelScope.launch { repository.setVideoEncoder(value) }
    fun setResolution(value: String) = viewModelScope.launch { repository.setResolution(value) }
    fun setRecordingOrientation(value: String) = viewModelScope.launch { repository.setRecordingOrientation(value) }

    // Setters — Audio
    fun setRecordAudio(value: Boolean) = viewModelScope.launch { repository.setRecordAudio(value) }
    fun setInternalAudio(value: Boolean) = viewModelScope.launch { repository.setInternalAudio(value) }
    fun setAudioBitrate(value: Int) = viewModelScope.launch { repository.setAudioBitrate(value) }
    fun setAudioSampleRate(value: Int) = viewModelScope.launch { repository.setAudioSampleRate(value) }
    fun setAudioChannels(value: String) = viewModelScope.launch { repository.setAudioChannels(value) }
    fun setAudioEncoder(value: String) = viewModelScope.launch { repository.setAudioEncoder(value) }
    fun setSeparateMicRecording(value: Boolean) = viewModelScope.launch { repository.setSeparateMicRecording(value) }

    // Setters — Controls
    fun setFloatingControls(value: Boolean) = viewModelScope.launch { repository.setFloatingControls(value) }
    fun setTouchOverlay(value: Boolean) = viewModelScope.launch { repository.setTouchOverlay(value) }
    fun setCountdown(value: Int) = viewModelScope.launch { repository.setCountdown(value) }
    fun setClipperDurationMinutes(value: Int) = viewModelScope.launch { repository.setClipperDurationMinutes(value) }
    fun setStopBehavior(newSelection: String) = viewModelScope.launch {
        val current = stopBehavior.value.toMutableSet()
        if (current.contains(newSelection)) {
            current.remove(newSelection)
        } else {
            current.add(newSelection)
            if (newSelection == StopBehaviorKeys.SCREEN_OFF && current.contains(StopBehaviorKeys.PAUSE_ON_SCREEN_OFF)) {
                current.remove(StopBehaviorKeys.PAUSE_ON_SCREEN_OFF)
            }
            if (newSelection == StopBehaviorKeys.PAUSE_ON_SCREEN_OFF && current.contains(StopBehaviorKeys.SCREEN_OFF)) {
                current.remove(StopBehaviorKeys.SCREEN_OFF)
            }
        }
        repository.setStopBehavior(current)
    }

    // Setters — Camera Overlay
    fun setCameraOverlay(value: Boolean) = viewModelScope.launch { repository.setCameraOverlay(value) }
    fun setCameraOverlaySize(value: Int) = viewModelScope.launch { repository.setCameraOverlaySize(value) }
    fun setCameraXFraction(value: Float) = viewModelScope.launch { repository.setCameraXFraction(value) }
    fun setCameraYFraction(value: Float) = viewModelScope.launch { repository.setCameraYFraction(value) }
    fun setCameraLockPosition(value: Boolean) = viewModelScope.launch { repository.setCameraLockPosition(value) }
    fun setCameraFacing(value: String) = viewModelScope.launch { repository.setCameraFacing(value) }
    fun setCameraAspectRatio(value: String) = viewModelScope.launch { repository.setCameraAspectRatio(value) }
    fun setCameraOrientation(value: String) = viewModelScope.launch { repository.setCameraOrientation(value) }
    fun setCameraOpacity(value: Int) = viewModelScope.launch { repository.setCameraOpacity(value) }

    // Setters — Watermark
    fun setShowWatermark(value: Boolean) = viewModelScope.launch { repository.setShowWatermark(value) }
    fun setWatermarkLocation(value: String) = viewModelScope.launch { repository.setWatermarkLocation(value) }
    fun setWatermarkImageUri(value: String?) = viewModelScope.launch { repository.setWatermarkImageUri(value) }
    fun setWatermarkShape(value: String) = viewModelScope.launch { repository.setWatermarkShape(value) }
    fun setWatermarkOpacity(value: Int) = viewModelScope.launch { repository.setWatermarkOpacity(value) }
    fun setWatermarkSize(value: Int) = viewModelScope.launch { repository.setWatermarkSize(value) }
    fun setWatermarkXFraction(value: Float) = viewModelScope.launch { repository.setWatermarkXFraction(value) }
    fun setWatermarkYFraction(value: Float) = viewModelScope.launch { repository.setWatermarkYFraction(value) }

    // Setters — Screenshots
    fun setScreenshotFormat(value: String) = viewModelScope.launch { repository.setScreenshotFormat(value) }
    fun setScreenshotQuality(value: Int) = viewModelScope.launch { repository.setScreenshotQuality(value) }

    // Setters — Theme & Language
    fun setAppTheme(value: String) = viewModelScope.launch { repository.setAppTheme(value) }
    fun setAppLanguage(value: String) = viewModelScope.launch { repository.setAppLanguage(value) }

    // Setters — Storage
    fun setFilenamePattern(value: String) = viewModelScope.launch { repository.setFilenamePattern(value) }
    fun setSaveLocationUri(value: String) = viewModelScope.launch { repository.setSaveLocationUri(value) }
    fun setAutoDelete(value: Boolean) = viewModelScope.launch { repository.setAutoDelete(value) }

    // Setters — General
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { repository.setKeepScreenOn(value) }
    fun setHapticFeedback(value: Boolean) = viewModelScope.launch { repository.setHapticFeedback(value) }
    fun setSoundFeedback(value: Boolean) = viewModelScope.launch { repository.setSoundFeedback(value) }

    // Setters — UI Mode
    fun setPerformanceMode(value: Boolean) = viewModelScope.launch { repository.setPerformanceMode(value) }

    // Setters — Privacy
    fun setAnalyticsEnabled(value: Boolean) = viewModelScope.launch {
        repository.setAnalyticsEnabled(value)
        val app = getApplication<Application>()
        app.applyAnalyticsCollectionEnabled(value)
        app.applyCrashlyticsCollectionEnabled(value)
        app.syncFirebaseUserIdentity(value)
        app.refreshCrashlyticsSessionKeys(
            repository.appLanguage.first(),
            repository.floatingControls.first(),
        )
    }

    fun setPersonalizedAdsEnabled(value: Boolean) = viewModelScope.launch {
        repository.setPersonalizedAdsEnabled(value)
        getApplication<Application>().applyPersonalizedAdsEnabled(value)
    }

    // Setters — Onboarding
    fun setBetaNoticeShown(value: Boolean) = viewModelScope.launch { repository.setBetaNoticeShown(value) }

    // Setters — Accent Color
    fun setAccentColor(value: String)        = viewModelScope.launch { repository.setAccentColor(value) }
    fun setAccentColor2(value: String)       = viewModelScope.launch { repository.setAccentColor2(value) }
    fun setAccentUseGradient(value: Boolean) = viewModelScope.launch { repository.setAccentUseGradient(value) }

    // Service Control
    fun startRecordingService(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
            putExtra(ScreenRecordService.EXTRA_FPS, fps.value.toInt())
            putExtra(ScreenRecordService.EXTRA_BITRATE, (bitrate.value * 1_000_000).toInt())
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENABLED, recordAudio.value)
            putExtra(ScreenRecordService.EXTRA_INTERNAL_AUDIO_ENABLED, internalAudio.value)
            putExtra(ScreenRecordService.EXTRA_AUDIO_BITRATE, audioBitrate.value * 1000)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SAMPLE_RATE, audioSampleRate.value)
            putExtra(ScreenRecordService.EXTRA_AUDIO_CHANNELS, audioChannels.value)
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENCODER, audioEncoder.value)
            putExtra(ScreenRecordService.EXTRA_SEPARATE_MIC_RECORDING, separateMicRecording.value)
            putExtra(ScreenRecordService.EXTRA_SHOW_CAMERA, cameraOverlay.value)
            putExtra(ScreenRecordService.EXTRA_CAMERA_SIZE, cameraOverlaySize.value)
            putExtra(ScreenRecordService.EXTRA_CAMERA_X_FRACTION, cameraXFraction.value)
            putExtra(ScreenRecordService.EXTRA_CAMERA_Y_FRACTION, cameraYFraction.value)
            putExtra(ScreenRecordService.EXTRA_CAMERA_LOCK_POSITION, cameraLockPosition.value)
            putExtra(ScreenRecordService.EXTRA_CAMERA_FACING, cameraFacing.value)
            putExtra(ScreenRecordService.EXTRA_CAMERA_ASPECT_RATIO, cameraAspectRatio.value)
            putExtra(ScreenRecordService.EXTRA_CAMERA_OPACITY, cameraOpacity.value)
            putExtra(ScreenRecordService.EXTRA_SHOW_WATERMARK, showWatermark.value)
            putStringArrayListExtra(ScreenRecordService.EXTRA_STOP_BEHAVIOR, ArrayList(stopBehavior.value))
            putExtra(ScreenRecordService.EXTRA_SAVE_LOCATION, saveLocationUri.value)
            putExtra(ScreenRecordService.EXTRA_VIDEO_ENCODER, videoEncoder.value)
            putExtra(ScreenRecordService.EXTRA_SHOW_FLOATING_CONTROLS, floatingControls.value)
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, resolution.value)
            putExtra(ScreenRecordService.EXTRA_FILENAME_PATTERN, filenamePattern.value)
            putExtra(ScreenRecordService.EXTRA_COUNTDOWN, countdown.value)
            putExtra(ScreenRecordService.EXTRA_KEEP_SCREEN_ON, keepScreenOn.value)
            putExtra(ScreenRecordService.EXTRA_RECORDING_ORIENTATION, recordingOrientation.value)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_LOCATION, watermarkLocation.value)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_IMAGE_URI, watermarkImageUri.value)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_SHAPE, watermarkShape.value)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_OPACITY, watermarkOpacity.value)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_SIZE, watermarkSize.value)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_X_FRACTION, watermarkXFraction.value)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_Y_FRACTION, watermarkYFraction.value)
            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_FORMAT, screenshotFormat.value)
            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_QUALITY, screenshotQuality.value)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopRecordingService(context: Context) {
        context.startService(Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        })
    }

    /** Starts the rolling-buffer engine (requires the same MediaProjection grant as recording). */
    fun startBufferService(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START_BUFFER
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
            putExtra(ScreenRecordService.EXTRA_FPS, fps.value.toInt())
            putExtra(ScreenRecordService.EXTRA_BITRATE, (bitrate.value * 1_000_000).toInt())
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENABLED, recordAudio.value)
            putExtra(ScreenRecordService.EXTRA_INTERNAL_AUDIO_ENABLED, internalAudio.value)
            putExtra(ScreenRecordService.EXTRA_AUDIO_BITRATE, audioBitrate.value * 1000)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SAMPLE_RATE, audioSampleRate.value)
            putExtra(ScreenRecordService.EXTRA_AUDIO_CHANNELS, audioChannels.value)
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENCODER, audioEncoder.value)
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, resolution.value)
            putExtra(ScreenRecordService.EXTRA_VIDEO_ENCODER, videoEncoder.value)
            putExtra(ScreenRecordService.EXTRA_CLIPPER_DURATION_MINUTES, clipperDurationMinutes.value)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Pre-grant mode: obtain MediaProjection while the Activity is visible and keep it
     * alive in [ScreenRecordService] so the overlay can start recordings without a dialog.
     */
    fun prepareForOverlayRecording(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_PREPARE
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun revokeOverlayPreparation(context: Context) {
        context.startService(Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_REVOKE_PREPARE
        })
    }

    fun stopBufferService(context: Context) {
        context.startService(Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP_BUFFER
        })
    }

    fun saveClip(context: Context) {
        context.startService(Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_SAVE_CLIP
        })
    }
}
