package com.ibbie.catrec_screenrecorcer.service

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
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
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
        const val ACTION_UPDATE_PAUSE_STATE = "ACTION_UPDATE_PAUSE_STATE"
        const val ACTION_UPDATE_MUTE_STATE = "ACTION_UPDATE_MUTE_STATE"
        const val ACTION_UPDATE_RECORDING_STATE = "ACTION_UPDATE_RECORDING_STATE"
        const val EXTRA_IS_RECORDING = "EXTRA_IS_RECORDING"

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

        private const val CAMERA_CHANNEL_ID = "CatRec_Camera_Channel"
        private const val CAMERA_NOTIFICATION_ID = 42

        var onPreviewPositionChanged: ((xFraction: Float, yFraction: Float) -> Unit)? = null
        var onCameraPreviewPositionChanged: ((xFraction: Float, yFraction: Float) -> Unit)? = null
        private var instance: OverlayService? = null

        fun updatePreviewIfActive(sizeDp: Int, opacity: Int, shape: String, imageUri: String?, xFraction: Float, yFraction: Float) {
            instance?.updateWatermarkPreview(sizeDp, opacity, shape, imageUri, xFraction, yFraction)
        }

        fun updateCameraPreviewIfActive(sizeDp: Int, xFraction: Float, yFraction: Float) {
            instance?.updateCameraPreview(sizeDp, xFraction, yFraction)
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
    private var shouldPersistBubble = false
    private var bubbleAnimator: ValueAnimator? = null
    private var dismissIndicatorView: View? = null
    private var dismissIndicatorParams: WindowManager.LayoutParams? = null

    // Live preview (watermark)
    private var previewBgView: View? = null
    private var previewWatermarkView: ImageView? = null
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        when (intent.action) {
            ACTION_SHOW_OVERLAYS -> {
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
                val watermarkLoc = intent.getStringExtra(EXTRA_WATERMARK_LOCATION) ?: "Top Left"
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
                if (showWatermark) showWatermarkOverlay(watermarkLoc, watermarkImg, watermarkShape, watermarkOpacity, watermarkSize, watermarkX, watermarkY) else hideWatermarkOverlay()
                if (showControls) {
                    controlsIsRecording = true
                    if (controlsBubbleView != null) {
                        // Already showing idle bubble — switch to recording state
                        if (controlsCardExpanded) { hideControlsCard(); showControlsCard() }
                    } else {
                        showControlsOverlay()
                    }
                } else {
                    hideControlsOverlay(userDismissed = false)
                }
            }

            ACTION_HIDE_OVERLAYS -> {
                hideCameraOverlay(); hideWatermarkOverlay()
                hideWatermarkPreview(); hideCameraPreview()
                if (shouldPersistBubble) {
                    // Switch bubble back to idle mode instead of hiding it
                    controlsIsRecording = false
                    if (controlsCardExpanded) { hideControlsCard(); showControlsCard() }
                } else {
                    hideControlsOverlay(userDismissed = false)
                    stopSelf()
                }
            }

            ACTION_SHOW_IDLE_CONTROLS -> {
                shouldPersistBubble = true
                controlsIsRecording = false
                controlsDismissedByUser = false
                if (controlsBubbleView == null) showControlsOverlay()
            }

            ACTION_SHOW_CONTROLS -> {
                controlsDismissedByUser = false
                showControlsOverlay()
                startService(Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_CONTROLS_RESHOWN
                })
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
                    if (controlsCardExpanded) { hideControlsCard(); showControlsCard() }
                }
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

    @SuppressLint("ClickableViewAccessibility")
    private fun showCameraOverlay(
        sizeDp: Int = 120, xFraction: Float = 0.05f, yFraction: Float = 0.1f,
        locked: Boolean = false, aspectRatio: String = "Circle", opacity: Int = 100
    ) {
        if (cameraView != null) return

        val notification = buildCameraNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(CAMERA_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(CAMERA_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Camera foreground start failed: ${e.message}", e)
            return
        }

        val metrics = resources.displayMetrics
        val (widthPx, heightPx) = computeCameraViewSize(sizeDp, aspectRatio)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val params = WindowManager.LayoutParams(
            widthPx, heightPx,
            overlayType(), flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW * xFraction).toInt().coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
            y = (screenH * yFraction).toInt().coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
        }
        cameraViewParams = params

        val container = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = buildOutlineProvider(aspectRatio)
            setBackgroundColor(Color.BLACK)
            alpha = opacity.coerceIn(0, 100) / 100f
        }

        val previewView = PreviewView(this).apply {
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
        } catch (e: Exception) {
            Log.e("OverlayService", "Camera view add failed", e)
            cameraView = null; cameraViewParams = null
            return
        }
        startCamera()
    }

    private fun computeCameraViewSize(sizeDp: Int, aspectRatio: String): Pair<Int, Int> {
        val w = dpToPx(sizeDp)
        val h = when (aspectRatio) {
            "16:9" -> (w * 9 / 16)
            "4:3" -> (w * 3 / 4)
            else -> w // Circle or Square
        }
        return Pair(w, h)
    }

    private fun buildOutlineProvider(aspectRatio: String): ViewOutlineProvider {
        return when (aspectRatio) {
            "Circle" -> object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            "Square" -> object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRect(0, 0, view.width, view.height)
                }
            }
            else -> object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = dpToPx(8).toFloat()
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeCameraGestureListener(
        params: WindowManager.LayoutParams,
        container: FrameLayout,
        aspectRatio: String
    ): View.OnTouchListener {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val newW = (params.width * detector.scaleFactor).toInt().coerceIn(dpToPx(60), dpToPx(240))
                    params.width = newW
                    params.height = when (aspectRatio) {
                        "16:9" -> newW * 9 / 16
                        "4:3" -> newW * 3 / 4
                        else -> newW
                    }
                    try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {}
                    return true
                }
            })

        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var hasDragged = false

        return View.OnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    hasDragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (!hasDragged && (abs(dx) > 8 || abs(dy) > 8)) hasDragged = true
                        if (hasDragged) {
                            params.x = initialX + dx; params.y = initialY + dy
                            try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {}
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
        }
    }

    private fun flipCamera() {
        useFrontCamera = !useFrontCamera
        cameraPreviewView?.let { bindCamera(it) }
    }

    private fun hideCameraOverlay() {
        cameraProvider?.unbindAll(); cameraProvider = null; cameraPreviewView = null
        cameraView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        cameraView = null; cameraViewParams = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    // ── Watermark Overlay ──────────────────────────────────────────────────────

    private fun showWatermarkOverlay(location: String, imageUri: String?, shape: String, opacity: Int, sizeDp: Int, xFraction: Float, yFraction: Float) {
        if (watermarkView != null) return
        val metrics = resources.displayMetrics
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels; val screenH = metrics.heightPixels
        val params = WindowManager.LayoutParams(sizePx, sizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW * xFraction).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
            y = (screenH * yFraction).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
        }
        val image = buildWatermarkImageView(sizePx, opacity, shape, imageUri)
        watermarkView = image
        try { windowManager?.addView(image, params) } catch (e: Exception) {
            Log.e("OverlayService", "Watermark add failed", e); watermarkView = null
        }
    }

    private fun hideWatermarkOverlay() {
        watermarkView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        watermarkView = null
    }

    // ── Controls Overlay ───────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showControlsOverlay() {
        if (controlsBubbleView != null) return
        controlsDismissedByUser = false

        val bubbleSizePx = dpToPx(52)
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels; val screenH = metrics.heightPixels

        val params = WindowManager.LayoutParams(bubbleSizePx, bubbleSizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - bubbleSizePx; y = dpToPx(280)
        }
        controlsBubbleParams = params

        val bubble = buildControlsBubble(bubbleSizePx)
        controlsBubbleView = bubble
        bubble.setOnTouchListener(makeControlsDragListener(params, bubble, bubbleSizePx, screenW, screenH))

        try {
            windowManager?.addView(bubble, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Controls bubble add failed", e)
            controlsBubbleView = null; controlsBubbleParams = null
        }
    }

    private fun buildControlsBubble(sizePx: Int): FrameLayout {
        val bubble = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF0131313.toInt())
                setStroke(dpToPx(2), 0xFFE53935.toInt())
            }
            elevation = dpToPx(6).toFloat()
        }
        val iconView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dpToPx(10); setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val recDot = View(this).apply {
            val dotSize = dpToPx(9)
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dpToPx(4), dpToPx(4), 0)
            }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE53935.toInt()) }
        }
        bubble.addView(iconView); bubble.addView(recDot)
        return bubble
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeControlsDragListener(
        params: WindowManager.LayoutParams, bubble: FrameLayout,
        bubbleSizePx: Int, screenW: Int, screenH: Int
    ): View.OnTouchListener {
        val dismissZoneTop = (screenH * 0.80f).toInt()
        val dismissZoneCenterX = screenW / 2
        val dismissZoneHalfWidth = (screenW * 0.18f).toInt()
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var hasDragged = false; var inDismissZone = false

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleAnimator?.cancel()
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    hasDragged = false; inDismissZone = false; true
                }
                MotionEvent.ACTION_MOVE -> {
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
                        try { windowManager?.updateViewLayout(bubble, params) } catch (_: Exception) {}
                        val bubbleCenterX = params.x + bubbleSizePx / 2
                        val nowInDismiss = params.y >= dismissZoneTop && abs(bubbleCenterX - dismissZoneCenterX) <= dismissZoneHalfWidth
                        if (nowInDismiss != inDismissZone) {
                            inDismissZone = nowInDismiss
                            showOrUpdateDismissIndicator(screenW, screenH, inZone = nowInDismiss)
                            bubble.animate().scaleX(if (nowInDismiss) 0.85f else 1f).scaleY(if (nowInDismiss) 0.85f else 1f).setDuration(150).start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideDismissIndicator()
                    bubble.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    if (!hasDragged) {
                        if (controlsCardExpanded) hideControlsCard()
                        else { controlsCardExpanded = true; showControlsCard() }
                    } else {
                        val bubbleCenterX = params.x + bubbleSizePx / 2
                        if (params.y >= dismissZoneTop && abs(bubbleCenterX - dismissZoneCenterX) <= dismissZoneHalfWidth) {
                            animateDismissBubble(params, bubble, screenW, screenH, bubbleSizePx)
                        } else {
                            animateSnapBubble(params, bubble, if (bubbleCenterX < screenW / 2) 0 else screenW - bubbleSizePx)
                        }
                    }
                    inDismissZone = false; true
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
    private fun showOrUpdateDismissIndicator(screenW: Int, screenH: Int, inZone: Boolean) {
        val wm = windowManager ?: return
        val activeColor  = if (inZone) 0xEEE53935.toInt() else 0xDD333333.toInt()
        val strokeColor  = if (inZone) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()

        if (dismissIndicatorView == null) {
            // First call — create the indicator
            val size = dpToPx(52)
            val indicatorParams = WindowManager.LayoutParams(size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenW / 2 - size / 2; y = screenH - dpToPx(90)
            }
            dismissIndicatorParams = indicatorParams
            val indicator = FrameLayout(this).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(activeColor); setStroke(dpToPx(2), strokeColor)
                }
                elevation = dpToPx(4).toFloat(); alpha = 0f
            }
            val xText = android.widget.TextView(this).apply {
                text = "\u2715"; textSize = 20f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            indicator.addView(xText); dismissIndicatorView = indicator
            try {
                wm.addView(indicator, indicatorParams)
                indicator.animate().alpha(1f).setDuration(150).start()
            } catch (_: Exception) { dismissIndicatorView = null }
        } else {
            // Already shown — just update the background colour to reflect zone state
            (dismissIndicatorView?.background as? GradientDrawable)?.apply {
                setColor(activeColor); setStroke(dpToPx(2), strokeColor)
            }
        }
    }

    private fun hideDismissIndicator() {
        dismissIndicatorView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        dismissIndicatorView = null; dismissIndicatorParams = null
    }

    private fun showControlsCard() {
        if (controlsCardView != null) return
        val bubbleParams = controlsBubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val bubbleSizePx = bubbleParams.width
        val cardW = dpToPx(232)
        val cardH = dpToPx(60)
        val margin = dpToPx(10)
        val isUpperHalf = bubbleParams.y < metrics.heightPixels / 2
        val cardX = (bubbleParams.x + bubbleSizePx / 2 - cardW / 2).coerceIn(0, (metrics.widthPixels - cardW).coerceAtLeast(0))
        val cardY = if (isUpperHalf) bubbleParams.y + bubbleSizePx + margin else (bubbleParams.y - cardH - margin).coerceAtLeast(0)

        val cardParams = WindowManager.LayoutParams(cardW, cardH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = cardX; y = cardY }
        controlsCardParams = cardParams

        val card = buildControlsCard()
        controlsCardView = card; card.alpha = 0f
        try {
            wm.addView(card, cardParams)
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        } catch (e: Exception) {
            Log.e("OverlayService", "Controls card add failed", e)
            controlsCardView = null; controlsCardParams = null
            controlsPauseButton = null; controlsMuteButton = null; controlsCardExpanded = false
        }
    }

    private fun buildControlsCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF0131313.toInt()); cornerRadius = dpToPx(30).toFloat()
                setStroke(dpToPx(1), 0x33FFFFFF.toInt())
            }
            elevation = dpToPx(8).toFloat()
            val padH = dpToPx(12); val padV = dpToPx(8)
            setPadding(padH, padV, padH, padV)
        }

        if (controlsIsRecording) {
            buildRecordingCard(card)
        } else {
            buildIdleCard(card)
        }
        return card
    }

    private fun buildRecordingCard(card: LinearLayout) {
        val btnSize = dpToPx(40); val btnMargin = dpToPx(4)

        // Pause / Resume
        val pauseBtn = ImageView(this).apply {
            setImageResource(if (controlsIsPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { setMargins(btnMargin, 0, btnMargin, 0) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF.toInt()) }
            val pad = dpToPx(8); setPadding(pad, pad, pad, pad)
        }
        controlsPauseButton = pauseBtn
        pauseBtn.setOnClickListener {
            startService(Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                action = if (controlsIsPaused) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE
            })
        }

        // Stop
        val stopBtn = ImageView(this).apply {
            setImageDrawable(GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFE53935.toInt()); cornerRadius = dpToPx(3).toFloat()
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(0xFFE53935.toInt())
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { setMargins(btnMargin, 0, btnMargin, 0) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22E53935.toInt()) }
            val stopPad = dpToPx(12); setPadding(stopPad, stopPad, stopPad, stopPad)
        }
        stopBtn.setOnClickListener {
            startService(Intent(this@OverlayService, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP })
        }

        // Mute
        val muteBtn = ImageView(this).apply {
            setImageResource(if (controlsIsMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
            colorFilter = null
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { setMargins(btnMargin, 0, btnMargin, 0) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF.toInt()) }
            val pad = dpToPx(8); setPadding(pad, pad, pad, pad)
        }
        controlsMuteButton = muteBtn
        muteBtn.setOnClickListener {
            startService(Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                action = if (controlsIsMuted) ScreenRecordService.ACTION_UNMUTE else ScreenRecordService.ACTION_MUTE
            })
        }

        // Screenshot
        val screenshotBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_screenshot)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { setMargins(btnMargin, 0, btnMargin, 0) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF.toInt()) }
            val pad = dpToPx(8); setPadding(pad, pad, pad, pad)
        }
        screenshotBtn.setOnClickListener {
            startService(Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
            })
        }

        card.addView(pauseBtn); card.addView(stopBtn); card.addView(muteBtn); card.addView(screenshotBtn)
    }

    /** Controls card shown when NOT recording — open app to start, or take screenshot. */
    private fun buildIdleCard(card: LinearLayout) {
        val btnSize = dpToPx(40); val btnMargin = dpToPx(4)

        // Record button — opens MainActivity to start a new recording
        val recordBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_video_online)
            setColorFilter(0xFFE53935.toInt())
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { setMargins(btnMargin, 0, btnMargin, 0) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33E53935.toInt()) }
            val pad = dpToPx(8); setPadding(pad, pad, pad, pad)
        }
        recordBtn.setOnClickListener {
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    action = "ACTION_START_RECORDING_FROM_OVERLAY"
                }
                if (intent != null) startActivity(intent)
            } catch (_: Exception) {}
        }

        // Screenshot button
        val screenshotBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_screenshot)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { setMargins(btnMargin, 0, btnMargin, 0) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF.toInt()) }
            val pad = dpToPx(8); setPadding(pad, pad, pad, pad)
        }
        screenshotBtn.setOnClickListener {
            startService(Intent(this@OverlayService, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
            })
        }

        // Label
        val label = android.widget.TextView(this).apply {
            text = "CatRec"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(dpToPx(4), 0, dpToPx(4), 0) }
        }

        card.addView(recordBtn); card.addView(screenshotBtn); card.addView(label)
    }

    private fun hideControlsCard() {
        controlsCardView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        controlsCardView = null; controlsCardParams = null
        controlsPauseButton = null; controlsMuteButton = null; controlsCardExpanded = false
    }

    private fun hideControlsOverlay(userDismissed: Boolean) {
        bubbleAnimator?.cancel(); bubbleAnimator = null
        hideDismissIndicator(); hideControlsCard()
        controlsBubbleView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        controlsBubbleView = null; controlsBubbleParams = null
        controlsDismissedByUser = userDismissed
        if (userDismissed) {
            startService(Intent(this, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_CONTROLS_DISMISSED })
        }
    }

    private fun updateControlsPauseButton() {
        controlsPauseButton?.setImageResource(
            if (controlsIsPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
    }

    private fun updateControlsMuteButton() {
        controlsMuteButton?.setImageResource(if (controlsIsMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
    }

    private fun animateSnapBubble(params: WindowManager.LayoutParams, bubble: FrameLayout, targetX: Int) {
        bubbleAnimator?.cancel()
        val startX = params.x
        bubbleAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 240; interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try { windowManager?.updateViewLayout(bubble, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun animateDismissBubble(params: WindowManager.LayoutParams, bubble: FrameLayout, screenW: Int, screenH: Int, bubbleSizePx: Int) {
        bubbleAnimator?.cancel()
        val targetX = screenW / 2 - bubbleSizePx / 2
        val targetY = screenH - dpToPx(90) - bubbleSizePx / 2
        val startX = params.x; val startY = params.y
        bubbleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250; interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                params.x = (startX + (targetX - startX) * f).toInt()
                params.y = (startY + (targetY - startY) * f).toInt()
                bubble.alpha = 1f - f
                try { windowManager?.updateViewLayout(bubble, params) } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { hideControlsOverlay(userDismissed = true) }
            })
            start()
        }
    }

    // ── Camera Live Preview (Settings) ─────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showCameraPreview(sizeDp: Int, xFraction: Float, yFraction: Float) {
        if (cameraPreviewOverlayView != null) { updateCameraPreview(sizeDp, xFraction, yFraction); return }
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics

        val bgParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val bg = FrameLayout(this).apply { setBackgroundColor(0x44000000.toInt()) }
        cameraPreviewBgView = bg
        try { wm.addView(bg, bgParams) } catch (e: Exception) {
            Log.e("OverlayService", "Camera preview bg add failed", e); cameraPreviewBgView = null; return
        }

        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels; val screenH = metrics.heightPixels
        val wmParams = WindowManager.LayoutParams(sizePx, sizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW * xFraction).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
            y = (screenH * yFraction).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
        }

        val container = buildCameraPreviewContainer(sizePx)
        container.setOnTouchListener(makeCameraPreviewDragListener(wmParams, container, screenW, screenH, sizePx))
        cameraPreviewOverlayView = container; cameraPreviewOverlayParams = wmParams

        try { wm.addView(container, wmParams) } catch (e: Exception) {
            Log.e("OverlayService", "Camera preview add failed", e)
            cameraPreviewOverlayView = null; cameraPreviewOverlayParams = null
        }
    }

    private fun buildCameraPreviewContainer(sizePx: Int): FrameLayout {
        val container = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            setBackgroundColor(Color.BLACK); elevation = dpToPx(6).toFloat()
        }
        val pv = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        settingsCameraPreviewView = pv; container.addView(pv)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                settingsCameraProvider = future.get()
                val previewView = settingsCameraPreviewView ?: return@addListener
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                settingsCameraProvider?.unbindAll()
                settingsCameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            } catch (e: Exception) { Log.e("OverlayService", "Settings camera preview bind failed", e) }
        }, ContextCompat.getMainExecutor(this))
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeCameraPreviewDragListener(
        params: WindowManager.LayoutParams, view: FrameLayout, screenW: Int, screenH: Int, sizePx: Int
    ) = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { view.tag = floatArrayOf(params.x.toFloat(), params.y.toFloat(), event.rawX, event.rawY); true }
            MotionEvent.ACTION_MOVE -> {
                val tag = view.tag as? FloatArray ?: return@OnTouchListener false
                val newX = (tag[0] + (event.rawX - tag[2])).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
                val newY = (tag[1] + (event.rawY - tag[3])).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
                params.x = newX; params.y = newY
                try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}; true
            }
            MotionEvent.ACTION_UP -> {
                val maxX = (screenW - sizePx).coerceAtLeast(1).toFloat()
                val maxY = (screenH - sizePx).coerceAtLeast(1).toFloat()
                onCameraPreviewPositionChanged?.invoke((params.x / maxX).coerceIn(0f, 1f), (params.y / maxY).coerceIn(0f, 1f)); true
            }
            else -> false
        }
    }

    private fun updateCameraPreview(sizeDp: Int, xFraction: Float, yFraction: Float) {
        val wm = windowManager ?: return
        val view = cameraPreviewOverlayView ?: return
        val params = cameraPreviewOverlayParams ?: return
        val metrics = resources.displayMetrics
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels; val screenH = metrics.heightPixels
        params.width = sizePx; params.height = sizePx
        params.x = (screenW * xFraction).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
        params.y = (screenH * yFraction).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    private fun hideCameraPreview() {
        settingsCameraProvider?.unbindAll(); settingsCameraProvider = null; settingsCameraPreviewView = null
        cameraPreviewBgView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }; cameraPreviewBgView = null
        cameraPreviewOverlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        cameraPreviewOverlayView = null; cameraPreviewOverlayParams = null
    }

    // ── Watermark Live Preview ─────────────────────────────────────────────────

    private fun showWatermarkPreview(sizeDp: Int, opacity: Int, shape: String, imageUri: String?, xFraction: Float, yFraction: Float) {
        if (previewBgView != null) { updateWatermarkPreview(sizeDp, opacity, shape, imageUri, xFraction, yFraction); return }
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val bgParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val bg = FrameLayout(this).apply { setBackgroundColor(0x44000000.toInt()) }
        previewBgView = bg
        try { wm.addView(bg, bgParams) } catch (e: Exception) {
            Log.e("OverlayService", "Preview bg add failed", e); previewBgView = null; return
        }
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels; val screenH = metrics.heightPixels
        val wmParams = WindowManager.LayoutParams(sizePx, sizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW * xFraction).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
            y = (screenH * yFraction).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
        }
        val imageView = buildWatermarkImageView(sizePx, opacity, shape, imageUri)
        imageView.setOnTouchListener(makePreviewDragListener(wmParams, imageView, screenW, screenH, sizePx))
        previewWatermarkView = imageView; previewWatermarkParams = wmParams
        try { wm.addView(imageView, wmParams) } catch (e: Exception) { Log.e("OverlayService", "Preview watermark add failed", e) }
    }

    private fun updateWatermarkPreview(sizeDp: Int, opacity: Int, shape: String, imageUri: String?, xFraction: Float, yFraction: Float) {
        val wm = windowManager ?: return
        val existingView = previewWatermarkView ?: return
        val params = previewWatermarkParams ?: return
        val metrics = resources.displayMetrics
        val sizePx = dpToPx(sizeDp)
        val screenW = metrics.widthPixels; val screenH = metrics.heightPixels
        try { wm.removeView(existingView) } catch (_: Exception) {}
        params.width = sizePx; params.height = sizePx
        params.x = (screenW * xFraction).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
        params.y = (screenH * yFraction).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
        val newView = buildWatermarkImageView(sizePx, opacity, shape, imageUri)
        newView.setOnTouchListener(makePreviewDragListener(params, newView, screenW, screenH, sizePx))
        previewWatermarkView = newView
        try { wm.addView(newView, params) } catch (e: Exception) { Log.e("OverlayService", "Preview update failed", e) }
    }

    private fun hideWatermarkPreview() {
        previewBgView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }; previewBgView = null
        previewWatermarkView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        previewWatermarkView = null; previewWatermarkParams = null
    }

    // ── Shared Helpers ─────────────────────────────────────────────────────────

    private fun buildWatermarkImageView(sizePx: Int, opacity: Int, shape: String, imageUri: String?): ImageView {
        return ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = opacity.coerceIn(0, 100) / 100f
            if (imageUri != null) {
                try {
                    setImageURI(Uri.parse(imageUri))
                    if (drawable == null) setImageResource(R.mipmap.ic_launcher)
                } catch (_: Exception) { setImageResource(R.mipmap.ic_launcher) }
            } else { setImageResource(R.mipmap.ic_launcher) }
            if (shape == "Circle") {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
                }
            }
        }
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun makePreviewDragListener(
        params: WindowManager.LayoutParams, view: ImageView, screenW: Int, screenH: Int, sizePx: Int
    ) = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { view.tag = floatArrayOf(params.x.toFloat(), params.y.toFloat(), event.rawX, event.rawY); true }
            MotionEvent.ACTION_MOVE -> {
                val tag = view.tag as? FloatArray ?: return@OnTouchListener false
                val newX = (tag[0] + (event.rawX - tag[2])).toInt().coerceIn(0, (screenW - sizePx).coerceAtLeast(0))
                val newY = (tag[1] + (event.rawY - tag[3])).toInt().coerceIn(0, (screenH - sizePx).coerceAtLeast(0))
                params.x = newX; params.y = newY
                try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}; true
            }
            MotionEvent.ACTION_UP -> {
                val maxX = (screenW - sizePx).coerceAtLeast(1).toFloat()
                val maxY = (screenH - sizePx).coerceAtLeast(1).toFloat()
                onPreviewPositionChanged?.invoke((params.x / maxX).coerceIn(0f, 1f), (params.y / maxY).coerceIn(0f, 1f)); true
            }
            else -> false
        }
    }

    // ── Camera Notification ────────────────────────────────────────────────────

    private fun createCameraChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CAMERA_CHANNEL_ID, "Camera Overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while the camera overlay is active"; setShowBadge(false)
                }
            )
        }
    }

    private fun buildCameraNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CAMERA_CHANNEL_ID)
            .setContentTitle("Camera in use")
            .setContentText("CatRec camera overlay is active — tap to return to app")
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
