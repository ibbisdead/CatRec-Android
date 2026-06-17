package com.ibbie.catrec_screenrecorcer.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.graphics.createBitmap
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingFeature
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingFeatureCompatibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ScreenshotCaptureController(
    private val service: Service,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val settingsRepository: SettingsRepository,
    private val mediaStorePublisher: MediaStorePublisher,
    private val mediaProjectionManagerProvider: () -> MediaProjectionManager?,
    private val mediaProjectionProvider: () -> MediaProjection?,
    private val setMediaProjection: (MediaProjection?) -> Unit,
    private val setProjectionResult: (code: Int, data: Intent?) -> Unit,
    private val recorderEngineProvider: () -> ActiveRecordingEngine?,
    private val rollingBufferEngineProvider: () -> RollingBufferEngine?,
    private val activeRecordingEngineModeProvider: () -> RecordingEngineMode?,
    private val activeBufferEngineModeProvider: () -> RecordingEngineMode?,
    private val recordingEngineModeProvider: () -> RecordingEngineMode,
    private val isRecorderRunningProvider: () -> Boolean,
    private val isBufferRunningProvider: () -> Boolean,
    private val mainForegroundActiveProvider: () -> Boolean,
    private val setMainForegroundActive: (Boolean) -> Unit,
    private val readyNotificationProvider: () -> Notification,
    private val screenshotFormatProvider: () -> String,
    private val screenshotQualityProvider: () -> Int,
    private val setScreenshotOptions: (format: String?, quality: Int?) -> Unit,
) {
    /**
     * [takeScreenshot] without a running engine uses a dedicated [VirtualDisplay]. From Android 14
     * onward the system rejects a second [MediaProjection.createVirtualDisplay] while one is still
     * active, so release this before starting an encoding engine.
     */
    private var pendingScreenshotVirtualDisplay: VirtualDisplay? = null

    /**
     * Consumer paired with [pendingScreenshotVirtualDisplay]. Tracked here so every teardown path
     * can close it in producer -> consumer -> projection order.
     */
    private var pendingScreenshotImageReader: ImageReader? = null

    /** Sole lock guarding [pendingScreenshotVirtualDisplay] and [pendingScreenshotImageReader]. */
    private val screenshotVirtualDisplayLock = Any()

    /**
     * Set by [ScreenRecordService.ACTION_TAKE_SCREENSHOT_ONE_SHOT] when the current projection was
     * created exclusively for a single standalone screenshot.
     */
    @Volatile
    private var oneShotScreenshotProjection: Boolean = false

    /** Single-flight guard for one-shot projection release and MediaProjection.onStop races. */
    private val releasingOneShotProjection = AtomicBoolean(false)

    fun handleScreenshotAction() {
        when {
            recorderEngineProvider() != null ||
                rollingBufferEngineProvider() != null ||
                mediaProjectionProvider() != null -> takeScreenshot()

            else -> {
                mainHandler.post {
                    try {
                        service.startActivity(
                            Intent(service, OverlayScreenshotProjectionActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } catch (_: Exception) {
                        val launch =
                            service.packageManager.getLaunchIntentForPackage(service.packageName)?.apply {
                                putExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT_PROJECTION, true)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                            }
                        if (launch != null) service.startActivity(launch)
                    }
                }
            }
        }
    }

    /**
     * Handle a single-use screenshot launched from a transparent consent activity. The service
     * must be in the foreground with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
     * before [MediaProjectionManager.getMediaProjection] is called on Android 14+. When any
     * engine or already-active projection exists, route through the normal [takeScreenshot]
     * path and drop the freshly granted token; the existing session's projection is reused.
     */
    @RequiresApi(30)
    fun handleOneShotScreenshotStart(intent: Intent) {
        val code = intent.getIntExtra(ScreenRecordService.EXTRA_RESULT_CODE, 0)
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(ScreenRecordService.EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(ScreenRecordService.EXTRA_DATA)
            }
        val q = intent.getIntExtra(ScreenRecordService.EXTRA_SCREENSHOT_QUALITY, -1)
        setScreenshotOptions(
            intent.getStringExtra(ScreenRecordService.EXTRA_SCREENSHOT_FORMAT),
            if (q >= 0) q.coerceIn(1, 100) else null,
        )

        if (code == 0 || data == null) {
            Log.w(LOG_TAG, "one_shot_screenshot missing projection result")
            stopOneShotForegroundAndSelfIfIdle()
            return
        }

        if (recorderEngineProvider() != null ||
            rollingBufferEngineProvider() != null ||
            mediaProjectionProvider() != null
        ) {
            Log.d(LOG_TAG, "one_shot_screenshot reusing active session projection")
            takeScreenshot()
            return
        }

        try {
            ServiceCompat.startForeground(
                service,
                ScreenRecordService.MAIN_FOREGROUND_NOTIFICATION_ID,
                readyNotificationProvider(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            setMainForegroundActive(true)
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            Log.e(LOG_TAG, "one_shot_screenshot startForeground failed", e)
            stopOneShotForegroundAndSelfIfIdle()
            return
        }

        val projection =
            try {
                mediaProjectionManagerProvider()?.getMediaProjection(code, data)
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                null
            }
        if (projection == null) {
            Log.w(LOG_TAG, "one_shot_screenshot getMediaProjection returned null")
            stopOneShotForegroundAndSelfIfIdle()
            return
        }

        setProjectionResult(code, data)
        setMediaProjection(projection)
        oneShotScreenshotProjection = true
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    mainHandler.post {
                        if (mediaProjectionProvider() === projection) {
                            setMediaProjection(null)
                        }
                        if (oneShotScreenshotProjection &&
                            releasingOneShotProjection.compareAndSet(false, true)
                        ) {
                            try {
                                oneShotScreenshotProjection = false
                                releasePendingScreenshotVirtualDisplay()
                                releasePendingScreenshotImageReader()
                                setProjectionResult(0, null)
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

    fun releasePendingCaptureResources() {
        releasePendingScreenshotVirtualDisplay()
        releasePendingScreenshotImageReader()
    }

    private fun releaseOneShotScreenshotProjectionIfNeeded() {
        if (!oneShotScreenshotProjection) return
        if (!releasingOneShotProjection.compareAndSet(false, true)) return
        try {
            oneShotScreenshotProjection = false
            releasePendingScreenshotVirtualDisplay()
            releasePendingScreenshotImageReader()
            try {
                mediaProjectionProvider()?.stop()
            } catch (_: Exception) {
            }
            setMediaProjection(null)
            setProjectionResult(0, null)
            stopOneShotForegroundAndSelfIfIdle()
        } finally {
            releasingOneShotProjection.set(false)
        }
    }

    private fun stopOneShotForegroundAndSelfIfIdle() {
        if (isRecorderRunningProvider() || isBufferRunningProvider()) return
        if (mainForegroundActiveProvider()) {
            try {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            setMainForegroundActive(false)
        }
        try {
            service.stopSelf()
        } catch (_: Exception) {
        }
        AppControlNotification.refresh(service)
    }

    private fun releasePendingScreenshotVirtualDisplay() {
        synchronized(screenshotVirtualDisplayLock) {
            try {
                pendingScreenshotVirtualDisplay?.release()
            } catch (_: Exception) {
            }
            pendingScreenshotVirtualDisplay = null
        }
    }

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
        recorderEngineProvider()?.let { eng ->
            if (blockUnavailableRecordingScreenshotIfNeeded(activeRecordingEngineModeProvider() ?: recordingEngineModeProvider())) return
            requestScreenshotFromEngine { cb -> eng.requestScreenshot(cb) }
            return
        }
        rollingBufferEngineProvider()?.let { eng ->
            if (blockUnavailableRecordingScreenshotIfNeeded(activeBufferEngineModeProvider() ?: recordingEngineModeProvider())) return
            requestScreenshotFromEngine { cb -> eng.requestScreenshot(cb) }
            return
        }
        val mp =
            mediaProjectionProvider() ?: run {
                mainHandler.post {
                    Toast.makeText(service, service.getString(R.string.error_screenshot_no_projection), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
                releaseOneShotScreenshotProjectionIfNeeded()
                return
            }
        doTakeScreenshotFromProjection(mp, service.resources.displayMetrics)
    }

    private fun blockUnavailableRecordingScreenshotIfNeeded(mode: RecordingEngineMode): Boolean {
        val compatibility =
            RecordingFeatureCompatibility.evaluate(
                mode,
                RecordingFeature.SCREENSHOT_WHILE_RECORDING,
            )
        if (compatibility.available) return false

        Log.i(LOG_TAG, "Screenshot while recording blocked for recording engine mode=$mode")
        mainHandler.post {
            Toast
                .makeText(
                    service,
                    service.getString(
                        compatibility.reasonMessageResId
                            ?: R.string.recording_screenshot_while_recording_unavailable,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            OverlayService.notifyScreenshotCaptureFinished()
        }
        return true
    }

    private fun doTakeScreenshotFromProjection(
        mp: MediaProjection,
        metrics: DisplayMetrics,
    ) {
        if (recorderEngineProvider() != null || rollingBufferEngineProvider() != null) {
            Log.w(LOG_TAG, "doTakeScreenshotFromProjection skipped - engine active, routing to engine path")
            takeScreenshot()
            return
        }
        releasePendingScreenshotVirtualDisplay()
        releasePendingScreenshotImageReader()
        val captureSize =
            RecordingResolutionSupport.clampForVirtualDisplaySafety(
                metrics.widthPixels,
                metrics.heightPixels,
            )
        val width = captureSize.width
        val height = captureSize.height
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
                mainHandler.post {
                    Toast.makeText(service, service.getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
                releaseOneShotScreenshotProjectionIfNeeded()
                return
            } ?: run {
                try {
                    imageReader.close()
                } catch (_: Exception) {
                }
                mainHandler.post {
                    Toast.makeText(service, service.getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
                releaseOneShotScreenshotProjectionIfNeeded()
                return
            }
        synchronized(screenshotVirtualDisplayLock) {
            pendingScreenshotVirtualDisplay = virtualDisplay
            pendingScreenshotImageReader = imageReader
        }

        val mirrorFrameIndex = AtomicInteger(0)
        val mirrorCaptureDone = AtomicBoolean(false)
        val skipMirrorFrames = 8
        val maxMirrorFrames = 64

        fun teardownScreenshotReader(
            reader: ImageReader,
            vd: VirtualDisplay,
        ) {
            if (!mirrorCaptureDone.compareAndSet(false, true)) return
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
                    mainHandler.post {
                        Toast
                            .makeText(
                                service,
                                service.getString(R.string.error_screenshot_capture_failed),
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
                    mainHandler.post {
                        Toast
                            .makeText(
                                service,
                                service.getString(R.string.error_screenshot_capture_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                } finally {
                    image.close()
                }
                teardownScreenshotReader(reader, virtualDisplay)
            },
            mainHandler,
        )
    }

    /** Relay thread invokes [block] with the next frame bitmap (or null). */
    private fun requestScreenshotFromEngine(block: ((Bitmap?) -> Unit) -> Unit) {
        val delivered = AtomicBoolean(false)
        val timeout =
            Runnable {
                if (delivered.compareAndSet(false, true)) {
                    Toast.makeText(service, service.getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    OverlayService.notifyScreenshotCaptureFinished()
                }
            }
        mainHandler.postDelayed(timeout, 3500L)
        block { bitmap ->
            if (!delivered.compareAndSet(false, true)) return@block
            mainHandler.removeCallbacks(timeout)
            mainHandler.post {
                try {
                    if (bitmap != null) {
                        saveScreenshotBitmap(bitmap)
                    } else {
                        Toast.makeText(service, service.getString(R.string.error_screenshot_capture_failed), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    OverlayService.notifyScreenshotCaptureFinished()
                }
            }
        }
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap) {
        val savedUri =
            mediaStorePublisher.saveScreenshotBitmap(
                bitmap = bitmap,
                screenshotFormat = screenshotFormatProvider(),
                screenshotQuality = screenshotQualityProvider(),
            )
        val uriAfterSave = savedUri
        scope.launch(Dispatchers.IO) {
            val showPostActions = settingsRepository.postScreenshotOptions.first()
            withContext(Dispatchers.Main) {
                if (uriAfterSave != null) {
                    if (showPostActions) {
                        service.startActivity(
                            Intent(service, ScreenshotPostActionActivity::class.java).apply {
                                putExtra(
                                    ScreenshotPostActionActivity.EXTRA_IMAGE_URI,
                                    uriAfterSave.toString(),
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } else {
                        Toast.makeText(service, service.getString(R.string.notif_screenshot_saved), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "ScreenRecordService"
    }
}
