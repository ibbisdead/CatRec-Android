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
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PointF
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.core.net.toUri

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

    // ── OverlayUiState ──────────────────────────────────────────────────────────
    private enum class OverlayUiState {
        COLLAPSED_IDLE, EXPANDED_IDLE, COLLAPSED_RECORDING, EXPANDED_RECORDING
    }

    private val overlayUiState: OverlayUiState
        get() = when {
            (controlsIsRecording || controlsIsBuffering) && controlsCardExpanded -> OverlayUiState.EXPANDED_RECORDING
            controlsIsRecording || controlsIsBuffering -> OverlayUiState.COLLAPSED_RECORDING
            controlsCardExpanded -> OverlayUiState.EXPANDED_IDLE
            else -> OverlayUiState.COLLAPSED_IDLE
        }

    // ── OverlayAction — single source of truth for radial button visibility ────
    private enum class OverlayAction {
        RECORD, HOME, SCREENSHOT, CAMERA, BRUSH, PAUSE_RESUME, CLIP, STOP
    }

    /** Button descriptor used by [buildRadialMenu]. Defined here so [actionToBtnSpec] can reference it. */
    private data class BtnSpec(
        val icon: Int,
        val tint: Int = 0xFFFF8C00.toInt(),
        val action: () -> Unit,
    )

    /**
     * Single source of truth for which radial buttons are visible.
     * Collapsed states always return an empty list — only the bubble/timer is shown.
     */
    private fun getVisibleActions(): List<OverlayAction> =
        when (overlayUiState) {
            OverlayUiState.EXPANDED_IDLE ->
                listOf(
                    OverlayAction.RECORD,
                    OverlayAction.HOME,
                    OverlayAction.SCREENSHOT,
                    OverlayAction.CAMERA,
                    OverlayAction.BRUSH,
                )
            OverlayUiState.EXPANDED_RECORDING ->
                buildList {
                    if (currentMode == CaptureMode.CLIPPER) add(OverlayAction.CLIP) else add(OverlayAction.PAUSE_RESUME)
                    add(OverlayAction.STOP)
                    add(OverlayAction.SCREENSHOT)
                    add(OverlayAction.BRUSH)
                }
            else -> emptyList()
        }

    /** Maps an [OverlayAction] to its [BtnSpec] with the correct icon and click handler. */
    private fun actionToBtnSpec(action: OverlayAction): BtnSpec =
        when (action) {
            OverlayAction.RECORD -> BtnSpec(idleRecordIconRes(), 0xFFFF8C00.toInt()) {
                cancelAutoCollapse()
                // Collapse the full-screen card the instant the user taps record so the OS
                // regains touch focus immediately — the subsequent recording-state broadcast
                // will keep it collapsed too.
                hideControlsCard()
                if (RecordingState.isPrepared.value) {
                    val overlayAction =
                        if (currentMode == CaptureMode.CLIPPER) {
                            ScreenRecordService.ACTION_START_BUFFER_FROM_OVERLAY
                        } else {
                            ScreenRecordService.ACTION_START_FROM_OVERLAY
                        }
                    startService(Intent(this, ScreenRecordService::class.java).apply { this.action = overlayAction })
                } else {
                    try {
                        val asBuffer = currentMode == CaptureMode.CLIPPER
                        startActivity(
                            Intent(this, OverlayRecordProjectionActivity::class.java).apply {
                                putExtra(OverlayRecordProjectionActivity.EXTRA_START_AS_BUFFER, asBuffer)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } catch (_: Exception) {}
                }
            }
            OverlayAction.HOME -> BtnSpec(R.drawable.ic_home) {
                cancelAutoCollapse()
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        },
                    )
                } catch (_: Exception) {}
            }
            OverlayAction.SCREENSHOT -> BtnSpec(R.drawable.ic_screenshot) {
                cancelAutoCollapse()
                scheduleScreenshotFromOverlay(hideBrushToolbarOnly = false)
            }
            OverlayAction.CAMERA -> BtnSpec(R.drawable.ic_webcam) {
                cancelAutoCollapse()
                if (cameraView != null) {
                    hideCameraOverlay()
                } else {
                    @Suppress("NewApi")
                    showCameraOverlay(lastCamSizeDp, lastCamXFraction, lastCamYFraction, cameraIsLocked, cameraAspectRatioSetting, cameraOpacityValue)
                }
            }
            OverlayAction.BRUSH -> BtnSpec(R.drawable.ic_brush) {
                cancelAutoCollapse()
                showBrushOverlay()
            }
            OverlayAction.PAUSE_RESUME -> BtnSpec(if (controlsIsPaused) R.drawable.ic_play else R.drawable.ic_pause) {
                cancelAutoCollapse()
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        this.action = if (controlsIsPaused) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE
                    },
                )
            }
            OverlayAction.CLIP -> BtnSpec(R.drawable.ic_record_clipper, 0xFFFF8C00.toInt()) {
                cancelAutoCollapse()
                // Collapse the card immediately so the clip-save action doesn't leave the
                // full-screen menu stealing gestures.
                hideControlsCard()
                startService(Intent(this, ScreenRecordService::class.java).apply { this.action = ScreenRecordService.ACTION_SAVE_CLIP })
            }
            OverlayAction.STOP -> BtnSpec(R.drawable.ic_stop_rec, 0xFFFF8C00.toInt()) {
                cancelAutoCollapse()
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        this.action = if (currentMode == CaptureMode.CLIPPER) ScreenRecordService.ACTION_STOP_BUFFER else ScreenRecordService.ACTION_STOP
                    },
                )
            }
        }

    /** Snapshot of actions currently rendered in the radial menu; used to skip unnecessary rebuilds. */
    private var currentVisibleActions: List<OverlayAction> = emptyList()

    /**
     * Pause state captured when the card was last built.
     * Tracked separately because [OverlayAction.PAUSE_RESUME] maps to different icons
     * depending on [controlsIsPaused], yet the action enum value itself never changes.
     */
    private var currentIsPausedForCard: Boolean = false

    /**
     * Updates the radial menu only when the visible action set or the pause icon has changed.
     * Must be called while [controlsCardExpanded] is still true.
     */
    private fun refreshControlsCardIfNeeded() {
        if (!controlsCardExpanded) return
        val newActions = getVisibleActions()
        val pauseIconChanged = newActions.contains(OverlayAction.PAUSE_RESUME) &&
            controlsIsPaused != currentIsPausedForCard
        if (newActions == currentVisibleActions && !pauseIconChanged) return
        hideControlsCard()
        // hideControlsCard() sets controlsCardExpanded = false; restore before showControlsCard()
        // so that overlayUiState returns the correct expanded state when building the menu.
        controlsCardExpanded = true
        showControlsCard()
    }

    // Controls overlay
    private var controlsBubbleView: FrameLayout? = null
    private var controlsBubbleParams: WindowManager.LayoutParams? = null
    private var controlsCardView: FrameLayout? = null
    private var controlsCardParams: WindowManager.LayoutParams? = null
    private var controlsRadialPauseButton: ImageView? = null
    // kept for API compatibility — always null in radial design
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

    // Bubble interior sub-views (set in buildControlsBubble, cleared in hideControlsOverlay)
    private var bubbleIconView: ImageView? = null
    private var bubbleTimerView: LinearLayout? = null
    private var bubbleTimerTextView: android.widget.TextView? = null

    // Auto-collapse after 4 s of inactivity (only when NOT recording)
    private val autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        if (!controlsIsRecording && !controlsIsBuffering && controlsCardExpanded) {
            hideControlsCard()
        }
    }

    // Stored camera params for toggle button (updated on ACTION_SHOW_OVERLAYS)
    private var lastCamSizeDp: Int = 120
    private var lastCamXFraction: Float = 0.05f
    private var lastCamYFraction: Float = 0.1f

    private var brushOverlayView: BrushOverlayLayout? = null
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }

    // ── Radial menu constants ────────────────────────────────────────────────
    // Sub-menu button diameter, shrunk 20% from the original 48dp so icons
    // feel more like satellites orbiting the 56dp bubble than separate tiles.
    private val RADIAL_BTN_SIZE_DP: Int = (48f * 0.8f).toInt() // 38dp
    // Inner padding around the glyph inside each round button, scaled to match.
    private val RADIAL_BTN_ICON_PAD_DP: Int = (10f * 0.8f).toInt() // 8dp
    // Designed maximum number of items on the arc (idle: 5, recording: 4).
    // The step angle stays constant so the arc is always a clean sub-arc of
    // the 5-item semicircle regardless of how many buttons are visible.
    private val RADIAL_TOTAL_ITEMS: Int = 5
    // 180° semicircle / (5 - 1) = 45° between adjacent buttons.
    private val RADIAL_STEP_DEG: Double = 180.0 / (RADIAL_TOTAL_ITEMS - 1)

    /** Locally cached capture mode — kept in sync by the [RecordingState.currentMode] collector. */
    private var currentMode: String = CaptureMode.RECORD

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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createCameraChannel()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.hideFloatingIconWhileRecording.collect { hide ->
                    hideFloatingIconWhileRecordingPref = hide
                    applyRecordingBubbleVisibilityFromPreference()
                }
            }
        }
        // Observe capture mode changes — cache locally, update bubble icon and radial menu.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingState.currentMode.collect { mode ->
                    currentMode = mode
                    refreshBubbleIcon()
                    refreshControlsCardIfNeeded()
                }
            }
        }
        // Drive the timer badge from RecordingState.recordingDuration (published by ScreenRecordService).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingState.recordingDuration.collect { durationMs ->
                    val totalSecs = (durationMs / 1000L).toInt()
                    val minutes = totalSecs / 60
                    val seconds = totalSecs % 60
                    bubbleTimerTextView?.let { tv ->
                        tv.text = getString(R.string.overlay_recording_timer_format, minutes, seconds)
                        tv.contentDescription =
                            getString(
                                R.string.overlay_recording_timer_content_description,
                                minutes,
                                seconds,
                            )
                    }
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
        // After clamping, snap the bubble to whichever horizontal edge it is closest to
        // so it doesn't get stranded in the interior after a rotation.
        controlsBubbleParams?.let { params ->
            val view = controlsBubbleView ?: return@let
            val bubbleSizePx = params.width
            val bubbleCenterX = params.x + bubbleSizePx / 2
            val targetX = if (bubbleCenterX < screenW / 2) 0 else (screenW - bubbleSizePx).coerceAtLeast(0)
            if (params.x != targetX) {
                params.x = targetX
                try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
            }
        }
        if (brushOverlayView != null) hideBrushOverlay()
        if (controlsCardExpanded) {
            hideControlsCard()
            // Geometry has changed; always rebuild. Restore the expanded flag so getVisibleActions()
            // derives the correct OverlayUiState when building the new card.
            controlsCardExpanded = true
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
        cancelAutoCollapse()
        hideDismissIndicator()
        hideControlsOverlay(userDismissed = false)
        hideCameraOverlay()
        hideWatermarkOverlay()
        hideWatermarkPreview()
        hideCameraPreview()
        cancelOverlayNotification()
    }

    @RequiresApi(30)
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
                lastCamSizeDp = camSize
                lastCamXFraction = camX
                lastCamYFraction = camY

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
                    val wasRecording = controlsIsRecording
                    controlsIsRecording = true
                    if (controlsBubbleView != null) {
                        if (!wasRecording) transitionBubbleToRecording()
                        refreshControlsCardIfNeeded()
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
                    transitionBubbleToIdle()
                    hideControlsCard()
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
                controlsIsRecording = RecordingState.isRecording.value
                controlsIsBuffering = RecordingState.isBuffering.value
                controlsDismissedByUser = false
                if (controlsBubbleView == null) {
                    showControlsOverlay()
                }
                cancelOverlayNotification()
                applyRecordingBubbleVisibilityFromPreference()
            }

            ACTION_REBUILD_CONTROLS_CARD -> {
                refreshControlsCardIfNeeded()
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
                updatePauseResumeIcon()
            }

            ACTION_UPDATE_MUTE_STATE -> {
                controlsIsMuted = intent.getBooleanExtra(EXTRA_IS_MUTED, false)
                updateControlsMuteButton()
            }

            ACTION_UPDATE_RECORDING_STATE -> {
                val newRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
                if (newRecording != controlsIsRecording) {
                    val wasRecording = controlsIsRecording
                    controlsIsRecording = newRecording
                    if (!newRecording) controlsIsBuffering = false
                    if (newRecording && !wasRecording) {
                        transitionBubbleToRecording()
                        // Record-lock fix: tear the full-screen card down when a session begins
                        // so the MATCH_PARENT window no longer sits on top and OS touch focus
                        // is fully restored. cancelAutoCollapse prevents a stale timer from
                        // firing a redundant hide later.
                        cancelAutoCollapse()
                        hideControlsCard()
                    } else if (!newRecording && wasRecording) {
                        transitionBubbleToIdle()
                        refreshControlsCardIfNeeded()
                    } else {
                        refreshControlsCardIfNeeded()
                    }
                }
                applyRecordingBubbleVisibilityFromPreference()
            }

            ACTION_UPDATE_BUFFERING_STATE -> {
                val newBuffering = intent.getBooleanExtra(EXTRA_IS_BUFFERING, false)
                if (newBuffering != controlsIsBuffering) {
                    val wasActive = controlsIsRecording || controlsIsBuffering
                    controlsIsBuffering = newBuffering
                    if (newBuffering) controlsIsRecording = false
                    val nowActive = controlsIsRecording || controlsIsBuffering
                    if (nowActive && !wasActive) {
                        transitionBubbleToRecording()
                    } else if (!nowActive && wasActive) {
                        transitionBubbleToIdle()
                    }
                    refreshControlsCardIfNeeded()
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
     * Typed foreground service ([ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA]) must match
     * [android:foregroundServiceType] and [Manifest.permission.FOREGROUND_SERVICE_CAMERA] on API 34+.
     * [Manifest.permission.CAMERA] must be granted at runtime or the system throws [SecurityException].
     */
    private fun hasRuntimeCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @RequiresApi(30)
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
            ServiceCompat.startForeground(
                this,
                CAMERA_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
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
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
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

        val bubbleSizePx = dpToPx(46)
        val (screenW, screenH) = currentScreenSizePx()
        val safeTop = safeAreaTop()
        val safeBottom = safeAreaBottom()

        var bubbleFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (keepScreenOnForControls) {
            bubbleFlags = bubbleFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        val initialY = dpToPx(280).coerceIn(safeTop, (screenH - safeBottom - bubbleSizePx).coerceAtLeast(safeTop))
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
                    y = initialY
                }
        controlsBubbleParams = params

        val bubble = buildControlsBubble()
        controlsBubbleView = bubble
        bubble.setOnTouchListener(makeControlsDragListener(params, bubble, bubbleSizePx))

        try {
            windowManager?.addView(bubble, params)
            idleControlsBubbleVisible = true
            crashlyticsLog("Overlay: controls bubble started")
            refreshBubbleIcon()
            // If already recording when overlay is re-shown, display the timer
            if (controlsIsRecording || controlsIsBuffering) {
                transitionBubbleToRecording()
            }
            applyRecordingBubbleVisibilityFromPreference()
        } catch (e: Exception) {
            Log.e("OverlayService", "Controls bubble add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: controls bubble add failed")
            controlsBubbleView = null
            controlsBubbleParams = null
            bubbleIconView = null
            bubbleTimerView = null
            bubbleTimerTextView = null
            idleControlsBubbleVisible = false
        }
    }

    private fun buildControlsBubble(): FrameLayout =
        FrameLayout(this).apply {
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF1A1A1A.toInt())
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
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            elevation = dpToPx(8).toFloat()

            // Mode icon (visible when idle). Two-tone vector XMLs (orange + light blue)
            // carry their own colors, so we do not apply a PorterDuffColorFilter here —
            // it would flatten them to a single hue.
            val icon =
                ImageView(this@OverlayService).apply {
                    setImageResource(idleRecordIconRes())
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = dpToPx(10)
                    setPadding(pad, pad, pad, pad)
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }
            bubbleIconView = icon
            addView(icon)

            // Timer badge (visible when recording) — orange accent instead of red.
            val pulseDot =
                View(this@OverlayService).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0xFFFF8C00.toInt())
                        }
                    val dotSizePx = dpToPx(8)
                    layoutParams =
                        LinearLayout.LayoutParams(dotSizePx, dotSizePx).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            rightMargin = dpToPx(4)
                        }
                }

            val timerText =
                android.widget.TextView(this@OverlayService).apply {
                    text = getString(R.string.overlay_recording_timer_format, 0, 0)
                    contentDescription =
                        getString(R.string.overlay_recording_timer_content_description, 0, 0)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
            bubbleTimerTextView = timerText

            val timerRow =
                LinearLayout(this@OverlayService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    alpha = 0f
                    addView(pulseDot)
                    addView(timerText)
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ).apply { gravity = Gravity.CENTER }
                }
            bubbleTimerView = timerRow
            addView(timerRow)
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
                        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (controlsCardExpanded) {
                            cancelAutoCollapse()
                            hideControlsCard()
                        } else {
                            controlsCardExpanded = true
                            showControlsCard()
                            if (!controlsIsRecording && !controlsIsBuffering) {
                                scheduleAutoCollapse()
                            }
                        }
                    } else {
                        cancelAutoCollapse()
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
        // NOTE: all geometry below is computed from the live bubbleParams.x/y. The drag handler
        // calls hideControlsCard() on ACTION_MOVE, so the next tap re-runs this function fresh —
        // that's what makes the menu direction update immediately after a drag.
        val (screenW, screenH) = currentScreenSizePx()
        val bubbleSizePx = bubbleParams.width
        var bubbleCenterX = bubbleParams.x + bubbleSizePx / 2
        var bubbleCenterY = bubbleParams.y + bubbleSizePx / 2

        // ── Deterministic 5-item / 180° semicircle geometry ─────────────────────
        // Step angle is constant so the arc is always a clean sub-arc of the
        // 5-item semicircle — no guessing per count.
        val btnSizePx = dpToPx(RADIAL_BTN_SIZE_DP)
        val actionCount = getVisibleActions().size.coerceIn(1, RADIAL_TOTAL_ITEMS)
        val arcSpanDeg: Double = RADIAL_STEP_DEG * (actionCount - 1).coerceAtLeast(0)

        // Radius from chord constraint only:
        //   chord(step) = 2·R·sin(step/2)  ≥  minChordPx
        //   → R = minChordPx / (2·sin(step/2))
        // Clamped to 64–84 dp so buttons always "orbit" the 56 dp bubble rather
        // than drifting far away. rClearance is intentionally dropped — it
        // explodes when the half-span is 90° (cos = 0) and is unnecessary here
        // because the card covers the whole screen.
        val minChordPx = (btnSizePx + dpToPx(12)).toFloat()
        val halfStepRad = Math.toRadians(RADIAL_STEP_DEG / 2.0)
        val arcRadiusPx: Float =
            if (actionCount <= 1) {
                dpToPx(70).toFloat()
            } else {
                (minChordPx / (2f * sin(halfStepRad).toFloat()))
                    .coerceIn(dpToPx(64).toFloat(), dpToPx(84).toFloat())
            }

        // Direction the arc fans out, based on which screen quadrant the bubble lives in.
        var arcCenterDeg = computeArcCenterDeg(bubbleCenterX, bubbleCenterY, screenW, screenH)
        // ── End deterministic arc geometry ──────────────────────────────────────

        // ── Edge-collision "Teleport" ───────────────────────────────────────────
        // Before the menu animates, walk every button's final position and check
        // whether any would clip past the safe area. If so, nudge the bubble by
        // exactly the overflow amount (with a small margin) and push the new
        // position to WindowManager instantly. This preserves the perfect circle
        // instead of deforming it via per-button clamping.
        val topSafe = safeAreaTop()
        val bottomSafe = safeAreaBottom()
        run {
            val halfBtn = btnSizePx / 2
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            for (i in 0 until actionCount) {
                val o = calculateOffset(i, actionCount, arcRadiusPx, arcCenterDeg, arcSpanDeg)
                val cx = bubbleCenterX + o.x.roundToInt()
                val cy = bubbleCenterY + o.y.roundToInt()
                if (cx - halfBtn < minX) minX = cx - halfBtn
                if (cx + halfBtn > maxX) maxX = cx + halfBtn
                if (cy - halfBtn < minY) minY = cy - halfBtn
                if (cy + halfBtn > maxY) maxY = cy + halfBtn
            }
            val margin = dpToPx(4)
            val topLimit = topSafe + margin
            val bottomLimit = screenH - bottomSafe - margin
            val leftLimit = margin
            val rightLimit = screenW - margin
            var deltaY = 0
            var deltaX = 0
            if (minY < topLimit) deltaY = topLimit - minY
            else if (maxY > bottomLimit) deltaY = bottomLimit - maxY
            if (minX < leftLimit) deltaX = leftLimit - minX
            else if (maxX > rightLimit) deltaX = rightLimit - maxX

            if (deltaX != 0 || deltaY != 0) {
                bubbleParams.x = (bubbleParams.x + deltaX)
                    .coerceIn(0, (screenW - bubbleSizePx).coerceAtLeast(0))
                bubbleParams.y = (bubbleParams.y + deltaY)
                    .coerceIn(0, (screenH - bubbleSizePx).coerceAtLeast(0))
                try {
                    controlsBubbleView?.let { wm.updateViewLayout(it, bubbleParams) }
                } catch (_: Exception) {
                }
                bubbleCenterX = bubbleParams.x + bubbleSizePx / 2
                bubbleCenterY = bubbleParams.y + bubbleSizePx / 2
                arcCenterDeg = computeArcCenterDeg(bubbleCenterX, bubbleCenterY, screenW, screenH)
            }
        }

        // Full-screen transparent card window. FLAG_NOT_TOUCH_MODAL lets every
        // pixel outside the actual button views pass touches through to the app
        // below; only the button ImageViews consume taps. With this layout the
        // refX/refY for buildRadialMenu are the bubble center in screen coords.
        val cardW = screenW
        val cardH = screenH
        val cardX = 0
        val cardY = 0
        val refX = bubbleCenterX
        val refY = bubbleCenterY

        var cardFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (keepScreenOnForControls) cardFlags = cardFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val cardParams =
            WindowManager
                .LayoutParams(
                    cardW,
                    cardH,
                    overlayType(),
                    cardFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = cardX
                    y = cardY
                }
        controlsCardParams = cardParams

        val card = buildRadialMenu(refX, refY, cardW, cardH, arcRadiusPx, arcSpanDeg, arcCenterDeg)
        controlsCardView = card
        try {
            wm.addView(card, cardParams)
            currentVisibleActions = getVisibleActions()
            currentIsPausedForCard = controlsIsPaused
        } catch (e: Exception) {
            Log.e("OverlayService", "Controls card add failed", e)
            recordCrashlyticsNonFatal(e, "Overlay: controls card add failed")
            controlsCardView = null
            controlsCardParams = null
            controlsRadialPauseButton = null
            controlsPauseButton = null
            controlsMuteButton = null
            controlsCardExpanded = false
        }
    }

    /**
     * Maps the bubble's screen-quadrant position to the arc's opening direction in degrees.
     *
     * Convention (matches [calculateOffset]): 0°=right, 90°=down, 180°=left, 270°=up.
     *
     * - Single edges open perpendicular to that edge (into the screen).
     * - Corners open diagonally so none of the 5 buttons land off-screen.
     * - Values away from any edge fall back to opening downward.
     *
     * Called from [showControlsCard] every time the menu is shown, so the direction
     * always reflects the bubble's *current* [controlsBubbleParams].
     */
    private fun computeArcCenterDeg(
        bubbleCx: Int,
        bubbleCy: Int,
        screenW: Int,
        screenH: Int,
    ): Double {
        val cornerFrac = 0.25f
        val onLeft = bubbleCx <= screenW * cornerFrac
        val onRight = bubbleCx >= screenW * (1f - cornerFrac)
        val onTop = bubbleCy <= screenH * cornerFrac
        val onBottom = bubbleCy >= screenH * (1f - cornerFrac)
        return when {
            onTop && onLeft -> 45.0      // top-left corner     → opens down-right
            onTop && onRight -> 135.0    // top-right corner    → opens down-left
            onBottom && onLeft -> 315.0  // bottom-left corner  → opens up-right
            onBottom && onRight -> 225.0 // bottom-right corner → opens up-left
            onLeft -> 0.0                // left edge           → opens right
            onRight -> 180.0             // right edge          → opens left
            onTop -> 90.0                // top edge            → opens down
            onBottom -> 270.0            // bottom edge         → opens up
            else -> 90.0                 // not near any edge   → opens down
        }
    }

    /**
     * Calculates the (dx, dy) pixel offset of a radial menu button from the arc origin.
     *
     * @param index        zero-based position of the button in the list
     * @param totalItems   total number of buttons on the arc
     * @param radiusPx     arc radius in pixels
     * @param arcCenterDeg direction the arc faces in degrees (0°=right, 90°=down, 270°=up)
     * @param arcSpanDeg   total angular sweep of the arc in degrees; single items ignore this
     * @return pixel offset from the arc origin to the button centre
     */
    private fun calculateOffset(
        index: Int,
        totalItems: Int,
        radiusPx: Float,
        arcCenterDeg: Double,
        arcSpanDeg: Double,
    ): PointF {
        val startDeg = if (totalItems == 1) arcCenterDeg else arcCenterDeg - arcSpanDeg / 2.0
        val stepDeg  = if (totalItems  > 1) arcSpanDeg / (totalItems - 1) else 0.0
        val angleRad = Math.toRadians(startDeg + index * stepDeg)
        return PointF(
            (radiusPx * cos(angleRad)).toFloat(),
            (radiusPx * sin(angleRad)).toFloat(),
        )
    }

    /**
     * Builds the radial action menu as a transparent [FrameLayout].
     * Button set is determined entirely by [getVisibleActions]; positions are calculated
     * dynamically via [calculateOffset] so spacing is always even regardless of button count.
     *
     * @param refX         x of the bubble center relative to the card window's left edge (px)
     * @param refY         y of the bubble center relative to the card window's top edge (px)
     * @param cardW        card window width in px — used to clamp button positions
     * @param cardH        card window height in px — used to clamp button positions
     * @param arcRadiusPx  arc radius computed by [showControlsCard] to prevent overlap
     * @param arcSpanDeg   total arc span in degrees, also computed by [showControlsCard]
     * @param arcCenterDeg arc direction in degrees (0°=right, 90°=down); may include edge bias
     */
    private fun buildRadialMenu(
        refX: Int,
        refY: Int,
        cardW: Int,
        cardH: Int,
        arcRadiusPx: Float,
        arcSpanDeg: Double,
        arcCenterDeg: Double,
    ): FrameLayout {
        val btnSizePx = dpToPx(RADIAL_BTN_SIZE_DP)
        val pad = dpToPx(RADIAL_BTN_ICON_PAD_DP)

        val actions = getVisibleActions()
        val specs = actions.map { actionToBtnSpec(it) }
        val count = specs.size

        val container = FrameLayout(this)
        // Absorb taps on the transparent background so they close the menu instead of
        // falling through to (or silently blocking) the app behind the overlay.
        container.isClickable = true
        container.setOnClickListener {
            cancelAutoCollapse()
            hideControlsCard()
        }

        specs.forEachIndexed { i, spec ->
            val offset = calculateOffset(i, count, arcRadiusPx, arcCenterDeg, arcSpanDeg)

            // Button centre in screen-absolute coordinates (full-screen card).
            val centreX = refX + offset.x.roundToInt()
            val centreY = refY + offset.y.roundToInt()

            val btn = makeRadialButton(spec.icon, spec.tint, pad, spec.action)
            // Retain pause button reference so its icon can be toggled live without rebuilding.
            if (actions.getOrNull(i) == OverlayAction.PAUSE_RESUME) {
                controlsRadialPauseButton = btn
                controlsPauseButton = null
            }

            val lp = FrameLayout.LayoutParams(btnSizePx, btnSizePx)
            // Loose safety net only — the teleport step in showControlsCard() already
            // guarantees buttons land inside the safe area, so we no longer clamp to
            // topSafe/bottomSafe here (that would deform the "perfect circle").
            lp.leftMargin = (centreX - btnSizePx / 2)
                .coerceIn(0, (cardW - btnSizePx).coerceAtLeast(0))
            lp.topMargin = (centreY - btnSizePx / 2)
                .coerceIn(0, (cardH - btnSizePx).coerceAtLeast(0))
            btn.layoutParams = lp

            // Start at the bubble-centre (origin) so the fan-out translate begins there.
            btn.translationX = -offset.x
            btn.translationY = -offset.y
            btn.scaleX = 0f
            btn.scaleY = 0f
            btn.alpha = 0f

            container.addView(btn)

            // Staggered fan-out: translate from bubble centre to final arc position while
            // scaling in with an overshoot spring effect.
            btn.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(0.92f)
                .setDuration(260)
                .setStartDelay(i * 45L)
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()
        }

        return container
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
     * 48dp circular radial action button — white background. The [tint] parameter is kept
     * for API compatibility with [BtnSpec] and older call sites, but is intentionally NOT
     * applied as a [PorterDuffColorFilter] so multi-color vector XMLs (orange + light
     * blue "Vibe" icons) render with their own fillColors intact.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeRadialButton(
        iconRes: Int,
        @Suppress("UNUSED_PARAMETER")
        tint: Int = 0xFFFF8C00.toInt(),
        padPx: Int = dpToPx(10),
        onClick: () -> Unit,
    ): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(dpToPx(1), 0x1A000000)
                }
            setPadding(padPx, padPx, padPx, padPx)
            elevation = dpToPx(6).toFloat()
            addPressAnim()
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
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
        val card = controlsCardView ?: return
        // Clear state immediately so callers can re-show without waiting for animation.
        controlsCardView = null
        controlsCardParams = null
        controlsRadialPauseButton = null
        controlsPauseButton = null
        controlsMuteButton = null
        controlsCardExpanded = false
        currentVisibleActions = emptyList()
        currentIsPausedForCard = false

        val childCount = card.childCount
        val animDuration = 150L
        for (i in 0 until childCount) {
            val btn = card.getChildAt(i)
            btn.animate()
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(animDuration)
                .setStartDelay((childCount - 1 - i) * 25L)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }
        val totalDelay = (childCount - 1) * 25L + animDuration
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                windowManager?.removeView(card)
            } catch (_: Exception) {
            }
        }, totalDelay)
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
        cancelAutoCollapse()
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
        bubbleIconView = null
        bubbleTimerView = null
        bubbleTimerTextView = null
        idleControlsBubbleVisible = false
        controlsDismissedByUser = userDismissed
        if (userDismissed) {
            cancelOverlayNotification()
            shouldPersistBubble = false
            startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_CONTROLS_DISMISSED })
        }
    }

    /**
     * Updates the pause/resume icon in-place without rebuilding the radial menu.
     * Safe to call even when the menu is not visible — [controlsRadialPauseButton] will be null.
     * Also syncs [currentIsPausedForCard] so a subsequent rebuild won't fire unnecessarily.
     */
    private fun updatePauseResumeIcon() {
        controlsRadialPauseButton?.setImageResource(
            if (controlsIsPaused) R.drawable.ic_play else R.drawable.ic_pause,
        )
        currentIsPausedForCard = controlsIsPaused
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
        // Clamp Y to safe area (status bar / nav bar) on every snap.
        val (_, screenH) = currentScreenSizePx()
        val bubbleSizePx = params.width
        val safeTop = safeAreaTop()
        val safeBottom = safeAreaBottom()
        params.y = params.y.coerceIn(safeTop, (screenH - safeBottom - bubbleSizePx).coerceAtLeast(safeTop))
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

    /** Height in px to stay below the status bar. */
    private fun safeAreaTop(): Int = dpToPx(28)

    /** Height in px to stay above the navigation bar. */
    private fun safeAreaBottom(): Int = dpToPx(48)

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

    // ── Radial menu helpers ─────────────────────────────────────────────────────

    /** Returns the record icon resource matching the current capture mode. */
    private fun idleRecordIconRes(): Int =
        when (currentMode) {
            CaptureMode.CLIPPER -> R.drawable.ic_record_clipper
            CaptureMode.GIF -> R.drawable.ic_record_gif
            else -> R.drawable.ic_record_start
        }

    /** Applies the correct mode icon to [bubbleIconView] without rebuilding the bubble. */
    private fun refreshBubbleIcon() {
        bubbleIconView?.setImageResource(idleRecordIconRes())
    }

    /** Cross-fades the bubble from the mode icon to the recording timer badge. */
    private fun transitionBubbleToRecording() {
        bubbleIconView?.animate()?.alpha(0f)?.setDuration(180)?.start()
        bubbleTimerView?.animate()?.alpha(1f)?.setDuration(180)?.start()
    }

    /** Cross-fades the bubble from the timer badge back to the mode icon. */
    private fun transitionBubbleToIdle() {
        refreshBubbleIcon()
        bubbleTimerView?.animate()?.alpha(0f)?.setDuration(180)?.start()
        bubbleIconView?.animate()?.alpha(1f)?.setDuration(180)?.start()
    }

    // ── Auto-collapse ───────────────────────────────────────────────────────────

    private fun scheduleAutoCollapse() {
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        autoCollapseHandler.postDelayed(autoCollapseRunnable, 4000L)
    }

    private fun cancelAutoCollapse() {
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
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
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
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
                        setImageURI(imageUri.toUri())
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
        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
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
