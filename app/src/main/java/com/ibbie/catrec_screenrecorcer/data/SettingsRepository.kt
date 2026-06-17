package com.ibbie.catrec_screenrecorcer.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import com.ibbie.catrec_screenrecorcer.service.RecordingResolutionSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

private const val SETTINGS_DATASTORE_NAME = "settings"

/** Used by [SettingsRepository.seedAdaptivePerformanceDefaultForFreshInstallIfNeeded]. */
private const val FRESH_INSTALL_TIME_DELTA_MS = 60_000L

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME,
    corruptionHandler =
        ReplaceFileCorruptionHandler(
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

        /**
         * Color output mode for the video encoder.
         * "Full"     → Full-range (0–255) BT.709/SDR — default, screen-accurate.
         * "Standard" → Limited-range (16–235) BT.709/SDR — compatibility mode for devices
         *              where full-range metadata is ignored and playback looks washed-out.
         */
        val COLOR_MODE = stringPreferencesKey("color_mode")

        /**
         * When true (and [COLOR_MODE] == "Standard" / limited-range mode), the finalized MP4 is
         * repaired with an FFmpeg stream-copy metadata pass so AVC/HEVC bitstream metadata
         * explicitly advertises BT.709 limited range. Pixel data is not modified.
         * Defaults false; only shown and relevant in limited-range / compatibility mode.
         */
        val FORCE_REC709_COMPATIBILITY = booleanPreferencesKey("force_rec709_compatibility")

        /**
         * Optional post-repair gamma lift for devices where metadata-only Rec.709 compatibility
         * output is slightly dark. Applies only to Standard + Force Rec.709 compatibility and
         * re-encodes video, so OFF is the default.
         */
        val REC709_COMPAT_BRIGHTNESS_CORRECTION = stringPreferencesKey("rec709_compat_brightness_correction")

        // Audio
        val RECORD_AUDIO = booleanPreferencesKey("record_audio")
        val INTERNAL_AUDIO = booleanPreferencesKey("internal_audio")
        val AUDIO_BITRATE = intPreferencesKey("audio_bitrate")
        val AUDIO_SAMPLE_RATE = intPreferencesKey("audio_sample_rate")
        val AUDIO_CHANNELS = stringPreferencesKey("audio_channels")
        val AUDIO_ENCODER = stringPreferencesKey("audio_encoder")
        val SEPARATE_MIC_RECORDING = booleanPreferencesKey("separate_mic_recording")

        /**
         * When true, sustained silence on internal playback capture triggers microphone capture
         * automatically (full recording only), without a prompt.
         */
        val AUTO_MIC_FALLBACK_WHEN_INTERNAL_SILENT = booleanPreferencesKey("auto_mic_fallback_when_internal_silent")

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
        val RECORDING_ENGINE_MODE = stringPreferencesKey("recording_engine_mode")

        /** Opt-in: reduce recording load under stress; Advanced engine may also decimate relay frames. */
        val ADAPTIVE_RECORDING_PERFORMANCE = booleanPreferencesKey("adaptive_recording_performance")

        /**
         * One-time marker: after this exists, [seedAdaptivePerformanceDefaultForFreshInstallIfNeeded] does nothing.
         * Used so fresh installs default adaptive ON without overwriting users who already have the key stored.
         */
        val ADAPTIVE_PERF_DEFAULT_SEED_COMPLETE = booleanPreferencesKey("adaptive_perf_default_seed_complete_v1")

        // Privacy
        val ANALYTICS_ENABLED = booleanPreferencesKey("analytics_enabled")

        /** AdMob: personalized ads (default on); independent of Firebase Analytics. */
        val PERSONALIZED_ADS_ENABLED = booleanPreferencesKey("personalized_ads_enabled")

        /** Stable anonymous install ID for Firebase Crashlytics / Analytics (no PII). */
        val FIREBASE_ANONYMOUS_USER_ID = stringPreferencesKey("firebase_anonymous_user_id")

        /**
         * When true, all Pro feature gates are treated as unlocked.
         * Synced from Play Billing for [com.ibbie.catrec_screenrecorcer.billing.BillingProductIds.REMOVE_ADS] and persisted for fast UI/offline.
         */
        val ADS_DISABLED = booleanPreferencesKey("ads_disabled_entitlement")
        val PRO_FEATURES_UNLOCKED_UNTIL_MILLIS = longPreferencesKey("pro_features_unlocked_until_millis")

        // Accent Color
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val ACCENT_COLOR_2 = stringPreferencesKey("accent_color_2")
        val ACCENT_USE_GRADIENT = booleanPreferencesKey("accent_use_gradient")
    }

    // Video
    val fps: Flow<Float> = context.dataStore.data.map { it[FPS] ?: 30f }
    val bitrate: Flow<Float> = context.dataStore.data.map { it[BITRATE] ?: 10f }
    val videoEncoder: Flow<String> = context.dataStore.data.map { it[VIDEO_ENCODER] ?: "H.264" }
    val resolution: Flow<String> = context.dataStore.data.map { RecordingResolutionSupport.normalizeSavedSetting(it[RESOLUTION]) }
    val recordingOrientation: Flow<String> = context.dataStore.data.map { it[RECORDING_ORIENTATION] ?: "Auto" }
    val colorMode: Flow<String> = context.dataStore.data.map { it[COLOR_MODE] ?: ColorMode.FULL }
    val forceRec709Compatibility: Flow<Boolean> = context.dataStore.data.map { it[FORCE_REC709_COMPATIBILITY] ?: false }
    val rec709CompatBrightnessCorrection: Flow<String> =
        context.dataStore.data.map {
            Rec709CompatBrightnessCorrection.resolve(it[REC709_COMPAT_BRIGHTNESS_CORRECTION])
        }

    // Audio
    val recordAudio: Flow<Boolean> = context.dataStore.data.map { it[RECORD_AUDIO] ?: false }
    val internalAudio: Flow<Boolean> = context.dataStore.data.map { it[INTERNAL_AUDIO] ?: false }
    val audioBitrate: Flow<Int> = context.dataStore.data.map { it[AUDIO_BITRATE] ?: 128 }
    val audioSampleRate: Flow<Int> = context.dataStore.data.map { it[AUDIO_SAMPLE_RATE] ?: 44100 }
    val audioChannels: Flow<String> = context.dataStore.data.map { it[AUDIO_CHANNELS] ?: "Mono" }
    val audioEncoder: Flow<String> = context.dataStore.data.map { it[AUDIO_ENCODER] ?: "AAC-LC" }
    val separateMicRecording: Flow<Boolean> = context.dataStore.data.map { it[SEPARATE_MIC_RECORDING] ?: false }
    val autoMicFallbackWhenInternalSilent: Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_MIC_FALLBACK_WHEN_INTERNAL_SILENT] ?: false }

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
    val recordingEngineMode: Flow<RecordingEngineMode> =
        context.dataStore.data.map { prefs ->
            RecordingEngineMode.fromStorageValue(prefs[RECORDING_ENGINE_MODE])
        }

    /** When true, recording/buffer sessions may lower bitrate and Advanced engine may decimate relay frames. */
    val adaptiveRecordingPerformance: Flow<Boolean> =
        context.dataStore.data.map { it[ADAPTIVE_RECORDING_PERFORMANCE] ?: false }

    // Privacy — analytics default on until the user changes Settings; personalized ads default on (independent toggles)
    val analyticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[ANALYTICS_ENABLED] ?: true }
    val personalizedAdsEnabled: Flow<Boolean> = context.dataStore.data.map { it[PERSONALIZED_ADS_ENABLED] ?: true }

    /** True after remove-ads purchase (or while a pending remove-ads flow completes — Play is source of truth on next sync). */
    val adsDisabled: Flow<Boolean> = context.dataStore.data.map { it[ADS_DISABLED] ?: false }
    val proFeaturesUnlockedUntilMillis: Flow<Long> =
        context.dataStore.data.map { it[PRO_FEATURES_UNLOCKED_UNTIL_MILLIS] ?: 0L }

    // Accent Color
    val accentColor: Flow<String> = context.dataStore.data.map { it[ACCENT_COLOR] ?: "FF0033" }
    val accentColor2: Flow<String> = context.dataStore.data.map { it[ACCENT_COLOR_2] ?: "FF8C00" }
    val accentUseGradient: Flow<Boolean> = context.dataStore.data.map { it[ACCENT_USE_GRADIENT] ?: false }

    val settingsSnapshot: Flow<SettingsUiState> =
        context.dataStore.data.map { prefs ->
            val captureMode = prefs[CAPTURE_MODE]?.takeIf(CaptureMode::isValid) ?: CaptureMode.RECORD
            SettingsUiState(
                fps = prefs[FPS] ?: 30f,
                bitrate = prefs[BITRATE] ?: 10f,
                resolution = RecordingResolutionSupport.normalizeSavedSetting(prefs[RESOLUTION]),
                videoEncoder = prefs[VIDEO_ENCODER] ?: "H.264",
                recordingOrientation = prefs[RECORDING_ORIENTATION] ?: "Auto",
                colorMode = prefs[COLOR_MODE]?.takeIf(ColorMode::isValid) ?: ColorMode.FULL,
                forceRec709Compatibility = prefs[FORCE_REC709_COMPATIBILITY] ?: false,
                rec709CompatBrightnessCorrection =
                    Rec709CompatBrightnessCorrection.resolve(prefs[REC709_COMPAT_BRIGHTNESS_CORRECTION]),
                isGifCaptureMode = captureMode == CaptureMode.GIF,
                adaptivePerformanceEnabled = prefs[ADAPTIVE_RECORDING_PERFORMANCE] ?: false,
                gifRecorderPresetId = prefs[GIF_RECORDER_PRESET_ID] ?: GifRecordingPresets.default.id,
                recordAudio = prefs[RECORD_AUDIO] ?: false,
                internalAudio = prefs[INTERNAL_AUDIO] ?: false,
                audioBitrate = prefs[AUDIO_BITRATE] ?: 128,
                audioSampleRate = prefs[AUDIO_SAMPLE_RATE] ?: 44100,
                audioChannels = prefs[AUDIO_CHANNELS] ?: "Mono",
                audioEncoder = prefs[AUDIO_ENCODER] ?: "AAC-LC",
                separateMicRecording = prefs[SEPARATE_MIC_RECORDING] ?: false,
                autoMicFallbackWhenInternalSilent = prefs[AUTO_MIC_FALLBACK_WHEN_INTERNAL_SILENT] ?: false,
                floatingControls = prefs[FLOATING_CONTROLS] ?: false,
                hideFloatingIconWhileRecording = prefs[HIDE_FLOATING_ICON_WHILE_RECORDING] ?: false,
                postScreenshotOptions = prefs[POST_SCREENSHOT_OPTIONS] ?: false,
                touchOverlay = prefs[TOUCH_OVERLAY] ?: false,
                countdown = prefs[COUNTDOWN] ?: 0,
                clipperDurationMinutes = (prefs[CLIPPER_DURATION_MINUTES] ?: 1).coerceIn(1, 5),
                stopBehavior = StopBehaviorKeys.migrateSet(prefs[STOP_BEHAVIOR]),
                cameraOverlay = prefs[CAMERA_OVERLAY] ?: false,
                cameraOverlaySize = prefs[CAMERA_OVERLAY_SIZE] ?: 120,
                cameraXFraction = prefs[CAMERA_X_FRACTION] ?: 0.05f,
                cameraYFraction = prefs[CAMERA_Y_FRACTION] ?: 0.1f,
                cameraLockPosition = prefs[CAMERA_LOCK_POSITION] ?: false,
                cameraFacing = prefs[CAMERA_FACING] ?: "Front",
                cameraAspectRatio = prefs[CAMERA_ASPECT_RATIO] ?: "Circle",
                cameraOpacity = prefs[CAMERA_OPACITY] ?: 100,
                cameraOrientation = prefs[CAMERA_ORIENTATION] ?: "Auto",
                showWatermark = prefs[SHOW_WATERMARK] ?: false,
                watermarkImageUri = prefs[WATERMARK_IMAGE_URI],
                watermarkShape = prefs[WATERMARK_SHAPE] ?: "Square",
                watermarkOpacity = prefs[WATERMARK_OPACITY] ?: 100,
                watermarkSize = prefs[WATERMARK_SIZE] ?: 80,
                watermarkXFraction = prefs[WATERMARK_X_FRACTION] ?: 0.05f,
                watermarkYFraction = prefs[WATERMARK_Y_FRACTION] ?: 0.05f,
                watermarkLocation = prefs[WATERMARK_LOCATION] ?: "Top Left",
                screenshotFormat = prefs[SCREENSHOT_FORMAT] ?: "JPEG",
                screenshotQuality = prefs[SCREENSHOT_QUALITY] ?: 90,
                appTheme = prefs[APP_THEME] ?: "System",
                appLanguage = prefs[APP_LANGUAGE] ?: "system",
                performanceMode = prefs[PERFORMANCE_MODE] ?: false,
                recordingEngineMode = RecordingEngineMode.fromStorageValue(prefs[RECORDING_ENGINE_MODE]),
                accentHex = prefs[ACCENT_COLOR] ?: "FF0033",
                accentHex2 = prefs[ACCENT_COLOR_2] ?: "FF8C00",
                accentGradient = prefs[ACCENT_USE_GRADIENT] ?: false,
                saveLocationUri = prefs[SAVE_LOCATION_URI],
                filenamePattern = prefs[FILENAME_PATTERN] ?: "yyyyMMdd_HHmmss",
                autoDelete = prefs[AUTO_DELETE] ?: false,
                keepScreenOn = prefs[KEEP_SCREEN_ON] ?: false,
                analyticsEnabled = prefs[ANALYTICS_ENABLED] ?: true,
                personalizedAdsEnabled = prefs[PERSONALIZED_ADS_ENABLED] ?: true,
                adsDisabled = prefs[ADS_DISABLED] ?: false,
            )
        }

    // Setters — Video
    suspend fun setColorMode(value: String) {
        val v = if (ColorMode.isValid(value)) value else ColorMode.FULL
        context.dataStore.edit { it[COLOR_MODE] = v }
    }

    suspend fun setForceRec709Compatibility(value: Boolean) {
        context.dataStore.edit { it[FORCE_REC709_COMPATIBILITY] = value }
    }

    suspend fun setRec709CompatBrightnessCorrection(value: String) {
        context.dataStore.edit {
            it[REC709_COMPAT_BRIGHTNESS_CORRECTION] =
                Rec709CompatBrightnessCorrection.resolve(value)
        }
    }

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
        context.dataStore.edit { it[RESOLUTION] = RecordingResolutionSupport.normalizeSavedSetting(value) }
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

    suspend fun setAutoMicFallbackWhenInternalSilent(value: Boolean) {
        context.dataStore.edit { it[AUTO_MIC_FALLBACK_WHEN_INTERNAL_SILENT] = value }
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

    suspend fun setRecordingEngineMode(mode: RecordingEngineMode) {
        val storedMode = if (mode == RecordingEngineMode.PERFORMANCE) mode else RecordingEngineMode.PERFORMANCE
        context.dataStore.edit { it[RECORDING_ENGINE_MODE] = storedMode.storageValue }
    }

    suspend fun setAdaptiveRecordingPerformance(value: Boolean) {
        context.dataStore.edit { it[ADAPTIVE_RECORDING_PERFORMANCE] = value }
    }

    /**
     * Enables adaptive performance by default on **fresh installs only**.
     *
     * - **Upgrade path:** If [ADAPTIVE_RECORDING_PERFORMANCE] was ever written (explicit user choice,
     *   including `false`), we never touch it.
     * - **Fresh install:** `lastUpdateTime - firstInstallTime` is within [FRESH_INSTALL_TIME_DELTA_MS],
     *   so we set adaptive ON once if the key is still absent.
     *
     * Safe to call multiple times; first successful write of [ADAPTIVE_PERF_DEFAULT_SEED_COMPLETE] skips later work.
     */
    suspend fun seedAdaptivePerformanceDefaultForFreshInstallIfNeeded() {
        context.dataStore.edit { prefs ->
            if (prefs[ADAPTIVE_PERF_DEFAULT_SEED_COMPLETE] == true) return@edit
            prefs[ADAPTIVE_PERF_DEFAULT_SEED_COMPLETE] = true
            if (prefs.contains(ADAPTIVE_RECORDING_PERFORMANCE)) return@edit
            val deltaMs = packageInstallDeltaMs(context)
            if (deltaMs in 0..FRESH_INSTALL_TIME_DELTA_MS) {
                prefs[ADAPTIVE_RECORDING_PERFORMANCE] = true
            }
        }
    }

    private fun packageInstallDeltaMs(appContext: Context): Long =
        runCatching {
            val pm = appContext.packageManager
            val pkg =
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(appContext.packageName, 0)
                }
            pkg.lastUpdateTime - pkg.firstInstallTime
        }.getOrElse { Long.MAX_VALUE }

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

    suspend fun setAdsDisabled(value: Boolean) {
        context.dataStore.edit { it[ADS_DISABLED] = value }
    }

    suspend fun setProFeaturesUnlockedUntilMillis(value: Long) {
        context.dataStore.edit { it[PRO_FEATURES_UNLOCKED_UNTIL_MILLIS] = value }
    }

    suspend fun grantTimedProAccess(nowMillis: Long = System.currentTimeMillis()): Long {
        val until = nowMillis + 3 * 60 * 60 * 1000L
        setProFeaturesUnlockedUntilMillis(until)
        return until
    }

    suspend fun hasProAccessNow(nowMillis: Long = System.currentTimeMillis()): Boolean = adsDisabled.first() || proFeaturesUnlockedUntilMillis.first() > nowMillis

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
