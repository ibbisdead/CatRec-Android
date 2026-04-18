package com.ibbie.catrec_screenrecorcer.service

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.utils.crashlyticsLog
import com.ibbie.catrec_screenrecorcer.utils.recordCrashlyticsNonFatal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

class OverlayService : LifecycleService() {
    companion object {
        const val ACTION_SHOW_OVERLAYS = "ACTION_SHOW_OVERLAYS"
        const val ACTION_HIDE_OVERLAYS = "ACTION_HIDE_OVERLAYS"
        const val ACTION_SHOW_WATERMARK_PREVIEW = "ACTION_SHOW_WATERMARK_PREVIEW"
        const val ACTION_HIDE_WATERMARK_PREVIEW = "ACTION_HIDE_WATERMARK_PREVIEW"
        const val ACTION_UPDATE_WATERMARK_PREVIEW = "ACTION_UPDATE_WATERMARK_PREVIEW"
        const val ACTION_SHOW_CAMERA_PREVIEW = "ACTION_SHOW_CAMERA_PREVIEW"
        const val ACTION_HIDE_CAMERA_PREVIEW = "ACTION_HIDE_CAMERA_PREVIEW"
        const val ACTION_SHOW_CONTROLS = "ACTION_SHOW_CONTROLS"
        const val ACTION_SHOW_IDLE_CONTROLS = "ACTION_SHOW_IDLE_CONTROLS"
        const val ACTION_HIDE_IDLE_CONTROLS = "ACTION_HIDE_IDLE_CONTROLS"

        /** Re-read overlay control prefs (e.g. Brush toggle) and rebuild the expanded pill if visible. */
        const val ACTION_REBUILD_CONTROLS_CARD = "ACTION_REBUILD_CONTROLS_CARD"
        const val ACTION_UPDATE_PAUSE_STATE = "ACTION_UPDATE_PAUSE_STATE"
        const val ACTION_UPDATE_MUTE_STATE = "ACTION_UPDATE_MUTE_STATE"
        const val ACTION_UPDATE_RECORDING_STATE = "ACTION_UPDATE_RECORDING_STATE"
        const val EXTRA_IS_RECORDING = "EXTRA_IS_RECORDING"
        const val ACTION_UPDATE_BUFFERING_STATE = "ACTION_UPDATE_BUFFERING_STATE"
        const val EXTRA_IS_BUFFERING = "EXTRA_IS_BUFFERING"

        const val EXTRA_SHOW_CAMERA = "EXTRA_SHOW_CAMERA"
        const val EXTRA_CAMERA_SIZE = "EXTRA_CAMERA_SIZE"
        const val EXTRA_CAMERA_X_FRACTION = "EXTRA_CAMERA_X_FRACTION"
        const val EXTRA_CAMERA_Y_FRACTION = "EXTRA_CAMERA_Y_FRACTION"
        const val EXTRA_CAMERA_LOCK_POSITION = "EXTRA_CAMERA_LOCK_POSITION"
        const val EXTRA_CAMERA_FACING = "EXTRA_CAMERA_FACING"
        const val EXTRA_CAMERA_ASPECT_RATIO = "EXTRA_CAMERA_ASPECT_RATIO"
        const val EXTRA_CAMERA_OPACITY = "EXTRA_CAMERA_OPACITY"
        const val EXTRA_SHOW_WATERMARK = "EXTRA_SHOW_WATERMARK"
        const val EXTRA_SHOW_CONTROLS = "EXTRA_SHOW_CONTROLS"
        const val EXTRA_IS_PAUSED = "EXTRA_IS_PAUSED"
        const val EXTRA_IS_MUTED = "EXTRA_IS_MUTED"
        const val EXTRA_WATERMARK_LOCATION = "EXTRA_WATERMARK_LOCATION"
        const val EXTRA_WATERMARK_IMAGE_URI = "EXTRA_WATERMARK_IMAGE_URI"
        const val EXTRA_WATERMARK_SHAPE = "EXTRA_WATERMARK_SHAPE"
        const val EXTRA_WATERMARK_OPACITY = "EXTRA_WATERMARK_OPACITY"
        const val EXTRA_WATERMARK_SIZE = "EXTRA_WATERMARK_SIZE"
        const val EXTRA_WATERMARK_X_FRACTION = "EXTRA_WATERMARK_X_FRACTION"
        const val EXTRA_WATERMARK_Y_FRACTION = "EXTRA_WATERMARK_Y_FRACTION"

        /** Mirrors Settings “keep screen on” while overlays are shown (uses [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]). */
        const val EXTRA_KEEP_SCREEN_ON = "EXTRA_KEEP_SCREEN_ON"

        private const val CAMERA_CHANNEL_ID = "CatRec_Camera_Channel"
        private const val CAMERA_NOTIFICATION_ID = 42

        private const val OVERLAY_NOTIFICATION_ID = 43
        const val ACTION_CLOSE_OVERLAY = "com.ibbie.catrec_screenrecorcer.CLOSE_OVERLAY"

        /** True while the floating controls bubble is attached (idle or expanded). */
        @Volatile
        var idleControlsBubbleVisible: Boolean = false
            private set

        var onPreviewPositionChanged: ((xFraction: Float, yFraction: Float) -> Unit)? = null
        var onCameraPreviewPositionChanged: ((xFraction: Float, yFraction: Float) -> Unit)? = null
        private var instance: OverlayService? = null

        fun updatePreviewIfActive(
            sizeDp: Int,
            opacity: Int,
            shape: String,
            imageUri: String?,
            xFraction: Float,
            yFraction: Float,
        ) {
            instance?.updateWatermarkPreview(sizeDp, opacity, shape, imageUri, xFraction, yFraction)
        }

        fun updateCameraPreviewIfActive(
            sizeDp: Int,
            xFraction: Float,
            yFraction: Float,
        ) {
            instance?.updateCameraPreview(sizeDp, xFraction, yFraction)
        }

        /** Restores floating / brush chrome hidden while a screenshot was taken. Safe to call multiple times. */
        fun notifyScreenshotCaptureFinished() {
            instance?.restoreAfterScreenshotCapture()
        }
    }

    private var windowManager: WindowManager? = null

    // Camera overlay
    private var cameraView: View? = null
    private var cameraViewParams: WindowManager.LayoutParams? = null
    private var cameraIsLocked: Boolean = false
    private var cameraFacingFront: Boolean = true
    private var cameraAspectRatioSetting: String = "Circle"
    private var cameraOpacityValue: Int = 100

    // Watermark overlay
    private var watermarkView: View? = null

    // Controls overlay
    private var controlsBubbleView: FrameLayout? = null
    private var controlsBubbleParams: WindowManager.LayoutParams? = null
    private var controlsCardView: LinearLayout? = null
    private var controlsCardParams: WindowManager.LayoutParams? = null
    private var controlsPauseButton: ImageView? = null
    private var controlsMuteButton: ImageView? = null
    private var controlsDismissedByUser = false
    private var controlsCardExpanded = false
    private var controlsIsPaused = false
    private var controlsIsMuted = false
    private var controlsIsRecording = false
    private var controlsIsBuffering = false
    private var shouldPersistBubble = false
    private var bubbleAnimator: ValueAnimator? = null
    private var dismissIndicatorView: View? = null
    private var dismissIndicatorParams: WindowManager.LayoutParams? = null

    private var brushOverlayView: BrushOverlayLayout? = null
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private var brushOverlayEnabled = false

    /** Mirrors DataStore hide-while-recording; applied when recording or buffering bubble is visible. */
    private var hideFloatingIconWhileRecordingPref: Boolean = false

    /** From [ScreenRecordService] while recording; adds [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] to overlay windows. */
    private var keepScreenOnForControls: Boolean = false

