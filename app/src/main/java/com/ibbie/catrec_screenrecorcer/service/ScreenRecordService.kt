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
import android.graphics.ImageFormat
import android.graphics.PixelFormat
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
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.utils.AppLogger
import com.ibbie.catrec_screenrecorcer.utils.recordCrashlyticsNonFatal
import com.ibbie.catrec_screenrecorcer.service.RecordingActionReceiver.Companion.ACTION_DELETE_RECORDING
import com.ibbie.catrec_screenrecorcer.service.RecordingActionReceiver.Companion.EXTRA_NOTIFICATION_ID
import com.ibbie.catrec_screenrecorcer.service.RecordingActionReceiver.Companion.EXTRA_RECORDING_URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ScreenRecordService : LifecycleService(), SensorEventListener {

    companion object {
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
        /** Toggle pause while recording (quick controls / app control notification). */
        const val ACTION_TOGGLE_PAUSE = "ACTION_TOGGLE_PAUSE"

        // Rolling buffer / clipping
        const val ACTION_START_BUFFER = "ACTION_START_BUFFER"
        const val ACTION_STOP_BUFFER  = "ACTION_STOP_BUFFER"
        const val ACTION_SAVE_CLIP    = "ACTION_SAVE_CLIP"

        /**
         * Pre-grant mode: obtain MediaProjection while the app Activity is visible, then
         * keep it alive in this foreground service so the overlay can trigger recordings
         * without any permission dialog.
         */
        const val ACTION_PREPARE              = "ACTION_PREPARE"
        const val ACTION_START_FROM_OVERLAY   = "ACTION_START_FROM_OVERLAY"
        const val ACTION_START_BUFFER_FROM_OVERLAY = "ACTION_START_BUFFER_FROM_OVERLAY"
        const val ACTION_REVOKE_PREPARE       = "ACTION_REVOKE_PREPARE"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
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

        private const val CHANNEL_ID = "CatRec_Recording_Channel"
        private const val CHANNEL_DONE_ID = "CatRec_Done_Channel"
        private const val CHANNEL_BUFFER_ID = "CatRec_Buffer_Channel"
        private const val NOTIFICATION_ID = 1
        private const val POST_NOTIFICATION_ID = 2
        private const val BUFFER_NOTIFICATION_ID = 3
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var recorderEngine: ScreenRecorderEngine? = null
    private var rollingBufferEngine: RollingBufferEngine? = null
    /**
     * [takeScreenshot] without a running engine uses a dedicated [VirtualDisplay]. From Android 14
     * onward the system rejects a second [MediaProjection.createVirtualDisplay] while one is still
     * active — always release this before starting [EncoderFrameRelay].
     */
    @Volatile
    private var pendingScreenshotVirtualDisplay: VirtualDisplay? = null
    private val screenshotVirtualDisplayLock = Any()
    private var isBufferRunning = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var screenDensity: Int = 0
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 2400
    private var fps: Int = 30
    private var bitrate: Int = 10_000_000
    private var audioEnabled: Boolean = false
    private var internalAudioEnabled: Boolean = false
    private var audioBitrate: Int = 128_000
    private var audioSampleRate: Int = 48_000
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
    /** Rolling Clipper buffer length (1–5 minutes). */
    private var clipperDurationMinutes: Int = 1

    private var isGifSession: Boolean = false
    private var gifMaxDurationSec: Int = 0
    private var gifScaleWidth: Int = 480
    private var gifOutputFps: Int = 10
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gifAutoStopRunnable: Runnable? = null

    private var isRecorderRunning = false
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
    private var orientationEventListener: OrientationEventListener? = null
    private var lastRecordingIsPortrait: Boolean = true

    private var countdownOverlayView: View? = null
    private var countdownNumberView: TextView? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) handleScreenOff()
        }
    }

    // ── Analytics helper ───────────────────────────────────────────────────────

    private fun logServiceAnalyticsEvent(name: String, params: Map<String, String> = emptyMap()) {
        try {
            val bundle = android.os.Bundle()
            params.forEach { (k, v) -> bundle.putString(k, v.take(100)) }
            FirebaseAnalytics.getInstance(this).logEvent(name, bundle)
            FirebaseCrashlytics.getInstance().log("analytics[$name] $params")
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val metrics = resources.displayMetrics
        screenDensity = metrics.densityDpi
        val (w, h) = currentDisplaySizePx()
        displayWidth = w
        displayHeight = h
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                }

                fps = intent.getIntExtra(EXTRA_FPS, 30)
                bitrate = intent.getIntExtra(EXTRA_BITRATE, 10_000_000)
                audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, false)
                internalAudioEnabled = intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO_ENABLED, false)
                audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000)
                audioSampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 48_000)
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
                hideFloatingIconWhileRecording = intent.getBooleanExtra(EXTRA_HIDE_FLOATING_ICON_WHILE_RECORDING, false)
                stopBehaviors = intent.getStringArrayListExtra(EXTRA_STOP_BEHAVIOR)?.let {
                    ArrayList(StopBehaviorKeys.migrateSet(it.toSet()).toList())
                }
                saveLocationUri = intent.getStringExtra(EXTRA_SAVE_LOCATION)
                filenamePattern = intent.getStringExtra(EXTRA_FILENAME_PATTERN) ?: "yyyyMMdd_HHmmss"
                resolutionSetting = intent.getStringExtra(EXTRA_RESOLUTION) ?: "Native"
                videoEncoder = intent.getStringExtra(EXTRA_VIDEO_ENCODER) ?: "H.264"
                countdownValue = intent.getIntExtra(EXTRA_COUNTDOWN, 0)
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
                gifScaleWidth = intent.getIntExtra(EXTRA_GIF_SCALE_WIDTH, 480).coerceIn(160, 1280)
                gifOutputFps = intent.getIntExtra(EXTRA_GIF_OUTPUT_FPS, 10).coerceIn(1, 30)
                if (!isGifSession) {
                    gifMaxDurationSec = 0
                }

                if (resultCode != 0 && resultData != null) {
                    try {
                        startRecording()
                    } catch (e: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
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
                startService(Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_MUTE_STATE
                    putExtra(OverlayService.EXTRA_IS_MUTED, true)
                })
            }
            ACTION_UNMUTE -> {
                isRecordingMuted = false
                recorderEngine?.unmute()
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
                startService(Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_MUTE_STATE
                    putExtra(OverlayService.EXTRA_IS_MUTED, false)
                })
            }
            ACTION_CONTROLS_DISMISSED -> {
                controlsDismissedByUser = true
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
            }
            ACTION_CONTROLS_RESHOWN -> {
                controlsDismissedByUser = false
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                if (isRecorderRunning) updateRecordingNotification(isPaused = isRecordingPaused)
            }
            ACTION_START_BUFFER -> {
                if (!isRecorderRunning && !isBufferRunning) {
                    resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                    }
                    fps             = intent.getIntExtra(EXTRA_FPS, 30)
                    bitrate         = intent.getIntExtra(EXTRA_BITRATE, 10_000_000)
                    audioEnabled    = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, false)
                    internalAudioEnabled = intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO_ENABLED, false)
                    audioBitrate    = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000)
                    audioSampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 48_000)
                    audioChannels   = intent.getStringExtra(EXTRA_AUDIO_CHANNELS) ?: "Mono"
                    audioEncoderType = intent.getStringExtra(EXTRA_AUDIO_ENCODER) ?: "AAC-LC"
                    resolutionSetting = intent.getStringExtra(EXTRA_RESOLUTION) ?: "Native"
                    videoEncoder    = intent.getStringExtra(EXTRA_VIDEO_ENCODER) ?: "H.264"
                    clipperDurationMinutes = intent.getIntExtra(EXTRA_CLIPPER_DURATION_MINUTES, 1).coerceIn(1, 5)
                    if (resultCode != 0 && resultData != null) startBuffer()
                }
            }
            ACTION_STOP_BUFFER -> stopBuffer()
            ACTION_SAVE_CLIP   -> saveClip()

            ACTION_PREPARE -> {
                if (isPrepared || isRecorderRunning || isBufferRunning) return START_STICKY
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode == 0 || resultData == null) return START_STICKY
                startPreparedForeground()
            }

            ACTION_START_FROM_OVERLAY -> {
                if (!isPrepared || isRecorderRunning) return START_STICKY
                val repo = com.ibbie.catrec_screenrecorcer.data.SettingsRepository(applicationContext)
                runBlocking {
                    val mode = repo.captureMode.first()
                    if (mode == com.ibbie.catrec_screenrecorcer.data.CaptureMode.GIF) {
                        val preset = com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets.byId(
                            repo.gifRecorderPresetId.first(),
                        )
                        fps = preset.fps
                        bitrate = preset.bitrateBitsPerSec
                        resolutionSetting = preset.resolutionSetting
                        isGifSession = true
                        gifMaxDurationSec = preset.maxDurationSec
                        gifScaleWidth = preset.maxWidth
                        gifOutputFps = preset.fps
                    } else {
                        isGifSession = false
                        gifMaxDurationSec = 0
                        fps = repo.fps.first().toInt()
                        bitrate = (repo.bitrate.first() * 1_000_000).toInt()
                        resolutionSetting = repo.resolution.first()
                    }
                    audioEnabled    = repo.recordAudio.first()
                    internalAudioEnabled = repo.internalAudio.first()
                    audioBitrate    = repo.audioBitrate.first() * 1000
                    audioSampleRate = repo.audioSampleRate.first()
                    audioChannels   = repo.audioChannels.first()
                    audioEncoderType = repo.audioEncoder.first()
                    separateMicRecording = repo.separateMicRecording.first()
                    showCamera      = repo.cameraOverlay.first()
                    cameraOverlaySize = repo.cameraOverlaySize.first()
                    cameraXFraction = repo.cameraXFraction.first()
                    cameraYFraction = repo.cameraYFraction.first()
                    cameraLockPosition = repo.cameraLockPosition.first()
                    cameraFacing    = repo.cameraFacing.first()
                    cameraAspectRatio = repo.cameraAspectRatio.first()
                    cameraOpacity   = repo.cameraOpacity.first()
                    showWatermark   = repo.showWatermark.first()
                    showFloatingControls = repo.floatingControls.first()
                    hideFloatingIconWhileRecording = repo.hideFloatingIconWhileRecording.first()
                    stopBehaviors   = ArrayList(repo.stopBehavior.first())
                    saveLocationUri = repo.saveLocationUri.first()
                    videoEncoder    = repo.videoEncoder.first()
                    filenamePattern = repo.filenamePattern.first()
                    countdownValue  = repo.countdown.first()
                    keepScreenOn    = repo.keepScreenOn.first()
                    recordingOrientationSetting = repo.recordingOrientation.first()
                    watermarkLocation = repo.watermarkLocation.first()
                    watermarkImageUri = repo.watermarkImageUri.first()
                    watermarkShape  = repo.watermarkShape.first()
                    watermarkOpacity = repo.watermarkOpacity.first()
                    watermarkSize   = repo.watermarkSize.first()
                    watermarkXFraction = repo.watermarkXFraction.first()
                    watermarkYFraction = repo.watermarkYFraction.first()
                    screenshotFormat = repo.screenshotFormat.first()
                    screenshotQuality = repo.screenshotQuality.first()
                }
                try {
                    startRecording()
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }

            ACTION_START_BUFFER_FROM_OVERLAY -> {
                if (!isPrepared || isBufferRunning || isRecorderRunning) return START_STICKY
                val repo = com.ibbie.catrec_screenrecorcer.data.SettingsRepository(applicationContext)
                runBlocking {
                    fps             = repo.fps.first().toInt()
                    bitrate         = (repo.bitrate.first() * 1_000_000).toInt()
                    audioEnabled    = repo.recordAudio.first()
                    internalAudioEnabled = repo.internalAudio.first()
                    audioBitrate    = repo.audioBitrate.first() * 1000
                    audioSampleRate = repo.audioSampleRate.first()
                    audioChannels   = repo.audioChannels.first()
                    audioEncoderType = repo.audioEncoder.first()
                    showFloatingControls = repo.floatingControls.first()
                    videoEncoder    = repo.videoEncoder.first()
                    resolutionSetting = repo.resolution.first()
                    clipperDurationMinutes = repo.clipperDurationMinutes.first()
                }
                startBuffer()
            }

            ACTION_REVOKE_PREPARE -> {
                if (!isRecorderRunning) {
                    isPrepared = false
                    RecordingState.setPrepared(false)
                    mediaProjection?.stop()
                    mediaProjection = null
                    try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_TAKE_SCREENSHOT -> {
                when {
                    recorderEngine != null || rollingBufferEngine != null || mediaProjection != null ->
                        takeScreenshot()
                    else -> {
                        Handler(Looper.getMainLooper()).post {
                            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                                putExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT_PROJECTION, true)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                            }
                            if (launch != null) startActivity(launch)
                            Toast.makeText(
                                this,
                                getString(R.string.toast_grant_capture_for_screenshot),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Consumes the pre-supplied MediaProjection token, keeps it alive in a foreground
     * service, and signals the overlay that it can now trigger recordings without a dialog.
     */
    private fun startPreparedForeground() {
        val notification = buildReadyNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "Prepare foreground start failed", e)
            stopSelf(); return
        }

        Log.d("ScreenRecordService", "startPreparedForeground: obtaining MediaProjection (resultCode=$resultCode)")
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
        Log.d("ScreenRecordService", "startPreparedForeground: MediaProjection=$mediaProjection")
        if (mediaProjection == null) {
            Log.e("ScreenRecordService", "startPreparedForeground: getMediaProjection returned null")
            logServiceAnalyticsEvent("capture_denied_or_unsupported", mapOf(
                "reason" to "projection_null_prepare",
                "api"    to Build.VERSION.SDK_INT.toString(),
                "brand"  to Build.BRAND,
            ))
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        logServiceAnalyticsEvent("projection_granted", mapOf(
            "path"  to "prepare",
            "api"   to Build.VERSION.SDK_INT.toString(),
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
        ))
        FirebaseCrashlytics.getInstance().log("MediaProjection granted (prepare path)")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.w("ScreenRecordService",
                    "MediaProjection.onStop() fired in prepared mode — projection revoked by OS " +
                    "(API=${Build.VERSION.SDK_INT} brand=${Build.BRAND})")
                FirebaseCrashlytics.getInstance().log("MediaProjection.onStop in prepared mode")
                isPrepared = false
                RecordingState.setPrepared(false)
                if (isRecorderRunning) stopRecording()
                else {
                    mediaProjection = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, null)

        isPrepared = true
        RecordingState.setPrepared(true)
    }

    private fun buildReadyNotification(): Notification {
        val tapPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val revokePI = PendingIntent.getService(
            this, 50,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_REVOKE_PREPARE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_ready_title))
            .setContentText(getString(R.string.notif_ready_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPI)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_revoke),
                revokePI,
            )
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun startRecording() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notification = buildRecordingNotification(isPaused = false, contentText = getString(R.string.notif_preparing))
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (hasAudioPermission && audioEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            type
        } else 0

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "Failed to start foreground service", e)
            stopSelf()
            return
        }

        lifecycleScope.launch {
            if (countdownValue > 0) {
                showCountdownOverlay(countdownValue)
                val notifMgr = getSystemService(NotificationManager::class.java)
                for (i in countdownValue downTo 1) {
                    updateCountdownOverlayNumber(i)
                    notifMgr.notify(
                        NOTIFICATION_ID,
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

        if (keepScreenOn) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "CatRec:RecordingWakeLock")
            wakeLock?.acquire()
        }

        // Apply orientation lock before getting display metrics
        applyOrientationLock()

        val showFloatingBubbleWhileRecording =
            showFloatingControls && !hideFloatingIconWhileRecording
        if (Settings.canDrawOverlays(this) && (showCamera || showWatermark || showFloatingBubbleWhileRecording)) {
            val overlayIntent = Intent(this, OverlayService::class.java).apply {
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
                putExtra(OverlayService.EXTRA_SHOW_CONTROLS, showFloatingBubbleWhileRecording)
                putExtra(OverlayService.EXTRA_WATERMARK_LOCATION, watermarkLocation)
                putExtra(OverlayService.EXTRA_WATERMARK_IMAGE_URI, watermarkImageUri)
                putExtra(OverlayService.EXTRA_WATERMARK_SHAPE, watermarkShape)
                putExtra(OverlayService.EXTRA_WATERMARK_OPACITY, watermarkOpacity)
                putExtra(OverlayService.EXTRA_WATERMARK_SIZE, watermarkSize)
                putExtra(OverlayService.EXTRA_WATERMARK_X_FRACTION, watermarkXFraction)
                putExtra(OverlayService.EXTRA_WATERMARK_Y_FRACTION, watermarkYFraction)
            }
            startService(overlayIntent)
        }

        val hasAudioPerm = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d("ScreenRecordService", "actualStartRecording: " +
                "RECORD_AUDIO=${if (hasAudioPerm) "GRANTED" else "DENIED"} " +
                "internalAudio=$internalAudioEnabled audioEnabled=$audioEnabled " +
                "API=${Build.VERSION.SDK_INT} brand=${Build.BRAND} model=${Build.MODEL}")

        if (mediaProjection == null) {
            // Normal path: consume the one-time token from the permission dialog.
            Log.d("ScreenRecordService", "Obtaining MediaProjection from token (resultCode=$resultCode)")
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            Log.d("ScreenRecordService", "MediaProjection obtained: $mediaProjection")

            if (mediaProjection != null) {
                FirebaseCrashlytics.getInstance().log("MediaProjection granted (normal path)")
                logServiceAnalyticsEvent("projection_granted", mapOf(
                    "path"  to "normal",
                    "api"   to Build.VERSION.SDK_INT.toString(),
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                ))
            } else {
                Log.e("ScreenRecordService", "getMediaProjection returned null — cannot record")
                FirebaseCrashlytics.getInstance().log("MediaProjection null after getMediaProjection")
                logServiceAnalyticsEvent("capture_denied_or_unsupported", mapOf(
                    "reason" to "projection_null",
                    "api"    to Build.VERSION.SDK_INT.toString(),
                    "brand"  to Build.BRAND,
                ))
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    // The OS revoked the projection — most commonly because the user navigated
                    // away from the captured app in single-app recording mode (Android 14+ / Samsung).
                    Log.w("ScreenRecordService",
                        "MediaProjection.onStop() fired — projection revoked by OS " +
                        "(API=${Build.VERSION.SDK_INT} brand=${Build.BRAND})")
                    FirebaseCrashlytics.getInstance().log("MediaProjection.onStop — projection revoked")
                    projectionStoppedRecording = true
                    stopRecording()
                }
            }, null)
        } else {
            // Prepared-mode path: mediaProjection is already live from ACTION_PREPARE.
            Log.d("ScreenRecordService", "MediaProjection reused from prepared mode: $mediaProjection")
            FirebaseCrashlytics.getInstance().log("MediaProjection reused from prepared mode")
            logServiceAnalyticsEvent("projection_granted", mapOf(
                "path"  to "prepared",
                "api"   to Build.VERSION.SDK_INT.toString(),
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
            ))
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
                    Toast.makeText(
                        this,
                        getString(R.string.toast_separate_mic_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val mode = when {
            internalAudioEnabled && audioEnabled -> ScreenRecorderEngine.AudioMode.MIXED
            internalAudioEnabled -> ScreenRecorderEngine.AudioMode.INTERNAL
            audioEnabled -> ScreenRecorderEngine.AudioMode.MIC
            else -> ScreenRecorderEngine.AudioMode.NONE
        }

        val channelCount = if (audioChannels == "Stereo") 2 else 1

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                releasePendingScreenshotVirtualDisplay()
                recorderEngine = ScreenRecorderEngine(
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
                    onInternalAudioSilence = {
                        // Fired on the audio-capture thread after SILENCE_TIMEOUT_MS of all-zero
                        // PCM from internal audio. Show a clear UI message and log for diagnostics.
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_internal_audio_silent),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        Log.w("ScreenRecordService",
                            "Internal audio silence detected — " +
                            "brand=${Build.BRAND} model=${Build.MODEL} API=${Build.VERSION.SDK_INT}. " +
                            "The captured app may block playback capture (capture policy ALLOW_CAPTURE_BY_NONE) " +
                            "or this OEM restricts AudioPlaybackCapture on Android ${Build.VERSION.SDK_INT}.")
                        FirebaseCrashlytics.getInstance().log(
                            "Internal audio silence: ${Build.BRAND} ${Build.MODEL} API ${Build.VERSION.SDK_INT}"
                        )
                    },
                )
                recorderEngine?.start()
                withContext(Dispatchers.Main) {
                    isRecorderRunning = true
                    RecordingState.setRecording(true)
                    RecordingState.setRecordingPaused(false)
                    updateRecordingNotification(isPaused = false)
                    gifAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (isGifSession && gifMaxDurationSec > 0) {
                        val r = Runnable {
                            gifAutoStopRunnable = null
                            if (isRecorderRunning) stopRecording()
                        }
                        gifAutoStopRunnable = r
                        mainHandler.postDelayed(r, gifMaxDurationSec * 1000L)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Recorder start failed", e)
                FirebaseCrashlytics.getInstance().log(AppLogger.dump())
                FirebaseCrashlytics.getInstance().recordException(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScreenRecordService, getString(R.string.toast_recorder_start_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    stopRecording()
                }
            }
        }
    }

    private fun applyOrientationLock() {
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

    private fun setupAutoOrientationListener() {
        orientationEventListener?.disable()
        orientationEventListener = object : OrientationEventListener(this@ScreenRecordService) {
            private var debounceTime = 0L
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || !isRecorderRunning) return
                val now = System.currentTimeMillis()
                if (now - debounceTime < 1000) return  // debounce 1 second

                // Portrait: 315–360 or 0–45 or 135–225; Landscape: 45–135 or 225–315
                val isCurrentlyPortrait = orientation in 315..360 || orientation in 0..45 ||
                        orientation in 135..225

                if (isCurrentlyPortrait != lastRecordingIsPortrait) {
                    debounceTime = now
                    lastRecordingIsPortrait = isCurrentlyPortrait
                    restartRecordingForOrientationChange(isCurrentlyPortrait)
                }
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    private fun restartRecordingForOrientationChange(isPortrait: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedUri = currentFileUri
            val savedMicUri = currentMicDestUri
            val eng = recorderEngine
            val hadSegmentOutput = try {
                eng?.hadOutput() ?: false
            } catch (_: Exception) {
                false
            }
            try {
                eng?.stop()
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Orientation restart stop error", e)
            } finally {
                recorderEngine = null
            }

            try { currentPfd?.close() } catch (_: Exception) {}
            try { separateMicPfd?.close() } catch (_: Exception) {}

            val tempVid = currentTempRecordingFile
            if (hadSegmentOutput && savedUri != null && tempVid != null) {
                commitTempFileToUri(tempVid, savedUri)
                try { tempVid.delete() } catch (_: Exception) {}
                finalizeMediaStoreEntry(savedUri)
                if (isGifSession) {
                    val gifOk = GifTranscodeHelper.transcodeMp4ToGif(
                        this@ScreenRecordService,
                        savedUri,
                        gifScaleWidth,
                        gifOutputFps,
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ScreenRecordService,
                            getString(
                                if (gifOk) R.string.toast_gif_saved else R.string.toast_gif_transcode_failed,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } else {
                try { tempVid?.delete() } catch (_: Exception) {}
                savedUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            }
            currentTempRecordingFile = null

            val tempMic = currentTempMicFile
            if (hadSegmentOutput && savedMicUri != null && tempMic != null && tempMic.exists() && tempMic.length() > 0L) {
                commitTempFileToUri(tempMic, savedMicUri)
                try { tempMic.delete() } catch (_: Exception) {}
                finalizeAudioMediaStoreEntry(savedMicUri)
            } else {
                try { tempMic?.delete() } catch (_: Exception) {}
                savedMicUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            }
            currentTempMicFile = null
            currentMicDestUri = null

            val (nativeW, nativeH) = currentDisplaySizePx()

            if (isPortrait) {
                displayWidth = minOf(nativeW, nativeH)
                displayHeight = maxOf(nativeW, nativeH)
            } else {
                displayWidth = maxOf(nativeW, nativeH)
                displayHeight = minOf(nativeW, nativeH)
            }
            displayWidth = (displayWidth / 16) * 16
            displayHeight = (displayHeight / 16) * 16

            val pfd = getOutputFileDescriptor()
            if (pfd == null) { withContext(Dispatchers.Main) { stopRecording() }; return@launch }
            currentPfd = pfd

            var micPfd: ParcelFileDescriptor? = null
            if (separateMicRecording && audioEnabled) {
                micPfd = getSeparateMicFileDescriptor()
                separateMicPfd = micPfd
            }

            val mode = when {
                internalAudioEnabled && audioEnabled -> ScreenRecorderEngine.AudioMode.MIXED
                internalAudioEnabled -> ScreenRecorderEngine.AudioMode.INTERNAL
                audioEnabled -> ScreenRecorderEngine.AudioMode.MIC
                else -> ScreenRecorderEngine.AudioMode.NONE
            }
            val channelCount = if (audioChannels == "Stereo") 2 else 1

            try {
                releasePendingScreenshotVirtualDisplay()
                recorderEngine = ScreenRecorderEngine(
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
                )
                recorderEngine?.start()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScreenRecordService, getString(R.string.toast_recording_restarted), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Orientation restart failed", e)
                withContext(Dispatchers.Main) { stopRecording() }
            }
        }
    }

    private fun calculateDimensions() {
        val (nativeWidth, nativeHeight) = currentDisplaySizePx()

        // Respect orientation lock
        val (baseW, baseH) = when (recordingOrientationSetting) {
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
                    val targetHeight = when {
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

    /**
     * Returns the current real screen bounds in pixels.
     * `resources.displayMetrics` can be stale in a long-lived Service across rotations.
     */
    private fun currentDisplaySizePx(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        val fileName = when (filenamePattern) {
            "CatRec_Timestamp" -> "CatRec_${timestamp}.mp4"
            "Date_Time" -> "Record_${timestamp}.mp4"
            else -> "${timestamp}.mp4"
        }

        try { currentTempRecordingFile?.delete() } catch (_: Exception) {}
        currentTempRecordingFile = null

        val destUri: Uri? = if (!saveLocationUri.isNullOrEmpty()) {
            try {
                val treeUri = Uri.parse(saveLocationUri)
                val docFile = DocumentFile.fromTreeUri(this, treeUri)
                val file = docFile?.createFile("video/mp4", fileName)
                if (file != null) {
                    currentFileUri = file.uri
                    file.uri
                } else null
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Error with SAF location", e)
                null
            }
        } else {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "CatRec")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    currentFileUri = uri
                    uri
                } else null
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "MediaStore insert failed", e)
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
            Log.e("ScreenRecordService", "Temp recording file failed", e)
            try { contentResolver.delete(destUri, null, null) } catch (_: Exception) {}
            currentFileUri = null
            currentTempRecordingFile = null
            null
        }
    }

    private fun getSeparateMicFileDescriptor(): ParcelFileDescriptor? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Mic_${timestamp}.m4a"

        try { currentTempMicFile?.delete() } catch (_: Exception) {}
        currentTempMicFile = null
        currentMicDestUri = null

        val destUri: Uri? = if (!saveLocationUri.isNullOrEmpty()) {
            try {
                val treeUri = Uri.parse(saveLocationUri)
                val docFile = DocumentFile.fromTreeUri(this, treeUri)
                val file = docFile?.createFile("audio/mp4", fileName)
                if (file != null) {
                    currentMicDestUri = file.uri
                    file.uri
                } else null
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "SAF mic file error", e)
                null
            }
        } else {
            // Audio MediaStore only allows certain primary directories (Alarms, Music, Recordings,
            // Ringtones, etc.). Movies/ throws IllegalArgumentException on Android 10+.
            // We try Music/CatRec first, then Recordings/CatRec (API 31+) as an alternative.
            fun buildAudioContentValues(relPath: String?): ContentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relPath != null) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            try {
                val musicPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    Environment.DIRECTORY_MUSIC + File.separator + "CatRec" else null

                var uri: Uri? = null
                try {
                    uri = contentResolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        buildAudioContentValues(musicPath)
                    )
                } catch (e: Exception) {
                    Log.w("ScreenRecordService", "Music/CatRec mic insert failed, trying Recordings/CatRec", e)
                }

                // Fallback to Recordings/CatRec (API 31+, explicitly allowed by AOSP)
                if (uri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val recPath = Environment.DIRECTORY_RECORDINGS + File.separator + "CatRec"
                        uri = contentResolver.insert(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            buildAudioContentValues(recPath)
                        )
                    } catch (e: Exception) {
                        Log.w("ScreenRecordService", "Recordings/CatRec mic insert failed", e)
                    }
                }

                if (uri != null) {
                    currentMicDestUri = uri
                    uri
                } else null
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Mic MediaStore insert failed", e)
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
            Log.e("ScreenRecordService", "Temp mic file failed", e)
            try { contentResolver.delete(destUri, null, null) } catch (_: Exception) {}
            currentMicDestUri = null
            currentTempMicFile = null
            null
        }
    }

    private fun commitTempFileToUri(source: File, destUri: Uri): Boolean {
        return try {
            if (!source.exists() || source.length() == 0L) return false
            contentResolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "commitTempFileToUri failed", e)
            false
        }
    }

    private fun stopRecording() {
        gifAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        gifAutoStopRunnable = null

        orientationEventListener?.disable()
        orientationEventListener = null

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
        RecordingState.setRecording(false)
        RecordingState.setRecordingPaused(false)

        val savedUri = currentFileUri
        val savedMicUri = currentMicDestUri
        val snapshotGifSession = isGifSession
        val snapshotGifScaleW = gifScaleWidth
        val snapshotGifFps = gifOutputFps
        isGifSession = false
        gifMaxDurationSec = 0

        lifecycleScope.launch(Dispatchers.IO) {
            val engine = recorderEngine
            // Snapshot before stop(): stop() clears muxer state used by hadOutput().
            val hadOutput = try {
                engine?.hadOutput() ?: false
            } catch (_: Exception) {
                false
            }
            try {
                engine?.stop()
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Engine stop error", e)
            } finally {
                recorderEngine = null
            }

            try { currentPfd?.close() } catch (_: Exception) {}
            try { separateMicPfd?.close() } catch (_: Exception) {}

            if (hadOutput) {
                val tempVid = currentTempRecordingFile
                if (savedUri != null && tempVid != null) {
                    commitTempFileToUri(tempVid, savedUri)
                    try { tempVid.delete() } catch (_: Exception) {}
                }
                currentTempRecordingFile = null
                savedUri?.let { finalizeMediaStoreEntry(it) }

                if (hadOutput && savedUri != null && snapshotGifSession) {
                    val gifOk = GifTranscodeHelper.transcodeMp4ToGif(
                        this@ScreenRecordService,
                        savedUri,
                        snapshotGifScaleW,
                        snapshotGifFps,
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
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
                    try { tempMic.delete() } catch (_: Exception) {}
                    finalizeAudioMediaStoreEntry(savedMicUri)
                }
                currentTempMicFile = null
                currentMicDestUri = null
            } else {
                try { currentTempRecordingFile?.delete() } catch (_: Exception) {}
                try { currentTempMicFile?.delete() } catch (_: Exception) {}
                currentTempRecordingFile = null
                currentTempMicFile = null
                // Muxer was never started — the file has no usable content (recording was
                // aborted before any frames were encoded, e.g. projection revoked immediately).
                // Delete the empty MediaStore entry so it doesn't appear as a broken file.
                savedUri?.let {
                    try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
                }
                savedMicUri?.let {
                    try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
                }
                currentMicDestUri = null
            }
            currentFileUri = null

            if (hadOutput && savedUri != null) showPostRecordingNotification(savedUri)

            withContext(Dispatchers.Main) {
                when {
                    wasStoppedByProjection ->
                        Toast.makeText(
                            this@ScreenRecordService,
                            getString(R.string.toast_recording_stopped_projection),
                            Toast.LENGTH_LONG
                        ).show()
                    !hadOutput ->
                        Toast.makeText(
                            this@ScreenRecordService,
                            getString(R.string.toast_recording_failed_no_video),
                            Toast.LENGTH_LONG
                        ).show()
                }
                cleanup()
            }
        }
    }

    // ── Rolling Buffer ─────────────────────────────────────────────────────────

    private fun startBuffer() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notification = buildBufferNotification(getString(R.string.notif_buffer_starting))
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (hasAudioPermission && (audioEnabled || internalAudioEnabled))
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            type
        } else 0

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(BUFFER_NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(BUFFER_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "Buffer foreground failed", e)
            stopSelf(); return
        }

        // In prepared mode the MediaProjection is already held — reuse it instead of
        // consuming the one-time token a second time.
        if (mediaProjection == null) {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { super.onStop(); stopBuffer() }
            }, null)
        }

        calculateDimensions()

        val audioMode = when {
            internalAudioEnabled && audioEnabled -> RollingBufferEngine.AudioMode.MIXED
            internalAudioEnabled -> RollingBufferEngine.AudioMode.INTERNAL
            audioEnabled -> RollingBufferEngine.AudioMode.MIC
            else -> RollingBufferEngine.AudioMode.NONE
        }
        val channelCount = if (audioChannels == "Stereo") 2 else 1

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                releasePendingScreenshotVirtualDisplay()
                rollingBufferEngine = RollingBufferEngine(
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
                    onInternalAudioSilence = {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                this@ScreenRecordService,
                                getString(R.string.toast_internal_audio_silent),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        Log.w("ScreenRecordService",
                            "Buffer: internal audio silence detected — " +
                            "brand=${Build.BRAND} model=${Build.MODEL} API=${Build.VERSION.SDK_INT}")
                        FirebaseCrashlytics.getInstance().log(
                            "Buffer internal audio silence: ${Build.BRAND} ${Build.MODEL} API ${Build.VERSION.SDK_INT}"
                        )
                    },
                )
                rollingBufferEngine?.start()
                withContext(Dispatchers.Main) {
                    isBufferRunning = true
                    RecordingState.setBuffering(true)
                    getSystemService(NotificationManager::class.java)
                        .notify(BUFFER_NOTIFICATION_ID, buildBufferNotification())
                    startService(Intent(this@ScreenRecordService, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_UPDATE_BUFFERING_STATE
                        putExtra(OverlayService.EXTRA_IS_BUFFERING, true)
                    })
                }
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Buffer start failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
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
            cleanupBuffer(); return
        }
        isBufferRunning = false
        RecordingState.setBuffering(false)
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_BUFFERING_STATE
            putExtra(OverlayService.EXTRA_IS_BUFFERING, false)
        })

        lifecycleScope.launch(Dispatchers.IO) {
            try { rollingBufferEngine?.stop() } catch (e: Exception) {
                Log.e("ScreenRecordService", "Buffer engine stop error", e)
            } finally { rollingBufferEngine = null }
            withContext(Dispatchers.Main) { cleanupBuffer() }
        }
    }

    private fun saveClip() {
        if (!isBufferRunning) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName  = "Clip_${timestamp}.mp4"

                // Write to a temp file first; MediaMuxer (used in ClipMerger) needs a real path.
                val tempFile = File(cacheDir, "clips").also { it.mkdirs() }.let {
                    File(it, fileName)
                }

                val ok = rollingBufferEngine?.saveClip(tempFile) ?: false
                if (!ok || !tempFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScreenRecordService, getString(R.string.toast_clip_empty), Toast.LENGTH_SHORT).show()
                    }
                    tempFile.delete()
                    return@launch
                }

                // Copy to MediaStore so the clip appears in the gallery
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + File.separator + "CatRec")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.update(uri,
                            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                            null, null)
                    }
                }
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScreenRecordService, getString(R.string.toast_clip_saved), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "saveClip error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScreenRecordService,
                        getString(R.string.toast_clip_save_failed, e.message ?: ""),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun cleanupBuffer() {
        if (isPrepared) {
            // Keep the service and MediaProjection alive so the overlay can start again.
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildReadyNotification())
        } else {
            mediaProjection?.stop()
            mediaProjection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!isRecorderRunning) stopSelf()
        }
    }

    private fun buildBufferNotification(statusText: String? = null): Notification {
        val tapPI = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPI = PendingIntent.getService(this, 20,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP_BUFFER },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val clipPI = PendingIntent.getService(this, 21,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_SAVE_CLIP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val bufferSecs = clipperDurationMinutes.coerceIn(1, 5) * 60
        return NotificationCompat.Builder(this, CHANNEL_BUFFER_ID)
            .setContentTitle(getString(R.string.notif_buffer_title))
            .setContentText(
                statusText ?: getString(R.string.notif_buffer_buffering, bufferSecs.toInt()),
            )
            .setSmallIcon(R.mipmap.ic_launcher)
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

    private fun takeScreenshot() {
        recorderEngine?.let { eng ->
            requestScreenshotFromEngine { cb -> eng.requestScreenshot(cb) }
            return
        }
        rollingBufferEngine?.let { eng ->
            requestScreenshotFromEngine { cb -> eng.requestScreenshot(cb) }
            return
        }
        val mp = mediaProjection ?: run {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, getString(R.string.error_screenshot_no_projection), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        releasePendingScreenshotVirtualDisplay()
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = try {
            mp.createVirtualDisplay(
                "CatRec_Screenshot",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null,
            )
        } catch (e: SecurityException) {
            Log.e("ScreenRecordService", "Screenshot VirtualDisplay rejected", e)
            imageReader.close()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        synchronized(screenshotVirtualDisplayLock) {
            pendingScreenshotVirtualDisplay = virtualDisplay
        }

        imageReader.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle()
                        saveScreenshotBitmap(croppedBitmap)
                    } catch (e: Exception) {
                        Log.e("ScreenRecordService", "Screenshot capture error", e)
                    } finally {
                        image.close()
                    }
                }
            } finally {
                try {
                    reader.close()
                } catch (_: Exception) {
                }
                try {
                    virtualDisplay.release()
                } catch (_: Exception) {
                }
                synchronized(screenshotVirtualDisplayLock) {
                    if (pendingScreenshotVirtualDisplay === virtualDisplay) {
                        pendingScreenshotVirtualDisplay = null
                    }
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** Relay thread invokes [block] with the next frame bitmap (or null). */
    private fun requestScreenshotFromEngine(block: ((Bitmap?) -> Unit) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val timeout = Runnable {
            if (delivered.compareAndSet(false, true)) {
                Toast.makeText(this, getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
            }
        }
        main.postDelayed(timeout, 3500L)
        block { bitmap ->
            if (!delivered.compareAndSet(false, true)) return@block
            main.removeCallbacks(timeout)
            main.post {
                if (bitmap != null) saveScreenshotBitmap(bitmap)
                else Toast.makeText(this, getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val (extension, mimeType) = when (screenshotFormat) {
            "PNG" -> Pair("png", "image/png")
            "WebP" -> Pair("webp", "image/webp")
            else -> Pair("jpg", "image/jpeg")
        }
        val compressFormat = when (screenshotFormat) {
            "PNG" -> Bitmap.CompressFormat.PNG
            "WebP" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val fileName = "Screenshot_${timestamp}.${extension}"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "CatRec" + File.separator + "Screenshots")
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                }
                savedUri = uri
            }
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "Screenshot save failed", e)
        } finally {
            bitmap.recycle()
        }
        val showPostActions = runBlocking(Dispatchers.IO) {
            com.ibbie.catrec_screenrecorcer.data.SettingsRepository(applicationContext).postScreenshotOptions.first()
        }
        Handler(Looper.getMainLooper()).post {
            val u = savedUri
            if (u != null) {
                if (showPostActions) {
                    startActivity(Intent(this, ScreenshotPostActionActivity::class.java).apply {
                        putExtra(ScreenshotPostActionActivity.EXTRA_IMAGE_URI, u.toString())
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } else {
                    Toast.makeText(this, getString(R.string.notif_screenshot_saved), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Finalize ───────────────────────────────────────────────────────────────

    private fun finalizeMediaStoreEntry(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    if (durationMs != null && durationMs > 0) put(MediaStore.Video.Media.DURATION, durationMs)
                }
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "MediaStore finalize failed", e)
                try {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                } catch (_: Exception) {}
            }
        } else {
            try {
                val path = uri.path
                if (path != null) MediaScannerConnection.scanFile(applicationContext, arrayOf(path), arrayOf("video/mp4"), null)
            } catch (_: Exception) {}
        }
    }

    private fun finalizeAudioMediaStoreEntry(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                    if (durationMs != null && durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                }
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.e("ScreenRecordService", "Audio MediaStore finalize failed", e)
                try {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                } catch (_: Exception) {}
            }
        } else {
            try {
                val path = uri.path
                if (path != null) MediaScannerConnection.scanFile(applicationContext, arrayOf(path), arrayOf("audio/mp4"), null)
            } catch (_: Exception) {}
        }
    }

    private fun showPostRecordingNotification(uri: Uri) {
        val notifMgr = getSystemService(NotificationManager::class.java)
        val thumbnail: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(320, 180), null)
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val bmp = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                bmp
            }
        } catch (e: Exception) { null }

        val tapPending = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val openPending = PendingIntent.getActivity(this, 11,
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, PendingIntent.FLAG_IMMUTABLE
        )
        val sharePending = PendingIntent.getActivity(this, 12,
            Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, getString(R.string.share_recording_title)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_IMMUTABLE
        )
        val deletePending = PendingIntent.getBroadcast(this, 13,
            Intent(this, RecordingActionReceiver::class.java).apply {
                action = ACTION_DELETE_RECORDING
                putExtra(EXTRA_RECORDING_URI, uri.toString())
                putExtra(EXTRA_NOTIFICATION_ID, POST_NOTIFICATION_ID)
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_DONE_ID)
            .setContentTitle(getString(R.string.notif_post_title))
            .setContentText(getString(R.string.notif_post_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_view, getString(R.string.notif_post_open), openPending)
            .addAction(android.R.drawable.ic_menu_share, getString(R.string.notif_post_share), sharePending)
            .addAction(android.R.drawable.ic_menu_delete, getString(R.string.notif_post_delete), deletePending)

        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumbnail))
        }
        notifMgr.notify(POST_NOTIFICATION_ID, builder.build())
    }

    // ── Pause / Resume ─────────────────────────────────────────────────────────

    private fun pauseRecording() {
        if (isRecorderRunning) {
            recorderEngine?.pause()
            isRecordingPaused = true
            RecordingState.setRecordingPaused(true)
            updateRecordingNotification(isPaused = true)
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_PAUSE_STATE
                putExtra(OverlayService.EXTRA_IS_PAUSED, true)
            })
        }
    }

    private fun resumeRecording() {
        if (isRecorderRunning) {
            recorderEngine?.resume()
            isRecordingPaused = false
            RecordingState.setRecordingPaused(false)
            updateRecordingNotification(isPaused = false)
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_PAUSE_STATE
                putExtra(OverlayService.EXTRA_IS_PAUSED, false)
            })
        }
    }

    private fun updateRecordingNotification(isPaused: Boolean, contentText: String? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildRecordingNotification(isPaused, contentText))
    }

    private fun cleanup() {
        hideCountdownOverlay()
        isRecordingPaused = false
        RecordingState.setRecordingPaused(false)
        isRecordingMuted = false
        controlsDismissedByUser = false
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_HIDE_OVERLAYS })
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        sensorManager?.unregisterListener(this)
        orientationEventListener?.disable()
        orientationEventListener = null
        if (wakeLock?.isHeld == true) { wakeLock?.release(); wakeLock = null }

        if (isPrepared) {
            // Keep the service and MediaProjection alive so the overlay can start
            // another recording without showing the permission dialog again.
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildReadyNotification())
        } else {
            mediaProjection?.stop()
            mediaProjection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Countdown Overlay ──────────────────────────────────────────────────────

    private fun showCountdownOverlay(seconds: Int) {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val container = FrameLayout(this)
        val dp = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = getString(R.string.notif_title_short); textSize = 18f; setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (6 * dp).toInt())
        }
        val numberView = TextView(this).apply {
            text = "$seconds"; textSize = 96f; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        countdownNumberView = numberView
        val subtitle = TextView(this).apply {
            text = getString(R.string.countdown_subtitle); textSize = 15f; setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER; setPadding(0, (6 * dp).toInt(), 0, 0)
        }

        val circleDiameter = (240 * dp).toInt()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1A1A2E.toInt())
                setStroke((3 * dp).toInt(), 0xFFD80D0C.toInt())
            }
            val pad = (24 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(title); addView(numberView); addView(subtitle)
        }

        container.addView(inner, FrameLayout.LayoutParams(circleDiameter, circleDiameter, Gravity.CENTER))
        countdownOverlayView = container
        try {
            wm.addView(container, params)
            FirebaseCrashlytics.getInstance().log("Overlay: countdown overlay added")
        } catch (e: Exception) {
            Log.e("ScreenRecordService", "Countdown overlay add failed", e)
            recordCrashlyticsNonFatal(e, "ScreenRecordService: countdown overlay add failed")
        }
    }

    private fun updateCountdownOverlayNumber(n: Int) {
        countdownNumberView?.let { tv ->
            tv.text = "$n"
            tv.animate().cancel()
            tv.scaleX = 1.3f; tv.scaleY = 1.3f
            tv.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
        }
    }

    private fun hideCountdownOverlay() {
        countdownOverlayView?.let { v ->
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
            countdownOverlayView = null
            countdownNumberView = null
        }
    }

    // ── Stop Behaviors ─────────────────────────────────────────────────────────

    private fun setupStopBehaviors() {
        if (stopBehaviors?.contains(StopBehaviorKeys.SCREEN_OFF) == true ||
            stopBehaviors?.contains(StopBehaviorKeys.PAUSE_ON_SCREEN_OFF) == true
        ) {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        if (stopBehaviors?.contains(StopBehaviorKeys.SHAKE) == true) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun handleScreenOff() {
        if (stopBehaviors?.contains(StopBehaviorKeys.PAUSE_ON_SCREEN_OFF) == true) pauseRecording()
        else stopRecording()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH
            val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
            if (gForce > 2.5f) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 1000) { lastShakeTime = now; stopRecording() }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_screen_recording), NotificationManager.IMPORTANCE_LOW),
            )
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE_ID, getString(R.string.notif_channel_recording_complete), NotificationManager.IMPORTANCE_DEFAULT),
            )
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_BUFFER_ID, getString(R.string.notif_channel_rolling_buffer), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildRecordingNotification(isPaused: Boolean, contentText: String? = null): Notification {
        val tapPending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPI = PendingIntent.getService(this, 0,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        val dismissedPI = PendingIntent.getService(this, 99,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_NOTIFICATION_DISMISSED },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val muteLabel = if (isRecordingMuted) getString(R.string.notif_action_unmute) else getString(R.string.notif_action_mute)
        val muteAction = if (isRecordingMuted) ACTION_UNMUTE else ACTION_MUTE
        val mutePI = PendingIntent.getService(this, 3,
            Intent(this, ScreenRecordService::class.java).apply { action = muteAction },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val statusLine = contentText ?: when {
            isPaused -> getString(R.string.notif_recording_paused)
            isRecordingMuted -> getString(R.string.notif_recording_muted)
            else -> getString(R.string.notif_recording_in_progress)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_short))
            .setContentText(statusLine)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPending)
            .setDeleteIntent(dismissedPI)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopPI)

        if (isPaused) {
            val resumePI = PendingIntent.getService(this, 1,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.notif_action_resume), resumePI)
        } else {
            val pausePI = PendingIntent.getService(this, 2,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_action_pause), pausePI)
        }

        builder.addAction(android.R.drawable.ic_lock_silent_mode, muteLabel, mutePI)

        if (showFloatingControls && controlsDismissedByUser) {
            val showControlsPI = PendingIntent.getService(this, 5,
                Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_SHOW_CONTROLS },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(R.mipmap.ic_launcher, getString(R.string.notif_action_show_controls), showControlsPI)
        }

        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return notification
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
