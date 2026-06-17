package com.ibbie.catrec_screenrecorcer.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Last-resort recorder used only when the primary MediaCodec pipeline cannot start.
 *
 * This intentionally keeps the surface path simple: MediaProjection -> MediaRecorder surface.
 * It does not support CatRec-only extras such as internal playback capture, separate mic sidecar,
 * screenshots while recording, adaptive bitrate, or relay frame skipping.
 */
internal class MediaRecorderFallbackEngine(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val audioMode: ScreenRecorderEngine.AudioMode,
    private val mediaProjection: MediaProjection,
    private val outputFileDescriptor: FileDescriptor,
    private val audioBitrate: Int,
    private val audioSampleRate: Int,
    private val audioChannelCount: Int,
    private val audioEncoderType: String,
) : ActiveRecordingEngine {
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val started = AtomicBoolean(false)
    private val stoppedWithOutput = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    override fun start() {
        val mediaRecorder = createMediaRecorder()
        recorder = mediaRecorder
        try {
            val useMic = configureAudioIfAvailable(mediaRecorder)
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (useMic) {
                mediaRecorder.setAudioEncoder(resolveAudioEncoder())
                mediaRecorder.setAudioEncodingBitRate(audioBitrate)
                mediaRecorder.setAudioSamplingRate(audioSampleRate)
                mediaRecorder.setAudioChannels(audioChannelCount.coerceIn(1, 2))
            }
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoEncodingBitRate(bitrate)
            mediaRecorder.setVideoFrameRate(fps)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setOutputFile(outputFileDescriptor)
            mediaRecorder.prepare()

            val surface = mediaRecorder.surface
            virtualDisplay =
                mediaProjection.createVirtualDisplay(
                    "CatRecSafeFallback",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null,
                ) ?: throw IllegalStateException("Safe fallback createVirtualDisplay returned null")

            mediaRecorder.start()
            started.set(true)
            Log.i(TAG, "Safe MediaRecorder fallback started size=${width}x$height fps=$fps mic=$useMic")
        } catch (e: Exception) {
            Log.e(TAG, "Safe MediaRecorder fallback start failed", e)
            cleanupAfterFailedStart(mediaRecorder)
            throw e
        }
    }

    override fun stop() {
        val wasStarted = started.getAndSet(false)
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "fallback virtualDisplay.release() failed: ${e.message}")
        }
        virtualDisplay = null

        val mediaRecorder = recorder
        if (mediaRecorder != null && wasStarted) {
            try {
                mediaRecorder.stop()
                stoppedWithOutput.set(true)
            } catch (e: RuntimeException) {
                stoppedWithOutput.set(false)
                Log.e(TAG, "Safe MediaRecorder fallback stop failed; output is not usable", e)
            } catch (e: Exception) {
                stoppedWithOutput.set(false)
                Log.e(TAG, "Safe MediaRecorder fallback stop failed", e)
            }
        }

        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "fallback recorder.release() failed: ${e.message}")
        }
        recorder = null
        paused.set(false)
    }

    override fun hadOutput(): Boolean = stoppedWithOutput.get()

    override fun pause() {
        if (Build.VERSION.SDK_INT < 24 || !started.get() || !paused.compareAndSet(false, true)) return
        try {
            recorder?.pause()
        } catch (e: Exception) {
            paused.set(false)
            Log.w(TAG, "fallback pause failed: ${e.message}")
        }
    }

    override fun resume() {
        if (Build.VERSION.SDK_INT < 24 || !started.get() || !paused.compareAndSet(true, false)) return
        try {
            recorder?.resume()
        } catch (e: Exception) {
            paused.set(true)
            Log.w(TAG, "fallback resume failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    @SuppressLint("MissingPermission")
    private fun configureAudioIfAvailable(mediaRecorder: MediaRecorder): Boolean {
        val wantsMic =
            audioMode == ScreenRecorderEngine.AudioMode.MIC ||
                audioMode == ScreenRecorderEngine.AudioMode.MIXED
        if (!wantsMic) {
            if (audioMode == ScreenRecorderEngine.AudioMode.INTERNAL) {
                Log.w(TAG, "Safe fallback does not support internal playback capture; recording video only")
            }
            return false
        }
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "Safe fallback microphone audio skipped: RECORD_AUDIO not granted")
            return false
        }
        return try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Safe fallback microphone audio skipped: ${e.message}")
            false
        }
    }

    private fun resolveAudioEncoder(): Int =
        when (audioEncoderType) {
            "AAC-HE" -> MediaRecorder.AudioEncoder.HE_AAC
            "AAC-ELD" -> MediaRecorder.AudioEncoder.AAC_ELD
            else -> MediaRecorder.AudioEncoder.AAC
        }

    private fun cleanupAfterFailedStart(mediaRecorder: MediaRecorder) {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            mediaRecorder.reset()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder.release()
        } catch (_: Exception) {
        }
        recorder = null
        started.set(false)
        stoppedWithOutput.set(false)
    }

    private companion object {
        private const val TAG = "MediaRecorderFallback"
    }
}