    /** Floating bubble was hidden while the brush overlay is open. */
    private var floatingChromeHiddenForBrushSession = false

    /** Bubble/card hidden briefly for a screenshot from the floating pill. */
    private var floatingChromeHiddenForScreenshot = false
    private var controlsCardWasExpandedBeforeScreenshot = false

    // Live preview (watermark)
    private var previewBgView: View? = null
    private var previewWatermarkView: View? = null
    private var previewWatermarkParams: WindowManager.LayoutParams? = null

    // Camera preview (settings)
    private var cameraPreviewBgView: View? = null
    private var cameraPreviewOverlayView: FrameLayout? = null
    private var cameraPreviewOverlayParams: WindowManager.LayoutParams? = null
    private var settingsCameraPreviewView: PreviewView? = null
    private var settingsCameraProvider: ProcessCameraProvider? = null

    private var useFrontCamera = true
    private var cameraPreviewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createCameraChannel()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.hideFloatingIconWhileRecording.collect { hide ->
                    hideFloatingIconWhileRecordingPref = hide
                    applyRecordingBubbleVisibilityFromPreference()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = windowManager ?: return
        val (screenW, screenH) = currentScreenSizePx()

        fun clamp(
            params: WindowManager.LayoutParams?,
            view: View?,
        ) {
            if (params == null || view == null) return
            val w = params.width.coerceAtLeast(0)
            val h = params.height.coerceAtLeast(0)
            params.x = params.x.coerceIn(0, (screenW - w).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }

        clamp(controlsBubbleParams, controlsBubbleView)
        if (brushOverlayView != null) hideBrushOverlay()
        if (controlsCardExpanded) {
            hideControlsCard()
            showControlsCard()
        }
        clamp(cameraPreviewOverlayParams, cameraPreviewOverlayView)
        clamp(previewWatermarkParams, previewWatermarkView)
        hideDismissIndicator()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        bubbleAnimator?.cancel()
        hideDismissIndicator()
        hideControlsOverlay(userDismissed = false)
        hideCameraOverlay()
        hideWatermarkOverlay()
        hideWatermarkPreview()
        hideCameraPreview()
        cancelOverlayNotification()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            hideControlsOverlay(userDismissed = false)
            hideCameraOverlay()
            hideWatermarkOverlay()
            hideWatermarkPreview()
            hideCameraPreview()
            stopSelf()
            return START_NOT_STICKY
        }

        intent.action?.let { crashlyticsLog("Overlay: $it") }

        if (intent.action == ACTION_CLOSE_OVERLAY) {
            hideControlsOverlay(userDismissed = true)
            cancelOverlayNotification()
            if (cameraView == null && watermarkView == null) stopSelf()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_SHOW_OVERLAYS -> {
                keepScreenOnForControls = intent.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, false)
                val showCamera = intent.getBooleanExtra(EXTRA_SHOW_CAMERA, false)
                val camSize = intent.getIntExtra(EXTRA_CAMERA_SIZE, 120)
                val camX = intent.getFloatExtra(EXTRA_CAMERA_X_FRACTION, 0.05f)
                val camY = intent.getFloatExtra(EXTRA_CAMERA_Y_FRACTION, 0.1f)
                val camLocked = intent.getBooleanExtra(EXTRA_CAMERA_LOCK_POSITION, false)
                val camFacing = intent.getStringExtra(EXTRA_CAMERA_FACING) ?: "Front"
                val camAspect = intent.getStringExtra(EXTRA_CAMERA_ASPECT_RATIO) ?: "Circle"
                val camOpacity = intent.getIntExtra(EXTRA_CAMERA_OPACITY, 100)
                val showWatermark = intent.getBooleanExtra(EXTRA_SHOW_WATERMARK, false)
                val showControls = intent.getBooleanExtra(EXTRA_SHOW_CONTROLS, false)
                val watermarkImg = intent.getStringExtra(EXTRA_WATERMARK_IMAGE_URI)
                val watermarkShape = intent.getStringExtra(EXTRA_WATERMARK_SHAPE) ?: "Square"
                val watermarkOpacity = intent.getIntExtra(EXTRA_WATERMARK_OPACITY, 100)
                val watermarkSize = intent.getIntExtra(EXTRA_WATERMARK_SIZE, 80)
                val watermarkX = intent.getFloatExtra(EXTRA_WATERMARK_X_FRACTION, 0.05f)
                val watermarkY = intent.getFloatExtra(EXTRA_WATERMARK_Y_FRACTION, 0.05f)

                cameraIsLocked = camLocked
                cameraFacingFront = (camFacing == "Front")
                cameraAspectRatioSetting = camAspect
                cameraOpacityValue = camOpacity
                useFrontCamera = cameraFacingFront

                if (showCamera) showCameraOverlay(camSize, camX, camY, camLocked, camAspect, camOpacity) else hideCameraOverlay()
                if (showWatermark) {
                    showWatermarkOverlay(
                        watermarkImg,
                        watermarkShape,
                        watermarkOpacity,
                        watermarkSize,
                        watermarkX,
                        watermarkY,
                    )
                } else {
                    hideWatermarkOverlay()
                }
                if (showControls) {
                    controlsIsRecording = true
                    if (controlsBubbleView != null) {
                        // Already showing idle bubble — switch to recording state
                        if (controlsCardExpanded) {
                            hideControlsCard()
                            showControlsCard()
                        }
                    } else {
                        showControlsOverlay()
                    }
                    applyRecordingBubbleVisibilityFromPreference()
                } else {
                    hideControlsOverlay(userDismissed = false)
                }
            }

            ACTION_HIDE_OVERLAYS -> {
                keepScreenOnForControls = false
                hideCameraOverlay()
                hideWatermarkOverlay()
                hideWatermarkPreview()
                hideCameraPreview()
                if (shouldPersistBubble) {
                    // Switch bubble back to idle mode — always collapse the card so the
                    // user gets immediate visual feedback that recording has stopped.
                    controlsIsRecording = false
                    controlsIsBuffering = false
                    hideControlsCard() // collapse; user taps bubble to see fresh idle controls
                    // Bubble was removed during recording (e.g. hide-while-recording) — restore if floating controls are on.
                    if (controlsBubbleView == null && readFloatingControlsEnabled()) {
                        showControlsOverlay()
                        cancelOverlayNotification()
                    }
                    applyRecordingBubbleVisibilityFromPreference()
                } else {
                    hideControlsOverlay(userDismissed = false)
                    stopSelf()
                }
            }

            ACTION_SHOW_IDLE_CONTROLS -> {
                shouldPersistBubble = true
                controlsIsRecording = com.ibbie.catrec_screenrecorcer.data.RecordingState.isRecording.value
                controlsIsBuffering = com.ibbie.catrec_screenrecorcer.data.RecordingState.isBuffering.value
                controlsDismissedByUser = false
                if (controlsBubbleView == null) {
                    showControlsOverlay()
                }
                cancelOverlayNotification()
                applyRecordingBubbleVisibilityFromPreference()
            }

            ACTION_REBUILD_CONTROLS_CARD -> {
                brushOverlayEnabled = readBrushOverlayEnabled()
                if (!brushOverlayEnabled) hideBrushOverlay()
                if (controlsCardExpanded) {
                    hideControlsCard()
                    showControlsCard()
                }
            }

            ACTION_HIDE_IDLE_CONTROLS -> {
                // User turned Floating Controls off in Settings.
                shouldPersistBubble = false
                if (!controlsIsRecording && !controlsIsBuffering) {
                    // Nothing is running — dismiss immediately.
                    hideControlsOverlay(userDismissed = false)
                    if (cameraView == null && watermarkView == null) stopSelf()
                }
                // If recording/buffering is active the overlay stays until the
                // session ends; cleanup() already checks shouldPersistBubble.
            }

            ACTION_SHOW_CONTROLS -> {
                controlsDismissedByUser = false
                showControlsOverlay()
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_CONTROLS_RESHOWN
                    },
                )
            }

            ACTION_UPDATE_PAUSE_STATE -> {
                controlsIsPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                updateControlsPauseButton()
            }

            ACTION_UPDATE_MUTE_STATE -> {
                controlsIsMuted = intent.getBooleanExtra(EXTRA_IS_MUTED, false)
                updateControlsMuteButton()
            }

            ACTION_UPDATE_RECORDING_STATE -> {
                val newRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
                if (newRecording != controlsIsRecording) {
                    controlsIsRecording = newRecording
                    if (!newRecording) controlsIsBuffering = false
                    if (controlsCardExpanded) {
                        hideControlsCard()
                        showControlsCard()
                    }
                }
                applyRecordingBubbleVisibilityFromPreference()
            }

            ACTION_UPDATE_BUFFERING_STATE -> {
                val newBuffering = intent.getBooleanExtra(EXTRA_IS_BUFFERING, false)
                if (newBuffering != controlsIsBuffering) {
                    controlsIsBuffering = newBuffering
                    if (newBuffering) controlsIsRecording = false
                    if (controlsCardExpanded) {
                        hideControlsCard()
                        showControlsCard()
                    }
                }
                applyRecordingBubbleVisibilityFromPreference()
            }

            ACTION_SHOW_CAMERA_PREVIEW -> {
                hideControlsOverlay(userDismissed = false)
                val sizeDp = intent.getIntExtra(EXTRA_CAMERA_SIZE, 120)
                val xFraction = intent.getFloatExtra(EXTRA_CAMERA_X_FRACTION, 0.05f)
                val yFraction = intent.getFloatExtra(EXTRA_CAMERA_Y_FRACTION, 0.1f)
                showCameraPreview(sizeDp, xFraction, yFraction)
            }

            ACTION_HIDE_CAMERA_PREVIEW -> {
                hideCameraPreview()
                if (cameraView == null && watermarkView == null && controlsBubbleView == null) stopSelf()
            }

            ACTION_SHOW_WATERMARK_PREVIEW -> {
                hideControlsOverlay(userDismissed = false)
                val sizeDp = intent.getIntExtra(EXTRA_WATERMARK_SIZE, 80)
                val opacity = intent.getIntExtra(EXTRA_WATERMARK_OPACITY, 100)
                val shape = intent.getStringExtra(EXTRA_WATERMARK_SHAPE) ?: "Square"
                val imageUri = intent.getStringExtra(EXTRA_WATERMARK_IMAGE_URI)
                val xFraction = intent.getFloatExtra(EXTRA_WATERMARK_X_FRACTION, 0.05f)
                val yFraction = intent.getFloatExtra(EXTRA_WATERMARK_Y_FRACTION, 0.05f)
                showWatermarkPreview(sizeDp, opacity, shape, imageUri, xFraction, yFraction)
            }

            ACTION_HIDE_WATERMARK_PREVIEW -> {
                hideWatermarkPreview()
                if (cameraView == null && watermarkView == null && controlsBubbleView == null && cameraPreviewOverlayView == null) stopSelf()
            }

            ACTION_UPDATE_WATERMARK_PREVIEW -> {
                val sizeDp = intent.getIntExtra(EXTRA_WATERMARK_SIZE, 80)
                val opacity = intent.getIntExtra(EXTRA_WATERMARK_OPACITY, 100)
                val shape = intent.getStringExtra(EXTRA_WATERMARK_SHAPE) ?: "Square"
                val imageUri = intent.getStringExtra(EXTRA_WATERMARK_IMAGE_URI)
                val xFraction = intent.getFloatExtra(EXTRA_WATERMARK_X_FRACTION, 0.05f)
                val yFraction = intent.getFloatExtra(EXTRA_WATERMARK_Y_FRACTION, 0.05f)
                updateWatermarkPreview(sizeDp, opacity, shape, imageUri, xFraction, yFraction)
            }
        }
        return START_STICKY
    }

    // ── Camera Overlay ─────────────────────────────────────────────────────────

    /**
     * From API 34+ with targetSdk 34+, [startForeground] with [ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA]
     * requires [Manifest.permission.CAMERA] to be granted at runtime; otherwise the system throws
     * [SecurityException] (see Crashlytics non-fatal: FGS camera without permission).
     */
    private fun hasRuntimeCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("ClickableViewAccessibility")
    private fun showCameraOverlay(
        sizeDp: Int = 120,
        xFraction: Float = 0.05f,
        yFraction: Float = 0.1f,
        locked: Boolean = false,
        aspectRatio: String = "Circle",
        opacity: Int = 100,
    ) {
        if (cameraView != null) return

        if (!hasRuntimeCameraPermission()) {
            Log.w("OverlayService", "Camera overlay skipped: CAMERA runtime permission not granted")
            crashlyticsLog("Overlay: camera overlay skipped (CAMERA not granted)")
            return
        }

        val notification = buildCameraNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(CAMERA_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(CAMERA_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Camera foreground start failed: ${e.message}", e)
            recordCrashlyticsNonFatal(e, "Overlay: camera foreground start failed")
            return
        }

        val metrics = resources.displayMetrics
        val (widthPx, heightPx) = computeCameraViewSize(sizeDp, aspectRatio)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        var flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (keepScreenOnForControls) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params =
            WindowManager
                .LayoutParams(
                    widthPx,
                    heightPx,
                    overlayType(),
                    flags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = fractionToOverlayOffset(xFraction, screenW, widthPx)
                    y = fractionToOverlayOffset(yFraction, screenH, heightPx)
                }
        cameraViewParams = params

        val container =
            FrameLayout(this).apply {
                clipToOutline = true
                outlineProvider = buildOutlineProvider(aspectRatio)
                setBackgroundColor(Color.BLACK)
                alpha = opacity.coerceIn(0, 100) / 100f
            }

        val previewView =
            PreviewView(this).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        cameraPreviewView = previewView
        container.addView(previewView)

        if (!locked) {
            container.setOnTouchListener(makeCameraGestureListener(params, container, aspectRatio))
        }

        cameraView = container
        try {
            windowManager?.addView(container, params)
            crashlyticsLog("Overlay: camera overlay view added")
        } catch (e: Exception) {
            Log.e("OverlayService", "Camera view add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: camera view add failed")
            cameraView = null
            cameraViewParams = null
            return
        }
        startCamera()
    }

    private fun computeCameraViewSize(
        sizeDp: Int,
        aspectRatio: String,
    ): Pair<Int, Int> {
        val w = dpToPx(sizeDp)
        val h =
            when (aspectRatio) {
                "16:9" -> (w * 9 / 16)
                "4:3" -> (w * 3 / 4)
                else -> w // Circle or Square
            }
        return Pair(w, h)
    }

    private fun buildOutlineProvider(aspectRatio: String): ViewOutlineProvider =
        when (aspectRatio) {
            "Circle" ->
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            "Square" ->
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        outline.setRect(0, 0, view.width, view.height)
                    }
                }
            else ->
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        val radius = dpToPx(8).toFloat()
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeCameraGestureListener(
        params: WindowManager.LayoutParams,
        container: FrameLayout,
        aspectRatio: String,
    ): View.OnTouchListener {
        val scaleDetector =
            ScaleGestureDetector(
                this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val newW = (params.width * detector.scaleFactor).toInt().coerceIn(dpToPx(60), dpToPx(240))
                        params.width = newW
                        params.height =
                            when (aspectRatio) {
                                "16:9" -> newW * 9 / 16
                                "4:3" -> newW * 3 / 4
                                else -> newW
                            }
                        try {
                            windowManager?.updateViewLayout(container, params)
                        } catch (_: Exception) {
                        }
                        return true
                    }
                },
            )

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var hasDragged = false

        return View.OnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    hasDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (!hasDragged && (abs(dx) > 8 || abs(dy) > 8)) hasDragged = true
                        if (hasDragged) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(container, params)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasDragged && !scaleDetector.isInProgress) flipCamera()
                    true
                }
                else -> false
            }
        }
    }

    private fun startCamera() {
        val previewView = cameraPreviewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera(previewView)
            } catch (e: Exception) {
                Log.e("OverlayService", "CameraProvider init failed", e)
                recordCrashlyticsNonFatal(e, "Overlay: CameraProvider init failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview)
        } catch (e: Exception) {
            Log.e("OverlayService", "Camera bind failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: camera bind failed")
        }
    }

    private fun flipCamera() {
        useFrontCamera = !useFrontCamera
        cameraPreviewView?.let { bindCamera(it) }
    }

    private fun hideCameraOverlay() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraPreviewView = null
        cameraView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        cameraView = null
        cameraViewParams = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    // ── Watermark Overlay ──────────────────────────────────────────────────────

    private fun showWatermarkOverlay(
        imageUri: String?,
        shape: String,
        opacity: Int,
        sizeDp: Int,
        xFraction: Float,
        yFraction: Float,
    ) {
        if (watermarkView != null) return
        val metrics = resources.displayMetrics
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        var wmFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (keepScreenOnForControls) wmFlags = wmFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val params =
            WindowManager
                .LayoutParams(
                    sizePx,
                    sizePx,
                    overlayType(),
                    wmFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = fractionToOverlayOffset(xFraction, screenW, sizePx)
                    y = fractionToOverlayOffset(yFraction, screenH, sizePx)
                }
        val image = buildWatermarkImageView(sizePx, opacity, shape, imageUri)
        watermarkView = image
        try {
            windowManager?.addView(image, params)
            crashlyticsLog("Overlay: watermark view added")
        } catch (e: Exception) {
            Log.e("OverlayService", "Watermark add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: watermark add failed")
            watermarkView = null
        }
    }

    private fun hideWatermarkOverlay() {
        watermarkView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        watermarkView = null
    }

    // ── Controls Overlay ───────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showControlsOverlay() {
        if (controlsBubbleView != null) return
        controlsDismissedByUser = false

        val bubbleSizePx = dpToPx(52)
        val (screenW, _) = currentScreenSizePx()

        var bubbleFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (keepScreenOnForControls) {
            bubbleFlags = bubbleFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        val params =
            WindowManager
                .LayoutParams(
                    bubbleSizePx,
                    bubbleSizePx,
                    overlayType(),
                    bubbleFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = screenW - bubbleSizePx
                    y = dpToPx(280)
                }
        controlsBubbleParams = params

        val bubble = buildControlsBubble()
        controlsBubbleView = bubble
        bubble.setOnTouchListener(makeControlsDragListener(params, bubble, bubbleSizePx))

        try {
            windowManager?.addView(bubble, params)
            idleControlsBubbleVisible = true
            crashlyticsLog("Overlay: controls bubble started")
            applyRecordingBubbleVisibilityFromPreference()
        } catch (e: Exception) {
            Log.e("OverlayService", "Controls bubble add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: controls bubble add failed")
            controlsBubbleView = null
            controlsBubbleParams = null
            idleControlsBubbleVisible = false
        }
    }

    private fun buildControlsBubble(): FrameLayout =
        FrameLayout(this).apply {
            // Oval GradientDrawable provides the circular clip background,
            // matching the adaptive icon's own background colour (#D80E0C).
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFD80E0C.toInt())
                }
            clipToOutline = true
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            // Hardware layer makes clipToOutline reliable on all API levels.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            elevation = dpToPx(8).toFloat()

            val iconView =
                ImageView(this@OverlayService).apply {
                    setImageDrawable(ContextCompat.getDrawable(this@OverlayService, R.mipmap.ic_launcher_foreground))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }
            addView(iconView)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeControlsDragListener(
        params: WindowManager.LayoutParams,
        bubble: FrameLayout,
        bubbleSizePx: Int,
    ): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var hasDragged = false
        var inDismissZone = false

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleAnimator?.cancel()
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    hasDragged = false
                    inDismissZone = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (screenW, screenH) = currentScreenSizePx()
                    val dismissZoneTop = (screenH * 0.80f).toInt()
                    val dismissZoneCenterX = screenW / 2
                    val dismissZoneHalfWidth = (screenW * 0.18f).toInt()
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!hasDragged && (abs(dx) > 8 || abs(dy) > 8)) {
                        hasDragged = true
                        if (controlsCardExpanded) hideControlsCard()
                        // Show X dismiss target immediately when drag begins
                        showOrUpdateDismissIndicator(screenW, screenH, inZone = false)
                    }
                    if (hasDragged) {
                        params.x = (initialX + dx).coerceIn(0, screenW - bubbleSizePx)
                        params.y = (initialY + dy).coerceIn(0, screenH - bubbleSizePx)
                        try {
                            windowManager?.updateViewLayout(bubble, params)
                        } catch (_: Exception) {
                        }
                        val bubbleCenterX = params.x + bubbleSizePx / 2
                        val nowInDismiss = params.y >= dismissZoneTop && abs(bubbleCenterX - dismissZoneCenterX) <= dismissZoneHalfWidth
                        if (nowInDismiss != inDismissZone) {
                            inDismissZone = nowInDismiss
                            showOrUpdateDismissIndicator(screenW, screenH, inZone = nowInDismiss)
                            bubble
                                .animate()
                                .scaleX(
                                    if (nowInDismiss) 0.85f else 1f,
                                ).scaleY(if (nowInDismiss) 0.85f else 1f)
                                .setDuration(150)
                                .start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideDismissIndicator()
                    bubble
                        .animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                    if (!hasDragged) {
                        if (controlsCardExpanded) {
                            hideControlsCard()
                        } else {
                            controlsCardExpanded = true
                            showControlsCard()
                        }
                    } else {
                        val (screenW, screenH) = currentScreenSizePx()
                        val dismissZoneTop = (screenH * 0.80f).toInt()
                        val dismissZoneCenterX = screenW / 2
                        val dismissZoneHalfWidth = (screenW * 0.18f).toInt()
                        val bubbleCenterX = params.x + bubbleSizePx / 2
                        if (params.y >= dismissZoneTop && abs(bubbleCenterX - dismissZoneCenterX) <= dismissZoneHalfWidth) {
                            animateDismissBubble(params, bubble, bubbleSizePx)
                        } else {
                            val targetX = if (bubbleCenterX < screenW / 2) 0 else (screenW - bubbleSizePx).coerceAtLeast(0)
                            animateSnapBubble(params, bubble, targetX)
                        }
                    }
                    inDismissZone = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Shows the ✕ dismiss target at the bottom-centre immediately when dragging starts.
     * Colours update when the bubble enters the hot-drop zone (inZone = true).
     * Call [hideDismissIndicator] on ACTION_UP to remove it.
     */
    private fun showOrUpdateDismissIndicator(
        screenW: Int,
        screenH: Int,
        inZone: Boolean,
    ) {
        val wm = windowManager ?: return
        val activeColor = if (inZone) 0xEEE53935.toInt() else 0xDD333333.toInt()
        val strokeColor = if (inZone) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()

        if (dismissIndicatorView == null) {
            // First call — create the indicator
            val size = dpToPx(52)
            val indicatorParams =
                WindowManager
                    .LayoutParams(
                        size,
                        size,
                        overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = screenW / 2 - size / 2
                        y = screenH - dpToPx(90)
                    }
            dismissIndicatorParams = indicatorParams
            val indicator =
                FrameLayout(this).apply {
                    clipToOutline = true
                    outlineProvider =
                        object : ViewOutlineProvider() {
                            override fun getOutline(
                                view: View,
                                outline: Outline,
                            ) {
                                outline.setOval(0, 0, view.width, view.height)
                            }
                        }
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(activeColor)
                            setStroke(dpToPx(2), strokeColor)
                        }
                    elevation = dpToPx(4).toFloat()
                    alpha = 0f
                }
            val xText =
                android.widget.TextView(this).apply {
                    text = "\u2715"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            indicator.addView(xText)
            dismissIndicatorView = indicator
            try {
                wm.addView(indicator, indicatorParams)
                indicator
                    .animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            } catch (_: Exception) {
                dismissIndicatorView = null
            }
        } else {
            // Already shown — just update the background colour to reflect zone state
            (dismissIndicatorView?.background as? GradientDrawable)?.apply {
                setColor(activeColor)
                setStroke(dpToPx(2), strokeColor)
            }
        }
    }

    private fun hideDismissIndicator() {
        dismissIndicatorView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {
            }
        }
        dismissIndicatorView = null
        dismissIndicatorParams = null
    }

    private fun showControlsCard() {
        if (controlsCardView != null) return
        val bubbleParams = controlsBubbleParams ?: return
        val wm = windowManager ?: return
        brushOverlayEnabled = readBrushOverlayEnabled()
        val (screenW, screenH) = currentScreenSizePx()
        val bubbleSizePx = bubbleParams.width
        val margin = dpToPx(10)
        val isUpperHalf = bubbleParams.y < screenH / 2
        // Toolbar width grows with more buttons (e.g. brush); a fixed estimate mis-centers the pill.
        val estCardW = (screenW - margin * 2).coerceAtMost(dpToPx(560))
        val cardX = (bubbleParams.x + bubbleSizePx / 2 - estCardW / 2).coerceIn(0, (screenW - estCardW).coerceAtLeast(0))
        val estCardH = dpToPx(72)
        val cardY =
            if (isUpperHalf) {
                bubbleParams.y + bubbleSizePx + margin
            } else {
                (bubbleParams.y - estCardH - margin).coerceAtLeast(0)
            }

        var cardFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (keepScreenOnForControls) cardFlags = cardFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val cardParams =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    cardFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = cardX
                    y = cardY
                }
        controlsCardParams = cardParams

        val card = buildControlsCard()
        controlsCardView = card
        card.alpha = 0f
        try {
            wm.addView(card, cardParams)
            card
                .animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start()
            scheduleSnapControlsCardToBubble(card)
        } catch (e: Exception) {
            Log.e("OverlayService", "Controls card add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: controls card add failed")
            controlsCardView = null
            controlsCardParams = null
            controlsPauseButton = null
            controlsMuteButton = null
            controlsCardExpanded = false
        }
    }

    /**
     * Re-aligns the tool strip to the bubble using measured size (fixes wide rows when brush etc. are shown).
     */
    private fun snapControlsCardToBubble() {
        val card = controlsCardView ?: return
        val params = controlsCardParams ?: return
        val bubbleParams = controlsBubbleParams ?: return
        val wm = windowManager ?: return
        val w = card.width
        val h = card.height
        if (w <= 0 || h <= 0) return
        val (screenW, screenH) = currentScreenSizePx()
        val bubbleSizePx = bubbleParams.width
        val margin = dpToPx(10)
        val bubbleCenterX = bubbleParams.x + bubbleSizePx / 2
        params.x = (bubbleCenterX - w / 2).coerceIn(0, (screenW - w).coerceAtLeast(0))
        val isUpperHalf = bubbleParams.y < screenH / 2
        params.y =
            if (isUpperHalf) {
                bubbleParams.y + bubbleSizePx + margin
            } else {
                (bubbleParams.y - h - margin).coerceAtLeast(0)
            }
        try {
            wm.updateViewLayout(card, params)
        } catch (_: Exception) {
        }
    }

    private fun scheduleSnapControlsCardToBubble(card: LinearLayout) {
        val runSnap =
            Runnable {
                if (controlsCardView !== card) return@Runnable
                if (card.width > 0 && card.height > 0) {
                    snapControlsCardToBubble()
                }
            }
        card.post(runSnap)
        card.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (controlsCardView !== card) {
                        card.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        return
                    }
                    if (card.width > 0 && card.height > 0) {
                        card.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        snapControlsCardToBubble()
                    }
                }
            },
        )
    }

    private fun buildControlsCard(): LinearLayout {
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AppCompatResources.getDrawable(this@OverlayService, R.drawable.bg_overlay_glass_pill)
                elevation = dpToPx(10).toFloat()
                val padH = dpToPx(10)
                val padV = dpToPx(8)
                setPadding(padH, padV, padH, padV)
            }

        when {
            controlsIsBuffering -> buildBufferingCard(card)
            controlsIsRecording -> buildRecordingCard(card)
            else -> buildIdleCard(card)
        }
        return card
    }

    /** Controls card shown while the rolling-buffer (clipper) is active. */
    private fun buildBufferingCard(card: LinearLayout) {
        val btnSize = dpToPx(40)
        val btnMargin = dpToPx(4)

        // ── Clip button ───────────────────────────────────────────────────────
        val clipBtn =
            android.widget.TextView(this).apply {
                text = getString(R.string.overlay_clip_button)
                setTextColor(0xFF000000.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            btnSize,
                        ).apply { setMargins(btnMargin, 0, btnMargin, 0) }
                background = AppCompatResources.getDrawable(this@OverlayService, R.drawable.bg_btn_overlay_clip)
                val padH = dpToPx(14)
                val padV = dpToPx(8)
                setPadding(padH, padV, padH, padV)
            }
        clipBtn.addPressAnim()
        clipBtn.setOnClickListener {
            startService(
                Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_SAVE_CLIP
                },
            )
        }

        // ── Stop Clipper button ───────────────────────────────────────────────
        val stopBtn =
            makeOverlayButton(
                sizePx = btnSize,
                marginPx = btnMargin,
                iconRes = R.drawable.ic_stop_rec,
                bgRes = R.drawable.bg_btn_overlay_stop,
                padPx = dpToPx(9),
            ) {
                startService(
                    Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP_BUFFER
                    },
                )
            }

        // ── Label ─────────────────────────────────────────────────────────────
        val label =
            android.widget.TextView(this).apply {
                text = getString(R.string.overlay_clipper_label)
                setTextColor(0xFFFF8C00.toInt())
                textSize = 9f
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                        ).apply { setMargins(dpToPx(4), 0, dpToPx(4), 0) }
            }

        card.addView(clipBtn)
        card.addView(stopBtn)
        card.addView(label)
    }

    private fun buildRecordingCard(card: LinearLayout) {
        val btnSize = dpToPx(40)
        val btnMargin = dpToPx(4)
        val pad = dpToPx(9)

        // Pause / Resume
        val pauseBtn =
            makeOverlayButton(
                sizePx = btnSize,
                marginPx = btnMargin,
                iconRes = if (controlsIsPaused) R.drawable.ic_play else R.drawable.ic_pause,
                bgRes = R.drawable.bg_btn_overlay_normal,
                padPx = pad,
            ) {
                startService(
                    Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                        action = if (controlsIsPaused) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE
                    },
                )
            }
        controlsPauseButton = pauseBtn

        // Stop — accent-coloured ring background makes it stand out
        val stopBtn =
            makeOverlayButton(
                sizePx = btnSize,
                marginPx = btnMargin,
                iconRes = R.drawable.ic_stop_rec,
                bgRes = R.drawable.bg_btn_overlay_stop,
                padPx = dpToPx(10),
            ) {
                startService(
                    Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    },
                )
            }

        // Mute — icon colour is driven by the vector itself (white / red)
        val muteBtn =
            makeOverlayButton(
                sizePx = btnSize,
                marginPx = btnMargin,
                iconRes = if (controlsIsMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on,
                bgRes = R.drawable.bg_btn_overlay_normal,
                padPx = pad,
            ) {
                startService(
                    Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                        action = if (controlsIsMuted) ScreenRecordService.ACTION_UNMUTE else ScreenRecordService.ACTION_MUTE
                    },
                )
            }
        controlsMuteButton = muteBtn

        val brushBtn =
            if (brushOverlayEnabled) {
                makeOverlayButton(
                    sizePx = btnSize,
                    marginPx = btnMargin,
                    iconRes = R.drawable.ic_brush,
                    bgRes = R.drawable.bg_btn_overlay_normal,
                    padPx = pad,
                ) { showBrushOverlay() }
            } else {
                null
            }

        val screenshotBtn =
            makeOverlayButton(
                sizePx = btnSize,
                marginPx = btnMargin,
                iconRes = R.drawable.ic_screenshot,
                bgRes = R.drawable.bg_btn_overlay_normal,
                padPx = pad,
            ) {
                scheduleScreenshotFromOverlay(hideBrushToolbarOnly = false)
            }

        card.addView(pauseBtn)
        card.addView(stopBtn)
        card.addView(muteBtn)
        brushBtn?.let { card.addView(it) }
        card.addView(screenshotBtn)
    }

    /** Controls card shown when NOT recording — open app to start, or take screenshot. */
    private fun buildIdleCard(card: LinearLayout) {
        val btnSize = dpToPx(40)
        val btnMargin = dpToPx(4)
        val pad = dpToPx(9)

        val recordBtn =
            makeOverlayButton(
                sizePx = btnSize,
                marginPx = btnMargin,
                iconRes = R.drawable.ic_record_start,
                bgRes = R.drawable.bg_btn_overlay_normal,
                padPx = pad,
            ) {
                if (com.ibbie.catrec_screenrecorcer.data.RecordingState.isPrepared.value) {
                    val overlayAction =
                        if (
                            com.ibbie.catrec_screenrecorcer.data.RecordingState.currentMode.value ==
                            com.ibbie.catrec_screenrecorcer.data.CaptureMode.CLIPPER
                        ) {
                            ScreenRecordService.ACTION_START_BUFFER_FROM_OVERLAY
                        } else {
                            ScreenRecordService.ACTION_START_FROM_OVERLAY
                        }
                    startService(
                        Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                            action = overlayAction
                        },
                    )
                } else {
                    try {
                        val asBuffer = RecordingState.currentMode.value == CaptureMode.CLIPPER
                        startActivity(
                            Intent(this@OverlayService, OverlayRecordProjectionActivity::class.java).apply {
                                putExtra(OverlayRecordProjectionActivity.EXTRA_START_AS_BUFFER, asBuffer)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } catch (_: Exception) {
                    }
                }
            }

        val brushBtn =
            if (brushOverlayEnabled) {
                makeOverlayButton(
                    sizePx = btnSize,
                    marginPx = btnMargin,
                    iconRes = R.drawable.ic_brush,
                    bgRes = R.drawable.bg_btn_overlay_normal,
                    padPx = pad,
                ) { showBrushOverlay() }
            } else {
                null
            }

        val screenshotBtn =
            makeOverlayButton(
                sizePx = btnSize,
                marginPx = btnMargin,
                iconRes = R.drawable.ic_screenshot,
                bgRes = R.drawable.bg_btn_overlay_normal,
                padPx = pad,
            ) {
                scheduleScreenshotFromOverlay(hideBrushToolbarOnly = false)
            }

        val label =
            android.widget.TextView(this).apply {
                text = getString(R.string.notif_title_short)
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                        ).apply { setMargins(dpToPx(4), 0, dpToPx(4), 0) }
            }

        card.addView(recordBtn)
        brushBtn?.let { card.addView(it) }
        card.addView(screenshotBtn)
        card.addView(label)
    }

    /**
     * Builds a consistently styled overlay icon button with ripple background,
     * uniform padding, and a scale-down press animation.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeOverlayButton(
        sizePx: Int,
        marginPx: Int,
        iconRes: Int,
        bgRes: Int,
        padPx: Int = dpToPx(9),
        onClick: () -> Unit,
    ): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams =
                LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
            background = AppCompatResources.getDrawable(this@OverlayService, bgRes)
            setPadding(padPx, padPx, padPx, padPx)
            addPressAnim()
            setOnClickListener { onClick() }
        }

    /**
     * Attaches a lightweight scale-down/up animation on touch events so every
     * button has a clear "pressed" state without needing selector drawables.
     * Returns false so the click listener still fires normally.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.addPressAnim() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v
                        .animate()
                        .scaleX(0.82f)
                        .scaleY(0.82f)
                        .setDuration(70)
                        .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v
                        .animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(130)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
            }
            false
        }
    }

    private fun hideControlsCard() {
        controlsCardView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        controlsCardView = null
        controlsCardParams = null
        controlsPauseButton = null
        controlsMuteButton = null
        controlsCardExpanded = false
    }

    private fun readBrushOverlayEnabled(): Boolean =
        runBlocking(Dispatchers.IO) {
            settingsRepo.brushOverlayEnabled.first()
        }

    private fun readFloatingControlsEnabled(): Boolean =
        runBlocking(Dispatchers.IO) {
            settingsRepo.floatingControls.first()
        }

    /**
     * Reflects [hideFloatingIconWhileRecordingPref] on the bubble and expanded card while a
     * capture session is active; restores the bubble when idle. Skipped while brush/screenshot
     * flows temporarily hide chrome (they restore then re-apply).
     */
    private fun applyRecordingBubbleVisibilityFromPreference() {
        val bubble = controlsBubbleView ?: return
        val wm = windowManager ?: return
        if (floatingChromeHiddenForScreenshot || floatingChromeHiddenForBrushSession) return
        val params = controlsBubbleParams ?: return
        val sessionActive = controlsIsRecording || controlsIsBuffering
        val hideChrome = sessionActive && hideFloatingIconWhileRecordingPref
        if (hideChrome) {
            if (controlsCardExpanded || controlsCardView != null) hideControlsCard()
            bubble.visibility = View.GONE
        } else {
            bubble.visibility = View.VISIBLE
        }
        try {
            wm.updateViewLayout(bubble, params)
        } catch (_: Exception) {
        }
    }

    private fun hideFloatingChromeForBrushSession() {
        if (floatingChromeHiddenForBrushSession) return
        floatingChromeHiddenForBrushSession = true
        hideDismissIndicator()
        if (controlsCardExpanded) hideControlsCard()
        controlsBubbleView?.visibility = View.GONE
    }

    private fun restoreFloatingChromeAfterBrushSession() {
        if (!floatingChromeHiddenForBrushSession) return
        floatingChromeHiddenForBrushSession = false
        controlsBubbleView?.visibility = View.VISIBLE
        applyRecordingBubbleVisibilityFromPreference()
    }

    private fun hideFloatingChromeForScreenshotOnly() {
        if (floatingChromeHiddenForScreenshot) return
        floatingChromeHiddenForScreenshot = true
        controlsCardWasExpandedBeforeScreenshot = controlsCardExpanded
        hideDismissIndicator()
        hideControlsCard()
        controlsBubbleView?.visibility = View.GONE
    }

    private fun restoreFloatingChromeAfterScreenshotOnly() {
        if (!floatingChromeHiddenForScreenshot) return
        floatingChromeHiddenForScreenshot = false
        controlsBubbleView?.visibility = View.VISIBLE
        applyRecordingBubbleVisibilityFromPreference()
        if (controlsCardWasExpandedBeforeScreenshot) {
            controlsCardExpanded = true
            showControlsCard()
        }
    }

    private fun restoreAfterScreenshotCapture() {
        restoreFloatingChromeAfterScreenshotOnly()
        brushOverlayView?.setToolbarVisible(true)
    }

    private fun scheduleScreenshotFromOverlay(hideBrushToolbarOnly: Boolean) {
        val h = Handler(Looper.getMainLooper())
        if (hideBrushToolbarOnly) {
            brushOverlayView?.setToolbarVisible(false)
        } else {
            hideFloatingChromeForScreenshotOnly()
        }
        h.postDelayed({
            startService(
                Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
                },
            )
        }, 100L)
    }

    private fun hideBrushOverlay() {
        restoreFloatingChromeAfterBrushSession()
        brushOverlayView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {
            }
        }
        brushOverlayView = null
    }

    private fun showBrushOverlay() {
        if (brushOverlayView != null) return
        hideFloatingChromeForBrushSession()
        val wm =
            windowManager ?: run {
                restoreFloatingChromeAfterBrushSession()
                return
            }
        val root =
            BrushOverlayLayout(
                this,
                onClose = { hideBrushOverlay() },
                onScreenshot = { scheduleScreenshotFromOverlay(hideBrushToolbarOnly = true) },
            )
        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
        brushOverlayView = root
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Brush overlay add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: brush overlay add failed")
            brushOverlayView = null
            restoreFloatingChromeAfterBrushSession()
        }
    }

    private fun hideControlsOverlay(userDismissed: Boolean) {
        bubbleAnimator?.cancel()
        bubbleAnimator = null
        floatingChromeHiddenForScreenshot = false
        floatingChromeHiddenForBrushSession = false
        brushOverlayView?.setToolbarVisible(true)
        hideDismissIndicator()
        hideControlsCard()
        hideBrushOverlay()
        controlsBubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        controlsBubbleView = null
        controlsBubbleParams = null
        idleControlsBubbleVisible = false
        controlsDismissedByUser = userDismissed
        if (userDismissed) {
            cancelOverlayNotification()
            shouldPersistBubble = false
            startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_CONTROLS_DISMISSED })
        }
    }

    private fun updateControlsPauseButton() {
        controlsPauseButton?.setImageResource(
            if (controlsIsPaused) R.drawable.ic_play else R.drawable.ic_pause,
        )
    }

    private fun updateControlsMuteButton() {
        controlsMuteButton?.setImageResource(if (controlsIsMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
    }

    private fun animateSnapBubble(
        params: WindowManager.LayoutParams,
        bubble: FrameLayout,
        targetX: Int,
    ) {
        bubbleAnimator?.cancel()
        val startX = params.x
        bubbleAnimator =
            ValueAnimator.ofInt(startX, targetX).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    params.x = anim.animatedValue as Int
                    try {
                        windowManager?.updateViewLayout(bubble, params)
                    } catch (_: Exception) {
                    }
                }
                start()
            }
    }

    private fun animateDismissBubble(
        params: WindowManager.LayoutParams,
        bubble: FrameLayout,
        bubbleSizePx: Int,
    ) {
        bubbleAnimator?.cancel()
        val (screenW, screenH) = currentScreenSizePx()
        val targetX = screenW / 2 - bubbleSizePx / 2
        val targetY = screenH - dpToPx(90) - bubbleSizePx / 2
        val startX = params.x
        val startY = params.y
        bubbleAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedFraction
                    params.x = (startX + (targetX - startX) * f).toInt()
                    params.y = (startY + (targetY - startY) * f).toInt()
                    bubble.alpha = 1f - f
                    try {
                        windowManager?.updateViewLayout(bubble, params)
                    } catch (_: Exception) {
                    }
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            hideControlsOverlay(userDismissed = true)
                        }
                    },
                )
                start()
            }
    }

    // ── Camera Live Preview (Settings) ─────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showCameraPreview(
        sizeDp: Int,
        xFraction: Float,
        yFraction: Float,
    ) {
        if (!hasRuntimeCameraPermission()) {
            Log.w("OverlayService", "Settings camera preview skipped: CAMERA runtime permission not granted")
            crashlyticsLog("Overlay: settings camera preview skipped (CAMERA not granted)")
            return
        }
        if (cameraPreviewOverlayView != null) {
            updateCameraPreview(sizeDp, xFraction, yFraction)
            return
        }
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics

        val bgParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        val bg = FrameLayout(this).apply { setBackgroundColor(0x44000000) }
        cameraPreviewBgView = bg
        try {
            wm.addView(bg, bgParams)
            crashlyticsLog("Overlay: camera preview (settings) bg added")
        } catch (e: Exception) {
            Log.e("OverlayService", "Camera preview bg add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: camera preview bg add failed")
            cameraPreviewBgView = null
            return
        }

        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val wmParams =
            WindowManager
                .LayoutParams(
                    sizePx,
                    sizePx,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = fractionToOverlayOffset(xFraction, screenW, sizePx)
                    y = fractionToOverlayOffset(yFraction, screenH, sizePx)
                }

        val container = buildCameraPreviewContainer()
        container.setOnTouchListener(makeCameraPreviewDragListener(wmParams, container, sizePx))
        cameraPreviewOverlayView = container
        cameraPreviewOverlayParams = wmParams

        try {
            wm.addView(container, wmParams)
            crashlyticsLog("Overlay: camera preview (settings) container added")
        } catch (e: Exception) {
            Log.e("OverlayService", "Camera preview add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: camera preview container add failed")
            cameraPreviewOverlayView = null
            cameraPreviewOverlayParams = null
        }
    }

    private fun buildCameraPreviewContainer(): FrameLayout {
        val container =
            FrameLayout(this).apply {
                clipToOutline = true
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(
                            view: View,
                            outline: Outline,
                        ) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                setBackgroundColor(Color.BLACK)
                elevation = dpToPx(6).toFloat()
            }
        val pv =
            PreviewView(this).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        settingsCameraPreviewView = pv
        container.addView(pv)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                settingsCameraProvider = future.get()
                val previewView = settingsCameraPreviewView ?: return@addListener
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                settingsCameraProvider?.unbindAll()
                settingsCameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            } catch (e: Exception) {
                Log.e("OverlayService", "Settings camera preview bind failed", e)
                recordCrashlyticsNonFatal(e, "Overlay: settings camera preview bind failed")
            }
        }, ContextCompat.getMainExecutor(this))
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeCameraPreviewDragListener(
        params: WindowManager.LayoutParams,
        view: FrameLayout,
        sizePx: Int,
    ) = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.tag = floatArrayOf(params.x.toFloat(), params.y.toFloat(), event.rawX, event.rawY)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val (screenW, screenH) = currentScreenSizePx()
                val tag = view.tag as? FloatArray ?: return@OnTouchListener false
                val newX = (tag[0] + (event.rawX - tag[2])).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
                val newY = (tag[1] + (event.rawY - tag[3])).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
                params.x = newX
                params.y = newY
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val (screenW, screenH) = currentScreenSizePx()
                val maxX = (screenW - sizePx).coerceAtLeast(1).toFloat()
                val maxY = (screenH - sizePx).coerceAtLeast(1).toFloat()
                onCameraPreviewPositionChanged?.invoke((params.x / maxX).coerceIn(0f, 1f), (params.y / maxY).coerceIn(0f, 1f))
                true
            }
            else -> false
        }
    }

    private fun updateCameraPreview(
        sizeDp: Int,
        xFraction: Float,
        yFraction: Float,
    ) {
        val wm = windowManager ?: return
        val view = cameraPreviewOverlayView ?: return
        val params = cameraPreviewOverlayParams ?: return
        val metrics = resources.displayMetrics
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        params.width = sizePx
        params.height = sizePx
        params.x = fractionToOverlayOffset(xFraction, screenW, sizePx)
        params.y = fractionToOverlayOffset(yFraction, screenH, sizePx)
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun hideCameraPreview() {
        settingsCameraProvider?.unbindAll()
        settingsCameraProvider = null
        settingsCameraPreviewView = null
        cameraPreviewBgView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        cameraPreviewBgView = null
        cameraPreviewOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        cameraPreviewOverlayView = null
        cameraPreviewOverlayParams = null
    }

    // ── Watermark Live Preview ─────────────────────────────────────────────────

    private fun showWatermarkPreview(
        sizeDp: Int,
        opacity: Int,
        shape: String,
        imageUri: String?,
        xFraction: Float,
        yFraction: Float,
    ) {
        if (previewBgView != null) {
            updateWatermarkPreview(sizeDp, opacity, shape, imageUri, xFraction, yFraction)
            return
        }
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val bgParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        val bg = FrameLayout(this).apply { setBackgroundColor(0x44000000) }
        previewBgView = bg
        try {
            wm.addView(bg, bgParams)
            crashlyticsLog("Overlay: watermark preview bg added")
        } catch (e: Exception) {
            Log.e("OverlayService", "Preview bg add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: watermark preview bg add failed")
            previewBgView = null
            return
        }
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val wmParams =
            WindowManager
                .LayoutParams(
                    sizePx,
                    sizePx,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = fractionToOverlayOffset(xFraction, screenW, sizePx)
                    y = fractionToOverlayOffset(yFraction, screenH, sizePx)
                }
        val imageView = buildWatermarkImageView(sizePx, opacity, shape, imageUri)
        imageView.setOnTouchListener(makePreviewDragListener(wmParams, imageView, sizePx))
        previewWatermarkView = imageView
        previewWatermarkParams = wmParams
        try {
            wm.addView(imageView, wmParams)
            crashlyticsLog("Overlay: watermark preview image added")
        } catch (e: Exception) {
            Log.e("OverlayService", "Preview watermark add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: watermark preview add failed")
        }
    }

    private fun updateWatermarkPreview(
        sizeDp: Int,
        opacity: Int,
        shape: String,
        imageUri: String?,
        xFraction: Float,
        yFraction: Float,
    ) {
        val wm = windowManager ?: return
        val existingView = previewWatermarkView ?: return
        val params = previewWatermarkParams ?: return
        val metrics = resources.displayMetrics
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        try {
            wm.removeView(existingView)
        } catch (_: Exception) {
        }
        params.width = sizePx
        params.height = sizePx
        params.x = fractionToOverlayOffset(xFraction, screenW, sizePx)
        params.y = fractionToOverlayOffset(yFraction, screenH, sizePx)
        val newView = buildWatermarkImageView(sizePx, opacity, shape, imageUri)
        newView.setOnTouchListener(makePreviewDragListener(params, newView, sizePx))
        previewWatermarkView = newView
        try {
            wm.addView(newView, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Preview update failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: watermark preview update failed")
        }
    }

    private fun hideWatermarkPreview() {
        previewBgView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        previewBgView = null
        previewWatermarkView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        previewWatermarkView = null
        previewWatermarkParams = null
    }

    // ── Shared Helpers ─────────────────────────────────────────────────────────

    /** Same icon the launcher shows — matches "Default (app icon)" in settings, not a separate marketing drawable. */
    private fun defaultWatermarkDrawable(): Drawable? {
        val base =
            try {
                applicationInfo.loadIcon(packageManager)
            } catch (_: Exception) {
                ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
            }
        return base?.mutate()
    }

    private fun buildWatermarkImageView(
        sizePx: Int,
        opacity: Int,
        shape: String,
        imageUri: String?,
    ): View {
        val pad = if (shape == "Circle") (sizePx * 0.12f).toInt().coerceAtLeast(dpToPx(2)) else 0
        val appIcon = defaultWatermarkDrawable()
        val image =
            ImageView(this).apply {
                layoutParams =
                    if (shape == "Circle") {
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                    } else {
                        ViewGroup.LayoutParams(sizePx, sizePx)
                    }
                scaleType = if (shape == "Circle") ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
                alpha = opacity.coerceIn(0, 100) / 100f
                if (!imageUri.isNullOrBlank()) {
                    try {
                        setImageURI(Uri.parse(imageUri))
                        if (drawable == null) {
                            setImageDrawable(appIcon)
                        }
                    } catch (_: Exception) {
                        setImageDrawable(appIcon)
                    }
                } else {
                    setImageDrawable(appIcon)
                }
                if (pad > 0) setPadding(pad, pad, pad, pad)
            }
        if (shape != "Circle") return image
        return CardView(this).apply {
            radius = sizePx / 2f
            cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
            preventCornerOverlap = false
            useCompatPadding = false
            clipChildren = true
            clipToOutline = true
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        val w = view.width
                        val h = view.height
                        val r = (minOf(w, h) / 2f).coerceAtLeast(1f)
                        outline.setRoundRect(0, 0, w, h, r)
                    }
                }
            addView(image)
        }
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Overlay position fractions are stored as (pixel offset) / (max travel), matching
     * [makePreviewDragListener] / [makeCameraPreviewDragListener] on release — not as a fraction of full screen.
     * That way 0 / 1 land flush with start/end for the current view size on any aspect ratio or density.
     */
    private fun fractionToOverlayOffset(
        fraction: Float,
        screenSpanPx: Int,
        viewSpanPx: Int,
    ): Int {
        val maxTravel = (screenSpanPx - viewSpanPx).coerceAtLeast(0)
        return (maxTravel * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, maxTravel)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makePreviewDragListener(
        params: WindowManager.LayoutParams,
        view: View,
        sizePx: Int,
    ) = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.tag = floatArrayOf(params.x.toFloat(), params.y.toFloat(), event.rawX, event.rawY)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val (screenW, screenH) = currentScreenSizePx()
                val tag = view.tag as? FloatArray ?: return@OnTouchListener false
                val newX = (tag[0] + (event.rawX - tag[2])).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
                val newY = (tag[1] + (event.rawY - tag[3])).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
                params.x = newX
                params.y = newY
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val (screenW, screenH) = currentScreenSizePx()
                val maxX = (screenW - sizePx).coerceAtLeast(1).toFloat()
                val maxY = (screenH - sizePx).coerceAtLeast(1).toFloat()
                onPreviewPositionChanged?.invoke((params.x / maxX).coerceIn(0f, 1f), (params.y / maxY).coerceIn(0f, 1f))
                true
            }
            else -> false
        }
    }

    /** Real screen bounds in px for overlays (handles rotation reliably). */
    private fun currentScreenSizePx(): Pair<Int, Int> {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
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

    // Dismiss legacy overlay notification (id 43) so only ScreenRecordService’s notification shows.
    private fun cancelOverlayNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.cancel(OVERLAY_NOTIFICATION_ID)
    }

    // ── Camera Notification ────────────────────────────────────────────────────

    private fun createCameraChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(
                CAMERA_CHANNEL_ID,
                getString(R.string.notif_channel_camera_overlay),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_camera_overlay_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildCameraNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat
            .Builder(this, CAMERA_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_camera_in_use_title))
            .setContentText(getString(R.string.notif_camera_overlay_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
