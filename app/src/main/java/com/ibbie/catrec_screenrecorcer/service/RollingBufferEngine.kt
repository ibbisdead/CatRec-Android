package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
import com.ibbie.catrec_screenrecorcer.data.ColorMode
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.BuildConfig
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingFatalKind
import com.ibbie.catrec_screenrecorcer.utils.AppLogger
import com.ibbie.catrec_screenrecorcer.utils.AudioRecordingCrashlyticsReporter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Records video + audio into a rolling queue of short MP4 segments.
 *
 * Architecture
 * ────────────
 * • MediaCodec encoders (video + audio) run **continuously** — they are NEVER stopped during
 *   a rotation so there is no codec-startup gap between segments.
 * • Every SEGMENT_DURATION_MS an I-frame is requested.  The drain loop rotates the
 *   MediaMuxer to a fresh file on the very next key-frame so the seam is seamless.
 * • The segment deque keeps at most [maxSegmentsLimit] files.  Older files are deleted.
 * • saveClip() snapshots the current deque + the active partial segment and merges them.
 */
class RollingBufferEngine(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitrate: Int,
    private val fps: Int,
    audioMode: AudioMode,
    private val mediaProjection: MediaProjection,
    private val encoderType: String,
    private val colorMode: String = ColorMode.FULL,
    private val engineMode: RecordingEngineMode = RecordingEngineMode.DEFAULT,
    private val audioBitrate: Int = 128_000,
    private val audioSampleRate: Int = 44_100,
    private val audioChannelCount: Int = 1,
    private val audioEncoderType: String = "AAC-LC",
    /** Completed segments kept (each [SEGMENT_DURATION_MS]); oldest evicted when over limit. */
    maxSegmentsLimit: Int = maxSegmentsForClipperMinutes(1),
    /**
     * Invoked at most once on fatal encoder or muxer errors (drain threads).
     * [RecordingFatalKind] selects the matching [com.ibbie.catrec_screenrecorcer.data.recording.RecordingError] variant.
     */
    private val onFatalRecordingError: ((RecordingFatalKind, String) -> Unit)? = null,
    private val onAudioCaptureDowngraded: ((CharSequence) -> Unit)? = null,
    private val adaptivePreferAvcForPrepare: Boolean = false,
) {
    enum class AudioMode { NONE, MIC, INTERNAL, MIXED }

    private val maxSegments = maxSegmentsLimit.coerceIn(MIN_MAX_SEGMENTS, ABSOLUTE_MAX_SEGMENTS)

    companion object {
        private const val TAG = "RollingBufferEngine"
        const val SEGMENT_DURATION_MS = 10_000L
        private const val INTERNAL_SILENCE_STAGE1_MS = InternalAudioHealthTracker.INTERNAL_AUDIO_STAGE1_SILENCE_MS
        private const val INTERNAL_SILENCE_STAGE2_MS = InternalAudioHealthTracker.INTERNAL_AUDIO_STAGE2_SILENCE_MS
        private const val INTERNAL_SILENCE_PERSISTENT_MS =
            InternalAudioHealthTracker.INTERNAL_AUDIO_PERSISTENT_SILENCE_MS

        /** See [ScreenRecorderEngine.INTERNAL_SILENCE_RECREATE_ATTEMPTS]. */
        private const val INTERNAL_SILENCE_RECREATE_ATTEMPTS = 2

        /** Video drain pacing — see [ScreenRecorderEngine] companion. */
        private const val VIDEO_DRAIN_SLEEP_MS = 2L

        private const val READ_ERROR_LOG_EVERY_N = 50L
        private const val PCM_DROP_LOG_EVERY_N = 250L
        internal const val AUDIO_DIAG_MARKER = "[CatRecAudioSession]"

        private val verboseAudioDiagnosticsEnabled: Boolean
            get() = BuildConfig.DEBUG

        private const val MIN_MAX_SEGMENTS = 6 // 1 min
        private const val ABSOLUTE_MAX_SEGMENTS = 30 // 5 min × 6 segments

        /** @param minutes Clipper duration preset in minutes (1–5). */
        fun maxSegmentsForClipperMinutes(minutes: Int): Int = minutes.coerceIn(1, 5) * (60_000 / SEGMENT_DURATION_MS.toInt())
    }

    // ── Encoder objects ────────────────────────────────────────────────────────
    private var videoEncoder: MediaCodec? = null
    private var captureWidth: Int = width
    private var captureHeight: Int = height
    private var configuredVideoMime: String = ""
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var frameRelay: EncoderFrameRelay? = null
    private var directVirtualDisplay: VirtualDisplay? = null
    private var mAudioMode = audioMode

    // ── Muxer state (guarded by muxerLock) ────────────────────────────────────
    private val muxerLock = object {}

    /** Reused for [MediaMuxer.writeSampleData] on the video drain thread only. */
    private val muxerVideoSampleInfo = MediaCodec.BufferInfo()

    /** Reused for [MediaMuxer.writeSampleData] on the audio drain thread only. */
    private val muxerAudioSampleInfo = MediaCodec.BufferInfo()
    private var currentMuxer: MediaMuxer? = null
    private var currentSegFile: File? = null // file currently being written
    private var muxerVideoTrack = -1
    private var muxerAudioTrack = -1
    private var isMuxerReady = false
    private var storedVideoFmt: MediaFormat? = null
    private var storedAudioFmt: MediaFormat? = null

    /** Last PTS written to the muxer for the audio track in the current segment; guarded by [muxerLock]. */
    private var lastAudioMuxerPtsUs = -1L

    // Timestamp base for the current segment (encoder time when the segment started)
    private var segStartPtsUs = AtomicLong(-1L)

    // ── Completed segment queue ────────────────────────────────────────────────
    private val segments = ArrayDeque<File>() // oldest-first; guarded by muxerLock
    private val segmentDir = File(context.cacheDir, "rolling_segments").also { it.mkdirs() }

    // ── Control flags ──────────────────────────────────────────────────────────
    private val isRunning = AtomicBoolean(false)
    private val pendingRotate = AtomicBoolean(false)
    private val videoEncodeFatalSignaled = AtomicBoolean(false)

    // ── Audio ──────────────────────────────────────────────────────────────────
    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private val effectiveChannelCount = AtomicInteger(audioChannelCount.coerceIn(1, 2))
    private val isMuted = AtomicBoolean(false)

    private val pcmDropRollingAacInput = AtomicLong(0)
    private val rollingAudioReadNegativeCount = AtomicLong(0)
    private val rollingAudioPcmSamplesQueued = AtomicLong(0)
    private val rollingInternalSilenceDiagFired = AtomicBoolean(false)

    private val rollingInternalPlaybackPcmReadsPositive = AtomicLong(0)
    private val rollingInternalPlaybackPcmNonZeroBuffers = AtomicLong(0)
    private val rollingInternalPlaybackPcmSilentOnlyBuffers = AtomicLong(0)

    /** Loop index of the first non-zero internal PCM buffer, or -1 if never observed. */
    private val rollingInternalPlaybackPcmFirstNonZeroLoop = AtomicLong(-1L)

    /** elapsedRealtime() of session start; used to compute "first non-zero delay" diagnostics. */
    private var rollingCaptureStartElapsedMs: Long = 0L

    /** elapsedRealtime() of the first non-zero internal PCM buffer, or -1 if never observed. */
    private var rollingInternalPlaybackPcmFirstNonZeroElapsedMs: Long = -1L

    private val rollingInternalPlaybackRecreateAttempts = AtomicInteger(0)
    private val rollingInternalPlaybackRecreateSucceeded = AtomicBoolean(false)

    private var rollingInternalPlaybackPcmCountersActive = false

    @Volatile
    private var rollingMicLegEverAudibleThisSession: Boolean = false
    private var rollingInternalPlaybackBufferBytesConfigured = 0
    private var rollingInternalPlaybackChannelMaskConfigured = AudioFormat.CHANNEL_IN_MONO
    private var configuredBufferAacChannels: Int = 1
    private val rollingCaptureRequestedAudioMode: AudioMode = audioMode
    private val internalAudioHealth =
        InternalAudioHealthTracker(
            recordingType = "rolling_buffer",
            engineModeName = engineMode.name,
            requestedAudioModeName = audioMode.name,
            micRequested = audioMode == AudioMode.MIC || audioMode == AudioMode.MIXED,
            internalRequested = audioMode == AudioMode.INTERNAL || audioMode == AudioMode.MIXED,
            separateMicRequested = false,
            requestedSampleRate = audioSampleRate,
        )

    // ── Threads ────────────────────────────────────────────────────────────────
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var rotationScheduler: ScheduledExecutorService? = null
    private val performanceScreenshotBlockedLogged = AtomicBoolean(false)
    private val performanceResizeIgnoredLogged = AtomicBoolean(false)

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    fun start() {
        rollingInternalPlaybackPcmReadsPositive.set(0L)
        rollingInternalPlaybackPcmNonZeroBuffers.set(0L)
        rollingInternalPlaybackPcmSilentOnlyBuffers.set(0L)
        rollingInternalPlaybackPcmFirstNonZeroLoop.set(-1L)
        rollingInternalPlaybackPcmFirstNonZeroElapsedMs = -1L
        rollingInternalPlaybackRecreateAttempts.set(0)
        rollingInternalPlaybackRecreateSucceeded.set(false)
        rollingInternalPlaybackPcmCountersActive = false
        rollingMicLegEverAudibleThisSession = false
        rollingInternalPlaybackBufferBytesConfigured = 0
        rollingCaptureStartElapsedMs = SystemClock.elapsedRealtime()
        internalAudioHealth.reset(rollingCaptureStartElapsedMs)

        clearSegmentDir()
        prepareVideoEncoder()
        prepareAudioEncoder()
        openNewSegment() // prepare the first muxer before we start encoding

        try {
            videoEncoder?.start()
        } catch (e: Exception) {
            val wishedHevc =
                encoderType == "H.265 (HEVC)" &&
                    !Build.MODEL.contains("sdk_gphone", ignoreCase = true) &&
                    !Build.MODEL.contains("google_sdk", ignoreCase = true)
            if (wishedHevc && configuredVideoMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                Log.e(
                    TAG,
                    "Buffer video encoder start failed on HEVC (${e.javaClass.simpleName}: ${e.message}); " +
                        "re-preparing with AVC — brand=${Build.BRAND} model=${Build.MODEL}",
                    e,
                )
                try {
                    videoEncoder?.release()
                } catch (_: Exception) {
                }
                try {
                    inputSurface?.release()
                } catch (_: Exception) {
                }
                videoEncoder = null
                inputSurface = null
                synchronized(muxerLock) {
                    finalizeMuxer()
                    currentSegFile?.delete()
                    currentSegFile = null
                }
                storedVideoFmt = null
                prepareVideoEncoder(avcOnly = true)
                openNewSegment()
                Log.i(TAG, "Retrying buffer video encoder start with AVC after HEVC start failure")
                videoEncoder?.start()
            } else {
                throw e
            }
        }
        if (mAudioMode != AudioMode.NONE) audioEncoder?.start()

        startVideoProducer()

        isRunning.set(true)

        videoThread = Thread({ drainVideoLoop() }, "CatRec-Buffer-Video").also { it.start() }

        // Match normal recording: attach the MediaProjection producer before starting playback
        // capture, otherwise some OEM routes bind AudioRecord to an inactive projection and stay
        // silent for the whole buffer session.
        validateRollingAudioRecordCapturesOrAdjustOrThrow()
        Log.i(
            TAG,
            "${AUDIO_DIAG_MARKER} BUFFER PHASE=capture_ready mode=$mAudioMode effCh=${effectiveChannelCount.get()} " +
                "aacChCfg=$configuredBufferAacChannels mic=${micRecord != null} internal=${internalRecord != null}",
        )
        AudioRecordingCrashlyticsReporter.onBufferCaptureReady(
            capturedModeName = mAudioMode.name,
            micRecordPresent = micRecord != null,
            internalRecordPresent = internalRecord != null,
            effectiveChannelCount = effectiveChannelCount.get(),
            aacConfiguredChannels = configuredBufferAacChannels,
        )

        if (mAudioMode != AudioMode.NONE) {
            audioThread = Thread({ captureAudioLoop() }, "CatRec-Buffer-AudioCap").also { it.start() }
            audioDrainThread = Thread({ drainAudioLoop() }, "CatRec-Buffer-AudioDrain").also { it.start() }
        }

        // Schedule I-frame + rotation every SEGMENT_DURATION_MS
        rotationScheduler = Executors.newSingleThreadScheduledExecutor()
        rotationScheduler?.scheduleWithFixedDelay(
            { requestRotation() },
            SEGMENT_DURATION_MS,
            SEGMENT_DURATION_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun startVideoProducer() {
        val surface = inputSurface ?: throw IllegalStateException("Buffer video input surface unavailable")
        when (engineMode) {
            RecordingEngineMode.PERFORMANCE -> {
                Log.i(TAG, "Starting Performance rolling buffer engine: VirtualDisplay -> MediaCodec input surface")
                directVirtualDisplay =
                    mediaProjection.createVirtualDisplay(
                        "CatRecBuffer",
                        captureWidth,
                        captureHeight,
                        dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null,
                    ) ?: throw IllegalStateException("Performance rolling buffer createVirtualDisplay returned null")
            }

            RecordingEngineMode.COMPATIBILITY -> {
                Log.i(TAG, "Starting Compatibility rolling buffer engine: EncoderFrameRelay -> MediaCodec input surface")
                frameRelay =
                    EncoderFrameRelay(
                        mediaProjection,
                        surface,
                        captureWidth,
                        captureHeight,
                        dpi,
                        "CatRecBuffer",
                        fps,
                    ).also { it.start() }
            }
        }
    }

    private fun stopVideoProducer() {
        try {
            frameRelay?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "frameRelay.stop() failed: ${e.message}")
        }
        frameRelay = null

        try {
            directVirtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "directVirtualDisplay.release() failed: ${e.message}")
        }
        directVirtualDisplay = null
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "Stopping buffer engine…")

        rotationScheduler?.shutdown()
        rotationScheduler = null

        stopVideoProducer()

        try {
            micRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            internalRecord?.stop()
        } catch (_: Exception) {
        }
        audioThread?.join(2000)

        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        signalAudioEOS()

        videoThread?.join(2000)
        audioDrainThread?.join(2000)

        val finalMuxerAudioWritten = rollingAudioPcmSamplesQueued.get() > 0L
        val finalMuxerStarted =
            synchronized(muxerLock) {
                val started = isMuxerReady
                finalizeMuxer()
                started
            }

        logRollingBufferAudioStopDiag(
            muxerStartedBeforeFinalize = finalMuxerStarted,
            muxerAudioSamplesWrittenBeforeFinalize = finalMuxerAudioWritten,
        )

        videoEncoder?.release()
        videoEncoder = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null

        audioEncoder?.release()
        audioEncoder = null
        micRecord?.release()
        micRecord = null
        internalRecord?.release()
        internalRecord = null
        Log.d(TAG, "Buffer engine stopped.")
    }

    /**
     * Returns a snapshot of all completed segments plus the current partial segment.
     * Call this before stop() to ensure the active segment is included.
     */
    fun getSegmentSnapshot(): List<File> =
        synchronized(muxerLock) {
            (segments.toList() + listOfNotNull(currentSegFile?.takeIf { it.exists() && it.length() > 0 }))
        }

    /**
     * Merges all buffered segments into [outputFile] using stream-copy (no re-encode).
     * Returns true on success.
     */
    fun saveClip(outputFile: File): Boolean {
        val files = getSegmentSnapshot()
        if (files.isEmpty()) return false
        return ClipMerger.merge(files, outputFile)
    }

    /** Next frame after the request is delivered on the relay thread. */
    fun requestScreenshot(onBitmap: (Bitmap?) -> Unit) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) {
            if (performanceScreenshotBlockedLogged.compareAndSet(false, true)) {
                Log.w(TAG, "Screenshot while rolling buffer is unavailable in Performance engine")
            }
            onBitmap(null)
            return
        }
        val relay = frameRelay
        if (!isRunning.get() || relay == null) {
            onBitmap(null)
            return
        }
        relay.requestScreenshot(onBitmap)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Internal — segment management
    // ══════════════════════════════════════════════════════════════════════════

    private fun requestRotation() {
        pendingRotate.set(true)
        // Hint the encoder to produce an I-frame so the rotation is seamless
        try {
            val p = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            videoEncoder?.setParameters(p)
        } catch (_: Exception) {
        }
    }

    fun applyAdaptiveVideoBitrateBps(targetBps: Int): Boolean {
        val encoder = videoEncoder ?: return false
        val baseline = bitrate
        val floorBps = (baseline * 0.20).toInt().coerceAtLeast(200_000)
        val capped = targetBps.coerceIn(floorBps, baseline)
        return try {
            val b =
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, capped)
                }
            encoder.setParameters(b)
            true
        } catch (e: Exception) {
            Log.w(TAG, "applyAdaptiveVideoBitrateBps failed: ${e.message}")
            false
        }
    }

    fun setAdaptiveSkipModulo(modulo: Int) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) return
        frameRelay?.adaptiveSkipModulo = modulo
    }

    /**
     * Resizes the capture [VirtualDisplay] + [ImageReader] to [newW]×[newH] without changing
     * the encoder output resolution.  Call this when the OS reports a content-size change
     * (rotation, fold) so stale pixels no longer contaminate the captured frames.
     */
    fun resizeCaptureSource(newW: Int, newH: Int) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) {
            if (performanceResizeIgnoredLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "resizeCaptureSource ignored in Performance rolling buffer; encoder dimensions remain ${captureWidth}x$captureHeight",
                )
            }
            return
        }
        frameRelay?.resizeCaptureSource(newW, newH)
    }

    fun attachAdaptivePerformance(
        sink: AdaptiveRecordingSignalSink?,
        signalsEnabled: Boolean,
        adaptiveTierSupplier: (() -> Int)? = null,
    ) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) return
        frameRelay?.adaptiveSignalSink = sink
        frameRelay?.adaptiveSignalsEnabled = signalsEnabled
        frameRelay?.adaptiveTierSupplier = adaptiveTierSupplier
    }

    /** Called from the video drain thread at an I-frame boundary. */
    private fun rotateSegment() {
        synchronized(muxerLock) {
            // Finalise the current muxer
            finalizeMuxer()

            // Push completed file to the deque; evict oldest if over limit
            currentSegFile?.takeIf { it.exists() && it.length() > 0 }?.let { done ->
                segments.addLast(done)
                while (segments.size > maxSegments) segments.removeFirst().delete()
            }

            // Open the next segment
            openNewSegmentLocked()
        }
    }

    private fun openNewSegment() = synchronized(muxerLock) { openNewSegmentLocked() }

    private fun openNewSegmentLocked() {
        val file = File(segmentDir, "seg_${System.currentTimeMillis()}.mp4")
        currentSegFile = file
        segStartPtsUs.set(-1L)
        lastAudioMuxerPtsUs = -1L

        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        currentMuxer = muxer
        muxerVideoTrack = -1
        muxerAudioTrack = -1
        isMuxerReady = false

        // If we already know the codec formats (subsequent segments), start the muxer right away.
        val vFmt = storedVideoFmt
        val aFmt = storedAudioFmt
        if (vFmt != null) {
            muxerVideoTrack = muxer.addTrack(vFmt)
            if (aFmt != null && mAudioMode != AudioMode.NONE) muxerAudioTrack = muxer.addTrack(aFmt)
            muxer.start()
            isMuxerReady = true
        }
        Log.d(TAG, "New segment: ${file.name}  immediate=$isMuxerReady")
    }

    private fun finalizeMuxer() {
        if (isMuxerReady) {
            try {
                currentMuxer?.stop()
            } catch (_: Exception) {
            }
            isMuxerReady = false
        }
        try {
            currentMuxer?.release()
        } catch (_: Exception) {
        }
        currentMuxer = null
    }

    private fun clearSegmentDir() {
        segmentDir.listFiles()?.forEach { it.delete() }
        synchronized(muxerLock) { segments.clear() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Internal — video drain
    // ══════════════════════════════════════════════════════════════════════════

    private fun drainVideoLoop() {
        val info = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            drainVideoOnce(info)
            try {
                Thread.sleep(VIDEO_DRAIN_SLEEP_MS)
            } catch (_: InterruptedException) {
            }
        }
        // Drain remaining frames
        repeat(60) {
            drainVideoOnce(info)
            try {
                Thread.sleep(VIDEO_DRAIN_SLEEP_MS)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun reportFatalRecordingIfNeeded(
        e: Exception,
        kind: RecordingFatalKind,
        phase: String,
    ) {
        if (!videoEncodeFatalSignaled.compareAndSet(false, true)) return
        val detail =
            buildString {
                append(phase)
                append(" ")
                append(e.javaClass.simpleName)
                append(": ")
                append(e.message)
            }
        Log.e(
            TAG,
            "RecordingFatal kind=$kind phase=$phase mime=$configuredVideoMime encoder=$encoderType detail=$detail",
            e,
        )
        FirebaseCrashlytics.getInstance().log(
            "RecordingFatal kind=$kind phase=$phase $detail mime=$configuredVideoMime",
        )
        onFatalRecordingError?.invoke(kind, detail)
    }

    private fun drainVideoOnce(info: MediaCodec.BufferInfo) {
        val enc = videoEncoder ?: return
        loop@ while (true) {
            try {
                val status = enc.dequeueOutputBuffer(info, 0)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> break@loop

                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            storedVideoFmt = enc.outputFormat
                            if (!isMuxerReady) {
                                muxerVideoTrack = currentMuxer!!.addTrack(storedVideoFmt!!)
                                val aFmt = storedAudioFmt
                                if (aFmt != null && mAudioMode != AudioMode.NONE) {
                                    muxerAudioTrack = currentMuxer!!.addTrack(aFmt)
                                }
                                val audioReady = mAudioMode == AudioMode.NONE || muxerAudioTrack >= 0
                                if (audioReady) {
                                    currentMuxer!!.start()
                                    isMuxerReady = true
                                }
                            }
                        }
                    }

                    status >= 0 -> {
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        // Rotate at the I-frame boundary (seamless, no gap)
                        if (isKey && pendingRotate.compareAndSet(true, false)) {
                            rotateSegment()
                        }

                        val buf = enc.getOutputBuffer(status)
                        if (buf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            info.size > 0
                        ) {
                            synchronized(muxerLock) {
                                if (isMuxerReady && muxerVideoTrack >= 0) {
                                    // Normalise timestamps so each segment starts at t=0
                                    if (segStartPtsUs.compareAndSet(-1L, info.presentationTimeUs)) {
                                        // First frame of this segment — offset is now set
                                    }
                                    val adjPts = info.presentationTimeUs - segStartPtsUs.get()
                                    buf.position(info.offset)
                                    buf.limit(info.offset + info.size)
                                    muxerVideoSampleInfo.set(
                                        info.offset,
                                        info.size,
                                        adjPts.coerceAtLeast(0L),
                                        info.flags,
                                    )
                                    currentMuxer?.writeSampleData(muxerVideoTrack, buf, muxerVideoSampleInfo)
                                }
                            }
                        }
                        enc.releaseOutputBuffer(status, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            } catch (e: Exception) {
                reportFatalRecordingIfNeeded(
                    e,
                    kind = RecordingFatalKind.HardwareVideoEncoder,
                    phase = "video_buffer_dequeue_or_output",
                )
                break@loop
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Internal — audio capture + drain
    // ══════════════════════════════════════════════════════════════════════════

    private fun captureAudioLoop() {
        val bufSize = 2048 * effectiveChannelCount.get()
        val main = ByteArray(bufSize)
        val mix = ByteArray(bufSize)

        Log.d(
            TAG,
            "captureAudioLoop started: mode=$mAudioMode bufSize=$bufSize " +
                "internalRecord=${internalRecord != null} micRecord=${micRecord != null}",
        )

        var loopCount = 0L
        var firstAnyNonZeroLogged = false
        var firstInternalNonZeroLogged = false
        var internalSilentStartMs = -1L
        var silenceCallbackFired = false
        var recoveredAfterRebuildTelemetryReported = false
        var lateRecoveryTelemetryReported = false

        while (isRunning.get()) {
            var readCount = 0
            var internalReadCount = 0
            var internalHadAudibleThisIteration = false

            when {
                mAudioMode == AudioMode.MIXED && internalRecord != null && micRecord != null -> {
                    val r1 = internalRecord!!.read(main, 0, bufSize)
                    internalReadCount = r1
                    internalHadAudibleThisIteration =
                        internalAudioHealth.observeRead(main, internalReadCount, SystemClock.elapsedRealtime())
                    val r2 = micRecord!!.read(mix, 0, bufSize)
                    if (r2 > 0 && InternalPlaybackPcmSilenceAnalyzer.pcm16BufferHasAudibleSignal(mix, r2)) {
                        rollingMicLegEverAudibleThisSession = true
                    }
                    when {
                        r1 > 0 && r2 > 0 -> {
                            mixPcm(main, mix, minOf(r1, r2), effectiveChannelCount.get())
                            readCount = minOf(r1, r2)
                        }
                        r1 > 0 -> readCount = r1
                        r2 > 0 -> {
                            System.arraycopy(mix, 0, main, 0, r2)
                            readCount = r2
                        }
                    }
                }
                internalRecord != null -> {
                    readCount = internalRecord!!.read(main, 0, bufSize)
                    internalReadCount = readCount
                    internalHadAudibleThisIteration =
                        internalAudioHealth.observeRead(main, internalReadCount, SystemClock.elapsedRealtime())
                }
                micRecord != null -> readCount = micRecord!!.read(main, 0, bufSize)
            }

            loopCount++

            if (readCount > 0) {
                if (isMuted.get()) main.fill(0, 0, readCount)
                feedAudioEncoder(main, readCount)

                if (verboseAudioDiagnosticsEnabled &&
                    !firstAnyNonZeroLogged &&
                    main.asSequence().take(readCount).any { it != 0.toByte() }
                ) {
                    firstAnyNonZeroLogged = true
                    Log.d(TAG, "First non-zero audio buffer at loop=$loopCount mode=$mAudioMode")
                    logAnalyticsEvent(
                        "first_audio_buffer_received",
                        mapOf(
                            "loop" to loopCount.toString(),
                            "mode" to mAudioMode.name,
                            "brand" to Build.BRAND,
                            "model" to Build.MODEL,
                        ),
                    )
                }
            } else if (readCount < 0) {
                val n = rollingAudioReadNegativeCount.incrementAndGet()
                if (n == 1L || n % READ_ERROR_LOG_EVERY_N == 0L) {
                    Log.w(
                        TAG,
                        "${AUDIO_DIAG_MARKER} BUFFER AUDIO_READ_ERROR code=$readCount loop=$loopCount total=$n",
                    )
                }
                try {
                    Thread.sleep(5)
                } catch (_: Exception) {
                }
            }

            // ── Internal PCM diagnostics + silence handling ─────────────────────
            // Counters run for the whole session so the verbose intPCM summary
            // correctly reflects "any non-zero ever" (including app switches mid-session).
            if (internalRecord != null) {
                val internalAudible = internalHadAudibleThisIteration

                if (internalReadCount > 0 && rollingInternalPlaybackPcmCountersActive) {
                    rollingInternalPlaybackPcmReadsPositive.incrementAndGet()
                    if (internalAudible) {
                        rollingInternalPlaybackPcmNonZeroBuffers.incrementAndGet()
                        if (rollingInternalPlaybackPcmFirstNonZeroLoop.compareAndSet(-1L, loopCount)) {
                            rollingInternalPlaybackPcmFirstNonZeroElapsedMs = SystemClock.elapsedRealtime()
                            if (!firstInternalNonZeroLogged) {
                                firstInternalNonZeroLogged = true
                                val delayMs =
                                    if (rollingCaptureStartElapsedMs > 0L) {
                                        rollingInternalPlaybackPcmFirstNonZeroElapsedMs - rollingCaptureStartElapsedMs
                                    } else {
                                        -1L
                                    }
                                if (verboseAudioDiagnosticsEnabled) {
                                    Log.d(
                                        TAG,
                                        "First audible internal audio at loop=$loopCount delayMs=$delayMs " +
                                            "(post-recreate=${rollingInternalPlaybackRecreateSucceeded.get()})",
                                    )
                                }
                            }
                        }
                    } else {
                        rollingInternalPlaybackPcmSilentOnlyBuffers.incrementAndGet()
                    }
                }

                if (isMuted.get()) {
                    internalSilentStartMs = -1L
                } else {
                    val now = SystemClock.elapsedRealtime()
                    val internalStillSilent =
                        internalReadCount > 0 &&
                            internalAudioHealth.firstAudiblePcmElapsedMs < 0L

                    fun fireBufferPlaybackSilenceTimeout() {
                        if (silenceCallbackFired) return
                        silenceCallbackFired = true
                        rollingInternalSilenceDiagFired.set(true)
                        internalAudioHealth.markPersistentSilent()
                        val silentMs = now - internalSilentStartMs
                        val suppressedMixedMic =
                            mAudioMode == AudioMode.MIXED && rollingMicLegEverAudibleThisSession
                        val health =
                            internalAudioHealth.snapshot(
                                muxerStarted = isMuxerReady,
                                muxerAudioSamplesWritten = rollingAudioPcmSamplesQueued.get() > 0L,
                            )
                        Log.w(
                            TAG,
                            "${AUDIO_DIAG_MARKER} BUFFER internal playback remained silent for ${silentMs}ms; buffering continues mode=$mAudioMode",
                        )
                        if (verboseAudioDiagnosticsEnabled) {
                            Log.d(
                                TAG,
                                "${AUDIO_DIAG_MARKER} BUFFER internal_playback_silence_verbose " +
                                    "posReads=${rollingInternalPlaybackPcmReadsPositive.get()} " +
                                    "silentBufs=${rollingInternalPlaybackPcmSilentOnlyBuffers.get()} " +
                                    "nonSilentBufs=${rollingInternalPlaybackPcmNonZeroBuffers.get()} " +
                                    "mixedMicAudible=$rollingMicLegEverAudibleThisSession mode=$mAudioMode",
                            )
                        }
                        logAnalyticsEvent(
                            "silent_timeout",
                            mapOf(
                                "silent_ms" to silentMs.toString(),
                                "api" to Build.VERSION.SDK_INT.toString(),
                                "brand" to Build.BRAND,
                                "model" to Build.MODEL,
                                "mode" to mAudioMode.name,
                                "engine" to engineMode.name,
                                "recording_type" to "buffer",
                            ),
                            releaseSafe = true,
                        )
                        if (suppressedMixedMic) {
                            if (verboseAudioDiagnosticsEnabled) {
                                Log.d(TAG, "${AUDIO_DIAG_MARKER} BUFFER internal playback silent in mixed mode; mic leg has audio")
                            }
                            return
                        }
                        AudioRecordingCrashlyticsReporter.reportInternalAudioPersistentSilence(
                            AudioRecordingCrashlyticsReporter.RecordingKind.BUFFER,
                            health,
                        )
                    }

                    if (internalAudible) {
                        internalSilentStartMs = -1L
                        val shouldReportRecoveredAfterRebuild =
                            internalAudioHealth.recoveredAfterRebuild && !recoveredAfterRebuildTelemetryReported
                        val shouldReportLateRecovery =
                            internalAudioHealth.recoveredLate && !lateRecoveryTelemetryReported
                        if (shouldReportRecoveredAfterRebuild || shouldReportLateRecovery) {
                            val health =
                                internalAudioHealth.snapshot(
                                    muxerStarted = isMuxerReady,
                                    muxerAudioSamplesWritten = rollingAudioPcmSamplesQueued.get() > 0L,
                                )
                            if (shouldReportRecoveredAfterRebuild) {
                                recoveredAfterRebuildTelemetryReported = true
                                AudioRecordingCrashlyticsReporter.reportInternalAudioRecoveredAfterRebuild(
                                    AudioRecordingCrashlyticsReporter.RecordingKind.BUFFER,
                                    health,
                                )
                            }
                            if (shouldReportLateRecovery) {
                                lateRecoveryTelemetryReported = true
                                AudioRecordingCrashlyticsReporter.reportInternalAudioLateRecovery(
                                    AudioRecordingCrashlyticsReporter.RecordingKind.BUFFER,
                                    health,
                                )
                            }
                        }
                    } else if (internalStillSilent) {
                        if (internalSilentStartMs < 0L) {
                            internalSilentStartMs = now
                        }
                        val silentElapsedMs = now - internalSilentStartMs
                        if (!internalAudioHealth.stage1SilentAt4s && silentElapsedMs >= INTERNAL_SILENCE_STAGE1_MS) {
                            internalAudioHealth.markStage1Silent()
                            AudioRecordingCrashlyticsReporter.logInternalAudioStage(
                                AudioRecordingCrashlyticsReporter.RecordingKind.BUFFER,
                                "stage1_4s",
                                internalAudioHealth.snapshot(
                                    muxerStarted = isMuxerReady,
                                    muxerAudioSamplesWritten = rollingAudioPcmSamplesQueued.get() > 0L,
                                ),
                            )
                            attemptInternalAudioRecordRecreation(reason = "buffer_stage1_4s_silence")
                        } else if (!internalAudioHealth.stage2SilentAt10s && silentElapsedMs >= INTERNAL_SILENCE_STAGE2_MS) {
                            internalAudioHealth.markStage2Silent()
                            AudioRecordingCrashlyticsReporter.logInternalAudioStage(
                                AudioRecordingCrashlyticsReporter.RecordingKind.BUFFER,
                                "stage2_10s",
                                internalAudioHealth.snapshot(
                                    muxerStarted = isMuxerReady,
                                    muxerAudioSamplesWritten = rollingAudioPcmSamplesQueued.get() > 0L,
                                ),
                            )
                            attemptInternalAudioRecordRecreation(reason = "buffer_stage2_10s_silence")
                        } else if (!silenceCallbackFired && silentElapsedMs >= INTERNAL_SILENCE_PERSISTENT_MS) {
                            fireBufferPlaybackSilenceTimeout()
                        }
                    } else {
                        internalSilentStartMs = -1L
                    }
                }
            }

            if (verboseAudioDiagnosticsEnabled && loopCount % 500L == 0L) {
                val silentMs =
                    if (internalSilentStartMs >= 0L) {
                        SystemClock.elapsedRealtime() - internalSilentStartMs
                    } else {
                        0L
                    }
                Log.d(
                    TAG,
                    "captureAudioLoop: loop=$loopCount readCount=$readCount " +
                        "internalRead=$internalReadCount silenceCallbackFired=$silenceCallbackFired " +
                        "intPosReads=${rollingInternalPlaybackPcmReadsPositive.get()} " +
                        "intSilentBufs=${rollingInternalPlaybackPcmSilentOnlyBuffers.get()} " +
                        "intNonSilentBufs=${rollingInternalPlaybackPcmNonZeroBuffers.get()} " +
                        "internalNearSilenceAccumMs=$silentMs",
                )
            }
        }

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(TAG, "captureAudioLoop exited after $loopCount iterations")
        }
    }

    private fun drainAudioLoop() {
        val info = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            drainAudioOnce(info)
            try {
                Thread.sleep(4)
            } catch (_: InterruptedException) {
            }
        }
        repeat(60) {
            drainAudioOnce(info)
            try {
                Thread.sleep(4)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun drainAudioOnce(info: MediaCodec.BufferInfo) {
        val enc = audioEncoder ?: return
        loop@ while (true) {
            val status =
                try {
                    enc.dequeueOutputBuffer(info, 0)
                } catch (_: Exception) {
                    break@loop
                }
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> break@loop

                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        storedAudioFmt = enc.outputFormat
                        if (!isMuxerReady) {
                            val vFmt = storedVideoFmt
                            if (vFmt != null) {
                                if (muxerVideoTrack < 0) muxerVideoTrack = currentMuxer!!.addTrack(vFmt)
                                muxerAudioTrack = currentMuxer!!.addTrack(storedAudioFmt!!)
                                currentMuxer!!.start()
                                isMuxerReady = true
                            } else {
                                // Video format not yet known — audio format is stored; muxer will
                                // be started once the video format arrives.
                                muxerAudioTrack = -1 // will be set in the video path
                            }
                        }
                    }
                }

                status >= 0 -> {
                    val buf = enc.getOutputBuffer(status)
                    if (buf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                        info.size > 0
                    ) {
                        synchronized(muxerLock) {
                            if (isMuxerReady && muxerAudioTrack >= 0) {
                                val muxer = currentMuxer ?: return@synchronized
                                val base = segStartPtsUs.get()
                                val adjPts =
                                    if (base < 0) 0L else (info.presentationTimeUs - base).coerceAtLeast(0L)
                                val last = lastAudioMuxerPtsUs
                                val pts =
                                    if (last < 0L) {
                                        adjPts
                                    } else {
                                        maxOf(adjPts, last + 1L).also { p ->
                                            if (adjPts <= last) {
                                                Log.w(
                                                    TAG,
                                                    "Audio muxer PTS non-monotonic adj=$adjPts last=$last -> $p",
                                                )
                                            }
                                        }
                                    }
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxerAudioSampleInfo.set(
                                    info.offset,
                                    info.size,
                                    pts,
                                    info.flags,
                                )
                                try {
                                    muxer.writeSampleData(muxerAudioTrack, buf, muxerAudioSampleInfo)
                                    lastAudioMuxerPtsUs = pts
                                } catch (e: Exception) {
                                    reportFatalRecordingIfNeeded(
                                        e,
                                        kind = RecordingFatalKind.MediaMuxer,
                                        phase = "muxer_audio_writeSampleData",
                                    )
                                }
                            }
                        }
                    }
                    enc.releaseOutputBuffer(status, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private fun feedAudioEncoder(
        data: ByteArray,
        length: Int,
    ) {
        val enc = audioEncoder ?: return
        try {
            val idx = enc.dequeueInputBuffer(0)
            if (idx >= 0) {
                enc.getInputBuffer(idx)?.apply {
                    clear()
                    put(data, 0, length)
                }
                enc.queueInputBuffer(idx, 0, length, System.nanoTime() / 1000, 0)
                val ch = effectiveChannelCount.get().coerceIn(1, 2)
                val frameBytes = 2 * ch
                if (length > 0 && length % frameBytes == 0) {
                    rollingAudioPcmSamplesQueued.addAndGet((length / frameBytes).toLong())
                }
            } else {
                val d = pcmDropRollingAacInput.incrementAndGet()
                if (verboseAudioDiagnosticsEnabled && (d == 1L || d % PCM_DROP_LOG_EVERY_N == 0L)) {
                    Log.d(
                        TAG,
                        "${AUDIO_DIAG_MARKER} BUFFER AAC_INPUT_BACKPRESSURE drop_count=$d (dequeueInputBuffer unavailable, timeout=0)",
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun signalAudioEOS() {
        val enc = audioEncoder ?: return
        try {
            val idx = enc.dequeueInputBuffer(5000L)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (_: Exception) {
        }
    }

    private fun logAnalyticsEvent(
        name: String,
        params: Map<String, String> = emptyMap(),
        releaseSafe: Boolean = false,
    ) {
        if (!releaseSafe && !verboseAudioDiagnosticsEnabled) return
        try {
            val bundle = Bundle()
            params.forEach { (k, v) -> bundle.putString(k, v.take(100)) }
            FirebaseAnalytics.getInstance(context).logEvent(name, bundle)
            FirebaseCrashlytics.getInstance().log("analytics[$name] $params")
        } catch (_: Exception) {
        }
    }

    private fun mixPcm(
        base: ByteArray,
        overlay: ByteArray,
        sizeBytes: Int,
        channelCount: Int,
    ) {
        val ch = channelCount.coerceIn(1, 2)
        val frameBytes = 2 * ch
        var i = 0
        while (i + frameBytes <= sizeBytes) {
            for (c in 0 until ch) {
                val o = i + c * 2
                val s1 = ((base[o].toInt() and 0xFF) or (base[o + 1].toInt() shl 8)).toShort()
                val s2 = ((overlay[o].toInt() and 0xFF) or (overlay[o + 1].toInt() shl 8)).toShort()
                val mixed = (s1.toInt() + s2.toInt()).coerceIn(-32768, 32767)
                base[o] = (mixed and 0xFF).toByte()
                base[o + 1] = ((mixed shr 8) and 0xFF).toByte()
            }
            i += frameBytes
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Encoder setup
    // ══════════════════════════════════════════════════════════════════════════

    private fun prepareVideoEncoder(avcOnly: Boolean = false) {
        val forceAvcHint =
            !avcOnly &&
                adaptivePreferAvcForPrepare &&
                encoderType == "H.265 (HEVC)"
        val result =
            VideoEncoderConfigurator.configureScreenCaptureVideoEncoder(
                logTag = TAG,
                userEncoderType = encoderType,
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate,
                avcOnly = avcOnly || forceAvcHint,
                colorMode = colorMode,
            )
        videoEncoder = result.codec
        inputSurface = result.inputSurface
        configuredVideoMime = result.mime
        captureWidth = result.encodedWidth
        captureHeight = result.encodedHeight
        Log.d(TAG, "Buffer video encoder mime=$configuredVideoMime avcOnly=$avcOnly size=${captureWidth}x${captureHeight}")
    }

    private fun resolveRollingBufferChannelCount(wantStereo: Boolean): Int {
        val mic = micRecord
        val intl = internalRecord
        return when {
            mic != null && intl != null ->
                if (wantStereo) {
                    maxOf(mic.channelCount, intl.channelCount).coerceIn(1, 2)
                } else {
                    1
                }
            mic != null -> mic.channelCount.coerceIn(1, 2)
            intl != null -> intl.channelCount.coerceIn(1, 2)
            else -> 1
        }
    }

    /**
     * Same contract as [ScreenRecorderEngine.validateAudioRecorderCapturesOrAdjustSessionOrThrow] without sidecar AAC.
     */
    private fun validateRollingAudioRecordCapturesOrAdjustOrThrow() {
        if (mAudioMode == AudioMode.NONE) return

        val wantStereoUser = audioChannelCount == 2

        fun releaseQuiet(rec: AudioRecord?) {
            if (rec == null) return
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
        }

        fun tryStart(which: AudioRecord?, label: String): Throwable? {
            if (which == null) return null
            return try {
                which.startRecording()
                val rs =
                    try {
                        which.recordingState
                    } catch (_: Exception) {
                        AudioRecord.RECORDSTATE_STOPPED
                    }
                if (label == "internal") {
                    internalAudioHealth.noteAudioRecordStart(rs)
                }
                if (rs != AudioRecord.RECORDSTATE_RECORDING) {
                    IllegalStateException("buffer_$label recordingState=$rs")
                } else {
                    null
                }
            } catch (t: Throwable) {
                if (label == "internal") {
                    internalAudioHealth.noteAudioRecordStart(AudioRecord.RECORDSTATE_STOPPED)
                }
                IllegalStateException("buffer_$label.startRecording()", t)
            }
        }

        fun logFail(
            lab: String,
            err: Throwable,
        ) {
            Log.e(TAG, "${AUDIO_DIAG_MARKER} BUFFER AudioRecord_START_FAIL $lab ${err.javaClass.simpleName}: ${err.message}", err)
        }

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(
                TAG,
                "${AUDIO_DIAG_MARKER} BUFFER PREP requested=${rollingCaptureRequestedAudioMode.name} post_prepare=$mAudioMode sr=$audioSampleRate userCh=$audioChannelCount accChCfg=$configuredBufferAacChannels br=$audioBitrate enc=$audioEncoderType",
            )
        }

        val micDesired = micRecord != null
        val intDesired = internalRecord != null

        val micThr = micRecord?.let { tryStart(it, "mic") }?.also { logFail("mic", it) }
        val intThr =
            internalRecord?.let {
                if (micDesired && micThr == null) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                tryStart(it, "internal")?.also { t -> logFail("internal", t) }
            }

        val micFail = micDesired && micThr != null
        val intFail = intDesired && intThr != null
        val micOk = micDesired && micThr == null
        val intOk = intDesired && intThr == null
        if (intFail) {
            AudioRecordingCrashlyticsReporter.reportInternalAudioRecordStartFailed(
                AudioRecordingCrashlyticsReporter.RecordingKind.BUFFER,
                internalAudioHealth.snapshot(
                    muxerStarted = isMuxerReady,
                    muxerAudioSamplesWritten = rollingAudioPcmSamplesQueued.get() > 0L,
                ),
            )
        }

        when {
            micFail && intFail ->
                throw IllegalStateException("${AUDIO_DIAG_MARKER} Buffer: mic+internal AudioRecord.start both failed.")

            micFail && intOk ->
                resolveRollingMixedMicFailedContinueOrThrow(requireNotNull(micThr), wantStereoUser, ::releaseQuiet)

            intFail && micOk ->
                resolveRollingMixedInternalFailedContinueOrThrow(requireNotNull(intThr), wantStereoUser, ::releaseQuiet)

            micFail ->
                throw IllegalStateException("${AUDIO_DIAG_MARKER} Buffer mic start failed.", micThr)

            intFail ->
                throw IllegalStateException("${AUDIO_DIAG_MARKER} Buffer internal start failed.", intThr)
        }

        rollingInternalPlaybackPcmCountersActive =
            internalRecord != null &&
                runCatching {
                    internalRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING
                }.getOrElse { false }
        val rbIr = internalRecord
        if (Build.VERSION.SDK_INT >= 29 && rollingInternalPlaybackPcmCountersActive && rbIr != null) {
            PlaybackCaptureConfig.logInternalPlaybackRecordStarted(
                logTag = TAG,
                sessionDiagMarker = AUDIO_DIAG_MARKER,
                record = rbIr,
                configuredBufferBytes = rollingInternalPlaybackBufferBytesConfigured,
                configuredChannelMask = rollingInternalPlaybackChannelMaskConfigured,
                sampleRateFromBuilder = audioSampleRate,
            )
        }
    }

    private fun resolveRollingMixedMicFailedContinueOrThrow(
        primaryFailure: Throwable,
        wantStereoUser: Boolean,
        releaseQuiet: (AudioRecord?) -> Unit,
    ) {
        if (mAudioMode != AudioMode.MIXED || internalRecord == null) {
            throw IllegalStateException("${AUDIO_DIAG_MARKER} Buffer mic failed — no downgrade", primaryFailure)
        }
        val ir = internalRecord ?: throw IllegalStateException("${AUDIO_DIAG_MARKER} buffer degrade missing internal")
        micRecord?.let { releaseQuiet(it) }
        micRecord = null
        mAudioMode = AudioMode.INTERNAL
        val survivor =
            resolveRollingBufferChannelCount(wantStereoUser).also {
                effectiveChannelCount.set(it)
            }
        if (survivor != configuredBufferAacChannels) {
            throw IllegalStateException(
                "${AUDIO_DIAG_MARKER} Buffer downgrade blocked aacCfg=$configuredBufferAacChannels survivor=$survivor",
                primaryFailure,
            )
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val t = context.getString(R.string.toast_audio_downgraded_mic_unavailable_internal_only)
                onAudioCaptureDowngraded?.invoke(t) ?: Toast.makeText(context.applicationContext, t, Toast.LENGTH_LONG).show()
            }
        }
        Log.w(TAG, "${AUDIO_DIAG_MARKER} BUFFER MIXED→INTERNAL (mic failed) ch=$configuredBufferAacChannels")
        AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable("buffer_mic_leg_failed_kept_internal")
    }

    private fun resolveRollingMixedInternalFailedContinueOrThrow(
        primaryFailure: Throwable,
        wantStereoUser: Boolean,
        releaseQuiet: (AudioRecord?) -> Unit,
    ) {
        if (mAudioMode != AudioMode.MIXED || micRecord == null) {
            throw IllegalStateException("${AUDIO_DIAG_MARKER} Buffer internal failed — no downgrade", primaryFailure)
        }
        val mr = micRecord ?: throw IllegalStateException("${AUDIO_DIAG_MARKER} buffer degrade missing mic")
        internalRecord?.let { releaseQuiet(it) }
        internalRecord = null
        mAudioMode = AudioMode.MIC
        val survivor =
            resolveRollingBufferChannelCount(wantStereoUser).also {
                effectiveChannelCount.set(it)
            }
        if (survivor != configuredBufferAacChannels) {
            throw IllegalStateException(
                "${AUDIO_DIAG_MARKER} Buffer downgrade blocked aacCfg=$configuredBufferAacChannels survivor=$survivor",
                primaryFailure,
            )
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val t = context.getString(R.string.toast_audio_downgraded_internal_unavailable_mic_only)
                onAudioCaptureDowngraded?.invoke(t)
                    ?: Toast.makeText(context.applicationContext, t, Toast.LENGTH_LONG).show()
            }
        }
        Log.w(TAG, "${AUDIO_DIAG_MARKER} BUFFER MIXED→MIC (internal failed) ch=$configuredBufferAacChannels")
        AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable("buffer_internal_leg_failed_kept_mic")
    }

    /** Buffer-side counterpart of [ScreenRecorderEngine.attemptInternalAudioRecordRecreation]. */
    @SuppressLint("MissingPermission")
    private fun attemptInternalAudioRecordRecreation(reason: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val previous = internalRecord ?: return false
        val sampleRate = audioSampleRate
        val channelMask = rollingInternalPlaybackChannelMaskConfigured
        val bufferBytes = rollingInternalPlaybackBufferBytesConfigured
        if (bufferBytes <= 0) return false
        var attemptNumber = 0
        while (attemptNumber == 0) {
            val current = rollingInternalPlaybackRecreateAttempts.get()
            if (current >= INTERNAL_SILENCE_RECREATE_ATTEMPTS) return false
            if (rollingInternalPlaybackRecreateAttempts.compareAndSet(current, current + 1)) {
                attemptNumber = current + 1
            }
        }
        internalAudioHealth.noteRebuildAttempt()

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(
                TAG,
                "${AUDIO_DIAG_MARKER} BUFFER INTERNAL_PLAYBACK_RECREATE attempt#$attemptNumber reason=$reason " +
                    "sr=$sampleRate channelMask=$channelMask bufferBytes=$bufferBytes",
            )
        }

        val rebuilt =
            try {
                PlaybackCaptureConfig.rebuildInternalPlaybackAudioRecord(
                    mediaProjection = mediaProjection,
                    sampleRate = sampleRate,
                    channelMask = channelMask,
                    bufferBytes = bufferBytes,
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "${AUDIO_DIAG_MARKER} BUFFER INTERNAL_PLAYBACK_RECREATE build failed: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
                return false
            }
        internalAudioHealth.noteAudioRecordCreated(
            state = rebuilt.state,
            sampleRate = rebuilt.sampleRate,
            channelCount = rebuilt.channelCount,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes = bufferBytes,
        )
        try {
            rebuilt.startRecording()
        } catch (e: Exception) {
            internalAudioHealth.noteAudioRecordStart(AudioRecord.RECORDSTATE_STOPPED)
            Log.e(TAG, "${AUDIO_DIAG_MARKER} BUFFER INTERNAL_PLAYBACK_RECREATE startRecording failed: ${e.message}", e)
            try {
                rebuilt.release()
            } catch (_: Exception) {
            }
            return false
        }
        val rs =
            try {
                rebuilt.recordingState
            } catch (_: Exception) {
                AudioRecord.RECORDSTATE_STOPPED
            }
        internalAudioHealth.noteAudioRecordStart(rs)
        if (rs != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "${AUDIO_DIAG_MARKER} BUFFER INTERNAL_PLAYBACK_RECREATE recordingState=$rs; aborting")
            try {
                rebuilt.release()
            } catch (_: Exception) {
            }
            return false
        }
        if (!isRunning.get()) {
            try {
                if (rebuilt.recordingState == AudioRecord.RECORDSTATE_RECORDING) rebuilt.stop()
            } catch (_: Exception) {
            }
            try {
                rebuilt.release()
            } catch (_: Exception) {
            }
            return false
        }

        internalRecord = rebuilt
        rollingInternalPlaybackRecreateSucceeded.set(true)
        internalAudioHealth.noteRebuildSucceeded()
        try {
            if (previous.recordingState == AudioRecord.RECORDSTATE_RECORDING) previous.stop()
        } catch (_: Exception) {
        }
        try {
            previous.release()
        } catch (_: Exception) {
        }
        PlaybackCaptureConfig.logInternalPlaybackRecordStarted(
            logTag = TAG,
            sessionDiagMarker = "$AUDIO_DIAG_MARKER BUFFER RECREATED",
            record = rebuilt,
            configuredBufferBytes = bufferBytes,
            configuredChannelMask = channelMask,
            sampleRateFromBuilder = sampleRate,
        )
        return true
    }

    private fun logRollingBufferAudioStopDiag(
        muxerStartedBeforeFinalize: Boolean,
        muxerAudioSamplesWrittenBeforeFinalize: Boolean,
    ) {
        val rReads = rollingInternalPlaybackPcmReadsPositive.get()
        val rNz = rollingInternalPlaybackPcmNonZeroBuffers.get()
        val rSil = rollingInternalPlaybackPcmSilentOnlyBuffers.get()
        val internalHealthSnapshot =
            internalAudioHealth.snapshot(
                muxerStarted = muxerStartedBeforeFinalize,
                muxerAudioSamplesWritten = muxerAudioSamplesWrittenBeforeFinalize,
            )
        Log.i(
            TAG,
            "${AUDIO_DIAG_MARKER} BUFFER audio_summary requested=${rollingCaptureRequestedAudioMode.name} effective=$mAudioMode " +
                "sr=$audioSampleRate ch=${effectiveChannelCount.get()} pcmSamplesQueued=${rollingAudioPcmSamplesQueued.get()} " +
                "pcmBackpressure=${pcmDropRollingAacInput.get()} readErrors=${rollingAudioReadNegativeCount.get()} " +
                "internalSilence=${rollingInternalSilenceDiagFired.get()} internalRecovered=${rNz > 0L && rollingInternalSilenceDiagFired.get()}",
        )
        if (verboseAudioDiagnosticsEnabled) {
            val rFirstNzLoop = rollingInternalPlaybackPcmFirstNonZeroLoop.get()
            val rFirstNzMs = rollingInternalPlaybackPcmFirstNonZeroElapsedMs
            val rFirstNzDelayMs =
                if (rFirstNzMs >= 0L && rollingCaptureStartElapsedMs > 0L) {
                    rFirstNzMs - rollingCaptureStartElapsedMs
                } else {
                    -1L
                }
            val intPcmDiag =
                if (rollingInternalPlaybackPcmCountersActive) {
                    "intPCM_reads=$rReads intPCM_nzBufs=$rNz intPCM_silentBufs=$rSil " +
                        "intPCM_everNonZero=${rNz > 0L} intPCM_firstNzLoop=$rFirstNzLoop intPCM_firstNzMs=$rFirstNzDelayMs " +
                        "intPCM_recreateAttempts=${rollingInternalPlaybackRecreateAttempts.get()} intPCM_recreateSucceeded=${rollingInternalPlaybackRecreateSucceeded.get()}"
                } else {
                    "intPCM_reads=na intPCM_diagInactive"
                }
            Log.d(TAG, "${AUDIO_DIAG_MARKER} BUFFER audio_summary_verbose $intPcmDiag")
        }
        AudioRecordingCrashlyticsReporter.finalizeBufferSession(
            finalCaptureModeName = mAudioMode.name,
            pcmSamplesQueued = rollingAudioPcmSamplesQueued.get(),
            pcmDropCount = pcmDropRollingAacInput.get(),
            readNegCount = rollingAudioReadNegativeCount.get(),
            internalSilenceObservedThisSession = rollingInternalSilenceDiagFired.get(),
            internalRecoveredAfterInitialSilence = rNz > 0L && rollingInternalSilenceDiagFired.get(),
            internalPlaybackPcmReadsPositive = rReads.takeIf { rollingInternalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled },
            internalPlaybackPcmNonZeroBuffers = rNz.takeIf { rollingInternalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled },
            internalPlaybackPcmSilentBuffers = rSil.takeIf { rollingInternalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled },
            internalPlaybackPcmEverNonZero = (rNz > 0L).takeIf { rollingInternalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled },
            internalAudioHealth = internalHealthSnapshot,
        )
    }

    @SuppressLint("MissingPermission")
    private fun prepareAudioEncoder() {
        if (mAudioMode == AudioMode.NONE) return

        val wantStereo = audioChannelCount == 2
        val stereoMask = AudioFormat.CHANNEL_IN_STEREO
        val monoMask = AudioFormat.CHANNEL_IN_MONO
        val chanMask = if (wantStereo) stereoMask else monoMask
        val minBuf =
            AudioRecord
                .getMinBufferSize(audioSampleRate, chanMask, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4096)

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(
                TAG,
                "prepareAudioEncoder: mode=$mAudioMode sampleRate=$audioSampleRate " +
                    "channels=$audioChannelCount bufferSize=${minBuf * 2} " +
                    "API=${Build.VERSION.SDK_INT} brand=${Build.BRAND} model=${Build.MODEL}",
            )
        }

        try {
            val audioPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                )
            val permGranted = audioPermission == PackageManager.PERMISSION_GRANTED
            if (verboseAudioDiagnosticsEnabled) {
                Log.d(TAG, "RECORD_AUDIO permission: ${if (permGranted) "GRANTED" else "DENIED"}")
            }
            if (!permGranted) throw SecurityException("RECORD_AUDIO permission denied")

            if (Build.VERSION.SDK_INT >= 29 &&
                (mAudioMode == AudioMode.INTERNAL || mAudioMode == AudioMode.MIXED)
            ) {
                try {
                    if (verboseAudioDiagnosticsEnabled) {
                        Log.d(
                            TAG,
                            "Building AudioPlaybackCaptureConfiguration: matchingUsages=[${PlaybackCaptureConfig.MATCHED_USAGES_LOG}] projection=$mediaProjection",
                        )
                    }
                    val capture = PlaybackCaptureConfig.build(mediaProjection)
                    if (verboseAudioDiagnosticsEnabled) {
                        Log.d(TAG, "AudioPlaybackCaptureConfiguration built OK")
                    }
                    logAnalyticsEvent(
                        "capture_config_created",
                        mapOf(
                            "api" to Build.VERSION.SDK_INT.toString(),
                            "brand" to Build.BRAND,
                            "model" to Build.MODEL,
                        ),
                    )
                    val fmt =
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(audioSampleRate)
                            .setChannelMask(chanMask)
                            .build()
                    internalRecord =
                        AudioRecord
                            .Builder()
                            .setAudioFormat(fmt)
                            .setBufferSizeInBytes(minBuf * 2)
                            .setAudioPlaybackCaptureConfig(capture)
                            .build()
                    val state = internalRecord?.state
                    internalAudioHealth.noteAudioRecordCreated(
                        state = state ?: -1,
                        sampleRate = internalRecord?.sampleRate ?: audioSampleRate,
                        channelCount = internalRecord?.channelCount ?: audioChannelCount,
                        encoding = AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeBytes = minBuf * 2,
                    )
                    if (verboseAudioDiagnosticsEnabled) {
                        Log.d(
                            TAG,
                            "Internal AudioRecord state=$state " +
                                "(INITIALIZED=${AudioRecord.STATE_INITIALIZED}) " +
                                "channelCount=${internalRecord?.channelCount} " +
                                "sampleRate=${internalRecord?.sampleRate}",
                        )
                    }
                    if (state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("Internal AudioRecord not initialized (state=$state)")
                    }
                    rollingInternalPlaybackBufferBytesConfigured = minBuf * 2
                    rollingInternalPlaybackChannelMaskConfigured = chanMask
                } catch (e: Exception) {
                    Log.e(TAG, "${AUDIO_DIAG_MARKER} AudioRecord_CREATION_FAIL buffer internal: ${e.javaClass.simpleName}: ${e.message}", e)
                    Log.e(TAG, "Internal audio init failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    AppLogger.e(TAG, "Buffer internal audio init failed: ${e.message}")
                    AudioRecordingCrashlyticsReporter.noteCreationFailureInternal(e, "buffer")
                    internalAudioHealth.noteAudioRecordCreated(
                        state = -1,
                        sampleRate = audioSampleRate,
                        channelCount = 0,
                        encoding = AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeBytes = minBuf * 2,
                    )
                    logAnalyticsEvent(
                        "capture_denied_or_unsupported",
                        mapOf(
                            "reason" to (e.message?.take(80) ?: "unknown"),
                            "api" to Build.VERSION.SDK_INT.toString(),
                            "brand" to Build.BRAND,
                            "model" to Build.MODEL,
                        ),
                    )
                    internalRecord = null
                    rollingInternalPlaybackBufferBytesConfigured = 0
                    if (mAudioMode == AudioMode.INTERNAL) mAudioMode = AudioMode.MIC
                }
            }

            if (mAudioMode == AudioMode.MIC || mAudioMode == AudioMode.MIXED) {
                fun tryMic(mask: Int): AudioRecord? =
                    try {
                        val b =
                            AudioRecord
                                .getMinBufferSize(audioSampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                                .coerceAtLeast(4096)
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            audioSampleRate,
                            mask,
                            AudioFormat.ENCODING_PCM_16BIT,
                            b,
                        ).also {
                            if (it.state != AudioRecord.STATE_INITIALIZED) {
                                it.release()
                                return null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "${AUDIO_DIAG_MARKER} AudioRecord_CREATION_FAIL buffer mic: ${e.javaClass.simpleName}: ${e.message}", e)
                        AudioRecordingCrashlyticsReporter.noteCreationFailureMic(e)
                        null
                    }
                micRecord =
                    if (wantStereo) {
                        tryMic(stereoMask) ?: tryMic(monoMask)
                    } else {
                        tryMic(monoMask)
                    }
                if (verboseAudioDiagnosticsEnabled) {
                    Log.d(TAG, "Mic AudioRecord: ${if (micRecord != null) "OK (ch=${micRecord!!.channelCount})" else "FAILED"}")
                }
                if (micRecord == null && (mAudioMode == AudioMode.MIC || mAudioMode == AudioMode.MIXED)) {
                    AudioRecordingCrashlyticsReporter.noteCreationFailureMic(null, "buffer_mic_uninitialized_after_open")
                }
            }

            if (internalRecord == null && micRecord == null) {
                Log.e(TAG, "No audio sources available. Disabling audio.")
                AppLogger.e(TAG, "Buffer: no audio sources available")
                mAudioMode = AudioMode.NONE
                return
            }

            val resolvedCh = resolveRollingBufferChannelCount(wantStereo)
            effectiveChannelCount.set(resolvedCh)
            configuredBufferAacChannels = resolvedCh

            val aacProfile =
                when (audioEncoderType) {
                    "AAC-HE" -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
                    "AAC-HE v2" -> MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS
                    "AAC-ELD" -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
                    else -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
                }
            val fmt =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, resolvedCh).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                    setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    )
                }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(TAG, "Audio init error: ${e.message}")
            AudioRecordingCrashlyticsReporter.noteCreationFailureMic(e, "buffer_prepare_outer")
            mAudioMode = AudioMode.NONE
            micRecord?.release()
            micRecord = null
            internalRecord?.release()
            internalRecord = null
            audioEncoder?.release()
            audioEncoder = null
        }
    }
}
