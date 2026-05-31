package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.BuildConfig
import com.ibbie.catrec_screenrecorcer.CatRecApplication
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.ColorMode
import com.ibbie.catrec_screenrecorcer.data.GifPaletteDither
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.Rec709CompatBrightnessCorrection
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineEventBus
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingError
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingFatalKind
import com.ibbie.catrec_screenrecorcer.data.recording.ProRecordingFeature
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingStartProGate
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingStartProGateResult
import com.ibbie.catrec_screenrecorcer.data.recording.SessionConfig
import com.ibbie.catrec_screenrecorcer.data.recording.toMicAndInternalFlags
import com.ibbie.catrec_screenrecorcer.data.recording.toSessionConfig
import com.ibbie.catrec_screenrecorcer.utils.AppLogger
import com.ibbie.catrec_screenrecorcer.utils.AudioRecordingCrashlyticsReporter
import com.ibbie.catrec_screenrecorcer.utils.MediaStorePublishDiagnostics
import com.ibbie.catrec_screenrecorcer.utils.recordCrashlyticsNonFatal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import androidx.core.content.edit

class ScreenRecordService :
    LifecycleService(),
    SensorEventListener {
    companion object {
        private const val LOG_TAG = "ScreenRecordService"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CONTROLS_DISMISSED = "ACTION_CONTROLS_DISMISSED"
        const val ACTION_CONTROLS_RESHOWN = "ACTION_CONTROLS_RESHOWN"
        const val ACTION_MUTE = "ACTION_MUTE"
        const val ACTION_UNMUTE = "ACTION_UNMUTE"
        const val ACTION_NOTIFICATION_DISMISSED = "ACTION_NOTIFICATION_DISMISSED"
        const val ACTION_TAKE_SCREENSHOT = "ACTION_TAKE_SCREENSHOT"

        /** User chose mic fallback from internal-playback silence dialog / activity. */
        const val ACTION_INTERNAL_SILENCE_USE_MIC_FALLBACK =
            "com.ibbie.catrec_screenrecorcer.INTERNAL_SILENCE_USE_MIC_FALLBACK"

        private const val LOG_TAG_INTERNAL_SILENCE = "CatRecInternalSilence"

        /**
         * Standalone screenshot: caller supplies [EXTRA_RESULT_CODE] + [EXTRA_DATA] obtained
         * directly from a fresh [MediaProjectionManager.createScreenCaptureIntent] dialog. The
         * service starts foreground with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION],
         * captures one frame, then releases the projection and stops the service (unless an
         * unrelated recording/buffer/prepared session is already live, in which case it only
         * drops the one-shot projection).
         */
        const val ACTION_TAKE_SCREENSHOT_ONE_SHOT = "ACTION_TAKE_SCREENSHOT_ONE_SHOT"

        /** Toggle pause while recording (quick controls / app control notification). */
        const val ACTION_TOGGLE_PAUSE = "ACTION_TOGGLE_PAUSE"

        /** Rebuild and post [MAIN_FOREGROUND_NOTIFICATION_ID] (no second startForeground). */
        const val ACTION_REFRESH_MAIN_NOTIFICATION = "ACTION_REFRESH_MAIN_NOTIFICATION"

        /** Single persistent foreground notification for prepare + recording (never post a second ID for this UX). */
        const val MAIN_FOREGROUND_NOTIFICATION_ID = 1001

        // Rolling buffer / clipping
        const val ACTION_START_BUFFER = "ACTION_START_BUFFER"
        const val ACTION_STOP_BUFFER = "ACTION_STOP_BUFFER"
        const val ACTION_SAVE_CLIP = "ACTION_SAVE_CLIP"

        /**
         * Pre-grant mode: obtain MediaProjection while the app Activity is visible, then
         * keep it alive in this foreground service so the overlay can trigger recordings
         * without any permission dialog.
         */
        const val ACTION_PREPARE = "ACTION_PREPARE"
        const val ACTION_START_FROM_OVERLAY = "ACTION_START_FROM_OVERLAY"
        const val ACTION_START_BUFFER_FROM_OVERLAY = "ACTION_START_BUFFER_FROM_OVERLAY"
        const val ACTION_EXIT_SERVICE = "ACTION_EXIT_SERVICE"

        /**
         * After MediaProjection is granted from [OverlayRecordProjectionActivity], applies
         * settings from the repository and starts recording or rolling buffer.
         */
        const val ACTION_START_AFTER_OVERLAY_PROJECTION = "ACTION_START_AFTER_OVERLAY_PROJECTION"
        const val EXTRA_OVERLAY_SESSION_AS_BUFFER = "EXTRA_OVERLAY_SESSION_AS_BUFFER"
        const val ACTION_REVOKE_PREPARE = "ACTION_REVOKE_PREPARE"

        /**
         * [DisplayManager] can emit many [DisplayManager.DisplayListener.onDisplayChanged] events in
         * quick succession (VRR, mode reporting). Coalesce before remeasuring the full display.
         */
        private const val CAPTURE_RESIZE_DEBOUNCE_MS = 220L

        /** Delete the last saved recording from the combined "saved + ready" notification. */
        private const val ACTION_DELETE_SAVED_RECORDING = "com.ibbie.catrec_screenrecorcer.DELETE_SAVED_RECORDING"
        private const val EXTRA_LAST_SAVED_RECORDING_URI = "EXTRA_LAST_SAVED_RECORDING_URI"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        /** Optional [SessionConfig] (encode + dimension hints); projection grant is [EXTRA_DATA]. */
        const val EXTRA_SESSION_CONFIG = "EXTRA_SESSION_CONFIG"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_BITRATE = "EXTRA_BITRATE"
        const val EXTRA_AUDIO_ENABLED = "EXTRA_AUDIO_ENABLED"
        const val EXTRA_INTERNAL_AUDIO_ENABLED = "EXTRA_INTERNAL_AUDIO_ENABLED"
        const val EXTRA_AUDIO_BITRATE = "EXTRA_AUDIO_BITRATE"
        const val EXTRA_AUDIO_SAMPLE_RATE = "EXTRA_AUDIO_SAMPLE_RATE"
        const val EXTRA_AUDIO_CHANNELS = "EXTRA_AUDIO_CHANNELS"
        const val EXTRA_AUDIO_ENCODER = "EXTRA_AUDIO_ENCODER"
        const val EXTRA_SEPARATE_MIC_RECORDING = "EXTRA_SEPARATE_MIC_RECORDING"
        const val EXTRA_SHOW_CAMERA = "EXTRA_SHOW_CAMERA"
        const val EXTRA_CAMERA_SIZE = "EXTRA_CAMERA_SIZE"
        const val EXTRA_CAMERA_X_FRACTION = "EXTRA_CAMERA_X_FRACTION"
        const val EXTRA_CAMERA_Y_FRACTION = "EXTRA_CAMERA_Y_FRACTION"
        const val EXTRA_CAMERA_LOCK_POSITION = "EXTRA_CAMERA_LOCK_POSITION"
        const val EXTRA_CAMERA_FACING = "EXTRA_CAMERA_FACING"
        const val EXTRA_CAMERA_ASPECT_RATIO = "EXTRA_CAMERA_ASPECT_RATIO"
        const val EXTRA_CAMERA_OPACITY = "EXTRA_CAMERA_OPACITY"
        const val EXTRA_SHOW_WATERMARK = "EXTRA_SHOW_WATERMARK"
        const val EXTRA_SHOW_FLOATING_CONTROLS = "EXTRA_SHOW_FLOATING_CONTROLS"

        /** When true, floating controls bubble is not shown while recording (camera/watermark still apply). */
        const val EXTRA_HIDE_FLOATING_ICON_WHILE_RECORDING = "EXTRA_HIDE_FLOATING_ICON_WHILE_RECORDING"
        const val EXTRA_STOP_BEHAVIOR = "EXTRA_STOP_BEHAVIOR"
        const val EXTRA_SAVE_LOCATION = "EXTRA_SAVE_LOCATION"
        const val EXTRA_FILENAME_PATTERN = "EXTRA_FILENAME_PATTERN"
        const val EXTRA_RESOLUTION = "EXTRA_RESOLUTION"
        const val EXTRA_VIDEO_ENCODER = "EXTRA_VIDEO_ENCODER"
        const val EXTRA_COLOR_MODE = "EXTRA_COLOR_MODE"
        const val EXTRA_RECORDING_ENGINE_MODE = "EXTRA_RECORDING_ENGINE_MODE"

        /**
         * When true (and [EXTRA_COLOR_MODE] is "Standard"), the finalized MP4 is stream-copy
         * remuxed so AVC/HEVC bitstream metadata explicitly advertises Rec.709 limited range.
         * Pixel data is not modified. Default false.
         */
        const val EXTRA_FORCE_REC709 = "EXTRA_FORCE_REC709"
        const val EXTRA_REC709_COMPAT_BRIGHTNESS_CORRECTION = "EXTRA_REC709_COMPAT_BRIGHTNESS_CORRECTION"
        const val EXTRA_COUNTDOWN = "EXTRA_COUNTDOWN"
        const val EXTRA_KEEP_SCREEN_ON = "EXTRA_KEEP_SCREEN_ON"
        const val EXTRA_RECORDING_ORIENTATION = "EXTRA_RECORDING_ORIENTATION"
        const val EXTRA_WATERMARK_LOCATION = "EXTRA_WATERMARK_LOCATION"
        const val EXTRA_WATERMARK_IMAGE_URI = "EXTRA_WATERMARK_IMAGE_URI"
        const val EXTRA_WATERMARK_SHAPE = "EXTRA_WATERMARK_SHAPE"
        const val EXTRA_WATERMARK_OPACITY = "EXTRA_WATERMARK_OPACITY"
        const val EXTRA_WATERMARK_SIZE = "EXTRA_WATERMARK_SIZE"
        const val EXTRA_WATERMARK_X_FRACTION = "EXTRA_WATERMARK_X_FRACTION"
        const val EXTRA_WATERMARK_Y_FRACTION = "EXTRA_WATERMARK_Y_FRACTION"
        const val EXTRA_SCREENSHOT_FORMAT = "EXTRA_SCREENSHOT_FORMAT"
        const val EXTRA_SCREENSHOT_QUALITY = "EXTRA_SCREENSHOT_QUALITY"
        const val EXTRA_CLIPPER_DURATION_MINUTES = "EXTRA_CLIPPER_DURATION_MINUTES"
        const val EXTRA_GIF_SESSION = "EXTRA_GIF_SESSION"
        const val EXTRA_GIF_MAX_DURATION_SEC = "EXTRA_GIF_MAX_DURATION_SEC"
        const val EXTRA_GIF_SCALE_WIDTH = "EXTRA_GIF_SCALE_WIDTH"
        const val EXTRA_GIF_OUTPUT_FPS = "EXTRA_GIF_OUTPUT_FPS"

        /** FFmpeg palettegen max colors (64 / 128 / 256). */
        const val EXTRA_GIF_MAX_COLORS = "EXTRA_GIF_MAX_COLORS"

        /** Serialized [GifPaletteDither.name] for paletteuse (Bayer light/medium or Floyd–Steinberg). */
        const val EXTRA_GIF_DITHER_KIND = "EXTRA_GIF_DITHER_KIND"

        private const val POST_NOTIFICATION_ID = 2
        private const val BUFFER_NOTIFICATION_ID = 3

        /** Survives process death; cleared in [cleanup] / [cleanupBuffer]. Used for null-[Intent] restarts. */
        private const val PREFS_CAPTURE_SESSION = "catrec_capture_session"
        private const val PREF_CAPTURE_ACTIVE = "capture_active"
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var recorderEngine: ScreenRecorderEngine? = null
    private var rollingBufferEngine: RollingBufferEngine? = null

    /** Read from encoder threads (fatal callbacks); writes occur on main during buffer start/stop. */
    @Volatile
    private var isBufferRunning = false

    /** Coroutine that publishes elapsed recording time to [RecordingState.recordingDuration] every 500 ms. */
    private var durationTimerJob: Job? = null

    /** Duration accumulated across all non-paused recording segments (milliseconds). */
    private var accumulatedDurationMs: Long = 0L

    /** Wall-clock time of the most recent recording/resume start (milliseconds, elapsedRealtime). */
    private var lastStartTimeMs: Long = 0L

    /** Rolling-buffer session start; kept separate from the visible clip-cycle timer. */
    private var bufferSessionStartedAtMs: Long = 0L

    /** Timestamp used by the overlay timer while Clipper is active. Resets after a successful clip save. */
    private var currentClipTimerStartedAtMs: Long = 0L

    /** Last successful clip-save/reset point for diagnostics and future UI state. */
    private var lastClipSavedAtMs: Long = 0L

    /**
     * True after foreground started with [Companion.MAIN_FOREGROUND_NOTIFICATION_ID] until removed.
     */
    private var mainForegroundActive = false
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Cached color-mode snapshot from [com.ibbie.catrec_screenrecorcer.CatRecApplication].
     * Used as a validated fallback when [EXTRA_COLOR_MODE] is absent or invalid in an Intent.
     */
    private val cachedColorModeFromApp: String
        get() = (application as? CatRecApplication)?.settingsConfigCache?.current()?.colorMode
            ?: ColorMode.FULL

    /**
     * Cached force-rec709 snapshot from [com.ibbie.catrec_screenrecorcer.CatRecApplication].
     * Used as a fallback when [EXTRA_FORCE_REC709] is absent from an Intent.
     */
    private val cachedForceRec709FromApp: Boolean
        get() = (application as? CatRecApplication)?.settingsConfigCache?.current()?.forceRec709Compatibility
            ?: false

    private val cachedRec709CompatBrightnessCorrectionFromApp: String
        get() = (application as? CatRecApplication)?.settingsConfigCache?.current()?.rec709CompatBrightnessCorrection
            ?: Rec709CompatBrightnessCorrection.OFF

    private val cachedRecordingEngineModeFromApp: RecordingEngineMode
        get() = (application as? CatRecApplication)?.settingsConfigCache?.current()?.recordingEngineMode
            ?: RecordingEngineMode.DEFAULT

    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var screenDensity: Int = 0
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 2400

    /**
     * When [SessionConfig] supplies non-zero width/height, log a mismatch if the repository
     * size differs from the live centralized pipeline at record start.
     */
    private var captureDimensionsFromSessionConfig: Boolean = false
    private var fps: Int = 30
    private var bitrate: Int = 10_000_000
    private var audioEnabled: Boolean = false
    private var internalAudioEnabled: Boolean = false
    private var audioBitrate: Int = 128_000
    private var audioSampleRate: Int = 44_100
    private var audioChannels: String = "Mono"
    private var audioEncoderType: String = "AAC-LC"
    private var separateMicRecording: Boolean = false
    private var showCamera: Boolean = false
    private var cameraOverlaySize: Int = 120
    private var cameraXFraction: Float = 0.05f
    private var cameraYFraction: Float = 0.1f
    private var cameraLockPosition: Boolean = false
    private var cameraFacing: String = "Front"
    private var cameraAspectRatio: String = "Circle"
    private var cameraOpacity: Int = 100
    private var showWatermark: Boolean = false
    private var showFloatingControls: Boolean = false
    private var hideFloatingIconWhileRecording: Boolean = false
    private var stopBehaviors: ArrayList<String>? = null
    private var saveLocationUri: String? = null
    private var filenamePattern: String = "yyyyMMdd_HHmmss"
    private var resolutionSetting: String = "Native"
    private var videoEncoder: String = "H.264"
    private var colorMode: String = ColorMode.FULL
    private var recordingEngineMode: RecordingEngineMode = RecordingEngineMode.DEFAULT
    @Volatile private var activeRecordingEngineMode: RecordingEngineMode? = null
    @Volatile private var activeBufferEngineMode: RecordingEngineMode? = null
    private var forceRec709Compatibility: Boolean = false
    private var rec709CompatBrightnessCorrection: String = Rec709CompatBrightnessCorrection.OFF
    private var keepScreenOn: Boolean = false
    private var countdownValue: Int = 0
    private var recordingOrientationSetting: String = "Auto"
    private var watermarkLocation: String = "Top Left"
    private var watermarkImageUri: String? = null
    private var watermarkShape: String = "Square"
    private var watermarkOpacity: Int = 100
    private var watermarkSize: Int = 80
    private var watermarkXFraction: Float = 0.05f
    private var watermarkYFraction: Float = 0.05f
    private var screenshotFormat: String = "JPEG"
    private var screenshotQuality: Int = 90

    /** Clipper duration: rolling window (1–5 minutes). */
    private var clipperDurationMinutes: Int = 1

    private var isGifSession: Boolean = false
    private var gifMaxDurationSec: Int = 0
    private var gifScaleWidth: Int = 480
    private var gifOutputFps: Int = 10
    private var gifMaxColors: Int = 128
    private var gifPaletteDither: GifPaletteDither = GifPaletteDither.BAYER_MEDIUM
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gifAutoStopRunnable: Runnable? = null

    private val applyCaptureResizeAfterDisplayChangeRunnable =
        Runnable {
            if (!captureResizeListenerRegistered) return@Runnable
            val (w, h) = currentDisplaySizePx()
            if (w == captureContentW && h == captureContentH) return@Runnable
            if (BuildConfig.DEBUG) {
                Log.i(
                    LOG_TAG,
                    "captureResizeDisplayListener: display changed ${captureContentW}x${captureContentH} → ${w}x${h}",
                )
            }
            triggerCaptureResize(w, h)
        }

    // ── Capture-source resize (rotation / fold) ─────────────────────────────────
    /**
     * Last known captured-content dimensions.  Updated when [triggerCaptureResize] fires so we
     * only call [EncoderFrameRelay.resizeCaptureSource] when the size actually changes.
     */
    private var captureContentW: Int = -1
    private var captureContentH: Int = -1

    /**
     * Pre-API-34 fallback: detects display-size changes (rotation) via [DisplayManager].
     * On API 34+, [MediaProjection.Callback.onCapturedContentResize] is the primary trigger.
     */
    private val captureResizeDisplayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                // Quick-reject: skip debounce entirely if the display size hasn't changed.
                // This drops VRR / refresh-rate / brightness events (e.g. 165 Hz → 60 Hz)
                // without scheduling any Handler work or re-reading WindowMetrics later.
                val (w, h) = currentDisplaySizePx()
                if (w == captureContentW && h == captureContentH) return
                mainHandler.removeCallbacks(applyCaptureResizeAfterDisplayChangeRunnable)
                mainHandler.postDelayed(
                    applyCaptureResizeAfterDisplayChangeRunnable,
                    CAPTURE_RESIZE_DEBOUNCE_MS,
                )
            }
        }
    private var captureResizeListenerRegistered = false

    // Tells the service to revoke projection after the next stop completes
    private var revokeAfterStop = false

    /** Read from encoder threads (fatal callbacks); writes occur on main during record start/stop. */
    @Volatile
    private var isRecorderRunning = false
    private val isStoppingForCodec = AtomicBoolean(false)

    @Volatile
    private var isStopping = false
    private var recordingPerformanceController: RecordingPerformanceController? = null
    private val preferAvcNextEnginePrepare = AtomicBoolean(false)
    private var isRecordingPaused = false
    private var isRecordingMuted = false
    private var controlsDismissedByUser = false

    // Set by the MediaProjection.Callback when the OS revokes the projection (e.g. the user
    // switched away from the captured app in single-app recording mode on Android 14+ / Samsung).
    private var projectionStoppedRecording = false

    private val cleanupInProgress = AtomicBoolean(false)
    private val cleanupCompleted = AtomicBoolean(false)
    private val projectionStopExpected = AtomicBoolean(false)
    private val screenOffReceiverRegistered = AtomicBoolean(false)

    /** True when a live MediaProjection is held and the overlay can start recording directly. */
    private var isPrepared = false
    private var currentFileUri: Uri? = null

    /** MediaMuxer requires a seekable FD; we mux to this temp file then copy to [currentFileUri]. */
    private var currentTempRecordingFile: File? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var separateMicPfd: ParcelFileDescriptor? = null
    private var currentMicDestUri: Uri? = null
    private var currentTempMicFile: File? = null

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0
    private var lastRecordingIsPortrait: Boolean = true

    private var countdownOverlayView: View? = null
    private var countdownNumberView: TextView? = null

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val mediaStorePublisher by lazy { MediaStorePublisher(this) }
    private val notificationController by lazy { ScreenRecordNotificationController(this) }
    private val screenshotController by lazy {
        ScreenshotCaptureController(
            service = this,
            scope = lifecycleScope,
            mainHandler = mainHandler,
            settingsRepository = settingsRepository,
            mediaStorePublisher = mediaStorePublisher,
            mediaProjectionManagerProvider = { mediaProjectionManager },
            mediaProjectionProvider = { mediaProjection },
            setMediaProjection = { mediaProjection = it },
            setProjectionResult = { code, data ->
                resultCode = code
                resultData = data
            },
            recorderEngineProvider = { recorderEngine },
            rollingBufferEngineProvider = { rollingBufferEngine },
            activeRecordingEngineModeProvider = { activeRecordingEngineMode },
            activeBufferEngineModeProvider = { activeBufferEngineMode },
            recordingEngineModeProvider = { recordingEngineMode },
            isRecorderRunningProvider = { isRecorderRunning },
            isBufferRunningProvider = { isBufferRunning },
            isPreparedProvider = { isPrepared },
            mainForegroundActiveProvider = { mainForegroundActive },
            setMainForegroundActive = { mainForegroundActive = it },
            readyNotificationProvider = { buildReadyNotification() },
            screenshotFormatProvider = { screenshotFormat },
            screenshotQualityProvider = { screenshotQuality },
            setScreenshotOptions = { format, quality ->
                format?.let { screenshotFormat = it }
                quality?.let { screenshotQuality = it.coerceIn(1, 100) }
            },
        )
    }

    /** Updated from [SettingsRepository.floatingControls] without blocking notification builders. */
    @Volatile
    private var cachedFloatingControlsForNotification: Boolean = false

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) handleScreenOff()
            }
        }

    // ── Analytics helper ───────────────────────────────────────────────────────

    private fun resetCleanupGuardsForNewCaptureSession() {
        cleanupInProgress.set(false)
        cleanupCompleted.set(false)
        projectionStopExpected.set(false)
    }

    private fun ignoreExpectedProjectionStopCallback(source: String): Boolean {
        if (!projectionStopExpected.get()) return false
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "MediaProjection.onStop() ignored after app-initiated stop source=$source")
        }
        return true
    }

    private fun stopMediaProjectionExpected(reason: String) {
        val projection = mediaProjection ?: return
        projectionStopExpected.set(true)
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "Stopping MediaProjection as expected reason=$reason")
        }
        try {
            projection.stop()
        } catch (e: Exception) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "Expected MediaProjection.stop() failed reason=$reason: ${e.message}")
            }
        }
    }

    private fun unregisterScreenOffReceiverQuietly() {
        if (!screenOffReceiverRegistered.compareAndSet(true, false)) return
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "screenOffReceiver unregister skipped: ${e.message}")
            }
        }
    }

    private fun logServiceAnalyticsEvent(
        name: String,
        params: Map<String, String> = emptyMap(),
    ) {
        try {
            val bundle = android.os.Bundle()
            params.forEach { (k, v) -> bundle.putString(k, v.take(100)) }
            FirebaseAnalytics.getInstance(this).logEvent(name, bundle)
            FirebaseCrashlytics.getInstance().log("analytics[$name] $params")
        } catch (_: Exception) {
        }
    }

    private fun floatingOnForNotification(): Boolean =
        if (FloatingControlsNotificationCache.initialized) {
            FloatingControlsNotificationCache.peek()
        } else {
            cachedFloatingControlsForNotification
        }

    /** [ScreenRecorderEngine] / [RollingBufferEngine] invoke from encoder threads; we hop to main. */
    private fun handleFatalVideoEncodeFromEngine(
        mode: String,
        detail: String,
    ) {
        handleFatalRecordingFromEngine(
            mode = mode,
            detail = detail,
            kind = RecordingFatalKind.HardwareVideoEncoder,
        )
    }

    /**
     * Graceful stop after a fatal recording failure. [kind] distinguishes codec vs muxer for logs and UI.
     *
     * May be invoked from a background encoder thread. Claims [isStoppingForCodec] immediately so a
     * second fatal (e.g. video + audio) does not schedule duplicate toasts or stops. UI work runs on
     * the main looper.
     */
    private fun handleFatalRecordingFromEngine(
        mode: String,
        detail: String,
        kind: RecordingFatalKind,
    ) {
        val err =
            when (kind) {
                RecordingFatalKind.HardwareVideoEncoder ->
                    RecordingError.HardwareEncoder(source = mode, detail = detail)
                RecordingFatalKind.MediaMuxer ->
                    RecordingError.MediaMuxerFailure(source = mode, detail = detail)
            }

        val shouldStop =
            when (mode) {
                "record" -> isRecorderRunning
                "buffer" -> isBufferRunning
                else -> false
            }
        if (!shouldStop) {
            Log.w(
                LOG_TAG,
                "RecordingFatal_stop kind=$kind mode=$mode ignored (inactive) $detail",
            )
            return
        }
        if (!isStoppingForCodec.compareAndSet(false, true)) {
            Log.d(
                LOG_TAG,
                "RecordingFatal_stop kind=$kind mode=$mode suppressed (fatal stop already in flight) $detail",
            )
            return
        }

        mainHandler.post {
            val stillActive =
                when (mode) {
                    "record" -> isRecorderRunning
                    "buffer" -> isBufferRunning
                    else -> false
                }
            if (!stillActive) {
                isStoppingForCodec.set(false)
                Log.w(
                    LOG_TAG,
                    "RecordingFatal_stop kind=$kind mode=$mode aborted (inactive on main) $detail",
                )
                return@post
            }
            Log.e(LOG_TAG, "RecordingFatal_stop graceful kind=$kind mode=$mode $detail")
            FirebaseCrashlytics.getInstance().log(
                "graceful_stop_recording kind=$kind mode=$mode ${detail.take(180)}",
            )
            RecordingEngineEventBus.tryEmit(err)
            val toastRes =
                when (kind) {
                    RecordingFatalKind.HardwareVideoEncoder -> R.string.toast_video_encoder_failed
                    RecordingFatalKind.MediaMuxer -> R.string.toast_recording_muxer_failed
                }
            Toast.makeText(this, getString(toastRes), Toast.LENGTH_LONG).show()
            when (mode) {
                "record" -> stopRecording()
                "buffer" -> stopBuffer()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val metrics = resources.displayMetrics
        screenDensity = metrics.densityDpi
        val (w, h) = currentDisplaySizePx()
        displayWidth = w
        displayHeight = h
        createNotificationChannels()
        lifecycleScope.launch {
            cachedFloatingControlsForNotification = settingsRepository.floatingControls.first()
            FloatingControlsNotificationCache.update(cachedFloatingControlsForNotification)
            settingsRepository.floatingControls.collect {
                cachedFloatingControlsForNotification = it
                FloatingControlsNotificationCache.update(it)
            }
        }
    }

    override fun onDestroy() {
        unregisterCaptureResizeListener()
        AudioRecordingCrashlyticsReporter.notifySessionEnded()
        super.onDestroy()
    }

    @RequiresApi(30)
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            return handleNullStartCommandAfterPossibleSystemRestart()
        }
        // Intent extras default to a ClassLoader that may not resolve app Parcelable types; some
        // devices (e.g. certain OEM Android 13 builds) then NPE inside Parcel.readParcelableCreatorInternal.
        intent.setExtrasClassLoader(classLoader)

        when (intent.action) {
            ACTION_REFRESH_MAIN_NOTIFICATION -> {
                runCatching {
                    val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
                    when {
                        isRecorderRunning ->
                            nm.notify(
                                MAIN_FOREGROUND_NOTIFICATION_ID,
                                buildRecordingNotification(isPaused = isRecordingPaused),
                            )
                        isPrepared ->
                            nm.notify(MAIN_FOREGROUND_NOTIFICATION_ID, buildReadyNotification())
                        else -> Unit
                    }
                }
            }

            ACTION_START -> {
                promoteImmediateMediaProjectionForeground(forBuffer = false)
                captureDimensionsFromSessionConfig = false
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }

                fps = intent.getIntExtra(EXTRA_FPS, 30)
                bitrate = intent.getIntExtra(EXTRA_BITRATE, 10_000_000)
                audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, false)
                internalAudioEnabled = intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO_ENABLED, false)
                audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000)
                audioSampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 44_100)
                audioChannels = intent.getStringExtra(EXTRA_AUDIO_CHANNELS) ?: "Mono"
                audioEncoderType = intent.getStringExtra(EXTRA_AUDIO_ENCODER) ?: "AAC-LC"
                separateMicRecording = intent.getBooleanExtra(EXTRA_SEPARATE_MIC_RECORDING, false)
                showCamera = intent.getBooleanExtra(EXTRA_SHOW_CAMERA, false)
                cameraOverlaySize = intent.getIntExtra(EXTRA_CAMERA_SIZE, 120)
                cameraXFraction = intent.getFloatExtra(EXTRA_CAMERA_X_FRACTION, 0.05f)
                cameraYFraction = intent.getFloatExtra(EXTRA_CAMERA_Y_FRACTION, 0.1f)
                cameraLockPosition = intent.getBooleanExtra(EXTRA_CAMERA_LOCK_POSITION, false)
                cameraFacing = intent.getStringExtra(EXTRA_CAMERA_FACING) ?: "Front"
                cameraAspectRatio = intent.getStringExtra(EXTRA_CAMERA_ASPECT_RATIO) ?: "Circle"
                cameraOpacity = intent.getIntExtra(EXTRA_CAMERA_OPACITY, 100)
                showWatermark = intent.getBooleanExtra(EXTRA_SHOW_WATERMARK, false)
                showFloatingControls = intent.getBooleanExtra(EXTRA_SHOW_FLOATING_CONTROLS, false)
                cachedFloatingControlsForNotification = showFloatingControls
                FloatingControlsNotificationCache.update(showFloatingControls)
                hideFloatingIconWhileRecording = intent.getBooleanExtra(EXTRA_HIDE_FLOATING_ICON_WHILE_RECORDING, false)
                stopBehaviors =
                    intent.getStringArrayListExtra(EXTRA_STOP_BEHAVIOR)?.let {
                        ArrayList(StopBehaviorKeys.migrateSet(it.toSet()).toList())
                    }
                saveLocationUri = intent.getStringExtra(EXTRA_SAVE_LOCATION)
                filenamePattern = intent.getStringExtra(EXTRA_FILENAME_PATTERN) ?: "yyyyMMdd_HHmmss"
                resolutionSetting = intent.getStringExtra(EXTRA_RESOLUTION) ?: "Native"
                videoEncoder = intent.getStringExtra(EXTRA_VIDEO_ENCODER) ?: "H.264"
                colorMode = ColorMode.resolve(intent.getStringExtra(EXTRA_COLOR_MODE), cachedColorModeFromApp)
                recordingEngineMode =
                    RecordingEngineMode.fromStorageValue(intent.getStringExtra(EXTRA_RECORDING_ENGINE_MODE))
                forceRec709Compatibility = intent.getBooleanExtra(EXTRA_FORCE_REC709, cachedForceRec709FromApp)
                rec709CompatBrightnessCorrection =
                    Rec709CompatBrightnessCorrection.resolve(
                        intent.getStringExtra(EXTRA_REC709_COMPAT_BRIGHTNESS_CORRECTION),
                        cachedRec709CompatBrightnessCorrectionFromApp,
                    )
                countdownValue = intent.getIntExtra(EXTRA_COUNTDOWN, 0).coerceIn(0, 60)
                keepScreenOn = intent.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, false)
                recordingOrientationSetting = intent.getStringExtra(EXTRA_RECORDING_ORIENTATION) ?: "Auto"
                watermarkLocation = intent.getStringExtra(EXTRA_WATERMARK_LOCATION) ?: "Top Left"
                watermarkImageUri = intent.getStringExtra(EXTRA_WATERMARK_IMAGE_URI)
                watermarkShape = intent.getStringExtra(EXTRA_WATERMARK_SHAPE) ?: "Square"
                watermarkOpacity = intent.getIntExtra(EXTRA_WATERMARK_OPACITY, 100)
                watermarkSize = intent.getIntExtra(EXTRA_WATERMARK_SIZE, 80)
                watermarkXFraction = intent.getFloatExtra(EXTRA_WATERMARK_X_FRACTION, 0.05f)
                watermarkYFraction = intent.getFloatExtra(EXTRA_WATERMARK_Y_FRACTION, 0.05f)
                screenshotFormat = intent.getStringExtra(EXTRA_SCREENSHOT_FORMAT) ?: "JPEG"
                screenshotQuality = intent.getIntExtra(EXTRA_SCREENSHOT_QUALITY, 90)
                isGifSession = intent.getBooleanExtra(EXTRA_GIF_SESSION, false)
                gifMaxDurationSec = intent.getIntExtra(EXTRA_GIF_MAX_DURATION_SEC, 0)
                gifScaleWidth = intent.getIntExtra(EXTRA_GIF_SCALE_WIDTH, 480).coerceIn(160, 1920)
                gifOutputFps = intent.getIntExtra(EXTRA_GIF_OUTPUT_FPS, 10).coerceIn(1, 60)
                gifMaxColors = intent.getIntExtra(EXTRA_GIF_MAX_COLORS, 128).coerceIn(2, 256)
                gifPaletteDither =
                    intent.getStringExtra(EXTRA_GIF_DITHER_KIND)?.let { GifPaletteDither.fromSerialized(it) }
                        ?: GifPaletteDither.BAYER_MEDIUM
                if (!isGifSession) {
                    gifMaxDurationSec = 0
                }

                applySessionConfigFromIntent(intent)

                if (resultCode != 0 && resultData != null) {
                    when (val gate = runBlocking { checkCurrentRecordingProGate("service_action_start") }) {
                        RecordingStartProGateResult.Allowed -> Unit
                        is RecordingStartProGateResult.BlockedNeedsPro -> {
                            handleBlockedProStart(
                                source = "service_action_start",
                                asBuffer = false,
                                features = gate.features,
                                preservePreparedSession = false,
                            )
                            return START_STICKY
                        }
                    }
                    try {
                        startRecording()
                    } catch (e: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                } else {
                    abortForegroundServiceEntryPendingStop()
                }
            }
            ACTION_EXIT_SERVICE -> {
                revokeAfterStop = true
                if (isRecorderRunning) {
                    stopRecording()
                } else if (isBufferRunning) {
                    stopBuffer()
                } else {
                    isPrepared = false
                    RecordingState.setPrepared(false)
                    stopMediaProjectionExpected("exit_service")
                    mediaProjection = null
                    unregisterScreenOffReceiverQuietly()
                    mainForegroundActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_STOP -> stopRecording()

            ACTION_INTERNAL_SILENCE_USE_MIC_FALLBACK -> {
                Log.i(LOG_TAG_INTERNAL_SILENCE, "fallback_accepted mic_hot_swap_requested")
                recorderEngine?.requestMicFallbackFromSilentInternal()
            }

            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_TOGGLE_PAUSE -> {
                if (isRecorderRunning) {
                    if (isRecordingPaused) resumeRecording() else pauseRecording()
                }
            }
            ACTION_MUTE -> {
                isRecordingMuted = true
                recorderEngine?.mute()
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
                startService(
                    Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_UPDATE_MUTE_STATE
                        putExtra(OverlayService.EXTRA_IS_MUTED, true)
                    },
                )
            }
            ACTION_UNMUTE -> {
                isRecordingMuted = false
                recorderEngine?.unmute()
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
                startService(
                    Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_UPDATE_MUTE_STATE
                        putExtra(OverlayService.EXTRA_IS_MUTED, false)
                    },
                )
            }
            ACTION_CONTROLS_DISMISSED -> {
                controlsDismissedByUser = true
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
            }
            ACTION_CONTROLS_RESHOWN -> {
                controlsDismissedByUser = false
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
            }
            // Recording notification no longer uses setDeleteIntent — avoids notify-on-dismiss
            // that could reorder foreground / cause full-screen flicker on some devices.
            ACTION_NOTIFICATION_DISMISSED -> Unit
            ACTION_START_BUFFER -> {
                if (!isRecorderRunning && !isBufferRunning) {
                    promoteImmediateMediaProjectionForeground(forBuffer = true)
                    captureDimensionsFromSessionConfig = false
                    resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    resultData =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(EXTRA_DATA)
                        }
                    fps = intent.getIntExtra(EXTRA_FPS, 30)
                    bitrate = intent.getIntExtra(EXTRA_BITRATE, 10_000_000)
                    audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, false)
                    internalAudioEnabled = intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO_ENABLED, false)
                    audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000)
                    audioSampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 44_100)
                    audioChannels = intent.getStringExtra(EXTRA_AUDIO_CHANNELS) ?: "Mono"
                    audioEncoderType = intent.getStringExtra(EXTRA_AUDIO_ENCODER) ?: "AAC-LC"
                    saveLocationUri = intent.getStringExtra(EXTRA_SAVE_LOCATION)
                    resolutionSetting = intent.getStringExtra(EXTRA_RESOLUTION) ?: "Native"
                    videoEncoder = intent.getStringExtra(EXTRA_VIDEO_ENCODER) ?: "H.264"
                    colorMode = ColorMode.resolve(intent.getStringExtra(EXTRA_COLOR_MODE), cachedColorModeFromApp)
                    recordingEngineMode =
                        RecordingEngineMode.fromStorageValue(intent.getStringExtra(EXTRA_RECORDING_ENGINE_MODE))
                    forceRec709Compatibility = intent.getBooleanExtra(EXTRA_FORCE_REC709, cachedForceRec709FromApp)
                    rec709CompatBrightnessCorrection =
                        Rec709CompatBrightnessCorrection.resolve(
                            intent.getStringExtra(EXTRA_REC709_COMPAT_BRIGHTNESS_CORRECTION),
                            cachedRec709CompatBrightnessCorrectionFromApp,
                        )
                    clipperDurationMinutes = intent.getIntExtra(EXTRA_CLIPPER_DURATION_MINUTES, 1).coerceIn(1, 5)
                    countdownValue = intent.getIntExtra(EXTRA_COUNTDOWN, 0).coerceIn(0, 60)
                    applySessionConfigFromIntent(intent)
                    if (resultCode != 0 && resultData != null) {
                        when (val gate = runBlocking { checkCurrentBufferProGate("service_action_start_buffer") }) {
                            RecordingStartProGateResult.Allowed -> Unit
                            is RecordingStartProGateResult.BlockedNeedsPro -> {
                                handleBlockedProStart(
                                    source = "service_action_start_buffer",
                                    asBuffer = true,
                                    features = gate.features,
                                    preservePreparedSession = false,
                                )
                                return START_STICKY
                            }
                        }
                        startBuffer()
                    } else {
                        abortForegroundServiceEntryPendingStop()
                    }
                }
            }
            ACTION_STOP_BUFFER -> stopBuffer()
            ACTION_SAVE_CLIP -> saveClip()

            ACTION_PREPARE -> {
                promoteImmediateMediaProjectionForeground(forBuffer = false)
                if (isRecorderRunning) {
                    runCatching {
                        ServiceCompat.startForeground(
                            this,
                            MAIN_FOREGROUND_NOTIFICATION_ID,
                            buildRecordingNotification(isPaused = isRecordingPaused),
                            mediaProjectionForegroundServiceTypes(),
                        )
                        mainForegroundActive = true
                    }
                    return START_STICKY
                }
                if (isBufferRunning) {
                    runCatching {
                        ServiceCompat.startForeground(
                            this,
                            BUFFER_NOTIFICATION_ID,
                            buildBufferNotification(),
                            mediaProjectionForegroundServiceTypes(),
                        )
                        mainForegroundActive = false
                    }
                    return START_STICKY
                }
                if (isPrepared) {
                    runCatching {
                        ServiceCompat.startForeground(
                            this,
                            MAIN_FOREGROUND_NOTIFICATION_ID,
                            buildReadyNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                        )
                        mainForegroundActive = true
                    }
                    return START_STICKY
                }
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }
                if (resultCode == 0 || resultData == null) {
                    abortForegroundServiceEntryPendingStop()
                    return START_STICKY
                }
                intent.getStringExtra(EXTRA_SCREENSHOT_FORMAT)?.let { screenshotFormat = it }
                val q = intent.getIntExtra(EXTRA_SCREENSHOT_QUALITY, -1)
                if (q >= 0) screenshotQuality = q.coerceIn(1, 100)
                startPreparedForeground()
            }

            ACTION_START_FROM_OVERLAY -> {
                promoteImmediateMediaProjectionForeground(forBuffer = false)
                when {
                    isRecorderRunning -> {
                        runCatching {
                            ServiceCompat.startForeground(
                                this,
                                MAIN_FOREGROUND_NOTIFICATION_ID,
                                buildRecordingNotification(isPaused = isRecordingPaused),
                                mediaProjectionForegroundServiceTypes(),
                            )
                            mainForegroundActive = true
                        }
                        return START_STICKY
                    }
                    !isPrepared -> {
                        abortForegroundServiceEntryPendingStop()
                        return START_STICKY
                    }
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        applyOverlayRecordingRepoSnapshotToService()
                        val proGate = checkCurrentRecordingProGate("overlay_prepared_recording")
                        withContext(Dispatchers.Main) {
                            if (!isPrepared) {
                                Log.w(
                                    LOG_TAG,
                                    "stale_overlay_start_ignored recording prepared=false",
                                )
                                abortForegroundServiceEntryPendingStop()
                                return@withContext
                            }
                            if (isRecorderRunning) {
                                Log.w(
                                    LOG_TAG,
                                    "stale_overlay_start_ignored recording rec=true",
                                )
                                runCatching {
                                    ServiceCompat.startForeground(
                                        this@ScreenRecordService,
                                        MAIN_FOREGROUND_NOTIFICATION_ID,
                                        buildRecordingNotification(isPaused = isRecordingPaused),
                                        mediaProjectionForegroundServiceTypes(),
                                    )
                                    mainForegroundActive = true
                                }
                                return@withContext
                            }
                            if (proGate is RecordingStartProGateResult.BlockedNeedsPro) {
                                handleBlockedProStart(
                                    source = "overlay_prepared_recording",
                                    asBuffer = false,
                                    features = proGate.features,
                                    preservePreparedSession = true,
                                )
                                return@withContext
                            }
                            if (shouldAbortPreparedOverlayStartBecauseRecordAudioMissing()) {
                                return@withContext
                            }
                            try {
                                startRecording()
                            } catch (e: Exception) {
                                FirebaseCrashlytics.getInstance().recordException(e)
                            }
                        }
                    } catch (e: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
            }

            ACTION_START_BUFFER_FROM_OVERLAY -> {
                promoteImmediateMediaProjectionForeground(forBuffer = true)
                when {
                    isBufferRunning -> {
                        runCatching {
                            ServiceCompat.startForeground(
                                this,
                                BUFFER_NOTIFICATION_ID,
                                buildBufferNotification(),
                                mediaProjectionForegroundServiceTypes(),
                            )
                            mainForegroundActive = false
                        }
                        return START_STICKY
                    }
                    isRecorderRunning -> {
                        runCatching {
                            ServiceCompat.startForeground(
                                this,
                                MAIN_FOREGROUND_NOTIFICATION_ID,
                                buildRecordingNotification(isPaused = isRecordingPaused),
                                mediaProjectionForegroundServiceTypes(),
                            )
                            mainForegroundActive = true
                        }
                        return START_STICKY
                    }
                    !isPrepared -> {
                        abortForegroundServiceEntryPendingStop()
                        return START_STICKY
                    }
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        applyOverlayBufferRepoSnapshotToService()
                        val proGate = checkCurrentBufferProGate("overlay_prepared_buffer")
                        withContext(Dispatchers.Main) {
                            if (!isPrepared || isBufferRunning || isRecorderRunning) {
                                Log.w(
                                    LOG_TAG,
                                    "stale_overlay_start_ignored buffer prepared=$isPrepared buf=$isBufferRunning rec=$isRecorderRunning",
                                )
                                when {
                                    !isPrepared -> abortForegroundServiceEntryPendingStop()
                                    isBufferRunning ->
                                        runCatching {
                                            ServiceCompat.startForeground(
                                                this@ScreenRecordService,
                                                BUFFER_NOTIFICATION_ID,
                                                buildBufferNotification(),
                                                mediaProjectionForegroundServiceTypes(),
                                            )
                                            mainForegroundActive = false
                                        }
                                    isRecorderRunning ->
                                        runCatching {
                                            ServiceCompat.startForeground(
                                                this@ScreenRecordService,
                                                MAIN_FOREGROUND_NOTIFICATION_ID,
                                                buildRecordingNotification(isPaused = isRecordingPaused),
                                                mediaProjectionForegroundServiceTypes(),
                                            )
                                            mainForegroundActive = true
                                        }
                                }
                                return@withContext
                            }
                            if (proGate is RecordingStartProGateResult.BlockedNeedsPro) {
                                handleBlockedProStart(
                                    source = "overlay_prepared_buffer",
                                    asBuffer = true,
                                    features = proGate.features,
                                    preservePreparedSession = true,
                                )
                                return@withContext
                            }
                            if (shouldAbortPreparedOverlayStartBecauseRecordAudioMissing()) {
                                return@withContext
                            }
                            try {
                                startBuffer()
                            } catch (e: Exception) {
                                FirebaseCrashlytics.getInstance().recordException(e)
                            }
                        }
                    } catch (e: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
            }

            ACTION_START_AFTER_OVERLAY_PROJECTION -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val rd =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }
                val asBuffer = intent.getBooleanExtra(EXTRA_OVERLAY_SESSION_AS_BUFFER, false)

                if (isRecorderRunning || isBufferRunning) {
                    promoteImmediateMediaProjectionForeground(forBuffer = isBufferRunning)
                    runCatching {
                        if (isRecorderRunning) {
                            ServiceCompat.startForeground(
                                this,
                                MAIN_FOREGROUND_NOTIFICATION_ID,
                                buildRecordingNotification(isPaused = isRecordingPaused),
                                mediaProjectionForegroundServiceTypes(),
                            )
                            mainForegroundActive = true
                        } else {
                            ServiceCompat.startForeground(
                                this,
                                BUFFER_NOTIFICATION_ID,
                                buildBufferNotification(),
                                mediaProjectionForegroundServiceTypes(),
                            )
                            mainForegroundActive = false
                        }
                    }
                    return START_STICKY
                }

                if (rc == 0 || rd == null) {
                    promoteImmediateMediaProjectionForeground(forBuffer = asBuffer)
                    abortForegroundServiceEntryPendingStop()
                    return START_STICKY
                }

                promoteImmediateMediaProjectionForeground(forBuffer = asBuffer)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (asBuffer) {
                            applyOverlayBufferRepoSnapshotToService()
                        } else {
                            applyOverlayRecordingRepoSnapshotToService()
                        }
                        val proGate =
                            if (asBuffer) {
                                checkCurrentBufferProGate("overlay_projection_buffer")
                            } else {
                                checkCurrentRecordingProGate("overlay_projection_recording")
                            }
                        withContext(Dispatchers.Main) {
                            if (isRecorderRunning || isBufferRunning) {
                                runCatching {
                                    if (isRecorderRunning) {
                                        ServiceCompat.startForeground(
                                            this@ScreenRecordService,
                                            MAIN_FOREGROUND_NOTIFICATION_ID,
                                            buildRecordingNotification(isPaused = isRecordingPaused),
                                            mediaProjectionForegroundServiceTypes(),
                                        )
                                        mainForegroundActive = true
                                    } else {
                                        ServiceCompat.startForeground(
                                            this@ScreenRecordService,
                                            BUFFER_NOTIFICATION_ID,
                                            buildBufferNotification(),
                                            mediaProjectionForegroundServiceTypes(),
                                        )
                                        mainForegroundActive = false
                                    }
                                }
                                return@withContext
                            }
                            if (proGate is RecordingStartProGateResult.BlockedNeedsPro) {
                                handleBlockedProStart(
                                    source = if (asBuffer) "overlay_projection_buffer" else "overlay_projection_recording",
                                    asBuffer = asBuffer,
                                    features = proGate.features,
                                    preservePreparedSession = false,
                                )
                                return@withContext
                            }
                            // This action always carries a fresh consent token from the system
                            // dialog. Release any projection that is still alive from a previous
                            // session (e.g. stopRecording() async cleanup not yet finished) so
                            // actualStartRecording() goes through the normal getMediaProjection()
                            // path and does NOT try to reuse a spent projection — calling
                            // createVirtualDisplay twice on the same instance throws SecurityException
                            // on Android 14+ (API 34+).
                            if (mediaProjection != null) {
                                Log.d(LOG_TAG, "ACTION_START_AFTER_OVERLAY_PROJECTION: releasing stale projection before fresh grant")
                                stopMediaProjectionExpected("overlay_fresh_grant_replace")
                                mediaProjection = null
                            }
                            resultCode = rc
                            resultData = rd
                            try {
                                if (asBuffer) startBuffer() else startRecording()
                            } catch (e: Exception) {
                                FirebaseCrashlytics.getInstance().recordException(e)
                            }
                        }
                    } catch (e: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
            }

            ACTION_REVOKE_PREPARE -> {
                if (!isRecorderRunning) {
                    isPrepared = false
                    RecordingState.setPrepared(false)
                    stopMediaProjectionExpected("revoke_prepare")
                    mediaProjection = null
                    unregisterScreenOffReceiverQuietly()
                    mainForegroundActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_DELETE_SAVED_RECORDING -> {
                val u = intent.getStringExtra(EXTRA_LAST_SAVED_RECORDING_URI)?.let(Uri::parse)
                if (u != null) {
                    try {
                        contentResolver.delete(u, null, null)
                    } catch (_: Exception) {
                    }
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(POST_NOTIFICATION_ID)
                if (isPrepared) {
                    nm.notify(MAIN_FOREGROUND_NOTIFICATION_ID, buildReadyNotification())
                }
            }

            ACTION_TAKE_SCREENSHOT -> {
                screenshotController.handleScreenshotAction()
            }

            ACTION_TAKE_SCREENSHOT_ONE_SHOT -> {
                screenshotController.handleOneShotScreenshotStart(intent)
            }
        }
        return START_STICKY
    }

    /**
     * After a [START_STICKY] restart the system may deliver a null [Intent]. MediaProjection
     * cannot be restored from that delivery — tear down and optionally notify the user.
     */
    private fun handleNullStartCommandAfterPossibleSystemRestart(): Int {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (_: Exception) {
            }
            wakeLock = null
        }
        if (consumeCaptureSessionDiskFlag()) {
            RecordingInterruptionNotifier.ensureChannel(this)
            val posted = RecordingInterruptionNotifier.notifyInterrupted(this)
            if (!posted) {
                RecordingInterruptionNotifier.scheduleRetryAlarm(this)
            }
        }
        try {
            if (mainForegroundActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        } catch (_: Exception) {
        }
        mainForegroundActive = false
        RecordingState.setRecording(false)
        RecordingState.setBuffering(false)
        RecordingState.setPrepared(false)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun setCaptureSessionDiskFlag(active: Boolean) {
        getSharedPreferences(PREFS_CAPTURE_SESSION, MODE_PRIVATE).edit { putBoolean(PREF_CAPTURE_ACTIVE, active) }
    }

    private fun consumeCaptureSessionDiskFlag(): Boolean {
        val p = getSharedPreferences(PREFS_CAPTURE_SESSION, MODE_PRIVATE)
        if (!p.getBoolean(PREF_CAPTURE_ACTIVE, false)) return false
        p.edit { putBoolean(PREF_CAPTURE_ACTIVE, false) }
        return true
    }

    /**
     * Consumes the pre-supplied MediaProjection token, keeps it alive in a foreground
     * service, and signals the overlay that it can now trigger recordings without a dialog.
     */
    @RequiresApi(29)
    private fun startPreparedForeground() {
        resetCleanupGuardsForNewCaptureSession()
        AppControlNotification.cancel(this)
        val notification = buildReadyNotification()
        try {
            ServiceCompat.startForeground(
                this,
                MAIN_FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            mainForegroundActive = true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Prepare foreground start failed", e)
            mainForegroundActive = false
            stopSelf()
            return
        }

        Log.d(LOG_TAG, "startPreparedForeground: obtaining MediaProjection (resultCode=$resultCode)")
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
        Log.d(LOG_TAG, "startPreparedForeground: MediaProjection=$mediaProjection")
        if (mediaProjection == null) {
            Log.e(LOG_TAG, "startPreparedForeground: getMediaProjection returned null")
            RecordingEngineEventBus.tryEmit(RecordingError.PermissionDenied("media_projection_null_prepare"))
            logServiceAnalyticsEvent(
                "capture_denied_or_unsupported",
                mapOf(
                    "reason" to "projection_null_prepare",
                    "api" to Build.VERSION.SDK_INT.toString(),
                    "brand" to Build.BRAND,
                ),
            )
            mainForegroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        logServiceAnalyticsEvent(
            "projection_granted",
            mapOf(
                "path" to "prepare",
                "api" to Build.VERSION.SDK_INT.toString(),
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
            ),
        )
        FirebaseCrashlytics.getInstance().log("MediaProjection granted (prepare path)")

        mediaProjection?.registerCallback(
            object : MediaProjection.Callback() {
                @RequiresApi(34)
                override fun onCapturedContentResize(
                    width: Int,
                    height: Int,
                ) {
                    super.onCapturedContentResize(width, height)
                    logCapturedContentResize(width, height, "prepared")
                }

                override fun onStop() {
                    super.onStop()
                    if (ignoreExpectedProjectionStopCallback("prepared")) {
                        mediaProjection = null
                        return
                    }
                    Log.w(
                        LOG_TAG,
                        "MediaProjection.onStop() fired in prepared mode — projection revoked by OS " +
                            "(API=${Build.VERSION.SDK_INT} brand=${Build.BRAND})",
                    )
                    FirebaseCrashlytics.getInstance().log("MediaProjection.onStop in prepared mode")
                    RecordingEngineEventBus.tryEmit(
                        RecordingError.ProjectionStopped("prepared_media_projection_on_stop"),
                    )
                    isPrepared = false
                    RecordingState.setPrepared(false)
                    if (isRecorderRunning) {
                        stopRecording()
                    } else {
                        mediaProjection = null
                        mainForegroundActive = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            },
            null,
        )

        isPrepared = true
        RecordingState.setPrepared(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val v = settingsRepository.floatingControls.first()
                withContext(Dispatchers.Main) {
                    FloatingControlsNotificationCache.update(v)
                    cachedFloatingControlsForNotification = v
                    if (isPrepared) {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(MAIN_FOREGROUND_NOTIFICATION_ID, buildReadyNotification())
                    }
                    Log.d(LOG_TAG, "notif_floating_controls_warmed")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "notif_floating_controls_warm_failed: ${e.message}")
            }
        }
    }

    private fun buildReadyNotification(): Notification =
        notificationController.buildReadyNotification(
            floatingOn = floatingOnForNotification(),
            overlayVisible = OverlayService.idleControlsBubbleVisible,
        )

    /** Reads recording settings from the repository and assigns them on the main thread. */
    private suspend fun applyOverlayRecordingRepoSnapshotToService() {
        val repo = settingsRepository
        withContext(Dispatchers.IO) {
            val mode = repo.captureMode.first()
            val gifPreset =
                if (mode == com.ibbie.catrec_screenrecorcer.data.CaptureMode.GIF) {
                    com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets.byId(
                        repo.gifRecorderPresetId.first(),
                    )
                } else {
                    null
                }
            val fpsVal: Int
            val bitrateVal: Int
            val resVal: String
            val gifSession: Boolean
            val gifMaxSec: Int
            val gifScaleW: Int
            val gifOutFps: Int
            val gifColors: Int
            val gifDith: GifPaletteDither
            if (mode == com.ibbie.catrec_screenrecorcer.data.CaptureMode.GIF && gifPreset != null) {
                fpsVal = gifPreset.recordingFps
                bitrateVal = gifPreset.bitrateBitsPerSec
                resVal = gifPreset.resolutionSetting
                gifSession = true
                gifMaxSec = gifPreset.maxDurationSec
                gifScaleW = gifPreset.maxWidth
                gifOutFps = gifPreset.exportFps
                gifColors = gifPreset.maxColors
                gifDith = gifPreset.paletteDither
            } else {
                fpsVal = repo.fps.first().toInt()
                bitrateVal = (repo.bitrate.first() * 1_000_000).toInt()
                resVal = repo.resolution.first()
                gifSession = false
                gifMaxSec = 0
                gifScaleW = gifScaleWidth
                gifOutFps = gifOutputFps
                gifColors = gifMaxColors
                gifDith = gifPaletteDither
            }
            val audioEn = repo.recordAudio.first()
            val internalEn = repo.internalAudio.first()
            val audioBr = repo.audioBitrate.first() * 1000
            val audioSr = repo.audioSampleRate.first()
            val audioCh = repo.audioChannels.first()
            val audioEnc = repo.audioEncoder.first()
            val sepMic = repo.separateMicRecording.first()
            val cam = repo.cameraOverlay.first()
            val camSize = repo.cameraOverlaySize.first()
            val camX = repo.cameraXFraction.first()
            val camY = repo.cameraYFraction.first()
            val camLock = repo.cameraLockPosition.first()
            val camFace = repo.cameraFacing.first()
            val camAsp = repo.cameraAspectRatio.first()
            val camOp = repo.cameraOpacity.first()
            val wm = repo.showWatermark.first()
            val floatCtl = repo.floatingControls.first()
            val hideFloat = repo.hideFloatingIconWhileRecording.first()
            val stopB = ArrayList(repo.stopBehavior.first())
            val saveLoc = repo.saveLocationUri.first()
            val vidEnc = repo.videoEncoder.first()
            val colMode = repo.colorMode.first()
            val engineMode = cachedRecordingEngineModeFromApp
            val fnPat = repo.filenamePattern.first()
            val cd = repo.countdown.first()
            val kso = repo.keepScreenOn.first()
            val recOr = repo.recordingOrientation.first()
            val wml = repo.watermarkLocation.first()
            val wmi = repo.watermarkImageUri.first()
            val wms = repo.watermarkShape.first()
            val wmo = repo.watermarkOpacity.first()
            val wmz = repo.watermarkSize.first()
            val wmx = repo.watermarkXFraction.first()
            val wmy = repo.watermarkYFraction.first()
            val ssFmt = repo.screenshotFormat.first()
            val ssQ = repo.screenshotQuality.first()
            withContext(Dispatchers.Main) {
                fps = fpsVal
                bitrate = bitrateVal
                resolutionSetting = resVal
                isGifSession = gifSession
                gifMaxDurationSec = gifMaxSec
                gifScaleWidth = gifScaleW
                gifOutputFps = gifOutFps
                gifMaxColors = gifColors
                gifPaletteDither = gifDith
                audioEnabled = audioEn
                internalAudioEnabled = internalEn
                audioBitrate = audioBr
                audioSampleRate = audioSr
                audioChannels = audioCh
                audioEncoderType = audioEnc
                separateMicRecording = sepMic
                showCamera = cam
                cameraOverlaySize = camSize
                cameraXFraction = camX
                cameraYFraction = camY
                cameraLockPosition = camLock
                cameraFacing = camFace
                cameraAspectRatio = camAsp
                cameraOpacity = camOp
                showWatermark = wm
                showFloatingControls = floatCtl
                cachedFloatingControlsForNotification = floatCtl
                FloatingControlsNotificationCache.update(floatCtl)
                hideFloatingIconWhileRecording = hideFloat
                stopBehaviors = stopB
                saveLocationUri = saveLoc
                videoEncoder = vidEnc
                colorMode = colMode
                recordingEngineMode = engineMode
                forceRec709Compatibility = repo.forceRec709Compatibility.first()
                rec709CompatBrightnessCorrection = repo.rec709CompatBrightnessCorrection.first()
                filenamePattern = fnPat
                countdownValue = cd.coerceIn(0, 60)
                keepScreenOn = kso
                recordingOrientationSetting = recOr
                watermarkLocation = wml
                watermarkImageUri = wmi
                watermarkShape = wms
                watermarkOpacity = wmo
                watermarkSize = wmz
                watermarkXFraction = wmx
                watermarkYFraction = wmy
                screenshotFormat = ssFmt
                screenshotQuality = ssQ
            }
        }
    }

    private suspend fun applyOverlayBufferRepoSnapshotToService() {
        val repo = settingsRepository
        withContext(Dispatchers.IO) {
            val fpsVal = repo.fps.first().toInt()
            val bitrateVal = (repo.bitrate.first() * 1_000_000).toInt()
            val audioEn = repo.recordAudio.first()
            val internalEn = repo.internalAudio.first()
            val audioBr = repo.audioBitrate.first() * 1000
            val audioSr = repo.audioSampleRate.first()
            val audioCh = repo.audioChannels.first()
            val audioEnc = repo.audioEncoder.first()
            val floatCtl = repo.floatingControls.first()
            val vidEnc = repo.videoEncoder.first()
            val colMode = repo.colorMode.first()
            val engineMode = cachedRecordingEngineModeFromApp
            val resVal = repo.resolution.first()
            val saveLoc = repo.saveLocationUri.first()
            val clipMin = repo.clipperDurationMinutes.first()
            val cd = repo.countdown.first()
            withContext(Dispatchers.Main) {
                fps = fpsVal
                bitrate = bitrateVal
                audioEnabled = audioEn
                internalAudioEnabled = internalEn
                audioBitrate = audioBr
                audioSampleRate = audioSr
                audioChannels = audioCh
                audioEncoderType = audioEnc
                showFloatingControls = floatCtl
                cachedFloatingControlsForNotification = floatCtl
                FloatingControlsNotificationCache.update(floatCtl)
                videoEncoder = vidEnc
                colorMode = colMode
                recordingEngineMode = engineMode
                forceRec709Compatibility = repo.forceRec709Compatibility.first()
                rec709CompatBrightnessCorrection = repo.rec709CompatBrightnessCorrection.first()
                resolutionSetting = resVal
                saveLocationUri = saveLoc
                clipperDurationMinutes = clipMin
                countdownValue = cd.coerceIn(0, 60)
            }
        }
    }

    /** Bitmask passed to [ServiceCompat.startForeground] for capture sessions (projection ± mic). */
    private fun mediaProjectionForegroundServiceTypes(): Int {
        val hasAudioPermission =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (hasAudioPermission && (audioEnabled || internalAudioEnabled)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    /**
     * Android 14+: [MediaProjectionManager.getMediaProjection] requires an active foreground service
     * declared with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]. OEM builds may reject token
     * consumption after delays (e.g. countdown) unless types are re-bound immediately before the call
     * (Crashlytics: SecurityException in [actualStartRecording]).
     */
    private fun ensureRecordingForegroundBeforeMediaProjection() {
        if (Build.VERSION.SDK_INT < 34) return
        ServiceCompat.startForeground(
            this,
            MAIN_FOREGROUND_NOTIFICATION_ID,
            buildRecordingNotification(isPaused = false, contentText = getString(R.string.notif_preparing)),
            mediaProjectionForegroundServiceTypes(),
        )
        mainForegroundActive = true
    }

    private fun ensureBufferForegroundBeforeMediaProjection() {
        if (Build.VERSION.SDK_INT < 34) return
        ServiceCompat.startForeground(
            this,
            BUFFER_NOTIFICATION_ID,
            buildBufferNotification(getString(R.string.notif_buffer_starting)),
            mediaProjectionForegroundServiceTypes(),
        )
        mainForegroundActive = false
    }

    /**
     * [Context.startForegroundService] requires [startForeground] very soon — often before [onStartCommand]
     * returns. Deferred work ([lifecycleScope.launch(Dispatchers.IO)]) must not precede the first
     * [startForeground] call (Crashlytics: ForegroundServiceDidNotStartInTimeException).
     *
     * Projection-type only; [startRecording]/[startBuffer] re-bind with microphone OR when needed.
     */
    private fun promoteImmediateMediaProjectionForeground(forBuffer: Boolean) {
        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (forBuffer) {
                ServiceCompat.startForeground(
                    this,
                    BUFFER_NOTIFICATION_ID,
                    buildBufferNotification(getString(R.string.notif_buffer_starting)),
                    types,
                )
                mainForegroundActive = false
            } else {
                ServiceCompat.startForeground(
                    this,
                    MAIN_FOREGROUND_NOTIFICATION_ID,
                    buildRecordingNotification(isPaused = false, contentText = getString(R.string.notif_preparing)),
                    types,
                )
                mainForegroundActive = true
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "promoteImmediateMediaProjectionForeground failed", e)
        }
    }

    private fun abortForegroundServiceEntryPendingStop() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        mainForegroundActive = false
        stopSelf()
    }

    private suspend fun checkCurrentRecordingProGate(source: String): RecordingStartProGateResult =
        RecordingStartProGate.check(
            source = source,
            features =
                RecordingStartProGate.featuresForFullRecording(
                    fps = fps,
                    videoBitrateMbps = bitrate / 1_000_000f,
                    separateMicRecording = separateMicRecording,
                    cameraOverlay = showCamera,
                    showWatermark = showWatermark,
                ),
            adsDisabled = settingsRepository.adsDisabled.first(),
            proUnlockedUntilMillis = settingsRepository.proFeaturesUnlockedUntilMillis.first(),
        )

    private suspend fun checkCurrentBufferProGate(source: String): RecordingStartProGateResult =
        RecordingStartProGate.check(
            source = source,
            features =
                RecordingStartProGate.featuresForBuffer(
                    fps = fps,
                    videoBitrateMbps = bitrate / 1_000_000f,
                ),
            adsDisabled = settingsRepository.adsDisabled.first(),
            proUnlockedUntilMillis = settingsRepository.proFeaturesUnlockedUntilMillis.first(),
        )

    private fun routeProBlockedStartToMainActivity(
        source: String,
        asBuffer: Boolean,
        features: List<ProRecordingFeature>,
    ) {
        val featureNames = features.joinToString(",") { it.logName }
        Log.d(
            LOG_TAG,
            "Pro gate blocked start source=$source features=$featureNames; routing to MainActivity",
        )
        val action =
            if (asBuffer) {
                MainActivity.ACTION_START_BUFFER_FROM_OVERLAY
            } else {
                MainActivity.ACTION_START_RECORDING_FROM_OVERLAY
        }
        runCatching {
            MainActivity.markRoutedRecordingAppOpenSuppressed(source)
            startActivity(
                MainActivity.addRoutedRecordingSuppressionExtras(Intent(this, MainActivity::class.java)).apply {
                    this.action = action
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        }.onFailure {
            Log.w(LOG_TAG, "Failed to route Pro-blocked start to MainActivity source=$source", it)
        }
    }

    private fun restorePreparedForegroundAfterBlockedStart() {
        if (!isPrepared) {
            abortForegroundServiceEntryPendingStop()
            return
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                MAIN_FOREGROUND_NOTIFICATION_ID,
                buildReadyNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            mainForegroundActive = true
        }.onFailure {
            Log.w(LOG_TAG, "Failed to restore prepared foreground after Pro block", it)
        }
    }

    /**
     * Prepared overlay quick-start: if the user enabled mic/internal audio, [RECORD_AUDIO] is mandatory.
     * When missing, show a blocking toast and skip starting the session (no silent video-only).
     */
    private fun shouldAbortPreparedOverlayStartBecauseRecordAudioMissing(): Boolean {
        if (!audioEnabled && !internalAudioEnabled) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        Toast
            .makeText(
                this,
                getString(R.string.toast_audio_permission_required_recording_not_started),
                Toast.LENGTH_LONG,
            ).show()
        return true
    }

    private fun handleBlockedProStart(
        source: String,
        asBuffer: Boolean,
        features: List<ProRecordingFeature>,
        preservePreparedSession: Boolean,
    ) {
        routeProBlockedStartToMainActivity(source, asBuffer, features)
        if (preservePreparedSession) {
            restorePreparedForegroundAfterBlockedStart()
        } else {
            abortForegroundServiceEntryPendingStop()
        }
    }

    @RequiresApi(30)
    @SuppressLint("WakelockTimeout")
    private fun startRecording() {
        resetCleanupGuardsForNewCaptureSession()
        isStoppingForCodec.set(false)

        val notification = buildRecordingNotification(isPaused = false, contentText = getString(R.string.notif_preparing))
        val serviceType = mediaProjectionForegroundServiceTypes()

        val notifMgr = getSystemService(NotificationManager::class.java) ?: return
        try {
            // Prepared mode calls [startPreparedForeground] with MEDIA_PROJECTION only. If we only
            // [notify] here, the FGS never gains MICROPHONE — mic stays silent in background until
            // something else re-binds foreground. Re-post foreground with the full type set (API 29+).
            ServiceCompat.startForeground(this, MAIN_FOREGROUND_NOTIFICATION_ID, notification, serviceType)
            mainForegroundActive = true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to start foreground service", e)
            stopSelf()
            return
        }

        lifecycleScope.launch {
            if (countdownValue > 0) {
                showCountdownOverlay(countdownValue)
                for (i in countdownValue downTo 1) {
                    updateCountdownOverlayNumber(i)
                    notifMgr.notify(
                        MAIN_FOREGROUND_NOTIFICATION_ID,
                        buildRecordingNotification(false, getString(R.string.notif_recording_starting_in, i)),
                    )
                    delay(1000)
                }
                hideCountdownOverlay()
            }
            // Keep "Preparing…" until the recorder engine actually starts (see actualStartRecording).
            actualStartRecording()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun actualStartRecording() {
        setupStopBehaviors()

        val showFloatingControlsOverlayWhileRecording = showFloatingControls

        // Keep screen on: prefer FLAG_KEEP_SCREEN_ON on overlay windows (no extra power vs bright wake lock).
        // If overlays are unavailable, fall back to a time-bounded dim wake lock (much cheaper than SCREEN_BRIGHT).
        if (keepScreenOn) {
            val overlayPathKeepsScreen =
                Settings.canDrawOverlays(this) &&
                    (showCamera || showWatermark || showFloatingControlsOverlayWhileRecording)
            if (!overlayPathKeepsScreen) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "CatRec:KeepScreenDim")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
        }

        // Apply orientation lock before getting display metrics
        applyOrientationLock()

        if (Settings.canDrawOverlays(this) && (showCamera || showWatermark || showFloatingControlsOverlayWhileRecording)) {
            val overlayIntent =
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SHOW_OVERLAYS
                    putExtra(OverlayService.EXTRA_SHOW_CAMERA, showCamera)
                    putExtra(OverlayService.EXTRA_CAMERA_SIZE, cameraOverlaySize)
                    putExtra(OverlayService.EXTRA_CAMERA_X_FRACTION, cameraXFraction)
                    putExtra(OverlayService.EXTRA_CAMERA_Y_FRACTION, cameraYFraction)
                    putExtra(OverlayService.EXTRA_CAMERA_LOCK_POSITION, cameraLockPosition)
                    putExtra(OverlayService.EXTRA_CAMERA_FACING, cameraFacing)
                    putExtra(OverlayService.EXTRA_CAMERA_ASPECT_RATIO, cameraAspectRatio)
                    putExtra(OverlayService.EXTRA_CAMERA_OPACITY, cameraOpacity)
                    putExtra(OverlayService.EXTRA_SHOW_WATERMARK, showWatermark)
                    putExtra(OverlayService.EXTRA_SHOW_CONTROLS, showFloatingControlsOverlayWhileRecording)
                    putExtra(OverlayService.EXTRA_WATERMARK_LOCATION, watermarkLocation)
                    putExtra(OverlayService.EXTRA_WATERMARK_IMAGE_URI, watermarkImageUri)
                    putExtra(OverlayService.EXTRA_WATERMARK_SHAPE, watermarkShape)
                    putExtra(OverlayService.EXTRA_WATERMARK_OPACITY, watermarkOpacity)
                    putExtra(OverlayService.EXTRA_WATERMARK_SIZE, watermarkSize)
                    putExtra(OverlayService.EXTRA_WATERMARK_X_FRACTION, watermarkXFraction)
                    putExtra(OverlayService.EXTRA_WATERMARK_Y_FRACTION, watermarkYFraction)
                    putExtra(OverlayService.EXTRA_KEEP_SCREEN_ON, keepScreenOn)
                }
            startService(overlayIntent)
        }

        val hasAudioPerm =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        Log.d(
            LOG_TAG,
            "actualStartRecording: " +
                "RECORD_AUDIO=${if (hasAudioPerm) "GRANTED" else "DENIED"} " +
                "internalAudio=$internalAudioEnabled audioEnabled=$audioEnabled " +
                "API=${Build.VERSION.SDK_INT} brand=${Build.BRAND} model=${Build.MODEL}",
        )

        if (mediaProjection == null) {
            // Normal path: consume the one-time token from the permission dialog.
            Log.d(LOG_TAG, "Obtaining MediaProjection from token (resultCode=$resultCode)")
            try {
                ensureRecordingForegroundBeforeMediaProjection()
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "getMediaProjection SecurityException (recording)", e)
                FirebaseCrashlytics.getInstance().recordException(e)
                RecordingEngineEventBus.tryEmit(
                    RecordingError.PermissionDenied("media_projection_security_exception"),
                )
                logServiceAnalyticsEvent(
                    "capture_denied_or_unsupported",
                    mapOf(
                        "reason" to "projection_security_exception",
                        "api" to Build.VERSION.SDK_INT.toString(),
                        "brand" to Build.BRAND,
                    ),
                )
                stopRecording()
                return
            }
            Log.d(LOG_TAG, "MediaProjection obtained: $mediaProjection")

            if (mediaProjection != null) {
                FirebaseCrashlytics.getInstance().log("MediaProjection granted (normal path)")
                logServiceAnalyticsEvent(
                    "projection_granted",
                    mapOf(
                        "path" to "normal",
                        "api" to Build.VERSION.SDK_INT.toString(),
                        "brand" to Build.BRAND,
                        "model" to Build.MODEL,
                    ),
                )
            } else {
                Log.e(LOG_TAG, "getMediaProjection returned null — cannot record")
                FirebaseCrashlytics.getInstance().log("MediaProjection null after getMediaProjection")
                RecordingEngineEventBus.tryEmit(RecordingError.PermissionDenied("media_projection_null"))
                logServiceAnalyticsEvent(
                    "capture_denied_or_unsupported",
                    mapOf(
                        "reason" to "projection_null",
                        "api" to Build.VERSION.SDK_INT.toString(),
                        "brand" to Build.BRAND,
                    ),
                )
            }

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    @RequiresApi(34)
                    override fun onCapturedContentResize(
                        width: Int,
                        height: Int,
                    ) {
                        super.onCapturedContentResize(width, height)
                        logCapturedContentResize(width, height, "recording")
                    }

                    override fun onStop() {
                        super.onStop()
                        // The OS revoked the projection — most commonly because the user navigated
                        // away from the captured app in single-app recording mode (Android 14+ / Samsung).
                        if (ignoreExpectedProjectionStopCallback("recording")) {
                            mediaProjection = null
                            return
                        }
                        Log.w(
                            LOG_TAG,
                            "MediaProjection.onStop() fired — projection revoked by OS " +
                                "(API=${Build.VERSION.SDK_INT} brand=${Build.BRAND})",
                        )
                        FirebaseCrashlytics.getInstance().log("MediaProjection.onStop — projection revoked")
                        RecordingEngineEventBus.tryEmit(RecordingError.ProjectionStopped())
                        projectionStoppedRecording = true
                        stopRecording()
                    }
                },
                null,
            )
        } else {
            // Prepared-mode path: mediaProjection is already live from ACTION_PREPARE.
            Log.d(LOG_TAG, "MediaProjection reused from prepared mode: $mediaProjection")
            FirebaseCrashlytics.getInstance().log("MediaProjection reused from prepared mode")
            logServiceAnalyticsEvent(
                "projection_granted",
                mapOf(
                    "path" to "prepared",
                    "api" to Build.VERSION.SDK_INT.toString(),
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                ),
            )
        }

        if (mediaProjection == null) {
            stopRecording()
            return
        }

        calculateDimensions()

        FirebaseCrashlytics.getInstance().log("Recorder started with resolution: $displayWidth x $displayHeight")
        FirebaseCrashlytics.getInstance().log("Using codec: $videoEncoder")

        val pfd = getOutputFileDescriptor()
        if (pfd == null) {
            stopRecording()
            return
        }
        currentPfd = pfd

        // Separate mic file descriptor — only valid when mic is actually enabled
        var micPfd: ParcelFileDescriptor? = null
        if (separateMicRecording && audioEnabled) {
            micPfd = getSeparateMicFileDescriptor()
            separateMicPfd = micPfd
            if (micPfd == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast
                        .makeText(
                            this,
                            getString(R.string.toast_separate_mic_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }

        val mode =
            when {
                internalAudioEnabled && audioEnabled -> ScreenRecorderEngine.AudioMode.MIXED
                internalAudioEnabled -> ScreenRecorderEngine.AudioMode.INTERNAL
                audioEnabled -> ScreenRecorderEngine.AudioMode.MIC
                else -> ScreenRecorderEngine.AudioMode.NONE
            }

        val channelCount = if (audioChannels == "Stereo") 2 else 1

        lifecycleScope.launch(Dispatchers.IO) {
            val adaptiveEnabled = settingsRepository.adaptiveRecordingPerformance.first() && !isGifSession
            if (!adaptiveEnabled) {
                preferAvcNextEnginePrepare.set(false)
            }
            val forceAvc =
                adaptiveEnabled &&
                    preferAvcNextEnginePrepare.compareAndSet(true, false) &&
                    videoEncoder == "H.265 (HEVC)"
            val relayAdaptiveSignalsEnabled =
                adaptiveEnabled && recordingEngineMode == RecordingEngineMode.COMPATIBILITY
            val perfController =
                if (adaptiveEnabled) {
                    RecordingPerformanceController(
                        engineMode = recordingEngineMode,
                        sessionBaselineBitrateBps = bitrate,
                        applyAdaptiveVideoBitrateBps = { bps ->
                            when {
                                recorderEngine != null -> recorderEngine!!.applyAdaptiveVideoBitrateBps(bps)
                                rollingBufferEngine != null -> rollingBufferEngine!!.applyAdaptiveVideoBitrateBps(bps)
                                else -> false
                            }
                        },
                        setRelaySkipModulo = { modulo ->
                            recorderEngine?.setAdaptiveSkipModulo(modulo)
                            rollingBufferEngine?.setAdaptiveSkipModulo(modulo)
                        },
                        onPreferAvc = { preferAvcNextEnginePrepare.set(true) },
                    )
                } else {
                    null
                }
            try {
                screenshotController.releasePendingCaptureResources()
                AudioRecordingCrashlyticsReporter.beginSession(
                    recordingKind = AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                    recordAudioPermissionGranted = hasAudioPerm,
                    requestedAudioModeName = mode.name,
                    userSeparateMicRecording = separateMicRecording,
                    sampleRate = audioSampleRate,
                    userChannelCount = channelCount,
                    audioBitrate = audioBitrate,
                    audioEncoderProfile = audioEncoderType,
                    userMicEnabled = audioEnabled,
                    userInternalCaptureEnabled = internalAudioEnabled,
                    recordingEngineModeName = recordingEngineMode.name,
                )
                val autoMicWhenSilent = settingsRepository.autoMicFallbackWhenInternalSilent.first()
                activeRecordingEngineMode = recordingEngineMode
                recorderEngine =
                    ScreenRecorderEngine(
                        context = this@ScreenRecordService,
                        width = displayWidth,
                        height = displayHeight,
                        dpi = screenDensity,
                        bitrate = bitrate,
                        fps = fps,
                        audioMode = mode,
                        mediaProjection = mediaProjection!!,
                        outputFileDescriptor = pfd.fileDescriptor,
                        encoderType = videoEncoder,
                        colorMode = colorMode,
                        engineMode = recordingEngineMode,
                        audioBitrate = audioBitrate,
                        audioSampleRate = audioSampleRate,
                        audioChannelCount = channelCount,
                        audioEncoderType = audioEncoderType,
                        separateMicFileDescriptor = micPfd?.fileDescriptor,
                        adaptivePreferAvcForPrepare = forceAvc,
                        autoMicFallbackWhenInternalSilent = autoMicWhenSilent,
                        onInternalPlaybackSilenceSessionNotice = { notice ->
                            Handler(Looper.getMainLooper()).post {
                                showInternalSilenceSessionNotice(notice)
                            }
                        },
                        onAudioCaptureDowngraded = { msg ->
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@ScreenRecordService, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        onFatalVideoEncodeError = { detail ->
                            handleFatalVideoEncodeFromEngine("record", detail)
                        },
                    )
                recordingPerformanceController = perfController
                recorderEngine?.start()
                recorderEngine?.attachAdaptivePerformance(
                    perfController,
                    relayAdaptiveSignalsEnabled,
                    perfController?.let { c -> { c.currentTier() } },
                )
                perfController?.startSession()
                withContext(Dispatchers.Main) {
                    isRecorderRunning = true
                    registerCaptureResizeListener()
                    setCaptureSessionDiskFlag(true)
                    RecordingState.setRecording(true)
                    RecordingState.setRecordingPaused(false)
                    accumulatedDurationMs = 0L
                    lastStartTimeMs = SystemClock.elapsedRealtime()
                    durationTimerJob?.cancel()
                    durationTimerJob = lifecycleScope.launch {
                        while (true) {
                            RecordingState.updateDuration(
                                accumulatedDurationMs + SystemClock.elapsedRealtime() - lastStartTimeMs,
                            )
                            delay(500L)
                        }
                    }
                    notifyOverlayRecordingState(isRecording = true)
                    updateRecordingNotification(isPaused = false)
                    if (forceAvc) {
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.performance_fallback_active),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                    gifAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (isGifSession && gifMaxDurationSec > 0) {
                        val r =
                            Runnable {
                                gifAutoStopRunnable = null
                                if (isRecorderRunning) stopRecording()
                            }
                        gifAutoStopRunnable = r
                        mainHandler.postDelayed(r, gifMaxDurationSec * 1000L)
                    }
                }
            } catch (e: Exception) {
                val setupStopped = e is RecorderSetupStoppedException
                if (setupStopped) {
                    Log.i(LOG_TAG, "Recorder start aborted after projection stop during setup")
                } else {
                    Log.e(LOG_TAG, "Recorder start failed", e)
                }
                FirebaseCrashlytics.getInstance().log(AppLogger.dump())
                // If the projection was revoked while the engine was setting up (isStopping is
                // true because stopRecording() already ran on the main thread), the failure is
                // a known race — stopRecording()'s IO cleanup closed currentPfd before
                // prepareMuxer() could use it. Don't surface this as a user-visible non-fatal.
                if (!isStopping && !setupStopped) {
                    val categorizedAudio = AudioRecordingCrashlyticsReporter.tryReportSuspiciousAudioStartupThrowable(e)
                    if (!categorizedAudio) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
                recorderEngine?.attachAdaptivePerformance(null, false, null)
                perfController?.stopSession()
                recordingPerformanceController = null
                withContext(Dispatchers.Main) {
                    if (!isStopping && !setupStopped) {
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_recorder_start_failed, e.message ?: ""),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                    stopRecording()
                }
            }
        }
    }

    /** Applies the centralized encoder capture pipeline to [displayWidth] / [displayHeight]. */
    private fun applyFinalCaptureDimensions() {
        val trace =
            RecordingResolutionSupport.buildCaptureSizingTrace(
                context = this,
                resolutionSetting = resolutionSetting,
                recordingOrientation = recordingOrientationSetting,
                videoEncoder = videoEncoder,
                fps = fps,
            )
        displayWidth = trace.finalEncoder.width
        displayHeight = trace.finalEncoder.height
        lastRecordingIsPortrait = displayHeight >= displayWidth
        RecordingResolutionSupport.logCaptureSizingIfDebug(
            trace = trace,
            deviceModel = Build.MODEL,
            apiLevel = Build.VERSION.SDK_INT,
        )
        if (captureDimensionsFromSessionConfig) {
            val sessionW = intentSessionWidthPx
            val sessionH = intentSessionHeightPx
            if (sessionW > 0 && sessionH > 0 &&
                (sessionW != trace.finalEncoder.width || sessionH != trace.finalEncoder.height)
            ) {
                Log.w(
                    LOG_TAG,
                    "SessionConfig capture size ${sessionW}x$sessionH differs from pipeline " +
                        "${trace.finalEncoder.setting}; using pipeline (session=${sessionW}x$sessionH)",
                )
            }
        }
    }

    /** Last session width/height from [EXTRA_SESSION_CONFIG] for pipeline mismatch logging. */
    private var intentSessionWidthPx: Int = 0
    private var intentSessionHeightPx: Int = 0

    private fun applyOrientationLock() {
        val logical =
            RecordingResolutionSupport.getCurrentLogicalDisplayResolution(
                this,
                recordingOrientationSetting,
            )
        lastRecordingIsPortrait = logical.height >= logical.width
    }

    private fun calculateDimensions() {
        applyFinalCaptureDimensions()
    }

    private fun readSessionConfigExtra(intent: Intent): SessionConfig? =
        runCatching {
            // Prefer the Bundle-of-primitives form written by toBundle() — this path never
            // invokes Parcel.readParcelableCreatorInternal and is immune to the null class-loader
            // NPE seen on certain OEM Android 13 builds.
            intent.getBundleExtra(EXTRA_SESSION_CONFIG)?.toSessionConfig()
                ?: run {
                    // Legacy fallback: older installs may have written a Parcelable.
                    // The class-loader is already primed at the top of onStartCommand.
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_SESSION_CONFIG, SessionConfig::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_SESSION_CONFIG)
                    }
                }
        }
            .onFailure { e ->
                Log.w(LOG_TAG, "readSessionConfigExtra failed: ${e.message}", e)
                val forReporting = e as? Exception ?: Exception(e)
                recordCrashlyticsNonFatal(forReporting, "ScreenRecordService: readSessionConfigExtra")
            }
            .getOrNull()

    /**
     * Merges [EXTRA_SESSION_CONFIG] over intent extras: result code, fps, bitrate, audio flags,
     * and optional frozen capture size. MediaProjection grant [Intent] must come from [EXTRA_DATA]
     * (already read into [resultData]); it is not read from the parcel.
     */
    private fun applySessionConfigFromIntent(intent: Intent): Boolean {
        val session = readSessionConfigExtra(intent) ?: return false
        resultCode = session.mediaProjectionResultCode
        fps = session.frameRate
        bitrate = session.bitrateBitsPerSecond
        val (mic, internal) = session.audioSource.toMicAndInternalFlags()
        audioEnabled = mic
        internalAudioEnabled = internal
        val freeze =
            session.widthPx > SessionConfig.USE_SERVICE_DEFAULT_DIMENSIONS &&
                session.heightPx > SessionConfig.USE_SERVICE_DEFAULT_DIMENSIONS
        captureDimensionsFromSessionConfig = freeze
        intentSessionWidthPx = if (freeze) session.widthPx else 0
        intentSessionHeightPx = if (freeze) session.heightPx else 0
        return true
    }

    /**
     * Returns the current logical display bounds in px (Auto orientation).
     * Used for capture-resize listeners — not panel-native resolution.
     */
    private fun currentDisplaySizePx(): Pair<Int, Int> {
        val size = RecordingResolutionSupport.getCurrentLogicalDisplayResolution(this, "Auto")
        return size.width to size.height
    }

    /**
     * API 34+: OS reports logical captured-content size changes (rotation, fold, etc.).
     * Logs the event and delegates to [triggerCaptureResize] so the capture VirtualDisplay /
     * ImageReader is rebuilt at the new content dimensions — eliminating stale-pixel ghosting.
     * The encoder output resolution stays fixed; letterboxing adapts automatically.
     */
    private fun logCapturedContentResize(
        contentW: Int,
        contentH: Int,
        contextLabel: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.i(
                LOG_TAG,
                "onCapturedContentResize[$contextLabel] capturedContent=${contentW}x${contentH} " +
                    "encoderStable=${displayWidth}x${displayHeight}",
            )
        }
        triggerCaptureResize(contentW, contentH)
    }

    /**
     * Resizes the active engine's capture source (VirtualDisplay + ImageReader) to [newW]×[newH].
     * Called from both the API-34+ [MediaProjection.Callback] and the pre-34 [DisplayManager]
     * listener.  Debouncing is handled inside [EncoderFrameRelay.resizeCaptureSource].
     */
    private fun triggerCaptureResize(newW: Int, newH: Int) {
        captureContentW = newW
        captureContentH = newH
        recorderEngine?.resizeCaptureSource(newW, newH)
        rollingBufferEngine?.resizeCaptureSource(newW, newH)
    }

    /**
     * Registers the pre-API-34 [DisplayManager.DisplayListener] and seeds [captureContentW] /
     * [captureContentH] with the current display size so the first [onDisplayChanged] only fires
     * when the display actually changes (not spuriously on registration).
     */
    private fun registerCaptureResizeListener() {
        if (Build.VERSION.SDK_INT >= 34) return // onCapturedContentResize handles it
        if (captureResizeListenerRegistered) return
        val (w, h) = currentDisplaySizePx()
        captureContentW = w
        captureContentH = h
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(captureResizeDisplayListener, mainHandler)
        captureResizeListenerRegistered = true
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "captureResizeDisplayListener registered seed=${w}x${h}")
        }
    }

    /** Unregisters the pre-API-34 display listener if it was registered. */
    private fun unregisterCaptureResizeListener() {
        if (!captureResizeListenerRegistered) return
        mainHandler.removeCallbacks(applyCaptureResizeAfterDisplayChangeRunnable)
        try {
            val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(captureResizeDisplayListener)
        } catch (_: Exception) {
        }
        captureResizeListenerRegistered = false
        captureContentW = -1
        captureContentH = -1
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "captureResizeDisplayListener unregistered")
        }
    }

    /**
     * [MediaMuxer] requires a seekable file descriptor. URIs from MediaStore/SAF are typically
     * non-seekable pipes, so we mux into a temp file then [MediaStorePublisher.commitTempFileToUri]
     * copies to the gallery.
     */
    private fun getOutputFileDescriptor(): ParcelFileDescriptor? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName =
            when (filenamePattern) {
                "CatRec_Timestamp" -> "CatRec_$timestamp.mp4"
                "Date_Time" -> "Record_$timestamp.mp4"
                else -> "$timestamp.mp4"
            }

        try {
            currentTempRecordingFile?.delete()
        } catch (_: Exception) {
        }
        currentTempRecordingFile = null

        val destUri: Uri? = mediaStorePublisher.createRecordingVideoUri(fileName, saveLocationUri)
        if (destUri != null) {
            currentFileUri = destUri
        }

        if (destUri == null) return null

        return try {
            val dir = File(cacheDir, "rec_mux_tmp").apply { mkdirs() }
            val temp = File.createTempFile("catrec_vid_", ".mp4", dir)
            currentTempRecordingFile = temp
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Temp recording file failed", e)
            try {
                contentResolver.delete(destUri, null, null)
            } catch (_: Exception) {
            }
            currentFileUri = null
            currentTempRecordingFile = null
            null
        }
    }

    private fun getSeparateMicFileDescriptor(): ParcelFileDescriptor? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Mic_$timestamp.m4a"

        try {
            currentTempMicFile?.delete()
        } catch (_: Exception) {
        }
        currentTempMicFile = null
        currentMicDestUri = null

        val destUri: Uri? = mediaStorePublisher.createSeparateMicAudioUri(fileName, saveLocationUri)
        if (destUri != null) {
            currentMicDestUri = destUri
        }

        if (destUri == null) return null

        return try {
            val dir = File(cacheDir, "rec_mux_tmp").apply { mkdirs() }
            val temp = File.createTempFile("catrec_mic_", ".m4a", dir)
            currentTempMicFile = temp
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Temp mic file failed", e)
            try {
                contentResolver.delete(destUri, null, null)
            } catch (_: Exception) {
            }
            currentMicDestUri = null
            currentTempMicFile = null
            null
        }
    }

    private fun showInternalSilenceSessionNotice(notice: InternalPlaybackSilenceSessionNotice) {
        if (!isRecorderRunning) {
            Log.w(LOG_TAG_INTERNAL_SILENCE, "session_notice_suppressed reason=not_recording notice=$notice")
            return
        }
        Log.i(LOG_TAG_INTERNAL_SILENCE, "session_notice notice=$notice")
        val text =
            when (notice) {
                InternalPlaybackSilenceSessionNotice.NO_INTERNAL_AUDIO_CONTINUE_SILENT ->
                    getString(R.string.toast_internal_audio_silent)
                InternalPlaybackSilenceSessionNotice.MIC_FALLBACK_APPLIED ->
                    getString(R.string.toast_internal_audio_using_mic_fallback)
                InternalPlaybackSilenceSessionNotice.MIC_FALLBACK_NEEDS_PERMISSION ->
                    getString(R.string.toast_mic_fallback_needs_permission_recording_silent)
            }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun stopRecording() {
        if (cleanupCompleted.get() && projectionStopExpected.get() && !isRecorderRunning && recorderEngine == null) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "stopRecording skipped: cleanup already completed")
            }
            return
        }
        if (isStopping) return
        isStopping = true

        gifAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        gifAutoStopRunnable = null

        if (!isRecorderRunning && recorderEngine == null) {
            projectionStoppedRecording = false
            AudioRecordingCrashlyticsReporter.notifySessionEnded()
            cleanup()
            return
        }

        // Capture and reset the flag atomically on the main thread before launching the
        // background coroutine, so a second stopRecording() call doesn't re-use it.
        val wasStoppedByProjection = projectionStoppedRecording
        projectionStoppedRecording = false

        isRecorderRunning = false
        durationTimerJob?.cancel()
        durationTimerJob = null
        accumulatedDurationMs = 0L
        RecordingState.updateDuration(0L)
        RecordingState.setRecording(false)
        RecordingState.setRecordingPaused(false)
        RecordingState.setSaving(true)
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "stopRecording: isSaving=true (finalize on IO, then cleanup on Main)")
        }
        notifyOverlayRecordingState(isRecording = false)

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(MAIN_FOREGROUND_NOTIFICATION_ID, buildRecordingNotification(isPaused = false, contentText = getString(R.string.editor_saving)))

        val savedUri = currentFileUri
        val savedMicUri = currentMicDestUri
        val snapshotGifSession = isGifSession
        val snapshotCaptureFps = fps
        val snapshotGifScaleW = gifScaleWidth
        val snapshotGifFps = gifOutputFps
        val snapshotGifMaxColors = gifMaxColors
        val snapshotGifPaletteDitherKind = gifPaletteDither
        // Snapshot at stop time so GIF export uses the same color mode as the encoded MP4.
        val snapshotColorMode = colorMode
        val snapshotForceRec709 = forceRec709Compatibility
        val snapshotRec709BrightnessCorrection = rec709CompatBrightnessCorrection
        isGifSession = false
        gifMaxDurationSec = 0

        lifecycleScope.launch(Dispatchers.IO) {
            recorderEngine?.attachAdaptivePerformance(null, false, null)
            recordingPerformanceController?.stopSession()
            recordingPerformanceController = null
            val engine = recorderEngine
            // Snapshot before and after stop(): a very short recording may write its first video
            // sample during the final drain inside stop().
            var hadOutput =
                try {
                    engine?.hadOutput() ?: false
                } catch (_: Exception) {
                    false
                }
            try {
                engine?.stop()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Engine stop error", e)
            } finally {
                hadOutput =
                    hadOutput ||
                    try {
                        engine?.hadOutput() ?: false
                    } catch (_: Exception) {
                        false
                    }
                recorderEngine = null
            }
            AudioRecordingCrashlyticsReporter.notifySessionEnded()

            try {
                currentPfd?.close()
            } catch (_: Exception) {
            }
            try {
                separateMicPfd?.close()
            } catch (_: Exception) {
            }

            var lastSavedRecordingUriForCleanup: Uri? = null
            var videoCommittedOk = false
            var videoFinalizedOk = false
            if (hadOutput) {
                val tempVid = currentTempRecordingFile
                if (savedUri != null && tempVid != null) {
                    val finalVideoFile =
                        Rec709MetadataRepair.repairIfNeeded(
                            context = this@ScreenRecordService,
                            inputFile = tempVid,
                            colorMode = snapshotColorMode,
                            forceRec709Compatibility = snapshotForceRec709,
                            brightnessCorrection =
                                if (snapshotGifSession) {
                                    Rec709CompatBrightnessCorrection.OFF
                                } else {
                                    snapshotRec709BrightnessCorrection
                                },
                        )
                    val preCommitLen =
                        try {
                            finalVideoFile.length()
                        } catch (_: Exception) {
                            -1L
                        }
                    withContext(NonCancellable) {
                        val dest = savedUri
                        if (preCommitLen <= 0L) {
                            FirebaseCrashlytics.getInstance().log(
                                "recording_commit_video_skipped_empty api=${Build.VERSION.SDK_INT} " +
                                    "tempLen=$preCommitLen uri=${dest.toString().take(120)}",
                            )
                            MediaStorePublishDiagnostics.log(
                                "stop_record_video",
                                "skipped_empty_source api=${Build.VERSION.SDK_INT} tempLen=$preCommitLen",
                            )
                            videoCommittedOk = false
                        } else {
                            videoCommittedOk =
                                mediaStorePublisher.commitTempFileToUri(
                                    finalVideoFile,
                                    dest,
                                    "stop_record_video",
                                )
                        }
                        if (!videoCommittedOk) {
                            val crash = FirebaseCrashlytics.getInstance()
                            crash.log(
                                "recording_commit_video_failed api=${Build.VERSION.SDK_INT} " +
                                    "tempLen=$preCommitLen uri=${dest.toString().take(120)}",
                            )
                            if (preCommitLen > 0L) {
                                crash.recordException(
                                    IllegalStateException(
                                        "commitTempFileToUri failed (video) api=${Build.VERSION.SDK_INT}",
                                    ),
                                )
                            }
                            try {
                                contentResolver.delete(dest, null, null)
                            } catch (_: Exception) {
                            }
                        } else {
                            videoFinalizedOk = mediaStorePublisher.finalizeVideoUri(dest)
                            if (!videoFinalizedOk) {
                                FirebaseCrashlytics.getInstance().apply {
                                    log("recording_finalize_video_failed api=${Build.VERSION.SDK_INT}")
                                    recordException(
                                        IllegalStateException(
                                            "finalizeMediaStoreEntry failed api=${Build.VERSION.SDK_INT}",
                                        ),
                                    )
                                }
                                try {
                                    contentResolver.delete(dest, null, null)
                                } catch (_: Exception) {
                                }
                            } else if (Build.VERSION.SDK_INT >= 29) {
                                val vis =
                                    MediaStorePublishDiagnostics.catRecVideoLikelyVisibleInAppList(
                                        contentResolver,
                                        dest,
                                    )
                                MediaStorePublishDiagnostics.log(
                                    "stop_record_probe",
                                    "appListLike=$vis api=${Build.VERSION.SDK_INT}",
                                )
                            }
                        }
                        if (finalVideoFile != tempVid) {
                            try {
                                finalVideoFile.delete()
                            } catch (_: Exception) {
                            }
                        }
                        try {
                            tempVid.delete()
                        } catch (_: Exception) {
                        }
                    }
                } else if (savedUri != null) {
                    FirebaseCrashlytics.getInstance().log(
                        "recording_publish_anomaly hadOutput=true savedUri set but tempVid=null",
                    )
                    try {
                        contentResolver.delete(savedUri, null, null)
                    } catch (_: Exception) {
                    }
                }
                currentTempRecordingFile = null
                if (savedUri != null && videoCommittedOk && videoFinalizedOk) {
                    lastSavedRecordingUriForCleanup = savedUri
                }

                if (hadOutput && savedUri != null && videoCommittedOk && videoFinalizedOk && snapshotGifSession) {
                    Log.i(
                        LOG_TAG,
                        "GIF export starting: " +
                            "captureFps=$snapshotCaptureFps " +
                            "exportFps=$snapshotGifFps " +
                            "scaleWidth=$snapshotGifScaleW " +
                            "maxColors=$snapshotGifMaxColors " +
                            "dither=${snapshotGifPaletteDitherKind.name}",
                    )
                    val gifOk =
                        GifExportPipeline.transcodeMp4ToGif(
                            this@ScreenRecordService,
                            savedUri,
                            snapshotGifScaleW,
                            snapshotGifFps,
                            maxColors = snapshotGifMaxColors,
                            paletteDither = snapshotGifPaletteDitherKind,
                            colorMode = snapshotColorMode,
                            forceRec709Compatibility = snapshotForceRec709,
                        )
                    if (gifOk) {
                        try {
                            contentResolver.delete(savedUri, null, null)
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "GIF session: could not delete intermediate MP4", e)
                        }
                        lastSavedRecordingUriForCleanup = null
                    }
                    withContext(Dispatchers.Main) {
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(
                                    if (gifOk) R.string.toast_gif_saved else R.string.toast_gif_transcode_failed,
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                }
                if (lastSavedRecordingUriForCleanup != null) {
                    RecordingState.onRecordingSaved()
                }

                val tempMic = currentTempMicFile
                if (savedMicUri != null && tempMic != null) {
                    withContext(NonCancellable) {
                        val micDest = savedMicUri
                        val micOk =
                            mediaStorePublisher.commitTempFileToUri(
                                tempMic,
                                micDest,
                                "stop_record_mic",
                            )
                        if (!micOk) {
                            FirebaseCrashlytics.getInstance().log(
                                "recording_commit_mic_failed api=${Build.VERSION.SDK_INT} " +
                                    "uri=${micDest.toString().take(120)}",
                            )
                            FirebaseCrashlytics.getInstance().recordException(
                                IllegalStateException(
                                    "commitTempFileToUri failed (mic) api=${Build.VERSION.SDK_INT}",
                                ),
                            )
                            try {
                                contentResolver.delete(micDest, null, null)
                            } catch (_: Exception) {
                            }
                        } else {
                            val micFin = mediaStorePublisher.finalizeAudioUri(micDest)
                            if (!micFin) {
                                FirebaseCrashlytics.getInstance().apply {
                                    log("recording_finalize_mic_failed api=${Build.VERSION.SDK_INT}")
                                    recordException(
                                        IllegalStateException(
                                            "finalizeAudioMediaStoreEntry failed api=${Build.VERSION.SDK_INT}",
                                        ),
                                    )
                                }
                                try {
                                    contentResolver.delete(micDest, null, null)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        try {
                            tempMic.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
                currentTempMicFile = null
                currentMicDestUri = null
            } else {
                try {
                    currentTempRecordingFile?.delete()
                } catch (_: Exception) {
                }
                try {
                    currentTempMicFile?.delete()
                } catch (_: Exception) {
                }
                currentTempRecordingFile = null
                currentTempMicFile = null
                // Muxer was never started — the file has no usable content (recording was
                // aborted before any frames were encoded, e.g. projection revoked immediately).
                // Delete the empty MediaStore entry so it doesn't appear as a broken file.
                savedUri?.let {
                    try {
                        contentResolver.delete(it, null, null)
                    } catch (_: Exception) {
                    }
                }
                savedMicUri?.let {
                    try {
                        contentResolver.delete(it, null, null)
                    } catch (_: Exception) {
                    }
                }
                currentMicDestUri = null
            }
            currentFileUri = null

            withContext(Dispatchers.Main) {
                when {
                    wasStoppedByProjection ->
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_recording_stopped_projection),
                                Toast.LENGTH_LONG,
                            ).show()
                    !hadOutput ->
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_recording_failed_no_video),
                                Toast.LENGTH_LONG,
                            ).show()
                    hadOutput && savedUri != null && !videoCommittedOk ->
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_recording_muxer_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                    hadOutput && savedUri != null && videoCommittedOk && !videoFinalizedOk ->
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_recording_save_incomplete),
                                Toast.LENGTH_LONG,
                            ).show()
                }
                cleanup(lastSavedRecordingUri = lastSavedRecordingUriForCleanup)
            }
        }
    }

    // ── Rolling Buffer ─────────────────────────────────────────────────────────

    private fun startBufferClipDurationTimer(nowMs: Long = SystemClock.elapsedRealtime()) {
        bufferSessionStartedAtMs = nowMs
        currentClipTimerStartedAtMs = nowMs
        lastClipSavedAtMs = 0L
        durationTimerJob?.cancel()
        RecordingState.updateDuration(0L)
        durationTimerJob =
            lifecycleScope.launch {
                while (true) {
                    val timerStartMs =
                        currentClipTimerStartedAtMs
                            .takeIf { it > 0L }
                            ?: bufferSessionStartedAtMs
                    RecordingState.updateDuration((SystemClock.elapsedRealtime() - timerStartMs).coerceAtLeast(0L))
                    delay(500L)
                }
            }
    }

    private fun resetBufferClipDurationTimerAfterSuccessfulSave(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!isBufferRunning) return
        currentClipTimerStartedAtMs = nowMs
        lastClipSavedAtMs = nowMs
        RecordingState.updateDuration(0L)
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(
                LOG_TAG,
                "clipper timer reset after successful clip save sessionStarted=$bufferSessionStartedAtMs resetAt=$nowMs",
            )
        }
    }

    private fun clearBufferClipDurationTimerState() {
        bufferSessionStartedAtMs = 0L
        currentClipTimerStartedAtMs = 0L
        lastClipSavedAtMs = 0L
    }

    @RequiresApi(30)
    private fun startBuffer() {
        resetCleanupGuardsForNewCaptureSession()
        isStoppingForCodec.set(false)

        val notification = buildBufferNotification(getString(R.string.notif_buffer_starting))
        val serviceType = mediaProjectionForegroundServiceTypes()

        try {
            ServiceCompat.startForeground(this, BUFFER_NOTIFICATION_ID, notification, serviceType)
            // Foreground slot is now the buffer notification, not [MAIN_FOREGROUND_NOTIFICATION_ID].
            mainForegroundActive = false
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Buffer foreground failed", e)
            stopSelf()
            return
        }

        val notifMgr = getSystemService(NotificationManager::class.java) ?: return

        lifecycleScope.launch {
            if (countdownValue > 0) {
                showCountdownOverlay(countdownValue)
                for (i in countdownValue downTo 1) {
                    updateCountdownOverlayNumber(i)
                    notifMgr.notify(
                        BUFFER_NOTIFICATION_ID,
                        buildBufferNotification(getString(R.string.notif_recording_starting_in, i)),
                    )
                    delay(1000)
                }
                hideCountdownOverlay()
            }
            actualStartBuffer()
        }
    }

    private fun actualStartBuffer() {
        // In prepared mode the MediaProjection is already held — reuse it instead of
        // consuming the one-time token a second time.
        if (mediaProjection == null) {
            try {
                ensureBufferForegroundBeforeMediaProjection()
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "getMediaProjection SecurityException (buffer)", e)
                FirebaseCrashlytics.getInstance().recordException(e)
                RecordingEngineEventBus.tryEmit(
                    RecordingError.PermissionDenied("media_projection_security_exception_buffer"),
                )
                logServiceAnalyticsEvent(
                    "capture_denied_or_unsupported",
                    mapOf(
                        "reason" to "projection_security_exception_buffer",
                        "api" to Build.VERSION.SDK_INT.toString(),
                        "brand" to Build.BRAND,
                    ),
                )
                stopBuffer()
                return
            }
            if (mediaProjection == null) {
                Log.e(LOG_TAG, "getMediaProjection returned null — cannot start buffer")
                RecordingEngineEventBus.tryEmit(RecordingError.PermissionDenied("media_projection_null_buffer"))
                stopBuffer()
                return
            }
            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    @RequiresApi(34)
                    override fun onCapturedContentResize(
                        width: Int,
                        height: Int,
                    ) {
                        super.onCapturedContentResize(width, height)
                        logCapturedContentResize(width, height, "buffer")
                    }

                    override fun onStop() {
                        super.onStop()
                        if (ignoreExpectedProjectionStopCallback("buffer")) {
                            mediaProjection = null
                            return
                        }
                        RecordingEngineEventBus.tryEmit(
                            RecordingError.ProjectionStopped("buffer_media_projection_on_stop"),
                        )
                        stopBuffer()
                    }
                },
                null,
            )
        }

        calculateDimensions()

        val audioMode =
            when {
                internalAudioEnabled && audioEnabled -> RollingBufferEngine.AudioMode.MIXED
                internalAudioEnabled -> RollingBufferEngine.AudioMode.INTERNAL
                audioEnabled -> RollingBufferEngine.AudioMode.MIC
                else -> RollingBufferEngine.AudioMode.NONE
            }
        val channelCount = if (audioChannels == "Stereo") 2 else 1
        val bufferHasAudioPerm =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        lifecycleScope.launch(Dispatchers.IO) {
            val adaptiveEnabled = settingsRepository.adaptiveRecordingPerformance.first()
            if (!adaptiveEnabled) {
                preferAvcNextEnginePrepare.set(false)
            }
            val forceAvc =
                adaptiveEnabled &&
                    preferAvcNextEnginePrepare.compareAndSet(true, false) &&
                    videoEncoder == "H.265 (HEVC)"
            val relayAdaptiveSignalsEnabled =
                adaptiveEnabled && recordingEngineMode == RecordingEngineMode.COMPATIBILITY
            val perfController =
                if (adaptiveEnabled) {
                    RecordingPerformanceController(
                        engineMode = recordingEngineMode,
                        sessionBaselineBitrateBps = bitrate,
                        applyAdaptiveVideoBitrateBps = { bps ->
                            when {
                                recorderEngine != null -> recorderEngine!!.applyAdaptiveVideoBitrateBps(bps)
                                rollingBufferEngine != null -> rollingBufferEngine!!.applyAdaptiveVideoBitrateBps(bps)
                                else -> false
                            }
                        },
                        setRelaySkipModulo = { modulo ->
                            recorderEngine?.setAdaptiveSkipModulo(modulo)
                            rollingBufferEngine?.setAdaptiveSkipModulo(modulo)
                        },
                        onPreferAvc = { preferAvcNextEnginePrepare.set(true) },
                    )
                } else {
                    null
                }
            try {
                screenshotController.releasePendingCaptureResources()
                AudioRecordingCrashlyticsReporter.beginSession(
                    recordingKind = AudioRecordingCrashlyticsReporter.RecordingKind.BUFFER,
                    recordAudioPermissionGranted = bufferHasAudioPerm,
                    requestedAudioModeName = audioMode.name,
                    userSeparateMicRecording = false,
                    sampleRate = audioSampleRate,
                    userChannelCount = channelCount,
                    audioBitrate = audioBitrate,
                    audioEncoderProfile = audioEncoderType,
                    userMicEnabled = audioEnabled,
                    userInternalCaptureEnabled = internalAudioEnabled,
                    recordingEngineModeName = recordingEngineMode.name,
                )
                activeBufferEngineMode = recordingEngineMode
                rollingBufferEngine =
                    RollingBufferEngine(
                        context = this@ScreenRecordService,
                        width = displayWidth,
                        height = displayHeight,
                        dpi = screenDensity,
                        bitrate = bitrate,
                        fps = fps,
                        audioMode = audioMode,
                        mediaProjection = mediaProjection!!,
                        encoderType = videoEncoder,
                        colorMode = colorMode,
                        engineMode = recordingEngineMode,
                        audioBitrate = audioBitrate,
                        audioSampleRate = audioSampleRate,
                        audioChannelCount = channelCount,
                        audioEncoderType = audioEncoderType,
                        maxSegmentsLimit = RollingBufferEngine.maxSegmentsForClipperMinutes(clipperDurationMinutes),
                        adaptivePreferAvcForPrepare = forceAvc,
                        onAudioCaptureDowngraded = { msg ->
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@ScreenRecordService, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        onFatalRecordingError = { fatalKind, detail ->
                            handleFatalRecordingFromEngine("buffer", detail, fatalKind)
                        },
                    )
                recordingPerformanceController = perfController
                rollingBufferEngine?.start()
                rollingBufferEngine?.attachAdaptivePerformance(
                    perfController,
                    relayAdaptiveSignalsEnabled,
                    perfController?.let { c -> { c.currentTier() } },
                )
                perfController?.startSession()
                withContext(Dispatchers.Main) {
                    isBufferRunning = true
                    registerCaptureResizeListener()
                    setCaptureSessionDiskFlag(true)
                    RecordingState.setBuffering(true)
                    startBufferClipDurationTimer()
                    if (forceAvc) {
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.performance_fallback_active),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(BUFFER_NOTIFICATION_ID, buildBufferNotification())
                    startService(
                        Intent(this@ScreenRecordService, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_UPDATE_BUFFERING_STATE
                            putExtra(OverlayService.EXTRA_IS_BUFFERING, true)
                        },
                    )
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Buffer start failed", e)
                val audioCaught = AudioRecordingCrashlyticsReporter.tryReportSuspiciousAudioStartupThrowable(e)
                if (!audioCaught) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
                rollingBufferEngine?.attachAdaptivePerformance(null, false, null)
                perfController?.stopSession()
                recordingPerformanceController = null
                withContext(Dispatchers.Main) {
                    Toast
                        .makeText(
                            this@ScreenRecordService,
                            getString(R.string.toast_buffer_start_failed, e.message ?: ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    stopBuffer()
                }
            }
        }
    }

    private fun stopBuffer() {
        if (cleanupCompleted.get() && projectionStopExpected.get() && !isBufferRunning && rollingBufferEngine == null) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "stopBuffer skipped: cleanup already completed")
            }
            return
        }
        if (!isBufferRunning && rollingBufferEngine == null) {
            AudioRecordingCrashlyticsReporter.notifySessionEnded()
            cleanupBuffer()
            return
        }
        isBufferRunning = false
        durationTimerJob?.cancel()
        durationTimerJob = null
        clearBufferClipDurationTimerState()
        RecordingState.updateDuration(0L)
        RecordingState.setBuffering(false)
        startService(
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_BUFFERING_STATE
                putExtra(OverlayService.EXTRA_IS_BUFFERING, false)
            },
        )

        lifecycleScope.launch(Dispatchers.IO) {
            rollingBufferEngine?.attachAdaptivePerformance(null, false, null)
            recordingPerformanceController?.stopSession()
            recordingPerformanceController = null
            try {
                rollingBufferEngine?.stop()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Buffer engine stop error", e)
            } finally {
                rollingBufferEngine = null
            }
            AudioRecordingCrashlyticsReporter.notifySessionEnded()

            withContext(Dispatchers.Main) { cleanupBuffer() }
        }
    }

    private fun saveClip() {
        if (!isBufferRunning) return
        val snapshotColorMode = colorMode
        val snapshotForceRec709 = forceRec709Compatibility
        val snapshotRec709BrightnessCorrection = rec709CompatBrightnessCorrection
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "Clip_$timestamp.mp4"

                // Write to a temp file first; MediaMuxer (used in ClipMerger) needs a real path.
                val tempFile =
                    File(File(cacheDir, "clips").also { it.mkdirs() }, fileName)

                val ok = rollingBufferEngine?.saveClip(tempFile) ?: false
                if (!ok || !tempFile.exists() || tempFile.length() == 0L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScreenRecordService, getString(R.string.toast_clip_empty), Toast.LENGTH_SHORT).show()
                    }
                    tempFile.delete()
                    return@launch
                }
                val finalClipFile =
                    Rec709MetadataRepair.repairIfNeeded(
                        context = this@ScreenRecordService,
                        inputFile = tempFile,
                        colorMode = snapshotColorMode,
                        forceRec709Compatibility = snapshotForceRec709,
                        brightnessCorrection = snapshotRec709BrightnessCorrection,
                    )
                if (!finalClipFile.exists() || finalClipFile.length() == 0L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScreenRecordService, getString(R.string.toast_clip_empty), Toast.LENGTH_SHORT).show()
                    }
                    if (finalClipFile != tempFile) {
                        try {
                            finalClipFile.delete()
                        } catch (_: Exception) {
                        }
                    }
                    tempFile.delete()
                    return@launch
                }

                val uri =
                    mediaStorePublisher.createClipVideoUri(fileName, saveLocationUri)
                        ?: run {
                            withContext(Dispatchers.Main) {
                                Toast
                                    .makeText(
                                        this@ScreenRecordService,
                                        getString(R.string.toast_clip_save_failed, "insert"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                            if (finalClipFile != tempFile) {
                                try {
                                    finalClipFile.delete()
                                } catch (_: Exception) {
                                }
                            }
                            tempFile.delete()
                            return@launch
                        }

                var publishedOk = false
                withContext(NonCancellable) {
                    val committed = mediaStorePublisher.commitTempFileToUri(finalClipFile, uri, "save_clip")
                    if (!committed) {
                        FirebaseCrashlytics.getInstance().apply {
                            log("save_clip_commit_failed api=${Build.VERSION.SDK_INT}")
                            recordException(
                                IllegalStateException("saveClip commitTempFileToUri failed api=${Build.VERSION.SDK_INT}"),
                            )
                        }
                        try {
                            contentResolver.delete(uri, null, null)
                        } catch (_: Exception) {
                        }
                    } else {
                        val fin = mediaStorePublisher.finalizeVideoUri(uri)
                        if (!fin) {
                            FirebaseCrashlytics.getInstance().apply {
                                log("save_clip_finalize_failed api=${Build.VERSION.SDK_INT}")
                                recordException(
                                    IllegalStateException("saveClip finalize failed api=${Build.VERSION.SDK_INT}"),
                                )
                            }
                            try {
                                contentResolver.delete(uri, null, null)
                            } catch (_: Exception) {
                            }
                        } else {
                            publishedOk = true
                            if (Build.VERSION.SDK_INT >= 29) {
                                MediaStorePublishDiagnostics.logPostPublishVideo(
                                    contentResolver,
                                    uri,
                                    finalClipFile.length(),
                                    Build.VERSION.SDK_INT,
                                )
                            }
                        }
                    }
                    if (finalClipFile != tempFile) {
                        try {
                            finalClipFile.delete()
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        tempFile.delete()
                    } catch (_: Exception) {
                    }
                }

                withContext(Dispatchers.Main) {
                    if (publishedOk) {
                        RecordingState.onRecordingSaved()
                        resetBufferClipDurationTimerAfterSuccessfulSave()
                        Toast.makeText(this@ScreenRecordService, getString(R.string.toast_clip_saved), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast
                            .makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_clip_save_failed, "write"),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "saveClip error", e)
                withContext(Dispatchers.Main) {
                    Toast
                        .makeText(
                            this@ScreenRecordService,
                            getString(R.string.toast_clip_save_failed, e.message ?: ""),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }

    private fun cleanupBuffer() {
        if (cleanupCompleted.get() && projectionStopExpected.get()) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "cleanupBuffer skipped: already completed")
            }
            return
        }
        if (!cleanupInProgress.compareAndSet(false, true)) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "cleanupBuffer skipped: already in progress")
            }
            return
        }
        try {
            unregisterCaptureResizeListener()
            captureDimensionsFromSessionConfig = false
            activeBufferEngineMode = null
            setCaptureSessionDiskFlag(false)
            if (isPrepared) {
                // Keep the service and MediaProjection alive so the overlay can start again.
                mainForegroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).notify(MAIN_FOREGROUND_NOTIFICATION_ID, buildReadyNotification())
            } else {
                stopMediaProjectionExpected("buffer_cleanup_finished")
                mediaProjection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (!isRecorderRunning) stopSelf()
            }
            cleanupCompleted.set(true)
        } finally {
            cleanupInProgress.set(false)
        }
    }

    private fun buildBufferNotification(statusText: String? = null): Notification =
        notificationController.buildBufferNotification(
            clipperDurationMinutes = clipperDurationMinutes,
            statusText = statusText,
        )

    /**
     * Single foreground notification: "saved" affordances + prepared state, so the shade does not
     * stack a second high-priority notification that collapses the recording controls entry.
     */
    private fun buildPreparedNotificationWithSavedRecording(uri: Uri): Notification =
        notificationController.buildPreparedNotificationWithSavedRecording(uri)

    /** Keeps floating overlay in sync when recording is started/stopped from the app or notification. */
    private fun notifyOverlayRecordingState(isRecording: Boolean) {
        if (!OverlayService.idleControlsBubbleVisible) return
        startService(
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_RECORDING_STATE
                putExtra(OverlayService.EXTRA_IS_RECORDING, isRecording)
            },
        )
    }

    // ── Pause / Resume ─────────────────────────────────────────────────────────

    private fun pauseRecording() {
        if (isRecorderRunning) {
            recorderEngine?.pause()
            // Freeze the timer: stop the ticking job and capture elapsed time so far.
            durationTimerJob?.cancel()
            durationTimerJob = null
            accumulatedDurationMs += SystemClock.elapsedRealtime() - lastStartTimeMs
            RecordingState.updateDuration(accumulatedDurationMs)
            isRecordingPaused = true
            RecordingState.setRecordingPaused(true)
            updateRecordingNotification(isPaused = true)
            startService(
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_PAUSE_STATE
                    putExtra(OverlayService.EXTRA_IS_PAUSED, true)
                },
            )
        }
    }

    private fun resumeRecording() {
        if (isRecorderRunning) {
            recorderEngine?.resume()
            // Restart the timer from where the accumulated count left off.
            lastStartTimeMs = SystemClock.elapsedRealtime()
            durationTimerJob?.cancel()
            durationTimerJob = lifecycleScope.launch {
                while (true) {
                    RecordingState.updateDuration(
                        accumulatedDurationMs + SystemClock.elapsedRealtime() - lastStartTimeMs,
                    )
                    delay(500L)
                }
            }
            isRecordingPaused = false
            RecordingState.setRecordingPaused(false)
            updateRecordingNotification(isPaused = false)
            startService(
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_PAUSE_STATE
                    putExtra(OverlayService.EXTRA_IS_PAUSED, false)
                },
            )
        }
    }

    private fun updateRecordingNotification(
        isPaused: Boolean,
        contentText: String? = null,
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(MAIN_FOREGROUND_NOTIFICATION_ID, buildRecordingNotification(isPaused, contentText))
    }

    private fun cleanup(lastSavedRecordingUri: Uri? = null) {
        if (cleanupCompleted.get() && projectionStopExpected.get()) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "cleanup skipped: already completed")
            }
            return
        }
        if (!cleanupInProgress.compareAndSet(false, true)) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "cleanup skipped: already in progress")
            }
            return
        }
        try {
            captureDimensionsFromSessionConfig = false
            activeRecordingEngineMode = null
            setCaptureSessionDiskFlag(false)
            hideCountdownOverlay()
            isRecordingPaused = false
            RecordingState.setRecordingPaused(false)
            isRecordingMuted = false
            controlsDismissedByUser = false
            isStopping = false
            // Clear saving before notification / foreground transitions so UI (AppControlNotification) sees a consistent state.
            RecordingState.setSaving(false)
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(
                    LOG_TAG,
                    "cleanup: isSaving=false prepared=$isPrepared revokeAfterStop=$revokeAfterStop lastSavedUri=$lastSavedRecordingUri",
                )
            }
            unregisterCaptureResizeListener()
            startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_HIDE_OVERLAYS })
            unregisterScreenOffReceiverQuietly()
            sensorManager?.unregisterListener(this)
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
            }

            if (revokeAfterStop) {
                stopMediaProjectionExpected("cleanup_revoke_after_stop")
                mediaProjection = null
                mainForegroundActive = false
                if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                    Log.d(LOG_TAG, "cleanup: stopForeground+stopSelf (revokeAfterStop)")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (isPrepared) {
                // Keep the service and MediaProjection alive so the overlay can start
                // another recording without showing the permission dialog again.
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(POST_NOTIFICATION_ID)
                val notif =
                    if (lastSavedRecordingUri != null) {
                        buildPreparedNotificationWithSavedRecording(lastSavedRecordingUri)
                    } else {
                        buildReadyNotification()
                    }
                if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                    Log.d(
                        LOG_TAG,
                        "cleanup: notify MAIN_FOREGROUND (prepared, foreground slot retained)",
                    )
                }
                nm.notify(MAIN_FOREGROUND_NOTIFICATION_ID, notif)
            } else {
                stopMediaProjectionExpected("cleanup_finished")
                mediaProjection = null
                mainForegroundActive = false
                if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                    Log.d(LOG_TAG, "cleanup: stopForeground+stopSelf (!prepared)")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            cleanupCompleted.set(true)
        } finally {
            cleanupInProgress.set(false)
        }
    }

    // ── Countdown Overlay ──────────────────────────────────────────────────────

    private fun showCountdownOverlay(seconds: Int) {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        val layoutFlag =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )

        val container = FrameLayout(this)
        val dp = resources.displayMetrics.density

        val title =
            TextView(this).apply {
                text = getString(R.string.notif_title_short)
                textSize = 18f
                setTextColor(0xCCFFFFFF.toInt())
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, (6 * dp).toInt())
            }
        val numberView =
            TextView(this).apply {
                text = "$seconds"
                textSize = 96f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        countdownNumberView = numberView
        val subtitle =
            TextView(this).apply {
                text = getString(R.string.countdown_subtitle)
                textSize = 15f
                setTextColor(0xCCFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }

        val circleDiameter = (240 * dp).toInt()
        val inner =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xCC1A1A2E.toInt())
                        setStroke((3 * dp).toInt(), 0xFFD80D0C.toInt())
                    }
                val pad = (24 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                addView(title)
                addView(numberView)
                addView(subtitle)
            }

        container.addView(inner, FrameLayout.LayoutParams(circleDiameter, circleDiameter, Gravity.CENTER))
        countdownOverlayView = container
        try {
            wm.addView(container, params)
            FirebaseCrashlytics.getInstance().log("Overlay: countdown overlay added")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Countdown overlay add failed", e)
            recordCrashlyticsNonFatal(e, "ScreenRecordService: countdown overlay add failed")
        }
    }

    private fun updateCountdownOverlayNumber(n: Int) {
        countdownNumberView?.let { tv ->
            tv.text = "$n"
            tv.animate().cancel()
            tv.scaleX = 1.3f
            tv.scaleY = 1.3f
            tv
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .start()
        }
    }

    private fun hideCountdownOverlay() {
        countdownOverlayView?.let { v ->
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (_: Exception) {
            }
            countdownOverlayView = null
            countdownNumberView = null
        }
    }

    // ── Stop Behaviors ─────────────────────────────────────────────────────────

    private fun setupStopBehaviors() {
        if (stopBehaviors?.contains(StopBehaviorKeys.SCREEN_OFF) == true ||
            stopBehaviors?.contains(StopBehaviorKeys.PAUSE_ON_SCREEN_OFF) == true
        ) {
            if (screenOffReceiverRegistered.compareAndSet(false, true)) {
                try {
                    ContextCompat.registerReceiver(
                        this,
                        screenOffReceiver,
                        IntentFilter(Intent.ACTION_SCREEN_OFF),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                } catch (e: Exception) {
                    screenOffReceiverRegistered.set(false)
                    Log.w(LOG_TAG, "screenOffReceiver register failed: ${e.message}")
                }
            }
        }
        if (stopBehaviors?.contains(StopBehaviorKeys.SHAKE) == true) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun handleScreenOff() {
        if (stopBehaviors?.contains(StopBehaviorKeys.PAUSE_ON_SCREEN_OFF) == true) {
            pauseRecording()
        } else {
            stopRecording()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH
            val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
            if (gForce > 2.5f) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 1000) {
                    lastShakeTime = now
                    stopRecording()
                }
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {}

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        notificationController.createNotificationChannels()
    }

    private fun buildRecordingNotification(
        isPaused: Boolean,
        contentText: String? = null,
    ): Notification =
        notificationController.buildRecordingNotification(
            isPaused = isPaused,
            isRecordingMuted = isRecordingMuted,
            showFloatingControls = showFloatingControls,
            controlsDismissedByUser = controlsDismissedByUser,
            floatingOn = floatingOnForNotification(),
            overlayVisible = OverlayService.idleControlsBubbleVisible,
            contentText = contentText,
        )

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
