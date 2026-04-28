package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.GifPaletteDither
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineEventBus
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingError
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingFatalKind
import com.ibbie.catrec_screenrecorcer.data.recording.SessionConfig
import com.ibbie.catrec_screenrecorcer.data.recording.toMicAndInternalFlags
import com.ibbie.catrec_screenrecorcer.utils.AppLogger
import com.ibbie.catrec_screenrecorcer.utils.recordCrashlyticsNonFatal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.core.graphics.toColorInt
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

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

        /** [RemoteViews.setInt] on [R.id.btn_record_icon] while recording — live accent (#FF8C00). */
        private val NOTIF_RECORD_LIVE_TINT: Int = "#FF8C00".toColorInt()

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

        private const val CHANNEL_ID = "CatRec_Recording_Channel"
        private const val CHANNEL_DONE_ID = "CatRec_Done_Channel"
        private const val CHANNEL_BUFFER_ID = "CatRec_Buffer_Channel"
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

    /**
     * [takeScreenshot] without a running engine uses a dedicated [VirtualDisplay]. From Android 14
     * onward the system rejects a second [MediaProjection.createVirtualDisplay] while one is still
     * active — always release this before starting [EncoderFrameRelay].
     *
     * Locking: ALL reads and writes of this field MUST be performed while holding
     * [screenshotVirtualDisplayLock]. The lock is the sole gate for the one-shot capture
     * producer/consumer pair; `@Volatile` is intentionally NOT used so no caller is tempted to
     * rely on lock-free visibility — the lock provides both mutual exclusion and happens-before.
     */
    private var pendingScreenshotVirtualDisplay: VirtualDisplay? = null

    /**
     * Consumer paired with [pendingScreenshotVirtualDisplay]. Tracked at service scope so every
     * teardown path (listener, [MediaProjection.Callback.onStop], error branches) can explicitly
     * close it in the required order (VirtualDisplay → ImageReader → MediaProjection.stop).
     * Relying solely on the capture listener to close the reader was fragile — if the projection
     * was revoked before a frame arrived, the reader would leak. Owner: this service.
     *
     * Locking: shares [screenshotVirtualDisplayLock] with [pendingScreenshotVirtualDisplay]. The
     * reader and its producer VD must never be observed in inconsistent states, so both fields
     * are guarded by the same lock and written together when published/cleared.
     */
    private var pendingScreenshotImageReader: ImageReader? = null

    /**
     * Sole lock guarding [pendingScreenshotVirtualDisplay] and [pendingScreenshotImageReader].
     * Held for every read and write of either field so callers always observe them as an
     * atomic pair. Kept intentionally narrow — it does NOT cover [mediaProjection] or any
     * other service state.
     */
    private val screenshotVirtualDisplayLock = Any()

    /** Read from encoder threads (fatal callbacks); writes occur on main during buffer start/stop. */
    @Volatile
    private var isBufferRunning = false

    /** Coroutine that publishes elapsed recording time to [RecordingState.recordingDuration] every 500 ms. */
    private var durationTimerJob: Job? = null

    /** Duration accumulated across all non-paused recording segments (milliseconds). */
    private var accumulatedDurationMs: Long = 0L

    /** Wall-clock time of the most recent recording/resume start (milliseconds, elapsedRealtime). */
    private var lastStartTimeMs: Long = 0L

    /**
     * Set by [ACTION_TAKE_SCREENSHOT_ONE_SHOT] when the current [mediaProjection] was created
     * exclusively for a single standalone screenshot. Every teardown path that runs after the
     * capture must call [releaseOneShotScreenshotProjectionIfNeeded] so the projection is
     * stopped, the permission grant is dropped, and (when no other session is active) the
     * service exits the foreground and calls [stopSelf].
     */
    @Volatile
    private var oneShotScreenshotProjection: Boolean = false

    /**
     * Single-flight guard for [releaseOneShotScreenshotProjectionIfNeeded]. Prevents the
     * [MediaProjection.Callback.onStop] path and the synchronous teardown path from both
     * running the release sequence when they race (Android 14+ may deliver `onStop`
     * immediately after [MediaProjection.stop]).
     */
    private val releasingOneShotProjection = AtomicBoolean(false)

    /**
     * True after foreground started with [Companion.MAIN_FOREGROUND_NOTIFICATION_ID] until removed.
     */
    private var mainForegroundActive = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var screenDensity: Int = 0
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 2400

    /**
     * When [SessionConfig] supplies non-zero width/height, skip [calculateDimensions] / orientation
     * resizing so capture matches the repository-computed size (API 35–safe hand-off from UI).
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

    // Tells the service to revoke projection after the next stop completes
    private var revokeAfterStop = false

    /** Read from encoder threads (fatal callbacks); writes occur on main during record start/stop. */
    @Volatile
    private var isRecorderRunning = false
    private val isStoppingForCodec = AtomicBoolean(false)
    private var isStopping = false
    private var recordingPerformanceController: RecordingPerformanceController? = null
    private val preferAvcNextEnginePrepare = AtomicBoolean(false)
    private var isRecordingPaused = false
    private var isRecordingMuted = false
    private var controlsDismissedByUser = false

    // Set by the MediaProjection.Callback when the OS revokes the projection (e.g. the user
    // switched away from the captured app in single-app recording mode on Android 14+ / Samsung).
    private var projectionStoppedRecording = false

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
                    try {
                        startRecording()
                    } catch (e: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
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
                    mediaProjection?.stop()
                    mediaProjection = null
                    try {
                        unregisterReceiver(screenOffReceiver)
                    } catch (_: Exception) {
                    }
                    mainForegroundActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_STOP -> stopRecording()
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
                    resolutionSetting = intent.getStringExtra(EXTRA_RESOLUTION) ?: "Native"
                    videoEncoder = intent.getStringExtra(EXTRA_VIDEO_ENCODER) ?: "H.264"
                    clipperDurationMinutes = intent.getIntExtra(EXTRA_CLIPPER_DURATION_MINUTES, 1).coerceIn(1, 5)
                    countdownValue = intent.getIntExtra(EXTRA_COUNTDOWN, 0).coerceIn(0, 60)
                    applySessionConfigFromIntent(intent)
                    if (resultCode != 0 && resultData != null) startBuffer()
                }
            }
            ACTION_STOP_BUFFER -> stopBuffer()
            ACTION_SAVE_CLIP -> saveClip()

            ACTION_PREPARE -> {
                if (isPrepared || isRecorderRunning || isBufferRunning) return START_STICKY
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }
                if (resultCode == 0 || resultData == null) return START_STICKY
                intent.getStringExtra(EXTRA_SCREENSHOT_FORMAT)?.let { screenshotFormat = it }
                val q = intent.getIntExtra(EXTRA_SCREENSHOT_QUALITY, -1)
                if (q >= 0) screenshotQuality = q.coerceIn(1, 100)
                startPreparedForeground()
            }

            ACTION_START_FROM_OVERLAY -> {
                if (!isPrepared || isRecorderRunning) return START_STICKY
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        applyOverlayRecordingRepoSnapshotToService()
                        withContext(Dispatchers.Main) {
                            if (!isPrepared) {
                                Log.w(
                                    LOG_TAG,
                                    "stale_overlay_start_ignored recording prepared=false",
                                )
                                return@withContext
                            }
                            if (isRecorderRunning) {
                                Log.w(
                                    LOG_TAG,
                                    "stale_overlay_start_ignored recording rec=true",
                                )
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
                if (!isPrepared || isBufferRunning || isRecorderRunning) return START_STICKY
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        applyOverlayBufferRepoSnapshotToService()
                        withContext(Dispatchers.Main) {
                            if (!isPrepared || isBufferRunning || isRecorderRunning) {
                                Log.w(
                                    LOG_TAG,
                                    "stale_overlay_start_ignored buffer prepared=$isPrepared buf=$isBufferRunning rec=$isRecorderRunning",
                                )
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
                if (isRecorderRunning || isBufferRunning) return START_STICKY
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val rd =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }
                if (rc == 0 || rd == null) return START_STICKY
                val asBuffer = intent.getBooleanExtra(EXTRA_OVERLAY_SESSION_AS_BUFFER, false)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (asBuffer) {
                            applyOverlayBufferRepoSnapshotToService()
                        } else {
                            applyOverlayRecordingRepoSnapshotToService()
                        }
                        withContext(Dispatchers.Main) {
                            if (isRecorderRunning || isBufferRunning) return@withContext
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
                    mediaProjection?.stop()
                    mediaProjection = null
                    try {
                        unregisterReceiver(screenOffReceiver)
                    } catch (_: Exception) {
                    }
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
                when {
                    recorderEngine != null || rollingBufferEngine != null || mediaProjection != null ->
                        takeScreenshot()
                    else -> {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                startActivity(
                                    Intent(this, OverlayScreenshotProjectionActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            } catch (_: Exception) {
                                val launch =
                                    packageManager.getLaunchIntentForPackage(packageName)?.apply {
                                        putExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT_PROJECTION, true)
                                        addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                        )
                                    }
                                if (launch != null) startActivity(launch)
                            }
                        }
                    }
                }
            }

            ACTION_TAKE_SCREENSHOT_ONE_SHOT -> {
                handleOneShotScreenshotStart(intent)
            }
        }
        return START_STICKY
    }

    /**
     * Handle a single-use screenshot launched from a transparent consent activity. The service
     * must be in the foreground with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
     * before [MediaProjectionManager.getMediaProjection] is called on Android 14+. When any
     * engine or already-active projection exists, route through the normal [takeScreenshot]
     * path and drop the freshly granted token — the existing session's projection is reused.
     */
    @RequiresApi(30)
    private fun handleOneShotScreenshotStart(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
        intent.getStringExtra(EXTRA_SCREENSHOT_FORMAT)?.let { screenshotFormat = it }
        val q = intent.getIntExtra(EXTRA_SCREENSHOT_QUALITY, -1)
        if (q >= 0) screenshotQuality = q.coerceIn(1, 100)

        if (code == 0 || data == null) {
            Log.w(LOG_TAG, "one_shot_screenshot missing projection result")
            stopOneShotForegroundAndSelfIfIdle()
            return
        }

        if (recorderEngine != null || rollingBufferEngine != null || mediaProjection != null) {
            Log.d(LOG_TAG, "one_shot_screenshot reusing active session projection")
            takeScreenshot()
            return
        }

        try {
            ServiceCompat.startForeground(
                this,
                MAIN_FOREGROUND_NOTIFICATION_ID,
                buildReadyNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            mainForegroundActive = true
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            Log.e(LOG_TAG, "one_shot_screenshot startForeground failed", e)
            stopOneShotForegroundAndSelfIfIdle()
            return
        }

        val projection =
            try {
                mediaProjectionManager?.getMediaProjection(code, data)
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                null
            }
        if (projection == null) {
            Log.w(LOG_TAG, "one_shot_screenshot getMediaProjection returned null")
            stopOneShotForegroundAndSelfIfIdle()
            return
        }

        resultCode = code
        resultData = data
        mediaProjection = projection
        oneShotScreenshotProjection = true
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    mainHandler.post {
                        // System revoked the projection (user consent flow, security policy,
                        // single-app mode switch, etc.). If we already detached as part of
                        // [releaseOneShotScreenshotProjectionIfNeeded] the guard is a no-op.
                        if (mediaProjection === projection) {
                            mediaProjection = null
                        }
                        if (oneShotScreenshotProjection &&
                            releasingOneShotProjection.compareAndSet(false, true)
                        ) {
                            try {
                                oneShotScreenshotProjection = false
                                // Strict teardown order (VD → ImageReader → MediaProjection → null):
                                // the projection is already being stopped by the OS here, but we
                                // still release producer + consumer explicitly so a pending capture
                                // cannot keep either alive past onStop. Idempotent with the
                                // capture listener's own cleanup.
                                releasePendingScreenshotVirtualDisplay()
                                releasePendingScreenshotImageReader()
                                resultCode = 0
                                resultData = null
                                stopOneShotForegroundAndSelfIfIdle()
                            } finally {
                                releasingOneShotProjection.set(false)
                            }
                        }
                    }
                }
            },
            mainHandler,
        )

        takeScreenshot()
    }

    /**
     * Stop the projection acquired exclusively for [ACTION_TAKE_SCREENSHOT_ONE_SHOT] and, when
     * no other session is active, drop the foreground notification and stop the service so the
     * user must reconsent for the next standalone screenshot.
     *
     * Idempotent and non-reentrant — guarded by [releasingOneShotProjection] so the
     * capture-listener teardown and the [MediaProjection.Callback.onStop] handler cannot
     * both run the release sequence concurrently.
     *
     * Cleanup order (strict — matches [MediaProjection.Callback.onStop] and
     * [teardownScreenshotReader] so no branch can double-release or reorder the steps):
     *   1) [VirtualDisplay.release] (producer stops frame delivery)
     *   2) [ImageReader.close]      (consumer — explicit, no longer relying on listener drain)
     *   3) [MediaProjection.stop]   (revokes the grant)
     *   4) null out references      (mediaProjection, resultCode, resultData)
     *   5) drop foreground + stopSelf if idle
     * Idempotent: the reader / VD helpers are safe to call repeatedly.
     */
    private fun releaseOneShotScreenshotProjectionIfNeeded() {
        if (!oneShotScreenshotProjection) return
        if (!releasingOneShotProjection.compareAndSet(false, true)) return
        try {
            oneShotScreenshotProjection = false
            // 1) producer first (stops frame delivery)
            releasePendingScreenshotVirtualDisplay()
            // 2) consumer — must run before [MediaProjection.stop] so the reader never outlives
            //    its producer and projection. No-op if the capture listener already closed it.
            releasePendingScreenshotImageReader()
            // 3) then projection itself — this may synchronously fire onStop, which is now a no-op
            //    because we already flipped [oneShotScreenshotProjection] to false and hold the flag.
            try {
                mediaProjection?.stop()
            } catch (_: Exception) {
            }
            // 4) null out references
            mediaProjection = null
            resultCode = 0
            resultData = null
            // 5) foreground + stopSelf if no other session keeps us alive
            stopOneShotForegroundAndSelfIfIdle()
        } finally {
            releasingOneShotProjection.set(false)
        }
    }

    private fun stopOneShotForegroundAndSelfIfIdle() {
        if (isRecorderRunning || isBufferRunning || isPrepared) return
        if (mainForegroundActive) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            mainForegroundActive = false
        }
        try {
            stopSelf()
        } catch (_: Exception) {
        }
        // The one-shot path calls buildReadyNotification() (which cancels AppControlNotification)
        // and then drops the foreground without anyone re-posting the idle notification.
        AppControlNotification.refresh(this)
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
                override fun onStop() {
                    super.onStop()
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

    private fun bindShadeOverlayExit(
        collapsed: RemoteViews,
        expanded: RemoteViews,
        floatingOn: Boolean,
        overlayVisible: Boolean,
        overlayPi: PendingIntent,
        exitPi: PendingIntent,
    ) {
        val overlayDesc =
            when {
                !floatingOn -> getString(R.string.notif_action_overlay_enable_in_settings)
                overlayVisible -> getString(R.string.notif_action_overlay_hide)
                else -> getString(R.string.notif_action_overlay_show)
            }
        val alpha = if (floatingOn) 255 else 100
        collapsed.setContentDescription(R.id.notif_ac_overlay, overlayDesc)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_overlay, overlayPi)
        collapsed.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", alpha)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_exit, exitPi)
        expanded.setContentDescription(R.id.notif_ac_overlay, overlayDesc)
        expanded.setOnClickPendingIntent(R.id.notif_ac_overlay, overlayPi)
        expanded.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", alpha)
        expanded.setOnClickPendingIntent(R.id.notif_ac_exit, exitPi)
    }

    /**
     * Shade second column + record tint: [isRecording] true → stop + [STOP] + stop PI + red record icon;
     * false → screenshot + [SHOT] + screenshot PI + clear record color filter.
     */
    private fun updateNotificationState(
        isRecording: Boolean,
        collapsed: RemoteViews,
        expanded: RemoteViews,
    ) {
        val stopPi =
            PendingIntent.getService(
                this,
                100,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val screenshotPi =
            PendingIntent.getActivity(
                this,
                11,
                Intent(this, ScreenshotAfterShadeActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    )
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val views = arrayOf(collapsed, expanded)
        if (isRecording) {
            val stopLabel = getString(R.string.notif_action_stop)
            for (rv in views) {
                rv.setImageViewResource(R.id.btn_screenshot_icon, R.drawable.ic_stop)
                rv.setTextViewText(R.id.btn_screenshot_label, getString(R.string.notif_cc_stop))
                rv.setContentDescription(R.id.btn_screenshot, stopLabel)
                rv.setOnClickPendingIntent(R.id.btn_screenshot, stopPi)
                rv.setInt(R.id.btn_record_icon, "setColorFilter", NOTIF_RECORD_LIVE_TINT)
            }
        } else {
            val shotDesc = getString(R.string.notif_action_screenshot)
            for (rv in views) {
                rv.setImageViewResource(R.id.btn_screenshot_icon, R.drawable.ic_screenshot)
                rv.setTextViewText(R.id.btn_screenshot_label, getString(R.string.notif_cc_shot))
                rv.setContentDescription(R.id.btn_screenshot, shotDesc)
                rv.setOnClickPendingIntent(R.id.btn_screenshot, screenshotPi)
                rv.setInt(R.id.btn_record_icon, "setColorFilter", 0)
            }
        }
    }

    private fun buildReadyNotification(): Notification {
        AppControlNotification.cancel(this)
        val floatingOn = floatingOnForNotification()
        val overlayVisible = OverlayService.idleControlsBubbleVisible

        val tapPI =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val homePi =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val exitPi =
            PendingIntent.getBroadcast(
                this,
                2,
                Intent(this, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_EXIT_APP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val recordPi =
            PendingIntent.getBroadcast(
                this,
                10,
                Intent(this, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_RECORD_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val overlayPi =
            PendingIntent.getBroadcast(
                this,
                12,
                Intent(this, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_OVERLAY_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val collapsed = RemoteViews(packageName, R.layout.notification_app_controls_collapsed)
        val expanded = RemoteViews(packageName, R.layout.notification_app_controls_expanded)

        val startLabel = getString(R.string.notif_cc_record)
        collapsed.setImageViewResource(R.id.btn_record_icon, R.drawable.ic_record)
        collapsed.setTextViewText(R.id.btn_record_label, startLabel)
        collapsed.setContentDescription(R.id.btn_record, getString(R.string.recording_start))
        collapsed.setOnClickPendingIntent(R.id.btn_record, recordPi)
        expanded.setImageViewResource(R.id.btn_record_icon, R.drawable.ic_record)
        expanded.setTextViewText(R.id.btn_record_label, startLabel)
        expanded.setContentDescription(R.id.btn_record, getString(R.string.recording_start))
        expanded.setOnClickPendingIntent(R.id.btn_record, recordPi)

        updateNotificationState(isRecording = false, collapsed, expanded)

        bindShadeOverlayExit(collapsed, expanded, floatingOn, overlayVisible, overlayPi, exitPi)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_home, homePi)
        expanded.setOnClickPendingIntent(R.id.notif_ac_home, homePi)

        // One close control: row uses [Exit]; hide duplicate revoke (same icon) and spare row.
        expanded.setViewVisibility(R.id.notif_ac_revoke, View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_mute, View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_show_controls, View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_row_secondary, View.GONE)

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_ready_title))
            .setContentText(getString(R.string.notif_ready_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPI)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .build()
    }

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
            val resVal = repo.resolution.first()
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
                resolutionSetting = resVal
                clipperDurationMinutes = clipMin
                countdownValue = cd.coerceIn(0, 60)
            }
        }
    }

    @RequiresApi(30)
    @SuppressLint("WakelockTimeout")
    private fun startRecording() {
        isStoppingForCodec.set(false)
        val hasAudioPermission =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        val notification = buildRecordingNotification(isPaused = false, contentText = getString(R.string.notif_preparing))
        val serviceType = run {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // Match rolling-buffer logic: mic capture needs RECORD_AUDIO; internal capture is gated the same way.
            if (hasAudioPermission && (audioEnabled || internalAudioEnabled)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            type
        }

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
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
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
                    override fun onStop() {
                        super.onStop()
                        // The OS revoked the projection — most commonly because the user navigated
                        // away from the captured app in single-app recording mode (Android 14+ / Samsung).
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
            val perfController =
                if (adaptiveEnabled) {
                    RecordingPerformanceController(
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
                releasePendingScreenshotVirtualDisplay()
                releasePendingScreenshotImageReader()
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
                        audioBitrate = audioBitrate,
                        audioSampleRate = audioSampleRate,
                        audioChannelCount = channelCount,
                        audioEncoderType = audioEncoderType,
                        separateMicFileDescriptor = micPfd?.fileDescriptor,
                        adaptivePreferAvcForPrepare = forceAvc,
                        onInternalAudioSilence = {
                            // Fired on the audio-capture thread after SILENCE_TIMEOUT_MS of all-zero
                            // PCM from internal audio. Show a clear UI message and log for diagnostics.
                            Handler(Looper.getMainLooper()).post {
                                Toast
                                    .makeText(
                                        this@ScreenRecordService,
                                        getString(R.string.toast_internal_audio_silent),
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                            Log.w(
                                LOG_TAG,
                                "Internal audio silence detected — " +
                                    "brand=${Build.BRAND} model=${Build.MODEL} API=${Build.VERSION.SDK_INT}. " +
                                    "The captured app may block playback capture (capture policy ALLOW_CAPTURE_BY_NONE) " +
                                    "or this OEM restricts AudioPlaybackCapture on Android ${Build.VERSION.SDK_INT}.",
                            )
                            FirebaseCrashlytics.getInstance().log(
                                "Internal audio silence: ${Build.BRAND} ${Build.MODEL} API ${Build.VERSION.SDK_INT}",
                            )
                        },
                        onFatalVideoEncodeError = { detail ->
                            handleFatalVideoEncodeFromEngine("record", detail)
                        },
                    )
                recordingPerformanceController = perfController
                recorderEngine?.start()
                recorderEngine?.attachAdaptivePerformance(perfController, adaptiveEnabled)
                perfController?.startSession()
                withContext(Dispatchers.Main) {
                    isRecorderRunning = true
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
                Log.e(LOG_TAG, "Recorder start failed", e)
                FirebaseCrashlytics.getInstance().log(AppLogger.dump())
                FirebaseCrashlytics.getInstance().recordException(e)
                recorderEngine?.attachAdaptivePerformance(null, false)
                perfController?.stopSession()
                recordingPerformanceController = null
                withContext(Dispatchers.Main) {
                    Toast
                        .makeText(
                            this@ScreenRecordService,
                            getString(R.string.toast_recorder_start_failed, e.message ?: ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    stopRecording()
                }
            }
        }
    }

    /** Ceil to a multiple of 16 — same policy as [VideoEncoderDimensionMath] / [VideoEncoderConfigurator]. */
    private fun alignCaptureDimCeil16(value: Int): Int = VideoEncoderDimensionMath.alignCeil16(value)

    private fun applyOrientationLock() {
        if (captureDimensionsFromSessionConfig) {
            lastRecordingIsPortrait = displayHeight >= displayWidth
            return
        }
        val (nativeW, nativeH) = currentDisplaySizePx()

        when (recordingOrientationSetting) {
            "Portrait" -> {
                displayWidth = minOf(nativeW, nativeH)
                displayHeight = maxOf(nativeW, nativeH)
            }
            "Landscape" -> {
                displayWidth = maxOf(nativeW, nativeH)
                displayHeight = minOf(nativeW, nativeH)
            }
            else -> {
                // Auto — use current screen orientation
                displayWidth = nativeW
                displayHeight = nativeH
            }
        }
        lastRecordingIsPortrait = displayHeight >= displayWidth
    }

    private fun calculateDimensions() {
        if (captureDimensionsFromSessionConfig) return
        val (nativeWidth, nativeHeight) = currentDisplaySizePx()

        // Respect orientation lock
        val (baseW, baseH) =
            when (recordingOrientationSetting) {
                "Portrait" -> Pair(minOf(nativeWidth, nativeHeight), maxOf(nativeWidth, nativeHeight))
                "Landscape" -> Pair(maxOf(nativeWidth, nativeHeight), minOf(nativeWidth, nativeHeight))
                else -> Pair(nativeWidth, nativeHeight) // Auto uses current at start-time
            }

        val aspectRatio = baseW.toFloat() / baseH.toFloat()

        when (resolutionSetting) {
            "Native" -> {
                displayWidth = (baseW / 16) * 16
                displayHeight = (baseH / 16) * 16
            }
            else -> {
                if (resolutionSetting.contains("x")) {
                    // Custom resolution e.g. "1920x1080"
                    val parts = resolutionSetting.split("x")
                    val w = parts.getOrNull(0)?.toIntOrNull() ?: baseW
                    val h = parts.getOrNull(1)?.toIntOrNull() ?: baseH
                    displayWidth = (w / 16) * 16
                    displayHeight = (h / 16) * 16
                } else {
                    val targetHeight =
                        when {
                            resolutionSetting.contains("2160") || resolutionSetting.contains("4K") -> 2160
                            resolutionSetting.contains("1440") || resolutionSetting.contains("2K") -> 1440
                            resolutionSetting.contains("1080") -> 1080
                            resolutionSetting.contains("720") -> 720
                            resolutionSetting.contains("480") -> 480
                            resolutionSetting.contains("360") -> 360
                            else -> baseH
                        }
                    val targetWidth = (targetHeight * aspectRatio).roundToInt()
                    displayWidth = (targetWidth / 16) * 16
                    displayHeight = (targetHeight / 16) * 16
                }
            }
        }
    }

    private fun readSessionConfigExtra(intent: Intent): SessionConfig? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_SESSION_CONFIG, SessionConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_SESSION_CONFIG)
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
        if (freeze) {
            displayWidth = alignCaptureDimCeil16(session.widthPx)
            displayHeight = alignCaptureDimCeil16(session.heightPx)
        }
        return true
    }

    /**
     * Returns the current real screen bounds in pixels.
     * `resources.displayMetrics` can be stale in a long-lived Service across rotations.
     */
    private fun currentDisplaySizePx(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * [MediaMuxer] requires a seekable file descriptor. URIs from MediaStore/SAF are typically
     * non-seekable pipes, so we mux into a temp file then [commitTempFileToUri] copies to the gallery.
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

        val saveUri = saveLocationUri
        val destUri: Uri? =
            if (!saveUri.isNullOrEmpty()) {
                try {
                    val treeUri = saveUri.toUri()
                    val docFile = DocumentFile.fromTreeUri(this, treeUri)
                    val file = docFile?.createFile("video/mp4", fileName)
                    if (file != null) {
                        currentFileUri = file.uri
                        file.uri
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error with SAF location", e)
                    null
                }
            } else {
                try {
                    val contentValues =
                        ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            if (Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "CatRec")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        currentFileUri = uri
                        uri
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "MediaStore insert failed", e)
                    null
                }
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

        val saveUri = saveLocationUri
        val destUri: Uri? =
            if (!saveUri.isNullOrEmpty()) {
                try {
                    val treeUri = saveUri.toUri()
                    val docFile = DocumentFile.fromTreeUri(this, treeUri)
                    val file = docFile?.createFile("audio/mp4", fileName)
                    if (file != null) {
                        currentMicDestUri = file.uri
                        file.uri
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "SAF mic file error", e)
                    null
                }
            } else {
                // Audio MediaStore only allows certain primary directories (Alarms, Music, Recordings,
                // Ringtones, etc.). Movies/ throws IllegalArgumentException on Android 10+.
                // We try Music/CatRec first, then Recordings/CatRec (API 31+) as an alternative.
                fun buildAudioContentValues(relPath: String?): ContentValues =
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        if (Build.VERSION.SDK_INT >= 29 && relPath != null) {
                            put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                    }
                try {
                    val musicPath =
                        if (Build.VERSION.SDK_INT >= 29) {
                            Environment.DIRECTORY_MUSIC + File.separator + "CatRec"
                        } else {
                            null
                        }

                    var uri: Uri? = null
                    try {
                        uri =
                            contentResolver.insert(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                buildAudioContentValues(musicPath),
                            )
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Music/CatRec mic insert failed, trying Recordings/CatRec", e)
                    }

                    // Fallback to Recordings/CatRec (API 31+, explicitly allowed by AOSP)
                    if (uri == null && Build.VERSION.SDK_INT >= 31) {
                        try {
                            val recPath = Environment.DIRECTORY_RECORDINGS + File.separator + "CatRec"
                            uri =
                                contentResolver.insert(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                    buildAudioContentValues(recPath),
                                )
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "Recordings/CatRec mic insert failed", e)
                        }
                    }

                    if (uri != null) {
                        currentMicDestUri = uri
                        uri
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Mic MediaStore insert failed", e)
                    null
                }
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

    private fun commitTempFileToUri(
        source: File,
        destUri: Uri,
    ): Boolean {
        return try {
            if (!source.exists() || source.length() == 0L) return false
            contentResolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "commitTempFileToUri failed", e)
            false
        }
    }

    private fun stopRecording() {
        if (isStopping) return
        isStopping = true

        gifAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        gifAutoStopRunnable = null

        if (!isRecorderRunning && recorderEngine == null) {
            projectionStoppedRecording = false
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
        isGifSession = false
        gifMaxDurationSec = 0

        lifecycleScope.launch(Dispatchers.IO) {
            recorderEngine?.attachAdaptivePerformance(null, false)
            recordingPerformanceController?.stopSession()
            recordingPerformanceController = null
            val engine = recorderEngine
            // Snapshot before stop(): stop() clears muxer state used by hadOutput().
            val hadOutput =
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
                recorderEngine = null
            }

            try {
                currentPfd?.close()
            } catch (_: Exception) {
            }
            try {
                separateMicPfd?.close()
            } catch (_: Exception) {
            }

            var lastSavedRecordingUriForCleanup: Uri? = null
            if (hadOutput) {
                val tempVid = currentTempRecordingFile
                if (savedUri != null && tempVid != null) {
                    commitTempFileToUri(tempVid, savedUri)
                    try {
                        tempVid.delete()
                    } catch (_: Exception) {
                    }
                }
                currentTempRecordingFile = null
                savedUri?.let { finalizeMediaStoreEntry(it) }
                if (savedUri != null) {
                    lastSavedRecordingUriForCleanup = savedUri
                }

                if (hadOutput && savedUri != null && snapshotGifSession) {
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

                val tempMic = currentTempMicFile
                if (savedMicUri != null && tempMic != null) {
                    commitTempFileToUri(tempMic, savedMicUri)
                    try {
                        tempMic.delete()
                    } catch (_: Exception) {
                    }
                    finalizeAudioMediaStoreEntry(savedMicUri)
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
                }
                cleanup(lastSavedRecordingUri = lastSavedRecordingUriForCleanup)
            }
        }
    }

    // ── Rolling Buffer ─────────────────────────────────────────────────────────

    @RequiresApi(30)
    private fun startBuffer() {
        isStoppingForCodec.set(false)
        val hasAudioPermission =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        val notification = buildBufferNotification(getString(R.string.notif_buffer_starting))
        val serviceType = run {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (hasAudioPermission && (audioEnabled || internalAudioEnabled)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            type
        }

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
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            if (mediaProjection == null) {
                Log.e(LOG_TAG, "getMediaProjection returned null — cannot start buffer")
                RecordingEngineEventBus.tryEmit(RecordingError.PermissionDenied("media_projection_null_buffer"))
                stopBuffer()
                return
            }
            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
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

        lifecycleScope.launch(Dispatchers.IO) {
            val adaptiveEnabled = settingsRepository.adaptiveRecordingPerformance.first()
            if (!adaptiveEnabled) {
                preferAvcNextEnginePrepare.set(false)
            }
            val forceAvc =
                adaptiveEnabled &&
                    preferAvcNextEnginePrepare.compareAndSet(true, false) &&
                    videoEncoder == "H.265 (HEVC)"
            val perfController =
                if (adaptiveEnabled) {
                    RecordingPerformanceController(
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
                releasePendingScreenshotVirtualDisplay()
                releasePendingScreenshotImageReader()
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
                        audioBitrate = audioBitrate,
                        audioSampleRate = audioSampleRate,
                        audioChannelCount = channelCount,
                        audioEncoderType = audioEncoderType,
                        maxSegmentsLimit = RollingBufferEngine.maxSegmentsForClipperMinutes(clipperDurationMinutes),
                        adaptivePreferAvcForPrepare = forceAvc,
                        onInternalAudioSilence = {
                            Handler(Looper.getMainLooper()).post {
                                Toast
                                    .makeText(
                                        this@ScreenRecordService,
                                        getString(R.string.toast_internal_audio_silent),
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                            Log.w(
                                LOG_TAG,
                                "Buffer: internal audio silence detected — " +
                                    "brand=${Build.BRAND} model=${Build.MODEL} API=${Build.VERSION.SDK_INT}",
                            )
                            FirebaseCrashlytics.getInstance().log(
                                "Buffer internal audio silence: ${Build.BRAND} ${Build.MODEL} API ${Build.VERSION.SDK_INT}",
                            )
                        },
                        onFatalRecordingError = { fatalKind, detail ->
                            handleFatalRecordingFromEngine("buffer", detail, fatalKind)
                        },
                    )
                recordingPerformanceController = perfController
                rollingBufferEngine?.start()
                rollingBufferEngine?.attachAdaptivePerformance(perfController, adaptiveEnabled)
                perfController?.startSession()
                withContext(Dispatchers.Main) {
                    isBufferRunning = true
                    setCaptureSessionDiskFlag(true)
                    RecordingState.setBuffering(true)
                    durationTimerJob?.cancel()
                    val bufStartMs = SystemClock.elapsedRealtime()
                    durationTimerJob = lifecycleScope.launch {
                        while (true) {
                            RecordingState.updateDuration(SystemClock.elapsedRealtime() - bufStartMs)
                            delay(500L)
                        }
                    }
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
                rollingBufferEngine?.attachAdaptivePerformance(null, false)
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
        if (!isBufferRunning && rollingBufferEngine == null) {
            cleanupBuffer()
            return
        }
        isBufferRunning = false
        durationTimerJob?.cancel()
        durationTimerJob = null
        RecordingState.updateDuration(0L)
        RecordingState.setBuffering(false)
        startService(
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_BUFFERING_STATE
                putExtra(OverlayService.EXTRA_IS_BUFFERING, false)
            },
        )

        lifecycleScope.launch(Dispatchers.IO) {
            rollingBufferEngine?.attachAdaptivePerformance(null, false)
            recordingPerformanceController?.stopSession()
            recordingPerformanceController = null
            try {
                rollingBufferEngine?.stop()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Buffer engine stop error", e)
            } finally {
                rollingBufferEngine = null
            }
            withContext(Dispatchers.Main) { cleanupBuffer() }
        }
    }

    private fun saveClip() {
        if (!isBufferRunning) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "Clip_$timestamp.mp4"

                // Write to a temp file first; MediaMuxer (used in ClipMerger) needs a real path.
                val tempFile =
                    File(File(cacheDir, "clips").also { it.mkdirs() }, fileName)

                val ok = rollingBufferEngine?.saveClip(tempFile) ?: false
                if (!ok || !tempFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScreenRecordService, getString(R.string.toast_clip_empty), Toast.LENGTH_SHORT).show()
                    }
                    tempFile.delete()
                    return@launch
                }

                // Copy to MediaStore so the clip appears in the gallery
                val contentValues =
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(
                                MediaStore.Video.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_MOVIES + File.separator + "CatRec",
                            )
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    }
                }
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScreenRecordService, getString(R.string.toast_clip_saved), Toast.LENGTH_SHORT).show()
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
        captureDimensionsFromSessionConfig = false
        setCaptureSessionDiskFlag(false)
        if (isPrepared) {
            // Keep the service and MediaProjection alive so the overlay can start again.
            mainForegroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).notify(MAIN_FOREGROUND_NOTIFICATION_ID, buildReadyNotification())
        } else {
            mediaProjection?.stop()
            mediaProjection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!isRecorderRunning) stopSelf()
        }
    }

    private fun buildBufferNotification(statusText: String? = null): Notification {
        val tapPI = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPI =
            PendingIntent.getService(
                this,
                20,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP_BUFFER },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val clipPI =
            PendingIntent.getService(
                this,
                21,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_SAVE_CLIP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val bufferSecs = clipperDurationMinutes.coerceIn(1, 5) * 60
        return NotificationCompat
            .Builder(this, CHANNEL_BUFFER_ID)
            .setContentTitle(getString(R.string.notif_buffer_title))
            .setContentText(
                statusText ?: getString(R.string.notif_buffer_buffering, bufferSecs),
            ).setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPI)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_save, getString(R.string.notif_buffer_save_clip), clipPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopPI)
            .build()
    }

    // ── Screenshot ─────────────────────────────────────────────────────────────

    private fun releasePendingScreenshotVirtualDisplay() {
        synchronized(screenshotVirtualDisplayLock) {
            try {
                pendingScreenshotVirtualDisplay?.release()
            } catch (_: Exception) {
            }
            pendingScreenshotVirtualDisplay = null
        }
    }

    /**
     * Explicitly closes the one-shot screenshot [ImageReader] if present. Idempotent — safe to
     * call multiple times and from any teardown path (listener drain, [MediaProjection.Callback]
     * onStop, error branches). Also detaches the frame listener first so no callbacks run during
     * close. Paired with [releasePendingScreenshotVirtualDisplay] so callers can enforce the
     * producer → consumer → projection teardown order.
     */
    private fun releasePendingScreenshotImageReader() {
        synchronized(screenshotVirtualDisplayLock) {
            val reader = pendingScreenshotImageReader ?: return
            try {
                reader.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
            }
            try {
                reader.close()
            } catch (_: Exception) {
            }
            pendingScreenshotImageReader = null
        }
    }

    private fun takeScreenshot() {
        recorderEngine?.let { eng ->
            requestScreenshotFromEngine { cb -> eng.requestScreenshot(cb) }
            return
        }
        rollingBufferEngine?.let { eng ->
            requestScreenshotFromEngine { cb -> eng.requestScreenshot(cb) }
            return
        }
        val mp =
            mediaProjection ?: run {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, getString(R.string.error_screenshot_no_projection), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
                releaseOneShotScreenshotProjectionIfNeeded()
                return
            }
        // Idle (no active encode engine): mirror the real display via a dedicated VirtualDisplay.
        // The 8-frame skip inside [doTakeScreenshotFromProjection] absorbs stale SurfaceFlinger
        // buffers from any in-flight shade-close animation on high-refresh-rate devices; no
        // time-based countdown is required — we rely on the caller having already handed focus
        // back from the shade (see [ScreenshotAfterShadeActivity]).
        doTakeScreenshotFromProjection(mp, resources.displayMetrics)
    }

    private fun doTakeScreenshotFromProjection(
        mp: MediaProjection,
        metrics: DisplayMetrics,
    ) {
        // Enforce: never run a standalone VirtualDisplay capture while any engine is encoding.
        // The engines own the active VirtualDisplay tied to [mediaProjection]; from Android 14
        // onward the system rejects a second createVirtualDisplay() on the same projection.
        if (recorderEngine != null || rollingBufferEngine != null) {
            Log.w(LOG_TAG, "doTakeScreenshotFromProjection skipped — engine active, routing to engine path")
            takeScreenshot()
            return
        }
        releasePendingScreenshotVirtualDisplay()
        releasePendingScreenshotImageReader()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay =
            try {
                mp.createVirtualDisplay(
                    "CatRec_Screenshot",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    null,
                )
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "Screenshot VirtualDisplay rejected", e)
                try {
                    imageReader.close()
                } catch (_: Exception) {
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
                releaseOneShotScreenshotProjectionIfNeeded()
                return
            } ?: run {
                try {
                    imageReader.close()
                } catch (_: Exception) {
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
                releaseOneShotScreenshotProjectionIfNeeded()
                return
            }
        synchronized(screenshotVirtualDisplayLock) {
            pendingScreenshotVirtualDisplay = virtualDisplay
            pendingScreenshotImageReader = imageReader
        }

        // Auto-mirror VD often delivers stale buffers containing the notification shade or
        // transition frames. Skip enough frames to clear the pipeline on high-refresh-rate devices
        // (120/144 Hz) where the shade animation produces many more queued frames.
        val mirrorFrameIndex = AtomicInteger(0)
        val mirrorCaptureDone = AtomicBoolean(false)
        val skipMirrorFrames = 8
        val maxMirrorFrames = 64

        fun teardownScreenshotReader(
            reader: ImageReader,
            vd: VirtualDisplay,
        ) {
            if (!mirrorCaptureDone.compareAndSet(false, true)) return
            // Stop callbacks first, then release the producer (VirtualDisplay), then close the
            // consumer (ImageReader). Closing the reader before the VD yields "BufferQueue has been
            // abandoned" from SurfaceFlinger when the mirror pipeline still posts buffers.
            try {
                reader.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
            }
            try {
                vd.release()
            } catch (_: Exception) {
            }
            try {
                reader.close()
            } catch (_: Exception) {
            }
            synchronized(screenshotVirtualDisplayLock) {
                if (pendingScreenshotVirtualDisplay === vd) {
                    pendingScreenshotVirtualDisplay = null
                }
                // Reader is already closed above; drop the service-scope reference so the
                // [releasePendingScreenshotImageReader] call inside
                // [releaseOneShotScreenshotProjectionIfNeeded] is a safe no-op.
                if (pendingScreenshotImageReader === reader) {
                    pendingScreenshotImageReader = null
                }
            }
            OverlayService.notifyScreenshotCaptureFinished()
            releaseOneShotScreenshotProjectionIfNeeded()
        }

        imageReader.setOnImageAvailableListener(
            { reader ->
                if (mirrorCaptureDone.get()) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val frameIdx = mirrorFrameIndex.getAndIncrement()
                if (frameIdx >= maxMirrorFrames) {
                    image.close()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this@ScreenRecordService,
                            getString(R.string.error_screenshot_capture_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        teardownScreenshotReader(reader, virtualDisplay)
                    }
                    return@setOnImageAvailableListener
                }
                if (frameIdx < skipMirrorFrames) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap =
                        createBitmap(width + rowPadding / pixelStride, height)
                    bitmap.copyPixelsFromBuffer(buffer)
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    saveScreenshotBitmap(croppedBitmap)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Screenshot capture error", e)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this@ScreenRecordService,
                            getString(R.string.error_screenshot_capture_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally {
                    image.close()
                }
                teardownScreenshotReader(reader, virtualDisplay)
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** Relay thread invokes [block] with the next frame bitmap (or null). */
    private fun requestScreenshotFromEngine(block: ((Bitmap?) -> Unit) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val timeout =
            Runnable {
                if (delivered.compareAndSet(false, true)) {
                    Toast.makeText(this, getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
            }
        main.postDelayed(timeout, 3500L)
        block { bitmap ->
            if (!delivered.compareAndSet(false, true)) return@block
            main.removeCallbacks(timeout)
            main.post {
                try {
                    if (bitmap != null) {
                        saveScreenshotBitmap(bitmap)
                    } else {
                        Toast.makeText(this, getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    OverlayService.notifyScreenshotCaptureFinished()
                }
            }
        }
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val (extension, mimeType) =
            when (screenshotFormat) {
                "PNG" -> Pair("png", "image/png")
                "WebP" -> Pair("webp", "image/webp")
                else -> Pair("jpg", "image/jpeg")
            }
        val compressFormat =
            when (screenshotFormat) {
                "PNG" -> Bitmap.CompressFormat.PNG
                "WebP" ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                else -> Bitmap.CompressFormat.JPEG
            }
        val fileName = "Screenshot_$timestamp.$extension"

        val contentValues =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + File.separator + "CatRec" + File.separator + "Screenshots",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

        var savedUri: Uri? = null
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(compressFormat, screenshotQuality, out)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                }
                savedUri = uri
                RecordingState.onScreenshotSaved()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Screenshot save failed", e)
        } finally {
            bitmap.recycle()
        }
        val uriAfterSave = savedUri
        lifecycleScope.launch(Dispatchers.IO) {
            val showPostActions = settingsRepository.postScreenshotOptions.first()
            withContext(Dispatchers.Main) {
                if (uriAfterSave != null) {
                    if (showPostActions) {
                        startActivity(
                            Intent(this@ScreenRecordService, ScreenshotPostActionActivity::class.java).apply {
                                putExtra(ScreenshotPostActionActivity.EXTRA_IMAGE_URI,
                                    uriAfterSave.toString())
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } else {
                        Toast.makeText(this@ScreenRecordService, getString(R.string.notif_screenshot_saved), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ── Finalize ───────────────────────────────────────────────────────────────

    private fun finalizeMediaStoreEntry(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                val values =
                    ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        if (durationMs != null && durationMs > 0) put(MediaStore.Video.Media.DURATION, durationMs)
                    }
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "MediaStore finalize failed", e)
                try {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                } catch (_: Exception) {
                }
            }
        } else {
            try {
                val path = uri.path
                if (path != null) MediaScannerConnection.scanFile(applicationContext, arrayOf(path), arrayOf("video/mp4"), null)
            } catch (_: Exception) {
            }
        }
    }

    private fun finalizeAudioMediaStoreEntry(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                val values =
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                        if (durationMs != null && durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                    }
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Audio MediaStore finalize failed", e)
                try {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                } catch (_: Exception) {
                }
            }
        } else {
            try {
                val path = uri.path
                if (path != null) MediaScannerConnection.scanFile(applicationContext, arrayOf(path), arrayOf("audio/mp4"), null)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Single foreground notification: "saved" affordances + prepared state, so the shade does not
     * stack a second high-priority notification that collapses the recording controls entry.
     */
    private fun buildPreparedNotificationWithSavedRecording(uri: Uri): Notification {
        val thumbnail: Bitmap? =
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.loadThumbnail(uri, Size(320, 180), null)
                } else {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(applicationContext, uri)
                    val bmp = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    bmp
                }
            } catch (e: Exception) {
                null
            }

        val tapPending = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val openPending =
            PendingIntent.getActivity(
                this,
                11,
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        val sharePending =
            PendingIntent.getActivity(
                this,
                12,
                Intent
                    .createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        getString(R.string.share_recording_title),
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_IMMUTABLE,
            )
        val deletePending =
            PendingIntent.getService(
                this,
                13,
                Intent(this, ScreenRecordService::class.java).apply {
                    action = ACTION_DELETE_SAVED_RECORDING
                    putExtra(EXTRA_LAST_SAVED_RECORDING_URI, uri.toString())
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val revokePI =
            PendingIntent.getService(
                this,
                50,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_REVOKE_PREPARE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val style =
            NotificationCompat
                .BigPictureStyle()
                .setBigContentTitle(getString(R.string.notif_post_title))
                .setSummaryText(getString(R.string.notif_ready_text))
        if (thumbnail != null) {
            style.bigPicture(thumbnail)
        }

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_post_title))
            .setContentText(getString(R.string.notif_ready_text))
            .setSubText(getString(R.string.notif_post_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(style)
            .apply {
                if (thumbnail != null) setLargeIcon(thumbnail)
            }.addAction(android.R.drawable.ic_menu_view, getString(R.string.notif_post_open), openPending)
            .addAction(android.R.drawable.ic_menu_share, getString(R.string.notif_post_share), sharePending)
            .addAction(android.R.drawable.ic_menu_delete, getString(R.string.notif_post_delete), deletePending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_revoke),
                revokePI,
            ).build()
            .also { n ->
                n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

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
        captureDimensionsFromSessionConfig = false
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
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_HIDE_OVERLAYS })
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        sensorManager?.unregisterListener(this)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
        }

        if (revokeAfterStop) {
            mediaProjection?.stop()
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
            mediaProjection?.stop()
            mediaProjection = null
            mainForegroundActive = false
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "cleanup: stopForeground+stopSelf (!prepared)")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
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
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_screen_recording), NotificationManager.IMPORTANCE_LOW),
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE_ID,
                getString(R.string.notif_channel_recording_complete),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BUFFER_ID,
                getString(R.string.notif_channel_rolling_buffer),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildRecordingNotification(
        isPaused: Boolean,
        contentText: String? = null,
    ): Notification {
        AppControlNotification.cancel(this)
        val floatingOn = floatingOnForNotification()
        val overlayVisible = OverlayService.idleControlsBubbleVisible

        val tapPending =
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val homePi =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val exitPi =
            PendingIntent.getBroadcast(
                this,
                2,
                Intent(this, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_EXIT_APP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val overlayPi =
            PendingIntent.getBroadcast(
                this,
                12,
                Intent(this, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_OVERLAY_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val muteAction = if (isRecordingMuted) ACTION_UNMUTE else ACTION_MUTE
        val mutePI =
            PendingIntent.getService(
                this,
                3,
                Intent(this, ScreenRecordService::class.java).apply { action = muteAction },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val resumePI =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pausePI =
            PendingIntent.getService(
                this,
                2,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pauseOrResumePI = if (isPaused) resumePI else pausePI

        val statusLine =
            contentText ?: when {
                isPaused -> getString(R.string.notif_recording_paused)
                isRecordingMuted -> getString(R.string.notif_recording_muted)
                else -> getString(R.string.notif_recording_in_progress)
            }

        val collapsed = RemoteViews(packageName, R.layout.notification_app_controls_collapsed)
        val expanded = RemoteViews(packageName, R.layout.notification_app_controls_expanded)

        val pauseIcon =
            if (isPaused) {
                R.drawable.ic_play
            } else {
                R.drawable.ic_pause
            }
        val pauseDesc =
            getString(
                if (isPaused) {
                    R.string.notif_action_resume
                } else {
                    R.string.notif_action_pause
                },
            )

        collapsed.setImageViewResource(R.id.btn_record_icon, pauseIcon)
        collapsed.setTextViewText(R.id.btn_record_label, pauseDesc)
        collapsed.setContentDescription(R.id.btn_record, pauseDesc)
        collapsed.setOnClickPendingIntent(R.id.btn_record, pauseOrResumePI)

        expanded.setImageViewResource(R.id.btn_record_icon, pauseIcon)
        expanded.setTextViewText(R.id.btn_record_label, pauseDesc)
        expanded.setContentDescription(R.id.btn_record, pauseDesc)
        expanded.setOnClickPendingIntent(R.id.btn_record, pauseOrResumePI)

        updateNotificationState(isRecording = true, collapsed, expanded)

        bindShadeOverlayExit(collapsed, expanded, floatingOn, overlayVisible, overlayPi, exitPi)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_home, homePi)
        expanded.setOnClickPendingIntent(R.id.notif_ac_home, homePi)

        expanded.setViewVisibility(R.id.notif_ac_revoke, View.GONE)

        expanded.setViewVisibility(R.id.notif_ac_row_secondary, View.VISIBLE)
        expanded.setViewVisibility(R.id.notif_ac_mute, View.GONE)
        expanded.setImageViewResource(R.id.notif_ac_mute, android.R.drawable.ic_lock_silent_mode)
        expanded.setContentDescription(
            R.id.notif_ac_mute,
            getString(if (isRecordingMuted) R.string.notif_action_unmute else R.string.notif_action_mute),
        )
        expanded.setOnClickPendingIntent(R.id.notif_ac_mute, mutePI)

        if (showFloatingControls && controlsDismissedByUser) {
            val showControlsPI =
                PendingIntent.getService(
                    this,
                    5,
                    Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_SHOW_CONTROLS },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            expanded.setViewVisibility(R.id.notif_ac_show_controls, View.VISIBLE)
            expanded.setOnClickPendingIntent(R.id.notif_ac_show_controls, showControlsPI)
        } else {
            expanded.setViewVisibility(R.id.notif_ac_show_controls, View.GONE)
        }

        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title_short))
                .setContentText(statusLine)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(tapPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)

        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return notification
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
