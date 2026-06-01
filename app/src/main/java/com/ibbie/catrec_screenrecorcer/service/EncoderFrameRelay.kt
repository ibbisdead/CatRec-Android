package com.ibbie.catrec_screenrecorcer.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.ibbie.catrec_screenrecorcer.utils.crashlyticsLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import androidx.core.graphics.createBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Feeds a single [VirtualDisplay] into [encoderInputSurface] so [MediaProjection.createVirtualDisplay]
 * is only called once. Frames are copied from [ImageReader] via hardware canvas when possible, or
 * via a minimal GLES2 blit when the codec surface rejects [Surface.lockHardwareCanvas].
 */
internal class EncoderFrameRelay(
    private val mediaProjection: MediaProjection,
    private val encoderInputSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val virtualDisplayName: String,
    /** Selected recording FPS — relay will not run the bitmap/encode path faster than this cadence (monotonic pacing). */
    private val targetRecordingFps: Int,
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var relayThread: HandlerThread? = null
    private var relayHandler: Handler? = null

    private var scratchRowBitmap: Bitmap? = null

    /** Tight WxH copy when [ImageReader] row stride has padding; filled from [scratchRowBitmap]. */
    private var reuseCropBitmap: Bitmap? = null
    private var reuseCropCanvas: Canvas? = null
    private val bitmapSrcRect = Rect()
    private val bitmapDstRect = Rect()
    private var eglBlitter: EglBitmapBlitter? = null
    private var useCanvas: Boolean? = null

    /** Last captured frame size (from [Bitmap] after [imageToBitmap]); used for rotation/size logs. */
    private var lastRelayCaptureW = -1
    private var lastRelayCaptureH = -1

    private val encoderDrawPaint =
        Paint().apply {
            isAntiAlias = true
            isDither = true
            isFilterBitmap = true
        }

    private val drawSrcRect = Rect()
    private val drawDstRect = Rect()

    /**
     * Pairs a screenshot callback with the [frameGeneration] value observed when it was armed so
     * [deliverScreenshotIfNeeded] can skip any frame that was already being processed at the time
     * of the request. Prevents handing the caller a stale/in-flight frame.
     */
    private class ScreenshotRequest(
        val armedAtGen: Long,
        val callback: (Bitmap?) -> Unit,
    )

    private val pendingScreenshot = AtomicReference<ScreenshotRequest?>(null)

    /**
     * Monotonic counter stamped onto each [processLatestFrame] pass. A request only succeeds when
     * the current pass's generation is strictly greater than the generation observed when the
     * request was armed — i.e. the frame was acquired AFTER the caller asked for a screenshot.
     */
    private val frameGeneration = AtomicLong(0)

    /**
     * Coalesces burst [ImageReader] notifications into one [Handler] job so we do not queue
     * many full encode passes; paired with [acquireLatestImage] for latest-frame-first behavior.
     */
    private val processLatestFrameRunnable = Runnable { processLatestFrame() }

    /**
     * Wakes [processLatestFrame] after [SystemClock.elapsedRealtimeNanos] pacing wait.
     * Intentionally **not** removed by [scheduleProcessLatestFrame] so image-available spam does not
     * cancel a scheduled pacing wake ([scheduleProcessLatestFrame] only resets [processLatestFrameRunnable]).
     */
    private val pacedResumeRunnable = Runnable { processLatestFrame() }

    private val targetFrameIntervalNs: Long =
        (1_000_000_000L / targetRecordingFps.coerceIn(1, 240)).coerceAtLeast(1L)

    /** Next eligible wall time ([SystemClock.elapsedRealtimeNanos]) for a paced encode; 0 = no hold yet. */
    private var nextProcessEligibleElapsedNs: Long = 0L

    private var skippedByPacingTotal: Long = 0L
    private var skippedByAdaptiveTotal: Long = 0L

    /** Rolling window for measured FPS (relay thread only). */
    private var fpsWindowStartElapsedMs: Long = 0L
    private var framesProcessedInFpsWindow: Int = 0

    /** Supplier for adaptive tier (session diagnostics); optional when adaptive is off. */
    @Volatile
    var adaptiveTierSupplier: (() -> Int)? = null

    /** Single encode pass in flight (defensive; relay looper is already single-threaded). */
    private val framePipelineBusy = AtomicBoolean(false)

    /**
     * Set when [scheduleProcessLatestFrame] runs while [framePipelineBusy] is true so we reschedule
     * one drain after the current pass (avoids dropping notifications when skipping redundant posts).
     */
    private val pendingWhileBusy = AtomicBoolean(false)

    @Volatile
    var adaptiveSignalSink: AdaptiveRecordingSignalSink? = null

    @Volatile
    var adaptiveSignalsEnabled: Boolean = false

    /** When >1, only every Nth relay pass encodes (effective FPS reduction). */
    @Volatile
    var adaptiveSkipModulo: Int = 1
        get() = field.coerceIn(1, 10)
        set(value) {
            field = value.coerceIn(1, 10)
        }

    private var adaptiveFrameOrdinal = 0
    private var lastSlowSignalWallMs = 0L

    /**
     * Pending capture-source resize dimensions (written from any thread, consumed on relay thread).
     * Negative values mean no pending resize. The [resizeCaptureRunnable] is debounced so rapid
     * successive resize triggers (e.g., fold+rotate) coalesce into a single rebuild.
     */
    private val pendingResizeW = AtomicInteger(-1)
    private val pendingResizeH = AtomicInteger(-1)

    private val resizeCaptureRunnable = Runnable {
        val w = pendingResizeW.getAndSet(-1)
        val h = pendingResizeH.getAndSet(-1)
        if (w > 0 && h > 0) doResizeCaptureSource(w, h)
    }

    /**
     * Guards [ImageReader] / [VirtualDisplay] lifecycle and CPU bitmap buffers only.
     * [drawBitmapToEncoder] runs outside this lock so [stop] can tear down the reader/VD while
     * a slow encoder surface completes; bitmaps/EGL are released only after [relayThread] joins.
     */
    private val frameLock = Any()

    fun start() {
        synchronized(frameLock) {
            if (virtualDisplay != null) return
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, FRAME_READER_MAX_IMAGES)
            imageReader = reader
            val thread = HandlerThread("CatRec-FrameRelay").also { it.start() }
            relayThread = thread
            relayHandler = Handler(thread.looper)
            reader.setOnImageAvailableListener({ scheduleProcessLatestFrame() }, relayHandler)
            nextProcessEligibleElapsedNs = 0L
            skippedByPacingTotal = 0L
            skippedByAdaptiveTotal = 0L
            fpsWindowStartElapsedMs = SystemClock.elapsedRealtime()
            framesProcessedInFpsWindow = 0
            RecordingRelayDiagnostics.resetSessionClock()
            try {
                val display =
                    mediaProjection.createVirtualDisplay(
                        virtualDisplayName,
                        width,
                        height,
                        dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface,
                        null,
                        null,
                    )
                        ?: throw IllegalStateException("createVirtualDisplay returned null for $virtualDisplayName")
                virtualDisplay = display
            } catch (e: Exception) {
                // Android 14+: only one VirtualDisplay per MediaProjection; e.g. screenshot VD still active.
                Log.e(TAG, "createVirtualDisplay failed for $virtualDisplayName", e)
                crashlyticsLog("EncoderFrameRelay: createVirtualDisplay failed ($virtualDisplayName)")
                try {
                    reader.setOnImageAvailableListener(null, null)
                } catch (_: Exception) {
                }
                try {
                    reader.close()
                } catch (_: Exception) {
                }
                imageReader = null
                thread.quitSafely()
                relayThread = null
                relayHandler = null
                throw e
            }
        }
    }

    fun stop() {
        val threadToJoin =
            synchronized(frameLock) {
                try {
                    imageReader?.setOnImageAvailableListener(null, null)
                } catch (_: Exception) {
                }
                try {
                    virtualDisplay?.release()
                } catch (_: Exception) {
                }
                virtualDisplay = null
                try {
                    imageReader?.close()
                } catch (_: Exception) {
                }
                imageReader = null
                relayThread
            }
        relayHandler?.removeCallbacks(processLatestFrameRunnable)
        relayHandler?.removeCallbacks(pacedResumeRunnable)
        relayHandler?.removeCallbacks(resizeCaptureRunnable)
        pendingResizeW.set(-1)
        pendingResizeH.set(-1)
        framePipelineBusy.set(false)
        pendingWhileBusy.set(false)
        adaptiveSignalSink = null
        adaptiveSignalsEnabled = false
        adaptiveTierSupplier = null
        adaptiveSkipModulo = 1
        adaptiveFrameOrdinal = 0
        lastSlowSignalWallMs = 0L
        threadToJoin?.quitSafely()
        try {
            threadToJoin?.join(5000)
        } catch (_: Exception) {
        }
        synchronized(frameLock) {
            scratchRowBitmap?.recycle()
            scratchRowBitmap = null
            reuseCropBitmap?.recycle()
            reuseCropBitmap = null
            reuseCropCanvas = null
            eglBlitter?.release()
            eglBlitter = null
            useCanvas = null
            pendingScreenshot.set(null)
            lastRelayCaptureW = -1
            lastRelayCaptureH = -1
        }
        relayThread = null
        relayHandler = null
    }

    /**
     * Captures the next frame after [start] (or null if not running). Callback may run on the relay thread;
     * down-stream should post to main if needed.
     */
    fun requestScreenshot(callback: (Bitmap?) -> Unit) {
        if (virtualDisplay == null) {
            callback(null)
            return
        }
        // Capture the current generation so [deliverScreenshotIfNeeded] only satisfies the
        // request with a frame acquired AFTER this arm point. Any pass already in flight
        // (acquired BEFORE we were called) is rejected, guaranteeing no stale frame.
        pendingScreenshot.set(ScreenshotRequest(frameGeneration.get(), callback))
    }

    /**
     * Resizes the capture [VirtualDisplay] and [ImageReader] to [newW] × [newH] so that
     * SurfaceFlinger fills the **entire** new surface (no stale portrait pixels bleed into a
     * landscape frame or vice-versa).  The encoder output dimensions remain fixed; the
     * letterbox logic in [drawBitmapToEncoder] automatically adapts to any captured size.
     *
     * Safe to call from any thread.  Work is posted and debounced on the relay thread so
     * rapid successive calls (e.g. foldable fold + rotation) coalesce into one rebuild.
     */
    fun resizeCaptureSource(newW: Int, newH: Int) {
        val w = newW.coerceIn(16, 4096)
        val h = newH.coerceIn(16, 4096)
        pendingResizeW.set(w)
        pendingResizeH.set(h)
        val handler = relayHandler ?: return
        handler.removeCallbacks(resizeCaptureRunnable)
        handler.postDelayed(resizeCaptureRunnable, CAPTURE_RESIZE_DEBOUNCE_MS)
    }

    /**
     * Executed on the relay thread after [CAPTURE_RESIZE_DEBOUNCE_MS] of quiet.
     *
     * Root cause this fixes: when the device rotates, the VirtualDisplay retains its original
     * dimensions.  SurfaceFlinger scales the rotated screen into only *part* of the surface;
     * the rest of the buffer keeps stale pixels from the previous orientation.  Those stale
     * pixels survive [copyPixelsFromBuffer] and appear in the recorded video as ghost trails.
     *
     * By resizing the [VirtualDisplay] and recreating the [ImageReader] to match the actual
     * content dimensions, we ensure SurfaceFlinger fills the entire surface on every frame.
     */
    private fun doResizeCaptureSource(newW: Int, newH: Int) {
        synchronized(frameLock) {
            val vd = virtualDisplay ?: return
            val oldReader = imageReader ?: return
            if (newW == oldReader.width && newH == oldReader.height) {
                Log.d(TAG, "doResizeCaptureSource: dims unchanged ${newW}x${newH}, skipping")
                return
            }
            Log.i(
                TAG,
                "doResizeCaptureSource: ${oldReader.width}x${oldReader.height} → ${newW}x${newH} " +
                    "encoderFixed=${width}x${height}",
            )
            val newReader =
                try {
                    ImageReader.newInstance(newW, newH, PixelFormat.RGBA_8888, FRAME_READER_MAX_IMAGES)
                } catch (e: Exception) {
                    Log.e(TAG, "doResizeCaptureSource: ImageReader.newInstance(${newW}x${newH}) failed", e)
                    return
                }
            try {
                vd.resize(newW, newH, dpi)
                vd.setSurface(newReader.surface)
            } catch (e: Exception) {
                Log.e(TAG, "doResizeCaptureSource: VirtualDisplay resize/setSurface failed", e)
                try {
                    newReader.close()
                } catch (_: Exception) {
                }
                return
            }
            newReader.setOnImageAvailableListener({ scheduleProcessLatestFrame() }, relayHandler)
            try {
                oldReader.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
            }
            try {
                oldReader.close()
            } catch (_: Exception) {
            }
            imageReader = newReader
            // Scratch bitmaps sized for the old capture dimensions; discard so they are
            // reallocated with the new dimensions on the very next frame.
            scratchRowBitmap?.recycle()
            scratchRowBitmap = null
            reuseCropBitmap?.recycle()
            reuseCropBitmap = null
            reuseCropCanvas = null
            lastRelayCaptureW = -1
            lastRelayCaptureH = -1
        }
    }

    private fun scheduleProcessLatestFrame() {
        val h = relayHandler ?: return
        if (framePipelineBusy.get()) {
            pendingWhileBusy.set(true)
            if (adaptiveSignalsEnabled) {
                adaptiveSignalSink?.onRelayBackpressure()
            }
            return
        }
        // Only coalesce immediate invokes — never remove [pacedResumeRunnable] (pacing wake).
        h.removeCallbacks(processLatestFrameRunnable)
        h.post(processLatestFrameRunnable)
    }

    private fun schedulePacedResume(delayMs: Long) {
        val h = relayHandler ?: return
        h.removeCallbacks(pacedResumeRunnable)
        h.postDelayed(pacedResumeRunnable, delayMs.coerceIn(1L, 10_000L))
    }

    private fun processLatestFrame() {
        if (!framePipelineBusy.compareAndSet(false, true)) {
            return
        }
        // Bump BEFORE [acquireLatestImage] so the frame about to be grabbed belongs to this
        // generation. A request armed after this point sees a larger start value and will be
        // satisfied only by a later pass — never by this in-flight frame.
        val currentGen = frameGeneration.incrementAndGet()
        val screenshotBypass = pendingScreenshot.get() != null
        val adaptiveOn = adaptiveSignalsEnabled && adaptiveSignalSink != null
        val sink = adaptiveSignalSink
        val t0 = if (adaptiveOn) SystemClock.elapsedRealtime() else 0L
        try {
            val m = adaptiveSkipModulo
            if (adaptiveOn && !screenshotBypass && m > 1) {
                val n = adaptiveFrameOrdinal++
                if (n % m != 0) {
                    skippedByAdaptiveTotal++
                    return
                }
            }

            if (!screenshotBypass) {
                val nowNs = SystemClock.elapsedRealtimeNanos()
                if (nextProcessEligibleElapsedNs != 0L && nowNs < nextProcessEligibleElapsedNs) {
                    skippedByPacingTotal++
                    val delayMs =
                        ((nextProcessEligibleElapsedNs - nowNs + 999_999L) / 1_000_000L).coerceAtLeast(1L)
                    schedulePacedResume(delayMs)
                    return
                }
            }

            val bitmap: Bitmap? =
                synchronized(frameLock) {
                    val reader = imageReader ?: return@synchronized null
                    val image: Image =
                        try {
                            reader.acquireLatestImage()
                        } catch (e: Exception) {
                            Log.e(TAG, "acquireLatestImage failed", e)
                            return@synchronized null
                        } ?: return@synchronized null

                    try {
                        if (shouldDropStaleImage(image)) {
                            return@synchronized null
                        }
                        imageToBitmap(image)
                    } finally {
                        try {
                            image.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            if (bitmap == null) {
                return
            }
            drawBitmapToEncoder(bitmap)
            if (!screenshotBypass) {
                nextProcessEligibleElapsedNs = SystemClock.elapsedRealtimeNanos() + targetFrameIntervalNs
                framesProcessedInFpsWindow++
                val nowMs = SystemClock.elapsedRealtime()
                val winDur = nowMs - fpsWindowStartElapsedMs
                if (winDur >= FPS_MEASURE_WINDOW_MS) {
                    val measured = framesProcessedInFpsWindow * 1000f / winDur.coerceAtLeast(1L)
                    publishDiagnosticsSnapshot(measured, winDur)
                    framesProcessedInFpsWindow = 0
                    fpsWindowStartElapsedMs = nowMs
                }
            }
            if (adaptiveOn && sink != null) {
                val elapsed = SystemClock.elapsedRealtime() - t0
                if (elapsed > SLOW_FRAME_MS) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSlowSignalWallMs >= SLOW_SIGNAL_MIN_INTERVAL_MS) {
                        lastSlowSignalWallMs = now
                        sink.onSlowFrame()
                    }
                }
            }
            deliverScreenshotIfNeeded(bitmap, currentGen)
        } finally {
            maybePublishDiagnosticsNoWindowAdvance()
            framePipelineBusy.set(false)
            if (pendingWhileBusy.compareAndSet(true, false)) {
                scheduleProcessLatestFrame()
            }
        }
    }

    private fun publishDiagnosticsSnapshot(
        measuredFps: Float,
        windowMs: Long,
    ) {
        RecordingRelayDiagnostics.flushIfDue(
            targetFps = targetRecordingFps,
            measuredFpsSnapshot = measuredFps,
            adaptiveTier = adaptiveTierSupplier?.invoke() ?: 0,
            adaptiveSkipModulo = adaptiveSkipModulo,
            skippedByPacingTotal = skippedByPacingTotal,
            skippedByAdaptiveTotal = skippedByAdaptiveTotal,
            framesProcessedInWindow = framesProcessedInFpsWindow,
            windowDurationMs = windowMs,
        )
    }

    /** Time-based flush for tier/modulo/skip totals when the encode window has not ticked. */
    private fun maybePublishDiagnosticsNoWindowAdvance() {
        val nowMs = SystemClock.elapsedRealtime()
        val winDur = nowMs - fpsWindowStartElapsedMs
        val measured =
            if (framesProcessedInFpsWindow > 0 && winDur >= 1L) {
                framesProcessedInFpsWindow * 1000f / winDur
            } else {
                0f
            }
        RecordingRelayDiagnostics.flushIfDue(
            targetFps = targetRecordingFps,
            measuredFpsSnapshot = measured,
            adaptiveTier = adaptiveTierSupplier?.invoke() ?: 0,
            adaptiveSkipModulo = adaptiveSkipModulo,
            skippedByPacingTotal = skippedByPacingTotal,
            skippedByAdaptiveTotal = skippedByAdaptiveTotal,
            framesProcessedInWindow = framesProcessedInFpsWindow,
            windowDurationMs = winDur.coerceAtLeast(0L),
        )
    }

    private fun shouldDropStaleImage(image: Image): Boolean {
        if (!DROP_STALE_FRAMES) {
            return false
        }
        if (Build.VERSION.SDK_INT < 29) {
            return false
        }
        val ts = image.timestamp
        if (ts == 0L) {
            return false
        }
        val ageNs = SystemClock.elapsedRealtimeNanos() - ts
        return ageNs > MAX_CAPTURE_LATENCY_NS
    }

    private fun deliverScreenshotIfNeeded(
        bitmap: Bitmap,
        currentGen: Long,
    ) {
        val req = pendingScreenshot.get() ?: return
        // Only deliver when this frame was acquired AFTER the request was armed. If the request
        // arrived while this pass was already in flight (req.armedAtGen == currentGen), leave it
        // pending so the NEXT relay pass can satisfy it with a fresh frame.
        if (req.armedAtGen >= currentGen) return
        if (!pendingScreenshot.compareAndSet(req, null)) return
        val copy =
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.e(TAG, "screenshot copy failed", e)
                req.callback(null)
                return
            }
        req.callback(copy)
    }

    private fun drawBitmapToEncoder(bitmap: Bitmap) {
        val surface = encoderInputSurface
        val bw = bitmap.width
        val bh = bitmap.height
        val box = letterboxDest(bw, bh)
        maybeLogCaptureSizeChange(bw, bh, box)

        val mode = useCanvas
        if (mode != false) {
            try {
                val canvas = surface.lockHardwareCanvas()
                try {
                    // Encoder surface buffers may retain previous frames; always clear before draw so
                    // letterbox/pillarbox (and any transient capture-size mismatch) stays solid black.
                    canvas.drawColor(Color.BLACK)
                    if (bw > 0 && bh > 0) {
                        drawSrcRect.set(0, 0, bw, bh)
                        drawDstRect.set(box.dx, box.dy, box.dx + box.dw, box.dy + box.dh)
                        canvas.drawBitmap(bitmap, drawSrcRect, drawDstRect, encoderDrawPaint)
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                useCanvas = true
                return
            } catch (e: Exception) {
                Log.w(TAG, "lockHardwareCanvas not usable; using GLES blit", e)
                useCanvas = false
            }
        }
        val blitter = eglBlitter ?: EglBitmapBlitter(surface, width, height).also { eglBlitter = it }
        blitter.draw(bitmap)
    }

    private data class LetterboxDest(
        val dx: Int,
        val dy: Int,
        val dw: Int,
        val dh: Int,
        val scale: Float,
    )

    private fun letterboxDest(
        bw: Int,
        bh: Int,
    ): LetterboxDest {
        val outW = width.coerceAtLeast(1)
        val outH = height.coerceAtLeast(1)
        if (bw <= 0 || bh <= 0) {
            return LetterboxDest(0, 0, outW, outH, 1f)
        }
        val scale = min(outW.toFloat() / bw, outH.toFloat() / bh)
        val dw = (bw * scale).roundToInt().coerceIn(1, outW)
        val dh = (bh * scale).roundToInt().coerceIn(1, outH)
        val dx = (outW - dw) / 2
        val dy = (outH - dh) / 2
        return LetterboxDest(dx, dy, dw, dh, scale)
    }

    private fun maybeLogCaptureSizeChange(
        bw: Int,
        bh: Int,
        box: LetterboxDest,
    ) {
        if (bw == lastRelayCaptureW && bh == lastRelayCaptureH) return
        Log.i(
            TAG,
            "relay_capture_resize old=${lastRelayCaptureW}x${lastRelayCaptureH} new=${bw}x${bh} " +
                "encoder=${width}x${height} scale=${box.scale} dest=${box.dx},${box.dy} ${box.dw}x${box.dh} " +
                "fullFrameClearedBeforeDraw=true",
        )
        lastRelayCaptureW = bw
        lastRelayCaptureH = bh
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val w = image.width
        val h = image.height
        val rowPadding = rowStride - pixelStride * w
        val stridePixels = w + rowPadding / pixelStride
        if (scratchRowBitmap == null || scratchRowBitmap!!.width != stridePixels || scratchRowBitmap!!.height != h) {
            scratchRowBitmap?.recycle()
            scratchRowBitmap = createBitmap(stridePixels, h)
        }
        buffer.rewind()
        scratchRowBitmap!!.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) {
            return scratchRowBitmap!!
        }
        val crop = ensureReuseCropBitmap(w, h)
        val c = reuseCropCanvas!!
        bitmapSrcRect.set(0, 0, w, h)
        bitmapDstRect.set(0, 0, w, h)
        c.drawBitmap(scratchRowBitmap!!, bitmapSrcRect, bitmapDstRect, null)
        return crop
    }

    private fun ensureReuseCropBitmap(
        w: Int,
        h: Int,
    ): Bitmap {
        val existing = reuseCropBitmap
        if (existing != null && existing.width == w && existing.height == h) {
            return existing
        }
        existing?.recycle()
        val b = createBitmap(w, h)
        reuseCropBitmap = b
        reuseCropCanvas = Canvas(b)
        return b
    }

    private companion object {
        const val TAG = "EncoderFrameRelay"

        /**
         * Smallest practical depth for [ImageReader] + [ImageReader.acquireLatestImage].
         * Increase to 3 if QA finds producer underruns on specific OEMs.
         */
        private const val FRAME_READER_MAX_IMAGES = 2

        /**
         * Debounce window for [resizeCaptureSource].  Coalesces rapid rotation/fold events into
         * a single [doResizeCaptureSource] call so we don't thrash the ImageReader.
         */
        private const val CAPTURE_RESIZE_DEBOUNCE_MS = 300L

        /**
         * Drop acquired frames older than this (nanoseconds, [SystemClock.elapsedRealtimeNanos]
         * vs [Image.getTimestamp]) when [DROP_STALE_FRAMES] is true. ~100ms caps backlog at ~3
         * frames at 30fps without fps plumbing.
         */
        private const val MAX_CAPTURE_LATENCY_NS = 100_000_000L

        /** Set true to skip encoding when [Image.getTimestamp] shows excessive capture latency (API 29+). */
        private const val DROP_STALE_FRAMES = false

        private const val SLOW_FRAME_MS = 90L
        private const val SLOW_SIGNAL_MIN_INTERVAL_MS = 1000L

        /** Minimum duration for rolling measured-FPS snapshot before publishing. */
        private const val FPS_MEASURE_WINDOW_MS = 5_000L
    }
}

