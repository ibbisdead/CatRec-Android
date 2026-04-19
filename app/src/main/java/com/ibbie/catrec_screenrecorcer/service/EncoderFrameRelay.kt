package com.ibbie.catrec_screenrecorcer.service

import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.util.concurrent.atomic.AtomicReference
import androidx.core.graphics.createBitmap

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

    private val pendingScreenshot = AtomicReference<((Bitmap?) -> Unit)?>(null)

    /**
     * Coalesces burst [ImageReader] notifications into one [Handler] job so we do not queue
     * many full encode passes; paired with [acquireLatestImage] for latest-frame-first behavior.
     */
    private val processLatestFrameRunnable = Runnable { processLatestFrame() }

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
            try {
                virtualDisplay =
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
            } catch (e: SecurityException) {
                // Android 14+: only one VirtualDisplay per MediaProjection; e.g. screenshot VD still active.
                Log.e(TAG, "createVirtualDisplay rejected for $virtualDisplayName", e)
                crashlyticsLog("EncoderFrameRelay: SecurityException createVirtualDisplay ($virtualDisplayName)")
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
        framePipelineBusy.set(false)
        pendingWhileBusy.set(false)
        adaptiveSignalSink = null
        adaptiveSignalsEnabled = false
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
        pendingScreenshot.set(callback)
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
        h.removeCallbacks(processLatestFrameRunnable)
        h.post(processLatestFrameRunnable)
    }

    private fun processLatestFrame() {
        if (!framePipelineBusy.compareAndSet(false, true)) {
            return
        }
        val adaptiveOn = adaptiveSignalsEnabled && adaptiveSignalSink != null
        val sink = adaptiveSignalSink
        val t0 = if (adaptiveOn) SystemClock.elapsedRealtime() else 0L
        try {
            val m = adaptiveSkipModulo
            if (adaptiveOn && m > 1) {
                val n = adaptiveFrameOrdinal++
                if (n % m != 0) {
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
            deliverScreenshotIfNeeded(bitmap)
        } finally {
            framePipelineBusy.set(false)
            if (pendingWhileBusy.compareAndSet(true, false)) {
                scheduleProcessLatestFrame()
            }
        }
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

    private fun deliverScreenshotIfNeeded(bitmap: Bitmap) {
        val cb = pendingScreenshot.getAndSet(null) ?: return
        val copy =
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.e(TAG, "screenshot copy failed", e)
                cb(null)
                return
            }
        cb(copy)
    }

    private fun drawBitmapToEncoder(bitmap: Bitmap) {
        val surface = encoderInputSurface
        val mode = useCanvas
        if (mode != false) {
            try {
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
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
         * Drop acquired frames older than this (nanoseconds, [SystemClock.elapsedRealtimeNanos]
         * vs [Image.getTimestamp]) when [DROP_STALE_FRAMES] is true. ~100ms caps backlog at ~3
         * frames at 30fps without fps plumbing.
         */
        private const val MAX_CAPTURE_LATENCY_NS = 100_000_000L

        /** Set true to skip encoding when [Image.getTimestamp] shows excessive capture latency (API 29+). */
        private const val DROP_STALE_FRAMES = false

        private const val SLOW_FRAME_MS = 90L
        private const val SLOW_SIGNAL_MIN_INTERVAL_MS = 1000L
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
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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
              vec2 uv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
              gl_FragColor = texture2D(uTexture, uv);
            }
        """
    }
}
