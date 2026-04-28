package com.ibbie.catrec_screenrecorcer.ui.recording

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ibbie.catrec_screenrecorcer.CatRecApplication
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets
import com.ibbie.catrec_screenrecorcer.data.PreparedPausedSavingState
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.RecordingUiSnapshot
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingError
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingLifecycleState
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingSessionRepository
import com.ibbie.catrec_screenrecorcer.utils.LocaleHelper
import com.ibbie.catrec_screenrecorcer.utils.applyAnalyticsCollectionEnabled
import com.ibbie.catrec_screenrecorcer.utils.applyCrashlyticsCollectionEnabled
import com.ibbie.catrec_screenrecorcer.utils.applyPersonalizedAdsEnabled
import com.ibbie.catrec_screenrecorcer.utils.refreshCrashlyticsSessionKeys
import com.ibbie.catrec_screenrecorcer.utils.syncFirebaseUserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val recordingSessionRepository: RecordingSessionRepository =
        (application as CatRecApplication).recordingSessionRepository

    /** High-level capture lifecycle for Compose (Idle / Preparing / Recording / Paused). */
    val sessionLifecycleState: StateFlow<RecordingLifecycleState> = recordingSessionRepository.sessionState

    /** Encoder / projection failures — collect in UI for snackbars. */
    val recordingErrorEvents: SharedFlow<RecordingError> = recordingSessionRepository.errorEvents

    private val _lastRecordingError = MutableStateFlow<RecordingError?>(null)
    val lastRecordingError: StateFlow<RecordingError?> = _lastRecordingError

    init {
        viewModelScope.launch {
            combine(settingsRepository.captureMode, RecordingState.isBuffering) { mode, buffering ->
                if (buffering) CaptureMode.CLIPPER else mode
            }.collect { RecordingState.setMode(it) }
        }
        viewModelScope.launch {
            recordingSessionRepository.errorEvents.collect { err -> _lastRecordingError.value = err }
        }
    }

    val isRecording: StateFlow<Boolean> = RecordingState.isRecording
    val isBuffering: StateFlow<Boolean> = RecordingState.isBuffering
    val isPrepared: StateFlow<Boolean> = RecordingState.isPrepared
    val isSaving: StateFlow<Boolean> = RecordingState.isSaving
    val screenshotSavedCount: StateFlow<Int> = RecordingState.screenshotSavedCount

    // Video
    val fps: StateFlow<Float> = settingsRepository.fps.stateIn(viewModelScope, SharingStarted.Lazily, 30f)
    val bitrate: StateFlow<Float> = settingsRepository.bitrate.stateIn(viewModelScope, SharingStarted.Lazily, 10f)
    val videoEncoder: StateFlow<String> = settingsRepository.videoEncoder.stateIn(viewModelScope, SharingStarted.Lazily, "H.264")
    val resolution: StateFlow<String> = settingsRepository.resolution.stateIn(viewModelScope, SharingStarted.Lazily, "Native")
    val recordingOrientation: StateFlow<String> = settingsRepository.recordingOrientation.stateIn(viewModelScope, SharingStarted.Lazily, "Auto")

    // Audio
    val recordAudio: StateFlow<Boolean> = settingsRepository.recordAudio.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val internalAudio: StateFlow<Boolean> = settingsRepository.internalAudio.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val audioBitrate: StateFlow<Int> = settingsRepository.audioBitrate.stateIn(viewModelScope, SharingStarted.Lazily, 128)
    val audioSampleRate: StateFlow<Int> = settingsRepository.audioSampleRate.stateIn(viewModelScope, SharingStarted.Lazily, 44100)
    val audioChannels: StateFlow<String> = settingsRepository.audioChannels.stateIn(viewModelScope, SharingStarted.Lazily, "Mono")
    val audioEncoder: StateFlow<String> = settingsRepository.audioEncoder.stateIn(viewModelScope, SharingStarted.Lazily, "AAC-LC")
    val separateMicRecording: StateFlow<Boolean> = settingsRepository.separateMicRecording.stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Controls
    val floatingControls: StateFlow<Boolean> = settingsRepository.floatingControls.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val touchOverlay: StateFlow<Boolean> = settingsRepository.touchOverlay.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val countdown: StateFlow<Int> = settingsRepository.countdown.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /** Clipper duration: rolling window (1–5 minutes). */
    val clipperDurationMinutes: StateFlow<Int> =
        settingsRepository.clipperDurationMinutes.stateIn(viewModelScope, SharingStarted.Lazily, 1)
    val stopBehavior: StateFlow<Set<String>> =
        settingsRepository.stopBehavior.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            setOf(StopBehaviorKeys.NOTIFICATION),
        )
    val brushOverlayEnabled: StateFlow<Boolean> =
        settingsRepository.brushOverlayEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val hideFloatingIconWhileRecording: StateFlow<Boolean> =
        settingsRepository.hideFloatingIconWhileRecording.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val postScreenshotOptions: StateFlow<Boolean> =
        settingsRepository.postScreenshotOptions.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val recordSingleAppEnabled: StateFlow<Boolean> =
        settingsRepository.recordSingleAppEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val captureMode: StateFlow<String> =
        settingsRepository.captureMode.stateIn(viewModelScope, SharingStarted.Eagerly, CaptureMode.RECORD)

    /** One subscription for FAB + top-bar recording UI; reduces duplicate collectors and recompositions. */
    val recordingUiSnapshot: StateFlow<RecordingUiSnapshot> =
        combine(
            combine(isRecording, isBuffering, ::Pair),
            combine(captureMode, recordAudio, ::Pair),
            combine(internalAudio, recordSingleAppEnabled, ::Pair),
            combine(isPrepared, RecordingState.isRecordingPaused, RecordingState.isSaving) { p, pa, sv ->
                PreparedPausedSavingState(p, pa, sv)
            },
        ) { recBuf, modeAudio, internalSingle, prepPausedSaving ->
            RecordingUiSnapshot(
                isRecording = recBuf.first,
                isBuffering = recBuf.second,
                captureMode = modeAudio.first,
                recordAudio = modeAudio.second,
                internalAudio = internalSingle.first,
                recordSingleAppEnabled = internalSingle.second,
                isPrepared = prepPausedSaving.isPrepared,
                isRecordingPaused = prepPausedSaving.isRecordingPaused,
                isSaving = prepPausedSaving.isSaving,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            RecordingUiSnapshot(),
        )

    val gifRecorderPresetId: StateFlow<String> =
        settingsRepository.gifRecorderPresetId.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            GifRecordingPresets.default.id,
        )
    val isGifCaptureMode: StateFlow<Boolean> =
        captureMode.map { it == CaptureMode.GIF }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Values used when starting a screen recording (GIF uses the selected preset; user video prefs stay unchanged). */
    val effectiveRecordingFps: StateFlow<Int> =
        combine(captureMode, gifRecorderPresetId, settingsRepository.fps) { mode, presetId, userFps ->
            if (mode == CaptureMode.GIF) GifRecordingPresets.byId(presetId).recordingFps else userFps.toInt()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    val effectiveRecordingBitrateBits: StateFlow<Int> =
        combine(captureMode, gifRecorderPresetId, settingsRepository.bitrate) { mode, presetId, userMbps ->
            if (mode == CaptureMode.GIF) {
                GifRecordingPresets.byId(presetId).bitrateBitsPerSec
            } else {
                (userMbps * 1_000_000).toInt()
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 10_000_000)

    val effectiveRecordingResolution: StateFlow<String> =
        combine(captureMode, gifRecorderPresetId, settingsRepository.resolution) { mode, presetId, userRes ->
            if (mode == CaptureMode.GIF) GifRecordingPresets.byId(presetId).resolutionSetting else userRes
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "Native")

    // Camera Overlay
    val cameraOverlay: StateFlow<Boolean> = settingsRepository.cameraOverlay.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val cameraOverlaySize: StateFlow<Int> = settingsRepository.cameraOverlaySize.stateIn(viewModelScope, SharingStarted.Lazily, 120)
    val cameraXFraction: StateFlow<Float> = settingsRepository.cameraXFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.05f)
    val cameraYFraction: StateFlow<Float> = settingsRepository.cameraYFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.1f)
    val cameraLockPosition: StateFlow<Boolean> = settingsRepository.cameraLockPosition.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val cameraFacing: StateFlow<String> = settingsRepository.cameraFacing.stateIn(viewModelScope, SharingStarted.Lazily, "Front")
    val cameraAspectRatio: StateFlow<String> = settingsRepository.cameraAspectRatio.stateIn(viewModelScope, SharingStarted.Lazily, "Circle")
    val cameraOrientation: StateFlow<String> = settingsRepository.cameraOrientation.stateIn(viewModelScope, SharingStarted.Lazily, "Auto")
    val cameraOpacity: StateFlow<Int> = settingsRepository.cameraOpacity.stateIn(viewModelScope, SharingStarted.Lazily, 100)

    // Watermark
    val showWatermark: StateFlow<Boolean> = settingsRepository.showWatermark.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val watermarkLocation: StateFlow<String> = settingsRepository.watermarkLocation.stateIn(viewModelScope, SharingStarted.Lazily, "Top Left")
    val watermarkImageUri: StateFlow<String?> = settingsRepository.watermarkImageUri.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val watermarkShape: StateFlow<String> = settingsRepository.watermarkShape.stateIn(viewModelScope, SharingStarted.Lazily, "Square")
    val watermarkOpacity: StateFlow<Int> = settingsRepository.watermarkOpacity.stateIn(viewModelScope, SharingStarted.Lazily, 100)
    val watermarkSize: StateFlow<Int> = settingsRepository.watermarkSize.stateIn(viewModelScope, SharingStarted.Lazily, 80)
    val watermarkXFraction: StateFlow<Float> = settingsRepository.watermarkXFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.05f)
    val watermarkYFraction: StateFlow<Float> = settingsRepository.watermarkYFraction.stateIn(viewModelScope, SharingStarted.Lazily, 0.05f)

    // Screenshots
    val screenshotFormat: StateFlow<String> = settingsRepository.screenshotFormat.stateIn(viewModelScope, SharingStarted.Lazily, "JPEG")
    val screenshotQuality: StateFlow<Int> = settingsRepository.screenshotQuality.stateIn(viewModelScope, SharingStarted.Lazily, 90)

    // Theme & Language
    val appTheme: StateFlow<String> = settingsRepository.appTheme.stateIn(viewModelScope, SharingStarted.Lazily, "System")
    val appLanguage: StateFlow<String> = settingsRepository.appLanguage.stateIn(viewModelScope, SharingStarted.Lazily, "system")

    // Storage
    val filenamePattern: StateFlow<String> = settingsRepository.filenamePattern.stateIn(viewModelScope, SharingStarted.Lazily, "yyyyMMdd_HHmmss")
    val saveLocationUri: StateFlow<String?> = settingsRepository.saveLocationUri.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val autoDelete: StateFlow<Boolean> = settingsRepository.autoDelete.stateIn(viewModelScope, SharingStarted.Lazily, false)

    // General
    val keepScreenOn: StateFlow<Boolean> = settingsRepository.keepScreenOn.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val hapticFeedback: StateFlow<Boolean> = settingsRepository.hapticFeedback.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val soundFeedback: StateFlow<Boolean> = settingsRepository.soundFeedback.stateIn(viewModelScope, SharingStarted.Lazily, false)

    // UI Mode
    val performanceMode: StateFlow<Boolean> = settingsRepository.performanceMode.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val adaptivePerformanceEnabled: StateFlow<Boolean> =
        settingsRepository.adaptiveRecordingPerformance.stateIn(viewModelScope, SharingStarted.Lazily, false)

    /** Remove-ads entitlement: disables all rewarded-ad gating when true ([com.ibbie.catrec_screenrecorcer.data.AdGate]). */
    val adsDisabled: StateFlow<Boolean> =
        settingsRepository.adsDisabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Privacy
    val analyticsEnabled: StateFlow<Boolean> = settingsRepository.analyticsEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val personalizedAdsEnabled: StateFlow<Boolean> =
        settingsRepository.personalizedAdsEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)

    // Onboarding (StateFlow defaults false before DataStore loads; use [betaNoticePersistedValue] for gating.)
    val betaNoticeShown: StateFlow<Boolean> =
        settingsRepository.betaNoticeShown.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Reads the persisted flag so the beta dialog is not shown on every cold start (avoids stateIn initial false). */
    suspend fun betaNoticePersistedValue(): Boolean = settingsRepository.betaNoticeShown.first()

    // Accent Color
    val accentColor: StateFlow<String> = settingsRepository.accentColor.stateIn(viewModelScope, SharingStarted.Lazily, "FF0033")
    val accentColor2: StateFlow<String> = settingsRepository.accentColor2.stateIn(viewModelScope, SharingStarted.Lazily, "FF8C00")
    val accentUseGradient: StateFlow<Boolean> = settingsRepository.accentUseGradient.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val showDebugInfo: StateFlow<Boolean> = MutableStateFlow(false)

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted

    fun updatePermissionsState(granted: Boolean) {
        _permissionsGranted.value = granted
    }

    // Setters — Video
    fun setFps(value: Float) = viewModelScope.launch { settingsRepository.setFps(value) }

    fun setBitrate(value: Float) = viewModelScope.launch { settingsRepository.setBitrate(value) }

    fun setVideoEncoder(value: String) = viewModelScope.launch { settingsRepository.setVideoEncoder(value) }

    fun setResolution(value: String) = viewModelScope.launch { settingsRepository.setResolution(value) }

    fun setRecordingOrientation(value: String) = viewModelScope.launch { settingsRepository.setRecordingOrientation(value) }

    // Setters — Audio
    fun setRecordAudio(value: Boolean) = viewModelScope.launch { settingsRepository.setRecordAudio(value) }

    fun setInternalAudio(value: Boolean) = viewModelScope.launch { settingsRepository.setInternalAudio(value) }

    fun setAudioBitrate(value: Int) = viewModelScope.launch { settingsRepository.setAudioBitrate(value) }

    fun setAudioSampleRate(value: Int) = viewModelScope.launch { settingsRepository.setAudioSampleRate(value) }

    fun setAudioChannels(value: String) = viewModelScope.launch { settingsRepository.setAudioChannels(value) }

    fun setAudioEncoder(value: String) = viewModelScope.launch { settingsRepository.setAudioEncoder(value) }

    fun setSeparateMicRecording(value: Boolean) = viewModelScope.launch { settingsRepository.setSeparateMicRecording(value) }

    // Setters — Controls
    fun setFloatingControls(value: Boolean) = viewModelScope.launch { settingsRepository.setFloatingControls(value) }

    fun setTouchOverlay(value: Boolean) = viewModelScope.launch { settingsRepository.setTouchOverlay(value) }

    fun setCountdown(value: Int) = viewModelScope.launch { settingsRepository.setCountdown(value) }

    fun setClipperDurationMinutes(value: Int) = viewModelScope.launch { settingsRepository.setClipperDurationMinutes(value) }

    fun setBrushOverlayEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setBrushOverlayEnabled(value) }

    fun setHideFloatingIconWhileRecording(value: Boolean) =
        viewModelScope.launch {
            settingsRepository.setHideFloatingIconWhileRecording(value)
        }

    fun setPostScreenshotOptions(value: Boolean) = viewModelScope.launch { settingsRepository.setPostScreenshotOptions(value) }

    fun setRecordSingleAppEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setRecordSingleAppEnabled(value) }

    fun setCaptureMode(mode: String) = viewModelScope.launch { settingsRepository.setCaptureMode(mode) }

    fun setGifRecorderPresetId(id: String) = viewModelScope.launch { settingsRepository.setGifRecorderPresetId(id) }

    fun setStopBehavior(newSelection: String) =
        viewModelScope.launch {
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
            settingsRepository.setStopBehavior(current)
        }

    // Setters — Camera Overlay
    fun setCameraOverlay(value: Boolean) = viewModelScope.launch { settingsRepository.setCameraOverlay(value) }

    fun setCameraOverlaySize(value: Int) = viewModelScope.launch { settingsRepository.setCameraOverlaySize(value) }

    fun setCameraXFraction(value: Float) = viewModelScope.launch { settingsRepository.setCameraXFraction(value) }

    fun setCameraYFraction(value: Float) = viewModelScope.launch { settingsRepository.setCameraYFraction(value) }

    fun setCameraLockPosition(value: Boolean) = viewModelScope.launch { settingsRepository.setCameraLockPosition(value) }

    fun setCameraFacing(value: String) = viewModelScope.launch { settingsRepository.setCameraFacing(value) }

    fun setCameraAspectRatio(value: String) = viewModelScope.launch { settingsRepository.setCameraAspectRatio(value) }

    fun setCameraOrientation(value: String) = viewModelScope.launch { settingsRepository.setCameraOrientation(value) }

    fun setCameraOpacity(value: Int) = viewModelScope.launch { settingsRepository.setCameraOpacity(value) }

    // Setters — Watermark
    fun setShowWatermark(value: Boolean) = viewModelScope.launch { settingsRepository.setShowWatermark(value) }

    fun setWatermarkLocation(value: String) = viewModelScope.launch { settingsRepository.setWatermarkLocation(value) }

    fun setWatermarkImageUri(value: String?) = viewModelScope.launch { settingsRepository.setWatermarkImageUri(value) }

    fun setWatermarkShape(value: String) = viewModelScope.launch { settingsRepository.setWatermarkShape(value) }

    fun setWatermarkOpacity(value: Int) = viewModelScope.launch { settingsRepository.setWatermarkOpacity(value) }

    fun setWatermarkSize(value: Int) = viewModelScope.launch { settingsRepository.setWatermarkSize(value) }

    fun setWatermarkXFraction(value: Float) = viewModelScope.launch { settingsRepository.setWatermarkXFraction(value) }

    fun setWatermarkYFraction(value: Float) = viewModelScope.launch { settingsRepository.setWatermarkYFraction(value) }

    // Setters — Screenshots
    fun setScreenshotFormat(value: String) = viewModelScope.launch { settingsRepository.setScreenshotFormat(value) }

    fun setScreenshotQuality(value: Int) = viewModelScope.launch { settingsRepository.setScreenshotQuality(value) }

    // Setters — Theme & Language
    fun setAppTheme(value: String) = viewModelScope.launch { settingsRepository.setAppTheme(value) }

    fun setAppLanguage(value: String) = viewModelScope.launch { settingsRepository.setAppLanguage(value) }

    /**
     * Persists language to DataStore first, then updates app locale + SharedPreferences and recreates
     * the activity so resources and the settings row stay aligned after process restarts.
     */
    fun setAppLanguageWithUiApply(
        context: Context,
        code: String,
    ) = viewModelScope.launch {
        settingsRepository.setAppLanguage(code)
        withContext(Dispatchers.Main.immediate) {
            LocaleHelper.apply(context.applicationContext, code)
            (context as? Activity)?.recreate()
        }
    }

    // Setters — Storage
    fun setFilenamePattern(value: String) = viewModelScope.launch { settingsRepository.setFilenamePattern(value) }

    fun setSaveLocationUri(value: String) = viewModelScope.launch { settingsRepository.setSaveLocationUri(value) }

    fun setAutoDelete(value: Boolean) = viewModelScope.launch { settingsRepository.setAutoDelete(value) }

    // Setters — General
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { settingsRepository.setKeepScreenOn(value) }

    fun setHapticFeedback(value: Boolean) = viewModelScope.launch { settingsRepository.setHapticFeedback(value) }

    fun setSoundFeedback(value: Boolean) = viewModelScope.launch { settingsRepository.setSoundFeedback(value) }

    // Setters — UI Mode
    fun setPerformanceMode(value: Boolean) = viewModelScope.launch { settingsRepository.setPerformanceMode(value) }

    fun setAdaptivePerformanceEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setAdaptiveRecordingPerformance(value) }

    // Setters — Privacy
    fun setAnalyticsEnabled(value: Boolean) =
        viewModelScope.launch {
            settingsRepository.setAnalyticsEnabled(value)
            val app = getApplication<Application>()
            app.applyAnalyticsCollectionEnabled(value)
            applyCrashlyticsCollectionEnabled(value)
            app.syncFirebaseUserIdentity(value)
            refreshCrashlyticsSessionKeys(
                settingsRepository.appLanguage.first(),
                settingsRepository.floatingControls.first(),
            )
        }

    fun setPersonalizedAdsEnabled(value: Boolean) =
        viewModelScope.launch {
            settingsRepository.setPersonalizedAdsEnabled(value)
            applyPersonalizedAdsEnabled(
                value,
                adsSdkEnabled = !settingsRepository.adsDisabled.first(),
            )
        }

    // Setters — Onboarding
    fun setBetaNoticeShown(value: Boolean) = viewModelScope.launch { settingsRepository.setBetaNoticeShown(value) }

    // Setters — Accent Color
    fun setAccentColor(value: String) = viewModelScope.launch { settingsRepository.setAccentColor(value) }

    fun setAccentColor2(value: String) = viewModelScope.launch { settingsRepository.setAccentColor2(value) }

    fun setAccentUseGradient(value: Boolean) = viewModelScope.launch { settingsRepository.setAccentUseGradient(value) }

    // Session control (delegates to [RecordingSessionRepository] → [ScreenRecordService])
    // NOTE: Must be synchronous. Android 12+ only keeps the transient foreground-service grant
    // window open while the activity-result call stack is still unwinding. Deferring the
    // startForegroundService call through `viewModelScope.launch` would trigger
    // `ForegroundServiceStartNotAllowedException`. See [DefaultRecordingSessionRepository].
    fun startRecordingService(
        context: Context,
        resultCode: Int,
        data: Intent,
    ) {
        val config =
            recordingSessionRepository.createSessionConfigForFullRecording(
                context,
                resultCode,
            )
        recordingSessionRepository.startRecording(context, config, data)
    }

    fun stopRecordingService(context: Context) {
        recordingSessionRepository.stop(context)
    }

    /** Starts the rolling-buffer engine (requires the same MediaProjection grant as recording). */
    fun startBufferService(
        context: Context,
        resultCode: Int,
        data: Intent,
    ) {
        val config =
            recordingSessionRepository.createSessionConfigForBuffer(
                context,
                resultCode,
            )
        recordingSessionRepository.startBufferSession(context, config, data)
    }

    /**
     * Pre-grant mode: obtain MediaProjection while the Activity is visible and keep it
     * alive in [ScreenRecordService] so the overlay can start recordings without a dialog.
     */
    fun prepareForOverlayRecording(
        context: Context,
        resultCode: Int,
        data: Intent,
    ) {
        recordingSessionRepository.prepareOverlaySession(context, resultCode, data)
    }

    fun revokeOverlayPreparation(context: Context) {
        recordingSessionRepository.revokePrepare(context)
    }

    fun stopBufferService(context: Context) {
        recordingSessionRepository.stop(context)
    }

    fun saveClip(context: Context) {
        recordingSessionRepository.saveClip(context)
    }

    fun pauseRecordingSession(context: Context) {
        recordingSessionRepository.pause(context)
    }

    fun resumeRecordingSession(context: Context) {
        recordingSessionRepository.resume(context)
    }
}