/**
 * Minimal GLES2 path to feed RGBA bitmaps into a [Surface] (e.g. MediaCodec input).
 */
private class EglBitmapBlitter(
    surface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private val eglDisplay: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val eglContext: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uTexture: Int
    private var texId: Int = 0

    /** Interleaved position.xy + texCoord.xy for TRIANGLE_STRIP quad; reused every [draw]. */
    private val fullScreenQuad: FloatBuffer

    init {
        val major = IntArray(1)
        val minor = IntArray(1)
        if (!EGL14.eglInitialize(eglDisplay, major, 0, minor, 0)) {
            throw IllegalStateException("eglInitialize failed")
        }
        val config =
            chooseConfig(eglDisplay)
                ?: throw IllegalStateException("eglChooseConfig failed")
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        val surfAttrs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfAttrs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        fullScreenQuad =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(
                    floatArrayOf(
                        // Bitmap/Canvas coordinates are top-left origin.  The texture V coordinate
                        // therefore runs 0 at the top edge and 1 at the bottom edge so GLES fallback
                        // matches the hardware-canvas path without mirroring or vertical flip.
                        -1f,
                        -1f,
                        0f,
                        1f,
                        1f,
                        -1f,
                        1f,
                        1f,
                        -1f,
                        1f,
                        0f,
                        0f,
                        1f,
                        1f,
                        1f,
                        0f,
                    ),
                )
                position(0)
            }
    }

    fun draw(bitmap: Bitmap) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
        val bw = bitmap.width
        val bh = bitmap.height
        val outW = width.coerceAtLeast(1)
        val outH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (bw > 0 && bh > 0) {
            val scale = min(outW.toFloat() / bw, outH.toFloat() / bh)
            val dw = (bw * scale).roundToInt().coerceIn(1, outW)
            val dh = (bh * scale).roundToInt().coerceIn(1, outH)
            val dx = (outW - dw) / 2
            val dyTop = (outH - dh) / 2
            val glY = outH - dyTop - dh
            GLES20.glViewport(dx, glY, dw, dh)
        }
        GLES20.glUseProgram(program)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(uTexture, 0)
        fullScreenQuad.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, fullScreenQuad)
        fullScreenQuad.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, fullScreenQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        try {
            if (texId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                texId = 0
            }
        } catch (_: Exception) {
        }
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        } catch (_: Exception) {
        }
        try {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        } catch (_: Exception) {
        }
        try {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
        } catch (_: Exception) {
        }
        try {
            EGL14.eglTerminate(eglDisplay)
        } catch (_: Exception) {
        }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val attrs =
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGLExt.EGL_RECORDABLE_ANDROID,
                EGL14.EGL_TRUE,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0)) return null
        return configs[0]
    }

    private fun buildProgram(
        vs: String,
        fs: String,
    ): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] != GLES20.GL_TRUE) {
            Log.e("EglBitmapBlitter", "Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
        }
        return p
    }

    private fun loadShader(
        type: Int,
        src: String,
    ): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e("EglBitmapBlitter", "Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}")
        }
        return s
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = vec4(aPosition, 0.0, 1.0);
              vTexCoord = aTexCoord;
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
              gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
