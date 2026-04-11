package com.ibbie.catrec_screenrecorcer.service

import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.ibbie.catrec_screenrecorcer.utils.crashlyticsLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

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
    private var eglBlitter: EglBitmapBlitter? = null
    private var useCanvas: Boolean? = null

    private val pendingScreenshot = AtomicReference<((Bitmap?) -> Unit)?>(null)

    /** Serializes frame processing on the relay thread with [stop] so [scratchRowBitmap] is never recycled mid-frame. */
    private val frameLock = Any()

    fun start() {
        synchronized(frameLock) {
            if (virtualDisplay != null) return
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            val thread = HandlerThread("CatRec-FrameRelay").also { it.start() }
            relayThread = thread
            relayHandler = Handler(thread.looper)
            reader.setOnImageAvailableListener({ onImageAvailable() }, relayHandler)
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
            scratchRowBitmap?.recycle()
            scratchRowBitmap = null
            eglBlitter?.release()
            eglBlitter = null
            useCanvas = null
            pendingScreenshot.set(null)
        }
        relayThread?.quitSafely()
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

    private fun onImageAvailable() {
        synchronized(frameLock) {
            val reader = imageReader ?: return
            val image =
                try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    Log.e(TAG, "acquireLatestImage failed", e)
                    return
                } ?: return

            val bitmap =
                try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
            try {
                drawBitmapToEncoder(bitmap)
                deliverScreenshotIfNeeded(bitmap)
            } finally {
                if (bitmap !== scratchRowBitmap) bitmap.recycle()
            }
        }
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
            scratchRowBitmap = Bitmap.createBitmap(stridePixels, h, Bitmap.Config.ARGB_8888)
        }
        buffer.rewind()
        scratchRowBitmap!!.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            scratchRowBitmap!!
        } else {
            Bitmap.createBitmap(scratchRowBitmap!!, 0, 0, w, h)
        }
    }

    private companion object {
        const val TAG = "EncoderFrameRelay"
    }
}

/**
 * Minimal GLES2 path to feed RGBA bitmaps into a [Surface] (e.g. MediaCodec input).
 */
private class EglBitmapBlitter(
    private val surface: Surface,
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
        val full =
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
            )
        val fb = ByteBuffer.allocateDirect(full.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(full).position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, fb)
        fb.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, fb)
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
