package com.ibbie.catrec_screenrecorcer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ibbie.catrec_screenrecorcer.utils.applyAnalyticsCollectionEnabled
import com.ibbie.catrec_screenrecorcer.utils.applyCrashlyticsCollectionEnabled
import com.ibbie.catrec_screenrecorcer.utils.applyPersonalizedAdsEnabled
import com.ibbie.catrec_screenrecorcer.utils.syncFirebaseUserIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

private const val SETTINGS_DATASTORE_NAME = "settings"

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler(
        produceNewData = { emptyPreferences() },
    ),
)

class SettingsRepository(
    context: Context,
) {
    private val context = context.applicationContext

    companion object {
        /**
         * Ensures `files/datastore` exists before the first DataStore read.
         * Some devices (e.g. Android 16) have been observed to surface `FileNotFoundException` / ENOENT
         * when the parent directory was never created because no write had run yet.
         */
        fun prepareDataStoreFilesystem(context: Context) {
            File(context.applicationContext.filesDir, "datastore").mkdirs()
        }

        // Video
        val FPS = floatPreferencesKey("fps")
        val BITRATE = floatPreferencesKey("bitrate")
        val VIDEO_ENCODER = stringPreferencesKey("video_encoder")
        val RESOLUTION = stringPreferencesKey("resolution")
        val RECORDING_ORIENTATION = stringPreferencesKey("recording_orientation")

        // Audio
        val RECORD_AUDIO = booleanPreferencesKey("record_audio")
        val INTERNAL_AUDIO = booleanPreferencesKey("internal_audio")
        val AUDIO_BITRATE = intPreferencesKey("audio_bitrate")
        val AUDIO_SAMPLE_RATE = intPreferencesKey("audio_sample_rate")
        val AUDIO_CHANNELS = stringPreferencesKey("audio_channels")
        val AUDIO_ENCODER = stringPreferencesKey("audio_encoder")
        val SEPARATE_MIC_RECORDING = booleanPreferencesKey("separate_mic_recording")

        // Controls
        val FLOATING_CONTROLS = booleanPreferencesKey("floating_controls")
        val TOUCH_OVERLAY = booleanPreferencesKey("touch_overlay")
        val COUNTDOWN = intPreferencesKey("countdown")

        /** Clipper duration in minutes: rolling window length (1–5). */
        val CLIPPER_DURATION_MINUTES = intPreferencesKey("clipper_duration_minutes")
        val STOP_BEHAVIOR = stringSetPreferencesKey("stop_behavior_set")

        /** Show Brush tool on floating controls overlay. */
        val BRUSH_OVERLAY_ENABLED = booleanPreferencesKey("brush_overlay_enabled")

        /** Hide floating controls bubble while a recording session is active. */
        val HIDE_FLOATING_ICON_WHILE_RECORDING = booleanPreferencesKey("hide_floating_icon_while_recording")

        /** After saving a screenshot, show share/edit options. */
        val POST_SCREENSHOT_OPTIONS = booleanPreferencesKey("post_screenshot_options")

        /**
         * When true (Android 14+), screen capture intent allows choosing a single app window.
         * When false on API 34+, capture is restricted to the full display.
         */
        val RECORD_SINGLE_APP_ENABLED = booleanPreferencesKey("record_single_app_enabled")

        /** [CaptureMode.RECORD], [CaptureMode.CLIPPER], or [CaptureMode.GIF]. */
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val GIF_RECORDER_PRESET_ID = stringPreferencesKey("gif_recorder_preset_id")

        // Overlay — Camera
        val CAMERA_OVERLAY = booleanPreferencesKey("camera_overlay")
        val CAMERA_OVERLAY_SIZE = intPreferencesKey("camera_overlay_size")
        val CAMERA_X_FRACTION = floatPreferencesKey("camera_x_fraction")
        val CAMERA_Y_FRACTION = floatPreferencesKey("camera_y_fraction")
        val CAMERA_LOCK_POSITION = booleanPreferencesKey("camera_lock_position")
        val CAMERA_FACING = stringPreferencesKey("camera_facing")
        val CAMERA_ASPECT_RATIO = stringPreferencesKey("camera_aspect_ratio")
        val CAMERA_ORIENTATION = stringPreferencesKey("camera_orientation")
        val CAMERA_OPACITY = intPreferencesKey("camera_opacity")

        // Overlay — Watermark
        val SHOW_WATERMARK = booleanPreferencesKey("show_watermark")
        val WATERMARK_LOCATION = stringPreferencesKey("watermark_location")
        val WATERMARK_IMAGE_URI = stringPreferencesKey("watermark_image_uri")
        val WATERMARK_SHAPE = stringPreferencesKey("watermark_shape")
        val WATERMARK_OPACITY = intPreferencesKey("watermark_opacity")
        val WATERMARK_SIZE = intPreferencesKey("watermark_size")
        val WATERMARK_X_FRACTION = floatPreferencesKey("watermark_x_fraction")
        val WATERMARK_Y_FRACTION = floatPreferencesKey("watermark_y_fraction")

        // Screenshots
        val SCREENSHOT_FORMAT = stringPreferencesKey("screenshot_format")
        val SCREENSHOT_QUALITY = intPreferencesKey("screenshot_quality")

        // Theme & Language
        val APP_THEME = stringPreferencesKey("app_theme")
        val APP_LANGUAGE = stringPreferencesKey("app_language")

        // Storage
        val FILENAME_PATTERN = stringPreferencesKey("filename_pattern")
        val SAVE_LOCATION_URI = stringPreferencesKey("save_location_uri")
        val AUTO_DELETE = booleanPreferencesKey("auto_delete")

        // General
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val SOUND_FEEDBACK = booleanPreferencesKey("sound_feedback")

        // UI Mode
        val PERFORMANCE_MODE = booleanPreferencesKey("performance_mode")

        /** Opt-in: reduce encoder load under backpressure while recording (not GIF). */
        val ADAPTIVE_RECORDING_PERFORMANCE = booleanPreferencesKey("adaptive_recording_performance")

        // Privacy
        val ANALYTICS_ENABLED = booleanPreferencesKey("analytics_enabled")

        /** AdMob: personalized ads (default on); independent of Firebase Analytics. */
        val PERSONALIZED_ADS_ENABLED = booleanPreferencesKey("personalized_ads_enabled")

        /** One-time: user completed the first-launch analytics consent dialog. */
        val ANALYTICS_CONSENT_PROMPT_COMPLETED = booleanPreferencesKey("analytics_consent_prompt_completed")

        /** Stable anonymous install ID for Firebase Crashlytics / Analytics (no PII). */
        val FIREBASE_ANONYMOUS_USER_ID = stringPreferencesKey("firebase_anonymous_user_id")

        /**
         * When true, all ad-gated premium features are treated as unlocked (see [com.ibbie.catrec_screenrecorcer.data.AdGate]).
         * Synced from Play Billing for [com.ibbie.catrec_screenrecorcer.billing.BillingProductIds.REMOVE_ADS] and persisted for fast UI/offline.
         */
        val ADS_DISABLED = booleanPreferencesKey("ads_disabled_entitlement")

        // Onboarding
        val BETA_NOTICE_SHOWN = booleanPreferencesKey("beta_notice_shown")

        // Accent Color
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val ACCENT_COLOR_2 = stringPreferencesKey("accent_color_2")
        val ACCENT_USE_GRADIENT = booleanPreferencesKey("accent_use_gradient")
    }

    // Video
    val fps: Flow<Float> = context.dataStore.data.map { it[FPS] ?: 30f }
    val bitrate: Flow<Float> = context.dataStore.data.map { it[BITRATE] ?: 10f }
    val videoEncoder: Flow<String> = context.dataStore.data.map { it[VIDEO_ENCODER] ?: "H.264" }
    val resolution: Flow<String> = context.dataStore.data.map { it[RESOLUTION] ?: "Native" }
    val recordingOrientation: Flow<String> = context.dataStore.data.map { it[RECORDING_ORIENTATION] ?: "Auto" }

    // Audio
    val recordAudio: Flow<Boolean> = context.dataStore.data.map { it[RECORD_AUDIO] ?: false }
    val internalAudio: Flow<Boolean> = context.dataStore.data.map { it[INTERNAL_AUDIO] ?: false }
    val audioBitrate: Flow<Int> = context.dataStore.data.map { it[AUDIO_BITRATE] ?: 128 }
    val audioSampleRate: Flow<Int> = context.dataStore.data.map { it[AUDIO_SAMPLE_RATE] ?: 44100 }
    val audioChannels: Flow<String> = context.dataStore.data.map { it[AUDIO_CHANNELS] ?: "Mono" }
    val audioEncoder: Flow<String> = context.dataStore.data.map { it[AUDIO_ENCODER] ?: "AAC-LC" }
    val separateMicRecording: Flow<Boolean> = context.dataStore.data.map { it[SEPARATE_MIC_RECORDING] ?: false }

    // Controls
    val floatingControls: Flow<Boolean> = context.dataStore.data.map { it[FLOATING_CONTROLS] ?: false }
    val touchOverlay: Flow<Boolean> = context.dataStore.data.map { it[TOUCH_OVERLAY] ?: false }
    val countdown: Flow<Int> = context.dataStore.data.map { it[COUNTDOWN] ?: 0 }
    val clipperDurationMinutes: Flow<Int> =
        context.dataStore.data.map { prefs ->
            (prefs[CLIPPER_DURATION_MINUTES] ?: 1).coerceIn(1, 5)
        }
    val stopBehavior: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            StopBehaviorKeys.migrateSet(prefs[STOP_BEHAVIOR])
        }
    val brushOverlayEnabled: Flow<Boolean> = context.dataStore.data.map { it[BRUSH_OVERLAY_ENABLED] ?: false }
    val hideFloatingIconWhileRecording: Flow<Boolean> =
        context.dataStore.data.map { it[HIDE_FLOATING_ICON_WHILE_RECORDING] ?: false }
    val postScreenshotOptions: Flow<Boolean> =
        context.dataStore.data.map { it[POST_SCREENSHOT_OPTIONS] ?: false }
    val recordSingleAppEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[RECORD_SINGLE_APP_ENABLED] ?: false }
    val captureMode: Flow<String> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[CAPTURE_MODE] ?: CaptureMode.RECORD
            if (CaptureMode.isValid(raw)) raw else CaptureMode.RECORD
        }
    val gifRecorderPresetId: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[GIF_RECORDER_PRESET_ID] ?: GifRecordingPresets.default.id
        }

    // Camera Overlay
    val cameraOverlay: Flow<Boolean> = context.dataStore.data.map { it[CAMERA_OVERLAY] ?: false }
    val cameraOverlaySize: Flow<Int> = context.dataStore.data.map { it[CAMERA_OVERLAY_SIZE] ?: 120 }
    val cameraXFraction: Flow<Float> = context.dataStore.data.map { it[CAMERA_X_FRACTION] ?: 0.05f }
    val cameraYFraction: Flow<Float> = context.dataStore.data.map { it[CAMERA_Y_FRACTION] ?: 0.1f }
    val cameraLockPosition: Flow<Boolean> = context.dataStore.data.map { it[CAMERA_LOCK_POSITION] ?: false }
    val cameraFacing: Flow<String> = context.dataStore.data.map { it[CAMERA_FACING] ?: "Front" }
    val cameraAspectRatio: Flow<String> = context.dataStore.data.map { it[CAMERA_ASPECT_RATIO] ?: "Circle" }
    val cameraOrientation: Flow<String> = context.dataStore.data.map { it[CAMERA_ORIENTATION] ?: "Auto" }
    val cameraOpacity: Flow<Int> = context.dataStore.data.map { it[CAMERA_OPACITY] ?: 100 }

    // Watermark
    val showWatermark: Flow<Boolean> = context.dataStore.data.map { it[SHOW_WATERMARK] ?: false }
    val watermarkLocation: Flow<String> = context.dataStore.data.map { it[WATERMARK_LOCATION] ?: "Top Left" }
    val watermarkImageUri: Flow<String?> = context.dataStore.data.map { it[WATERMARK_IMAGE_URI] }
    val watermarkShape: Flow<String> = context.dataStore.data.map { it[WATERMARK_SHAPE] ?: "Square" }
    val watermarkOpacity: Flow<Int> = context.dataStore.data.map { it[WATERMARK_OPACITY] ?: 100 }
    val watermarkSize: Flow<Int> = context.dataStore.data.map { it[WATERMARK_SIZE] ?: 80 }
    val watermarkXFraction: Flow<Float> = context.dataStore.data.map { it[WATERMARK_X_FRACTION] ?: 0.05f }
    val watermarkYFraction: Flow<Float> = context.dataStore.data.map { it[WATERMARK_Y_FRACTION] ?: 0.05f }

    // Screenshots
    val screenshotFormat: Flow<String> = context.dataStore.data.map { it[SCREENSHOT_FORMAT] ?: "JPEG" }
    val screenshotQuality: Flow<Int> = context.dataStore.data.map { it[SCREENSHOT_QUALITY] ?: 90 }

    // Theme & Language
    val appTheme: Flow<String> = context.dataStore.data.map { it[APP_THEME] ?: "System" }
    val appLanguage: Flow<String> = context.dataStore.data.map { it[APP_LANGUAGE] ?: "system" }

    // Storage
    val filenamePattern: Flow<String> = context.dataStore.data.map { it[FILENAME_PATTERN] ?: "yyyyMMdd_HHmmss" }
    val saveLocationUri: Flow<String?> = context.dataStore.data.map { it[SAVE_LOCATION_URI] }
    val autoDelete: Flow<Boolean> = context.dataStore.data.map { it[AUTO_DELETE] ?: false }

    // General
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[KEEP_SCREEN_ON] ?: false }
    val hapticFeedback: Flow<Boolean> = context.dataStore.data.map { it[HAPTIC_FEEDBACK] ?: false }
    val soundFeedback: Flow<Boolean> = context.dataStore.data.map { it[SOUND_FEEDBACK] ?: false }

    // UI Mode
    val performanceMode: Flow<Boolean> = context.dataStore.data.map { it[PERFORMANCE_MODE] ?: false }

    /** When true, recording/buffer sessions may lower bitrate and decimate relay frames under stress. */
    val adaptiveRecordingPerformance: Flow<Boolean> =
        context.dataStore.data.map { it[ADAPTIVE_RECORDING_PERFORMANCE] ?: false }

    // Privacy — analytics default off; personalized ads default on (independent toggles)
    val analyticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[ANALYTICS_ENABLED] ?: false }
    val personalizedAdsEnabled: Flow<Boolean> = context.dataStore.data.map { it[PERSONALIZED_ADS_ENABLED] ?: true }
    val analyticsConsentPromptCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[ANALYTICS_CONSENT_PROMPT_COMPLETED] ?: false }

    /** True after remove-ads purchase (or while a pending remove-ads flow completes — Play is source of truth on next sync). */
    val adsDisabled: Flow<Boolean> = context.dataStore.data.map { it[ADS_DISABLED] ?: false }

    /**
     * Default true for GA (1.0+): skip legacy beta onboarding for new installs.
     */
    val betaNoticeShown: Flow<Boolean> = context.dataStore.data.map { it[BETA_NOTICE_SHOWN] ?: true }

    // Accent Color
    val accentColor: Flow<String> = context.dataStore.data.map { it[ACCENT_COLOR] ?: "FF0033" }
    val accentColor2: Flow<String> = context.dataStore.data.map { it[ACCENT_COLOR_2] ?: "FF6600" }
    val accentUseGradient: Flow<Boolean> = context.dataStore.data.map { it[ACCENT_USE_GRADIENT] ?: false }

    // Setters — Video
    suspend fun setFps(value: Float) {
        context.dataStore.edit { it[FPS] = value }
    }

    suspend fun setBitrate(value: Float) {
        context.dataStore.edit { it[BITRATE] = value }
    }

    suspend fun setVideoEncoder(value: String) {
        context.dataStore.edit { it[VIDEO_ENCODER] = value }
    }

    suspend fun setResolution(value: String) {
        context.dataStore.edit { it[RESOLUTION] = value }
    }

    suspend fun setRecordingOrientation(value: String) {
        context.dataStore.edit { it[RECORDING_ORIENTATION] = value }
    }

    // Setters — Audio
    suspend fun setRecordAudio(value: Boolean) {
        context.dataStore.edit { it[RECORD_AUDIO] = value }
    }

    suspend fun setInternalAudio(value: Boolean) {
        context.dataStore.edit { it[INTERNAL_AUDIO] = value }
    }

    suspend fun setAudioBitrate(value: Int) {
        context.dataStore.edit { it[AUDIO_BITRATE] = value }
    }

    suspend fun setAudioSampleRate(value: Int) {
        context.dataStore.edit { it[AUDIO_SAMPLE_RATE] = value }
    }

    suspend fun setAudioChannels(value: String) {
        context.dataStore.edit { it[AUDIO_CHANNELS] = value }
    }

    suspend fun setAudioEncoder(value: String) {
        context.dataStore.edit { it[AUDIO_ENCODER] = value }
    }

    suspend fun setSeparateMicRecording(value: Boolean) {
        context.dataStore.edit { it[SEPARATE_MIC_RECORDING] = value }
    }

    // Setters — Controls
    suspend fun setFloatingControls(value: Boolean) {
        context.dataStore.edit { it[FLOATING_CONTROLS] = value }
    }

    suspend fun setTouchOverlay(value: Boolean) {
        context.dataStore.edit { it[TOUCH_OVERLAY] = value }
    }

    suspend fun setCountdown(value: Int) {
        context.dataStore.edit { it[COUNTDOWN] = value }
    }

    suspend fun setClipperDurationMinutes(value: Int) {
        context.dataStore.edit { it[CLIPPER_DURATION_MINUTES] = value.coerceIn(1, 5) }
    }

    suspend fun setStopBehavior(value: Set<String>) {
        context.dataStore.edit { it[STOP_BEHAVIOR] = value }
    }

    suspend fun setBrushOverlayEnabled(value: Boolean) {
        context.dataStore.edit { it[BRUSH_OVERLAY_ENABLED] = value }
    }

    suspend fun setHideFloatingIconWhileRecording(value: Boolean) {
        context.dataStore.edit { it[HIDE_FLOATING_ICON_WHILE_RECORDING] = value }
    }

    suspend fun setPostScreenshotOptions(value: Boolean) {
        context.dataStore.edit { it[POST_SCREENSHOT_OPTIONS] = value }
    }

    suspend fun setRecordSingleAppEnabled(value: Boolean) {
        context.dataStore.edit { it[RECORD_SINGLE_APP_ENABLED] = value }
    }

    suspend fun setCaptureMode(value: String) {
        val v = if (CaptureMode.isValid(value)) value else CaptureMode.RECORD
        context.dataStore.edit { it[CAPTURE_MODE] = v }
    }

    suspend fun setGifRecorderPresetId(value: String) {
        val id = GifRecordingPresets.byId(value).id
        context.dataStore.edit { it[GIF_RECORDER_PRESET_ID] = id }
    }

    // Setters — Camera Overlay
    suspend fun setCameraOverlay(value: Boolean) {
        context.dataStore.edit { it[CAMERA_OVERLAY] = value }
    }

    suspend fun setCameraOverlaySize(value: Int) {
        context.dataStore.edit { it[CAMERA_OVERLAY_SIZE] = value }
    }

    suspend fun setCameraXFraction(value: Float) {
        context.dataStore.edit { it[CAMERA_X_FRACTION] = value }
    }

    suspend fun setCameraYFraction(value: Float) {
        context.dataStore.edit { it[CAMERA_Y_FRACTION] = value }
    }

    suspend fun setCameraLockPosition(value: Boolean) {
        context.dataStore.edit { it[CAMERA_LOCK_POSITION] = value }
    }

    suspend fun setCameraFacing(value: String) {
        context.dataStore.edit { it[CAMERA_FACING] = value }
    }

    suspend fun setCameraAspectRatio(value: String) {
        context.dataStore.edit { it[CAMERA_ASPECT_RATIO] = value }
    }

    suspend fun setCameraOrientation(value: String) {
        context.dataStore.edit { it[CAMERA_ORIENTATION] = value }
    }

    suspend fun setCameraOpacity(value: Int) {
        context.dataStore.edit { it[CAMERA_OPACITY] = value }
    }

    // Setters — Watermark
    suspend fun setShowWatermark(value: Boolean) {
        context.dataStore.edit { it[SHOW_WATERMARK] = value }
    }

    suspend fun setWatermarkLocation(value: String) {
        context.dataStore.edit { it[WATERMARK_LOCATION] = value }
    }

    suspend fun setWatermarkImageUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(WATERMARK_IMAGE_URI) else it[WATERMARK_IMAGE_URI] = value
        }
    }

    suspend fun setWatermarkShape(value: String) {
        context.dataStore.edit { it[WATERMARK_SHAPE] = value }
    }

    suspend fun setWatermarkOpacity(value: Int) {
        context.dataStore.edit { it[WATERMARK_OPACITY] = value }
    }

    suspend fun setWatermarkSize(value: Int) {
        context.dataStore.edit { it[WATERMARK_SIZE] = value }
    }

    suspend fun setWatermarkXFraction(value: Float) {
        context.dataStore.edit { it[WATERMARK_X_FRACTION] = value }
    }

    suspend fun setWatermarkYFraction(value: Float) {
        context.dataStore.edit { it[WATERMARK_Y_FRACTION] = value }
    }

    // Setters — Screenshots
    suspend fun setScreenshotFormat(value: String) {
        context.dataStore.edit { it[SCREENSHOT_FORMAT] = value }
    }

    suspend fun setScreenshotQuality(value: Int) {
        context.dataStore.edit { it[SCREENSHOT_QUALITY] = value }
    }

    // Setters — Theme & Language
    suspend fun setAppTheme(value: String) {
        context.dataStore.edit { it[APP_THEME] = value }
    }

    suspend fun setAppLanguage(value: String) {
        context.dataStore.edit { it[APP_LANGUAGE] = value }
    }

    // Setters — Storage
    suspend fun setFilenamePattern(value: String) {
        context.dataStore.edit { it[FILENAME_PATTERN] = value }
    }

    suspend fun setSaveLocationUri(value: String) {
        context.dataStore.edit { it[SAVE_LOCATION_URI] = value }
    }

    suspend fun setAutoDelete(value: Boolean) {
        context.dataStore.edit { it[AUTO_DELETE] = value }
    }

    // Setters — General
    suspend fun setKeepScreenOn(value: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = value }
    }

    suspend fun setHapticFeedback(value: Boolean) {
        context.dataStore.edit { it[HAPTIC_FEEDBACK] = value }
    }

    suspend fun setSoundFeedback(value: Boolean) {
        context.dataStore.edit { it[SOUND_FEEDBACK] = value }
    }

    // Setters — UI Mode
    suspend fun setPerformanceMode(value: Boolean) {
        context.dataStore.edit { it[PERFORMANCE_MODE] = value }
    }

    suspend fun setAdaptiveRecordingPerformance(value: Boolean) {
        context.dataStore.edit { it[ADAPTIVE_RECORDING_PERFORMANCE] = value }
    }

    // Setters — Privacy
    suspend fun setAnalyticsEnabled(value: Boolean) {
        context.dataStore.edit { it[ANALYTICS_ENABLED] = value }
    }

    suspend fun setPersonalizedAdsEnabled(value: Boolean) {
        context.dataStore.edit { it[PERSONALIZED_ADS_ENABLED] = value }
    }

    /**
     * Stable anonymous ID per install (UUID) for Firebase Crashlytics / Analytics user identifiers.
     * Not PII; unchanged until app data is cleared.
     */
    suspend fun getOrCreateFirebaseAnonymousUserId(): String {
        val prefs = context.dataStore.data.first()
        val existing = prefs[FIREBASE_ANONYMOUS_USER_ID]
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { it[FIREBASE_ANONYMOUS_USER_ID] = newId }
        return newId
    }

    /** First launch / regulated flow: set analytics and mark the one-time prompt completed. */
    suspend fun applyRegulatedConsentChoice(accepted: Boolean) {
        context.dataStore.edit {
            it[ANALYTICS_ENABLED] = accepted
            it[ANALYTICS_CONSENT_PROMPT_COMPLETED] = true
        }
        context.applyAnalyticsCollectionEnabled(accepted)
        applyCrashlyticsCollectionEnabled(accepted)
        applyPersonalizedAdsEnabled(
            personalizedAdsEnabled.first(),
            adsSdkEnabled = !adsDisabled.first(),
        )
        context.syncFirebaseUserIdentity(accepted)
    }

    suspend fun setAdsDisabled(value: Boolean) {
        context.dataStore.edit { it[ADS_DISABLED] = value }
    }

    // Setters — Onboarding
    suspend fun setBetaNoticeShown(value: Boolean) {
        context.dataStore.edit { it[BETA_NOTICE_SHOWN] = value }
    }

    // Setters — Accent Color
    suspend fun setAccentColor(value: String) {
        context.dataStore.edit { it[ACCENT_COLOR] = value }
    }

    suspend fun setAccentColor2(value: String) {
        context.dataStore.edit { it[ACCENT_COLOR_2] = value }
    }

    suspend fun setAccentUseGradient(value: Boolean) {
        context.dataStore.edit { it[ACCENT_USE_GRADIENT] = value }
    }
}
