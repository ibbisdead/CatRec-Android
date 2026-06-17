package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
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
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.BuildConfig
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.ColorMode
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import com.ibbie.catrec_screenrecorcer.utils.AppLogger
import com.ibbie.catrec_screenrecorcer.utils.AudioRecordingCrashlyticsReporter
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

class RecorderSetupStoppedException : IllegalStateException("stop() called during encoder setup — aborting muxer creation")

class ScreenRecorderEngine(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val audioMode: AudioMode,
    private val mediaProjection: MediaProjection,
    private val outputFileDescriptor: FileDescriptor,
    private val encoderType: String,
    private val colorMode: String = ColorMode.FULL,
    engineMode: RecordingEngineMode = RecordingEngineMode.DEFAULT,
    private val audioBitrate: Int = 128_000,
    private val audioSampleRate: Int = 44_100,
    private val audioChannelCount: Int = 1,
    private val audioEncoderType: String = "AAC-LC",
    private val separateMicFileDescriptor: FileDescriptor? = null,
    /**
     * When true, [captureAudioLoop] may switch to microphone capture (same session) after sustained
     * near-silence on the internal playback stream — non-blocking notice only (no modal).
     */
    private val autoMicFallbackWhenInternalSilent: Boolean = false,
    /**
     * Invoked on the audio-capture thread when a short toast/status notice should be shown
     * (full record only). Service posts to main thread.
     */
    private val onInternalPlaybackSilenceSessionNotice: ((InternalPlaybackSilenceSessionNotice) -> Unit)? = null,
    /** Main thread — user-visible hint when MIXED degrades because one AudioRecord failed to start while the AAC layout still matches the survivor. */
    private val onAudioCaptureDowngraded: ((CharSequence) -> Unit)? = null,
    /** Invoked at most once when the video encoder drain path hits a fatal error (encoder thread). */
    private val onFatalVideoEncodeError: ((String) -> Unit)? = null,
    /** When true with user HEVC, first [prepareVideoEncoder] uses AVC only (adaptive tier 4 hint). */
    private val adaptivePreferAvcForPrepare: Boolean = false,
) : ActiveRecordingEngine {
    enum class AudioMode { NONE, MIC, INTERNAL, MIXED }

    data class PcmSignalStats(
        val byteCount: Int,
        val peakAbs: Int,
        val rms: Int,
        val audible: Boolean,
    )

    private val engineMode: RecordingEngineMode =
        if (engineMode == RecordingEngineMode.PERFORMANCE) engineMode else RecordingEngineMode.PERFORMANCE

    companion object {
        private const val TAG = "RecorderEngine"

        /** First near-silence checkpoint: telemetry only; keep waiting for late game audio routes. */
        private const val INTERNAL_SILENCE_STAGE1_MS = InternalAudioHealthTracker.INTERNAL_AUDIO_STAGE1_SILENCE_MS

        /** Second near-silence checkpoint: telemetry only. */
        private const val INTERNAL_SILENCE_STAGE2_MS = InternalAudioHealthTracker.INTERNAL_AUDIO_STAGE2_SILENCE_MS

        /** Persistent near-silence checkpoint: existing user-facing notice/fallback behavior begins. */
        private const val INTERNAL_SILENCE_PERSISTENT_MS =
            InternalAudioHealthTracker.INTERNAL_AUDIO_PERSISTENT_SILENCE_MS

        /** Keep a non-blocking internal monitor briefly after mic fallback for late-recovery telemetry. */
        private const val INTERNAL_MONITOR_AFTER_FALLBACK_MS =
            InternalAudioHealthTracker.INTERNAL_AUDIO_LATE_MONITOR_MS

        /**
         * Keep internal playback capture to one [AudioRecord] per session. Re-opening playback
         * capture mid-session can leave OEM AudioFlinger implementations wedged after repeated
         * recordings, so staged silence handling now reports/falls back instead of rebuilding.
         */
        private const val INTERNAL_SILENCE_RECREATE_ATTEMPTS = 0

        /** Video drain pacing — lower than audio to pull encoded frames sooner (less encoder backpressure). */
        private const val VIDEO_DRAIN_SLEEP_MS = 2L

        /** Log throttle: negative AudioRecord.read summaries. */
        private const val READ_ERROR_LOG_EVERY_N = 50L

        /** Log throttle: AAC input backpressure drops. */
        private const val PCM_DROP_LOG_EVERY_N = 250L

        /** Stop-time budget for obtaining an AAC input buffer and queueing EOS. */
        private const val AUDIO_EOS_QUEUE_TIMEOUT_MS = 10_000L

        /** Stop-time budget after AAC input EOS is queued to observe output EOS. */
        private const val AUDIO_EOS_DRAIN_TIMEOUT_MS = 10_000L

        private const val AUDIO_DRAIN_JOIN_TIMEOUT_MS = 15_000L
        private const val AUDIO_CAPTURE_JOIN_TIMEOUT_MS = 15_000L

        internal const val AUDIO_DIAG_MARKER = "[CatRecAudioSession]"

        private val verboseAudioDiagnosticsEnabled: Boolean
            get() = BuildConfig.DEBUG
    }

    private var mAudioMode = audioMode

    /** What gets muxed into the main MP4. Differs from [mAudioMode] when mic is saved to a separate file. */
    private var mainMuxAudioMode: AudioMode = AudioMode.NONE

    private val routeMicToSeparateFile: Boolean
        get() = separateMicFileDescriptor != null

    // Actual channel count resolved after AudioRecord creation (may differ from audioChannelCount
    // if the device silently falls back to mono for playback capture).
    private val effectiveChannelCount = AtomicInteger(audioChannelCount.coerceIn(1, 2))

    private var videoEncoder: MediaCodec? = null

    /** Matches [VideoEncoderConfigurator] output (may differ from [width]/[height] after hardware fallback). */
    private var captureWidth: Int = width
    private var captureHeight: Int = height

    /** MIME from [VideoEncoderConfigurator]; used for HEVC→AVC recovery on [MediaCodec.start] failure. */
    private var configuredVideoMime: String = ""
    private var configuredVideoEncoder: ConfiguredVideoEncoder? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var frameRelay: EncoderFrameRelay? = null
    private var directVirtualDisplay: VirtualDisplay? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    /**
     * Muxer lifecycle flag. Set to true ONLY after [MediaMuxer.start] returns successfully
     * (all tracks added). Gated by [muxerLock] so drain threads never observe a torn state.
     * [MediaMuxer.stop] must only be invoked while this is true AND at least one track has
     * had a sample written (see [videoSamplesWritten] / [audioSamplesWritten]).
     */
    private var isMuxerStarted = false

    /**
     * Per-track write flags. Flipped to true on the first successful [MediaMuxer.writeSampleData]
     * for that track. Gated by [muxerLock].
     *
     * Required because some OEM MPEG4Writer implementations defer per-track `start` until the
     * first sample arrives; calling [MediaMuxer.stop] with a track that never received a sample
     * triggers the "Stop() called but track is not started" warning (and on a few devices throws
     * IllegalStateException from the native muxer).
     */
    private var videoSamplesWritten = false
    private var audioSamplesWritten = false

    /**
     * Once the muxer has been released (or will be imminently), prevents drain threads from
     * racing a late [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] into [MediaMuxer.start] /
     * [MediaMuxer.writeSampleData] during teardown.
     */
    private var muxerReleased = false
    private val muxerLock = object {}

    // Separate mic muxer/encoder
    private var separateMicEncoder: MediaCodec? = null
    private var separateMicMuxer: MediaMuxer? = null
    private var separateMicTrackIndex = -1
    private var isSeparateMicMuxerStarted = false
    private var separateMicSamplesWritten = false
    private var separateMicMuxerReleased = false
    private val separateMicLock = object {}

    private val isRecording = AtomicBoolean(false)
    private val audioCaptureStopping = AtomicBoolean(false)

    /** Guards against re-entrant / concurrent [stop] calls (e.g. from [start]'s failure path + service cleanup). */
    private val stopInvoked = AtomicBoolean(false)

    // Set to true by drain loops once the encoder signals end-of-stream.
    // Used to gate muxer.stop() so it is never called while draining is still in progress.
    private val videoEosReached = AtomicBoolean(false)
    private val audioEosReached = AtomicBoolean(false)
    private val separateMicEosReached = AtomicBoolean(false)
    private val audioInputEosQueued = AtomicBoolean(false)
    private val audioInputEosSignalFailed = AtomicBoolean(false)
    private val separateMicInputEosQueued = AtomicBoolean(false)
    private val separateMicInputEosSignalFailed = AtomicBoolean(false)

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private var internalMonitorRecord: AudioRecord? = null
    private var internalMonitorUntilElapsedMs: Long = 0L

    private var audioThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var videoDrainThread: Thread? = null
    private var separateMicDrainThread: Thread? = null

    private val isPaused = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val performanceScreenshotBlockedLogged = AtomicBoolean(false)
    private val totalPausedTimeUs = AtomicLong(0)
    private var pauseStartTimeUs = 0L

    private data class PerformanceResizeSampleLog(
        val requested: RecordingResolutionSize,
        val aligned: RecordingResolutionSize,
        val oldW: Int,
        val oldH: Int,
        val resizeElapsedMs: Long,
    )

    private val pendingPerformanceResizeSampleLog = AtomicReference<PerformanceResizeSampleLog?>(null)

    /** Ensures [onFatalVideoEncodeError] runs at most once for this engine instance. */
    private val videoEncodeFatalSignaled = AtomicBoolean(false)

    /** Number of AAC main-track encoder input buffers we skipped because dequeueInput returned none (timeout 0). */
    private val pcmDropMainAacInputCount = AtomicLong(0)

    /** Same for separate-mic encoder when routing mic to sidecar.m4a. */
    private val pcmDropSeparateMicInputCount = AtomicLong(0)

    /** Negative return values from AudioRecord.read. */
    private val audioReadNegativeErrorCount = AtomicLong(0)

    /** Main AAC encoder channel layout chosen in [prepareAudioEncoder] (AAC format may not safely change mid-session). */
    private var configuredMainAacChannels: Int = 1

    private val internalPlaybackSilenceDiagFired = AtomicBoolean(false)
    private val internalPlaybackRecoveredAfterSilenceLogged = AtomicBoolean(false)

    @Volatile
    private var micLegEverAudibleThisSession: Boolean = false

    private val pendingMicFallback = AtomicBoolean(false)

    /** True after a successful [performMicFallbackSwitch] or idempotent second request. */
    private val micFallbackHotSwapApplied = AtomicBoolean(false)

    private var earlySeparateMicMainMuxSwitchAttempted = false

    /**
     * Whole-session PCM counters for the internal playback [AudioRecord]. Once
     * [internalPlaybackPcmCountersActive] flips true (after a successful start), these are updated
     * on every read for the entire session — independently of whether the first-window silence
     * callback has fired or whether a recreation attempt has been performed. This is what makes
     * "intPCM_everNonZero" in [logAudioCaptureStopDiagnostics] correctly reflect whether *any*
     * non-zero internal PCM was ever observed (e.g. switching apps mid-session).
     */
    private val internalPlaybackPcmReadsPositive = AtomicLong(0)

    private val internalPlaybackPcmNonZeroBuffers = AtomicLong(0)
    private val internalPlaybackPcmSilentOnlyBuffers = AtomicLong(0)

    /** Loop index of the first non-zero internal PCM buffer, or -1 if never observed. */
    private val internalPlaybackPcmFirstNonZeroLoop = AtomicLong(-1L)

    /** SystemClock.elapsedRealtime() at session start for first-non-zero deltas in stop diagnostics. */
    private var captureSessionStartElapsedMs: Long = 0L

    /**
     * elapsedRealtime() of the first non-zero internal PCM buffer this session, or -1 if never
     * observed. Used by [logAudioCaptureStopDiagnostics] to distinguish initial silence followed
     * by later recovery from whole-session silence.
     */
    private var internalPlaybackPcmFirstNonZeroElapsedMs: Long = -1L

    /** Number of times we have rebuilt [internalRecord] mid-session (capped by [INTERNAL_SILENCE_RECREATE_ATTEMPTS]). */
    private val internalPlaybackRecreateAttempts = AtomicInteger(0)

    /** True after a recreation attempt succeeded (new AudioRecord is recording). */
    private val internalPlaybackRecreateSucceeded = AtomicBoolean(false)

    /** Set at end of [validateAudioRecorderCapturesOrAdjustSessionOrThrow] when internal playback [AudioRecord] is recording. */
    private var internalPlaybackPcmCountersActive = false

    /** Pass‑through diagnostics from [prepareAudioEncoder] for internal playback [AudioRecord] only. */
    private var internalPlaybackBufferBytesConfigured = 0
    private var internalPlaybackChannelMaskConfigured = AudioFormat.CHANNEL_IN_MONO

    private val internalAudioHealth =
        InternalAudioHealthTracker(
            recordingType = "normal",
            engineModeName = engineMode.name,
            requestedAudioModeName = audioMode.name,
            micRequested = audioMode == AudioMode.MIC || audioMode == AudioMode.MIXED,
            internalRequested = audioMode == AudioMode.INTERNAL || audioMode == AudioMode.MIXED,
            separateMicRequested = separateMicFileDescriptor != null,
            requestedSampleRate = audioSampleRate,
        )

    /**
     * Strategy hook used by tests to verify the Performance-engine resize request without
     * starting a real MediaProjection session.
     */
    internal var performanceVirtualDisplayResizeStrategy: PerformanceVirtualDisplayResizeStrategy =
        PerformanceVirtualDisplayResizeStrategy { virtualDisplay, newWidth, newHeight, densityDpi ->
            virtualDisplay.resize(newWidth, newHeight, densityDpi)
        }

    /** Original user-selected mode before prepare-time degradation (fallback internal→mic). */
    private val captureRequestedAudioMode: AudioMode = audioMode

    private val captureRequestedSeparateMicRoute: Boolean
        get() = separateMicFileDescriptor != null

    override fun start() {
        mAudioMode = audioMode
        internalPlaybackPcmReadsPositive.set(0L)
        internalPlaybackPcmNonZeroBuffers.set(0L)
        internalPlaybackPcmSilentOnlyBuffers.set(0L)
        internalPlaybackPcmFirstNonZeroLoop.set(-1L)
        internalPlaybackPcmFirstNonZeroElapsedMs = -1L
        internalPlaybackRecreateAttempts.set(0)
        internalPlaybackRecreateSucceeded.set(false)
        internalPlaybackPcmCountersActive = false
        internalPlaybackBufferBytesConfigured = 0
        internalPlaybackSilenceDiagFired.set(false)
        internalPlaybackRecoveredAfterSilenceLogged.set(false)
        micLegEverAudibleThisSession = false
        earlySeparateMicMainMuxSwitchAttempted = false
        pendingMicFallback.set(false)
        micFallbackHotSwapApplied.set(false)
        audioCaptureStopping.set(false)
        audioInputEosQueued.set(false)
        audioInputEosSignalFailed.set(false)
        separateMicInputEosQueued.set(false)
        separateMicInputEosSignalFailed.set(false)
        videoEosReached.set(false)
        audioEosReached.set(false)
        separateMicEosReached.set(false)
        captureSessionStartElapsedMs = SystemClock.elapsedRealtime()
        internalMonitorRecord = null
        internalMonitorUntilElapsedMs = 0L
        internalAudioHealth.reset(captureSessionStartElapsedMs)

        try {
            prepareVideoEncoder()
            prepareAudioEncoder()
            if (separateMicFileDescriptor != null) prepareSeparateMicEncoder()
            // Guard: stopRecording()'s IO coroutine calls stop() then immediately closes
            // currentPfd. If the projection was revoked while prepareVideoEncoder() was
            // running, stop() may have already executed and the fd is now closed. Bail
            // before passing a closed FileDescriptor to MediaMuxer, which would throw
            // IllegalArgumentException: Invalid file descriptor.
            throwIfStopRequestedDuringSetup()
            prepareMuxer()

            // Start encoders before any frame hits the input Surface. Feeding the encoder surface
            // while the codec is still Configured breaks some OEM stacks (CodecException in start()
            // or native_start) on newer Android — e.g. Nothing Phone + API 36.
            try {
                videoEncoder?.start()
            } catch (e: Exception) {
                VideoEncoderConfigurator.describeStartFailure(TAG, configuredVideoEncoder, e)
                if (shouldRetryVideoStart(e)) {
                    Log.e(
                        TAG,
                        "Video encoder start failed (${e.javaClass.simpleName}: ${e.message}); " +
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
                    configuredVideoEncoder = null
                    configuredVideoMime = ""
                    inputSurface = null
                    val conservativeRetry = Build.VERSION.SDK_INT >= 36
                    prepareVideoEncoder(avcOnly = true, safeStartFallback = conservativeRetry)
                    Log.i(
                        TAG,
                        if (conservativeRetry) {
                            "Retrying video encoder start with conservative AVC config after start failure"
                        } else {
                            "Retrying video encoder start with AVC after HEVC start failure"
                        },
                    )
                    try {
                        videoEncoder?.start()
                    } catch (retryError: Exception) {
                        VideoEncoderConfigurator.describeStartFailure(
                            TAG,
                            configuredVideoEncoder,
                            retryError,
                            phase = "start retry",
                        )
                        throw retryError
                    }
                } else {
                    throw e
                }
            }
            if (mainMuxAudioMode != AudioMode.NONE) {
                audioEncoder?.start()
                queueSilentAudioFrame()
            }
            separateMicEncoder?.start()

            // Start the selected video producer after the encoder accepts input.
            startVideoProducer()
            throwIfStopRequestedDuringSetup()

            isRecording.set(true)

            videoDrainThread = Thread { drainVideoLoop() }.apply { name = "CatRec-VideoDrain" }
            videoDrainThread?.start()

            // Start microphones / playback capture after the projection producer is attached.
            // Some OEM playback-capture routes bind to the active captured content at
            // AudioRecord.startRecording(); starting it before the VirtualDisplay can leave
            // internal audio permanently zero-filled for that session.
            throwIfStopRequestedDuringSetup()
            validateAudioRecorderCapturesOrAdjustSessionOrThrow()
            logAudioSessionConfiguration("capture_ready")
            AudioRecordingCrashlyticsReporter.onFullCaptureReady(
                capturedModeName = mAudioMode.name,
                mainMuxModeName = mainMuxAudioMode.name,
                micRecordPresent = micRecord != null,
                internalRecordPresent = internalRecord != null,
                effectiveChannelCount = effectiveChannelCount.get(),
                aacConfiguredChannels = configuredMainAacChannels,
            )

            if (mAudioMode != AudioMode.NONE) {
                audioThread = Thread { captureAudioLoop() }.apply { name = "CatRec-AudioCapture" }
                audioThread?.start()
                if (mainMuxAudioMode != AudioMode.NONE) {
                    audioDrainThread = Thread { drainAudioLoop() }.apply { name = "CatRec-AudioDrain" }
                    audioDrainThread?.start()
                }
            }

            if (separateMicEncoder != null) {
                separateMicDrainThread = Thread { drainSeparateMicLoop() }.apply { name = "CatRec-SeparateMicDrain" }
                separateMicDrainThread?.start()
            }
        } catch (e: Exception) {
            if (e is RecorderSetupStoppedException) {
                Log.i(TAG, "Start aborted because stop() was requested during encoder setup")
            } else {
                Log.e(TAG, "Start failed", e)
            }
            stop()
            throw e
        }
    }

    private fun startVideoProducer() {
        val surface = inputSurface ?: throw IllegalStateException("Video input surface unavailable")
        when (engineMode) {
            RecordingEngineMode.PERFORMANCE -> {
                Log.i(TAG, "Starting Performance recording engine: VirtualDisplay -> MediaCodec input surface")
                directVirtualDisplay =
                    mediaProjection.createVirtualDisplay(
                        "CatRecEngine",
                        captureWidth,
                        captureHeight,
                        dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null,
                    ) ?: throw IllegalStateException("Performance engine createVirtualDisplay returned null")
            }

            RecordingEngineMode.COMPATIBILITY -> {
                Log.i(TAG, "Starting Compatibility recording engine: EncoderFrameRelay -> MediaCodec input surface")
                frameRelay =
                    EncoderFrameRelay(
                        mediaProjection,
                        surface,
                        captureWidth,
                        captureHeight,
                        dpi,
                        "CatRecEngine",
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

    private fun throwIfStopRequestedDuringSetup() {
        if (stopInvoked.get()) throw RecorderSetupStoppedException()
    }

    internal var audioDowngradeNoticeDispatcher: (Int) -> Unit = { messageRes ->
        Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val txt = context.getString(messageRes)
                onAudioCaptureDowngraded?.invoke(txt)
                    ?: Toast.makeText(context.applicationContext, txt, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun releaseMainAudioEncoderAfterStartupFallback(reason: String) {
        val encoder = audioEncoder ?: return
        Log.w(TAG, "${AUDIO_DIAG_MARKER} releasing main audio encoder after startup fallback reason=$reason")
        try {
            encoder.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "main audio encoder stop ignored during startup fallback: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "main audio encoder stop failed during startup fallback: ${e.message}")
        }
        try {
            encoder.release()
        } catch (e: Exception) {
            Log.w(TAG, "main audio encoder release failed during startup fallback: ${e.message}")
        }
        audioEncoder = null
        audioTrackIndex = -1
        audioInputEosQueued.set(true)
        audioInputEosSignalFailed.set(false)
        audioEosReached.set(true)
    }

    private fun startMainMuxerIfVideoTrackAlreadyReadyAfterAudioFallback(reason: String) {
        synchronized(muxerLock) {
            val activeMuxer = muxer ?: return
            if (isMuxerStarted || muxerReleased || mainMuxAudioMode != AudioMode.NONE || videoTrackIndex < 0) return
            activeMuxer.start()
            isMuxerStarted = true
            Log.d(
                TAG,
                "Muxer started after audio startup fallback reason=$reason videoTrack=$videoTrackIndex",
            )
        }
    }

    /**
     * Brings active [AudioRecord] instances into [AudioRecord.RECORDSTATE_RECORDING] **before**
     * AAC encoders consume PCM. MIXED without a separate-mic route may downgrade one failed leg only
     * when AAC channel layout still matches the surviving [AudioRecord] (AAC format must not change).
     */
    private fun validateAudioRecorderCapturesOrAdjustSessionOrThrow() {
        if (mAudioMode == AudioMode.NONE) return

        val wantStereoUser = audioChannelCount == 2

        fun releaseRecordingQuiet(record: AudioRecord?) {
            if (record == null) return
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }

        fun tryStart(
            which: AudioRecord?,
            label: String,
        ): Throwable? {
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
                    IllegalStateException("$label recordingState=$rs after startRecording()")
                } else {
                    null
                }
            } catch (t: Throwable) {
                if (label == "internal") {
                    internalAudioHealth.noteAudioRecordStart(AudioRecord.RECORDSTATE_STOPPED)
                }
                IllegalStateException("$label startRecording()", t)
            }
        }

        fun logFailure(
            label: String,
            err: Throwable,
        ) {
            Log.e(TAG, "${AUDIO_DIAG_MARKER} AudioRecord_START_FAIL label=$label: ${err.javaClass.simpleName}: ${err.message}", err)
            AppLogger.e(TAG, "AudioRecord start failed [$label]: ${err.message}")
        }

        val micDesired = micRecord != null
        val intDesired = internalRecord != null

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(
                TAG,
                "${AUDIO_DIAG_MARKER} PREP requested_mode=${captureRequestedAudioMode.name} post_prepare_capture=$mAudioMode mainMux=$mainMuxAudioMode " +
                    "separate_mic_route=$captureRequestedSeparateMicRoute mic_on=${captureRequestedAudioMode == AudioMode.MIC || captureRequestedAudioMode == AudioMode.MIXED} " +
                    "internal_on=${captureRequestedAudioMode == AudioMode.INTERNAL || captureRequestedAudioMode == AudioMode.MIXED} " +
                    "srHz=$audioSampleRate userCh=$audioChannelCount aacCh=$configuredMainAacChannels bitrate=$audioBitrate encoder=$audioEncoderType " +
                    "mic_alive=$micDesired internal_alive=$intDesired brand=${Build.BRAND} model=${Build.MODEL} api=${Build.VERSION.SDK_INT}",
            )
        }

        throwIfStopRequestedDuringSetup()
        var micThr = micRecord?.let { tryStart(it, "mic") }
        micThr?.let { err -> micRecord?.let { logFailure("mic", err) } }

        throwIfStopRequestedDuringSetup()
        var intThr =
            internalRecord?.let {
                if (micDesired && micThr == null) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                val t = tryStart(it, "internal")
                if (t != null) logFailure("internal", t)
                t
            }

        val micFail = micDesired && micThr != null
        val intFail = intDesired && intThr != null
        val micOk = micDesired && micThr == null
        val intOk = intDesired && intThr == null
        if (intFail) {
            AudioRecordingCrashlyticsReporter.reportInternalAudioRecordStartFailed(
                AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                internalAudioHealth.snapshot(
                    muxerStarted = isMuxerStarted,
                    muxerAudioSamplesWritten = audioSamplesWritten,
                ),
            )
        }

        if (separateMicEncoder != null && micFail) {
            throw IllegalStateException("${AUDIO_DIAG_MARKER} Microphone capture required for separate-mic AAC", micThr)
        }

        when {
            micFail && intFail ->
                throw IllegalStateException("${AUDIO_DIAG_MARKER} Both mic and internal AudioRecord.start failed.")

            micFail && intOk ->
                resolveMixedMicStartFailureContinueOrThrow(requireNotNull(micThr), wantStereoUser, ::releaseRecordingQuiet)

            intFail && micOk ->
                resolveMixedInternalStartFailureContinueOrThrow(requireNotNull(intThr), wantStereoUser, ::releaseRecordingQuiet)

            micFail ->
                throw IllegalStateException("${AUDIO_DIAG_MARKER} Microphone capture failed", micThr)

            intFail ->
                throw IllegalStateException("${AUDIO_DIAG_MARKER} Internal (/playback) capture failed", intThr)
        }

        internalPlaybackPcmCountersActive =
            internalRecord != null &&
            runCatching {
                internalRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrElse { false }
        val ir = internalRecord
        if (Build.VERSION.SDK_INT >= 29 && internalPlaybackPcmCountersActive && ir != null) {
            PlaybackCaptureConfig.logInternalPlaybackRecordStarted(
                logTag = TAG,
                sessionDiagMarker = AUDIO_DIAG_MARKER,
                record = ir,
                configuredBufferBytes = internalPlaybackBufferBytesConfigured,
                configuredChannelMask = internalPlaybackChannelMaskConfigured,
                sampleRateFromBuilder = audioSampleRate,
            )
        }
    }

    /**
     * One-shot recreation of [internalRecord] from inside [captureAudioLoop] after a sustained
     * silence window. Bounded by [INTERNAL_SILENCE_RECREATE_ATTEMPTS]. Runs on the audio-capture
     * thread — that's the only thread that ever calls [AudioRecord.read] / .stop / .release on
     * [internalRecord], so no extra synchronization is needed. Channel-mask, sample rate and
     * buffer size are reused exactly so AAC layout and timestamps remain stable.
     *
     * Returns true on success (new AudioRecord is recording). Returns false if it gives up; the
     * caller should fall through to the user-facing silence toast as before.
     *
     * Why this helps:
     *  - On some games / OEMs the foreground app's audio path isn't yet wired up to the
     *    playback-capture mix when the recorder starts (overlay/QS launches CatRec → its tiny
     *    projection activity is briefly foreground → game routing settles after the first
     *    AudioRecord open). Recreating gives AudioFlinger a fresh chance to attach.
     *  - Costs at most two staged rebuild attempts per session.
     *  - Does NOT change the AAC encoder format, the muxer, the MediaProjection token, the
     *    VirtualDisplay, or any audio-routing state.
     */
    @SuppressLint("MissingPermission")
    private fun attemptInternalAudioRecordRecreation(reason: String): Boolean {
        if (INTERNAL_SILENCE_RECREATE_ATTEMPTS <= 0) return false
        if (Build.VERSION.SDK_INT < 29) return false
        val previous = internalRecord ?: return false
        val sampleRate = audioSampleRate
        val channelMask = internalPlaybackChannelMaskConfigured
        val bufferBytes = internalPlaybackBufferBytesConfigured
        if (bufferBytes <= 0) return false
        var attemptNumber = 0
        while (attemptNumber == 0) {
            val current = internalPlaybackRecreateAttempts.get()
            if (current >= INTERNAL_SILENCE_RECREATE_ATTEMPTS) return false
            if (internalPlaybackRecreateAttempts.compareAndSet(current, current + 1)) {
                attemptNumber = current + 1
            }
        }
        internalAudioHealth.noteRebuildAttempt()

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(
                TAG,
                "${AUDIO_DIAG_MARKER} INTERNAL_PLAYBACK_RECREATE attempt#$attemptNumber reason=$reason " +
                    "sr=$sampleRate channelMask=$channelMask bufferBytes=$bufferBytes",
            )
        }

        // Construct + start the new AudioRecord BEFORE releasing the old one. If anything fails
        // we fall back to the original instance and the session keeps running (possibly silent,
        // but never crashing). AudioFlinger allows multiple playback-capture AudioRecord
        // instances on the same MediaProjection token, so the brief overlap is safe.
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
                    "${AUDIO_DIAG_MARKER} INTERNAL_PLAYBACK_RECREATE build failed: ${e.javaClass.simpleName}: ${e.message}",
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
            Log.e(TAG, "${AUDIO_DIAG_MARKER} INTERNAL_PLAYBACK_RECREATE startRecording failed: ${e.message}", e)
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
            Log.w(TAG, "${AUDIO_DIAG_MARKER} INTERNAL_PLAYBACK_RECREATE recordingState=$rs after start; aborting")
            try {
                rebuilt.release()
            } catch (_: Exception) {
            }
            return false
        }
        if (!isRecording.get()) {
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

        // Hand the loop the rebuilt instance, then release the old one. internalRecord is only
        // touched on this audio-capture thread, so the swap is safe without extra locks.
        internalRecord = rebuilt
        internalPlaybackRecreateSucceeded.set(true)
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
            sessionDiagMarker = "$AUDIO_DIAG_MARKER RECREATED",
            record = rebuilt,
            configuredBufferBytes = bufferBytes,
            configuredChannelMask = channelMask,
            sampleRateFromBuilder = sampleRate,
        )
        return true
    }

    private fun resolveMixedMicStartFailureContinueOrThrow(
        primaryFailure: Throwable,
        wantStereoUser: Boolean,
        releaseQuiet: (AudioRecord?) -> Unit,
    ) {
        if (routeMicToSeparateFile || mAudioMode != AudioMode.MIXED || internalRecord == null) {
            throw IllegalStateException("${AUDIO_DIAG_MARKER} Mic capture failed — no downgrade", primaryFailure)
        }
        val ir = internalRecord ?: throw IllegalStateException("${AUDIO_DIAG_MARKER} degrade internal missing")
        micRecord?.let { releaseQuiet(it) }
        micRecord = null
        mAudioMode = AudioMode.INTERNAL
        mainMuxAudioMode = computeMainMuxAudioMode()
        val survivorCh =
            resolveMainMuxChannelCount(
                AudioMode.INTERNAL,
                wantStereoUser,
                ir,
                null,
            ).also { effectiveChannelCount.set(it) }

        if (survivorCh != configuredMainAacChannels) {
            throw IllegalStateException(
                "${AUDIO_DIAG_MARKER} downgrade blocked AAC_ch=$configuredMainAacChannels internal_ch=$survivorCh",
                primaryFailure,
            )
        }
        Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val txt = context.getString(R.string.toast_audio_downgraded_mic_unavailable_internal_only)
                onAudioCaptureDowngraded?.invoke(txt)
                    ?: Toast.makeText(context.applicationContext, txt, Toast.LENGTH_LONG).show()
            }
        }
        Log.w(TAG, "${AUDIO_DIAG_MARKER} MIXED→INTERNAL mic failed; AAC layout unchanged ch=$configuredMainAacChannels")
        AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable("mic_leg_failed_kept_internal")
    }

    private fun resolveMixedInternalStartFailureContinueOrThrow(
        primaryFailure: Throwable,
        wantStereoUser: Boolean,
        releaseQuiet: (AudioRecord?) -> Unit,
    ) {
        if (mAudioMode != AudioMode.MIXED || micRecord == null) {
            throw IllegalStateException("${AUDIO_DIAG_MARKER} Internal capture failed — no downgrade", primaryFailure)
        }
        val mr = micRecord ?: throw IllegalStateException("${AUDIO_DIAG_MARKER} degrade mic missing")
        internalRecord?.let { releaseQuiet(it) }
        internalRecord = null
        mAudioMode = AudioMode.MIC
        mainMuxAudioMode = if (routeMicToSeparateFile) AudioMode.NONE else computeMainMuxAudioMode()

        if (routeMicToSeparateFile) {
            effectiveChannelCount.set(mr.channelCount.coerceIn(1, 2))
            releaseMainAudioEncoderAfterStartupFallback("internal_leg_failed_kept_separate_mic")
            startMainMuxerIfVideoTrackAlreadyReadyAfterAudioFallback("internal_leg_failed_kept_separate_mic")
            audioDowngradeNoticeDispatcher(R.string.toast_audio_downgraded_internal_unavailable_mic_only)
            Log.w(TAG, "${AUDIO_DIAG_MARKER} MIXED→MIC separate-mic-only internal failed; main mux audio disabled")
            AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable("internal_leg_failed_kept_separate_mic")
            return
        }

        val survivorCh =
            resolveMainMuxChannelCount(
                AudioMode.MIC,
                wantStereoUser,
                null,
                mr,
            ).also { effectiveChannelCount.set(it) }

        if (survivorCh != configuredMainAacChannels) {
            throw IllegalStateException(
                "${AUDIO_DIAG_MARKER} downgrade blocked AAC_ch=$configuredMainAacChannels mic_ch=$survivorCh",
                primaryFailure,
            )
        }
        audioDowngradeNoticeDispatcher(R.string.toast_audio_downgraded_internal_unavailable_mic_only)
        Log.w(TAG, "${AUDIO_DIAG_MARKER} MIXED→MIC internal failed; AAC layout unchanged ch=$configuredMainAacChannels")
        AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable("internal_leg_failed_kept_mic")
    }

    private fun logAudioSessionConfiguration(stage: String) {
        Log.i(
            TAG,
            "${AUDIO_DIAG_MARKER} audio_$stage requested=${captureRequestedAudioMode.name} effective=$mAudioMode " +
                "mainMux=$mainMuxAudioMode sr=$audioSampleRate ch=${effectiveChannelCount.get()} " +
                "separateMic=$routeMicToSeparateFile micReady=${micRecord != null} internalReady=${internalRecord != null}",
        )
    }

    private fun logAudioCaptureStopDiagnostics() {
        val mainSamplesWritten: Boolean
        val sepSamplesWritten: Boolean
        val sepPipelineActive: Boolean
        synchronized(muxerLock) {
            mainSamplesWritten = audioSamplesWritten
        }
        synchronized(separateMicLock) {
            sepSamplesWritten = separateMicSamplesWritten
            sepPipelineActive = routeMicToSeparateFile && separateMicEncoder != null
        }
        val intReads = internalPlaybackPcmReadsPositive.get()
        val intNzBufs = internalPlaybackPcmNonZeroBuffers.get()
        val intSilentBufs = internalPlaybackPcmSilentOnlyBuffers.get()
        val internalRecoveredAfterInitialSilence = internalPlaybackRecoveredAfterSilenceLogged.get()
        val audioInitFailed = captureRequestedAudioMode != AudioMode.NONE && mAudioMode == AudioMode.NONE
        val internalHealthSnapshot =
            internalAudioHealth.snapshot(
                muxerStarted = mainSamplesWritten || videoSamplesWritten,
                muxerAudioSamplesWritten = mainSamplesWritten,
            )

        Log.i(
            TAG,
            "${AUDIO_DIAG_MARKER} audio_summary requested=${captureRequestedAudioMode.name} effective=$mAudioMode mainMux=$mainMuxAudioMode " +
                "sr=$audioSampleRate ch=${effectiveChannelCount.get()} fallbackUsed=${micFallbackHotSwapApplied.get()} " +
                "internalSilence=${internalPlaybackSilenceDiagFired.get()} internalRecovered=$internalRecoveredAfterInitialSilence " +
                "audioInitFailed=$audioInitFailed mainSamples=$mainSamplesWritten separateMicSamples=$sepSamplesWritten " +
                "mainInputEos=${audioInputEosQueued.get()} mainOutputEos=${audioEosReached.get()} " +
                "separateInputEos=${separateMicInputEosQueued.get()} separateOutputEos=${separateMicEosReached.get()} " +
                "pcmBackpressureMain=${pcmDropMainAacInputCount.get()} pcmBackpressureSeparate=${pcmDropSeparateMicInputCount.get()} " +
                "readErrors=${audioReadNegativeErrorCount.get()}",
        )
        if (verboseAudioDiagnosticsEnabled) {
            val firstNzLoop = internalPlaybackPcmFirstNonZeroLoop.get()
            val firstNzElapsedMs = internalPlaybackPcmFirstNonZeroElapsedMs
            val firstNzDelayMs =
                if (firstNzElapsedMs >= 0L && captureSessionStartElapsedMs > 0L) {
                    firstNzElapsedMs - captureSessionStartElapsedMs
                } else {
                    -1L
                }
            val intPcmDiag =
                if (internalPlaybackPcmCountersActive) {
                    "intPCM_reads=$intReads intPCM_nzBufs=$intNzBufs intPCM_silentBufs=$intSilentBufs " +
                        "intPCM_everNonZero=${intNzBufs > 0L} intPCM_firstNzLoop=$firstNzLoop intPCM_firstNzMs=$firstNzDelayMs " +
                        "intPCM_recreateAttempts=${internalPlaybackRecreateAttempts.get()} intPCM_recreateSucceeded=${internalPlaybackRecreateSucceeded.get()}"
                } else {
                    "intPCM_reads=na intPCM_diagInactive"
                }
            Log.d(TAG, "${AUDIO_DIAG_MARKER} audio_summary_verbose $intPcmDiag")
        }
        AudioRecordingCrashlyticsReporter.finalizeFullSession(
            finalCaptureModeName = mAudioMode.name,
            mainMuxModeName = mainMuxAudioMode.name,
            muxRequiredMainSamples = mainMuxAudioMode != AudioMode.NONE,
            mainMuxSamplesWritten = mainSamplesWritten,
            separateMicTrackActive = sepPipelineActive,
            separateMicSamplesWritten = sepSamplesWritten,
            pcmDropMain = pcmDropMainAacInputCount.get(),
            pcmDropSeparate = pcmDropSeparateMicInputCount.get(),
            readNegCount = audioReadNegativeErrorCount.get(),
            internalSilenceObservedThisSession = internalPlaybackSilenceDiagFired.get(),
            micFallbackUsed = micFallbackHotSwapApplied.get(),
            internalRecoveredAfterInitialSilence = internalRecoveredAfterInitialSilence,
            audioInputEosQueued = audioInputEosQueued.get(),
            audioOutputEosObserved = audioEosReached.get(),
            separateMicInputEosQueued = separateMicInputEosQueued.get(),
            separateMicOutputEosObserved = separateMicEosReached.get(),
            internalPlaybackPcmReadsPositive = intReads.takeIf { internalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled },
            internalPlaybackPcmNonZeroBuffers = intNzBufs.takeIf { internalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled },
            internalPlaybackPcmSilentBuffers = intSilentBufs.takeIf { internalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled },
            internalPlaybackPcmEverNonZero =
                (intNzBufs > 0L).takeIf {
                    internalPlaybackPcmCountersActive && verboseAudioDiagnosticsEnabled
                },
            internalAudioHealth = internalHealthSnapshot,
        )
    }

    /**
     * Teardown sequence — MUST run exactly once, in this strict order, to satisfy the
     * MediaCodec ↔ MediaMuxer state contract (otherwise MPEG4Writer logs
     * "Stop() called but track is not started" or throws IllegalStateException):
     *
     *   1. Stop producing into the encoder input surface (direct VirtualDisplay or frame relay) and audio sources.
     *   2. signalEndOfInputStream (video) / queue EOS input buffer (audio).
     *   3. Join drain threads — they run past isRecording=false until BUFFER_FLAG_END_OF_STREAM
     *      is observed (with a bounded deadline) so every pending output buffer is written.
     *   4. muxer.stop() — only if isMuxerStarted (i.e. [MediaMuxer.start] completed with all
     *      required tracks added). Guarded by [muxerLock] so drain threads cannot race.
     *   5. muxer.release(), then release encoders and audio sources.
     *
     * Re-entry is guarded by [stopInvoked] so [start]'s failure path and the service's
     * teardown cannot both execute the sequence.
     */
    override fun stop() {
        if (!stopInvoked.compareAndSet(false, true)) {
            Log.d(TAG, "stop(): already invoked, ignoring duplicate call")
            return
        }
        audioCaptureStopping.set(true)
        isRecording.set(false)
        Log.d(TAG, "Stopping recorder…")

        // ── 1. Halt input into encoders ───────────────────────────────────────
        stopVideoProducer()

        try {
            micRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "micRecord.stop() in unexpected state: ${e.message}")
        }
        try {
            internalRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "internalRecord.stop() in unexpected state: ${e.message}")
        }
        try {
            internalMonitorRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "internalMonitorRecord.stop() in unexpected state: ${e.message}")
        }

        try {
            audioThread?.join(AUDIO_CAPTURE_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (audioThread?.isAlive == true) {
            Log.w(TAG, "Audio capture thread still alive after ${AUDIO_CAPTURE_JOIN_TIMEOUT_MS}ms; proceeding with guarded EOS shutdown")
        }

        // ── 2. Signal EOS to every encoder ────────────────────────────────────
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: IllegalStateException) {
            // Only legitimately throws if the encoder was never configured/started.
            Log.w(TAG, "signalEndOfInputStream in unexpected state: ${e.message}")
        }
        signalAudioEOS()
        signalSeparateMicEOS()

        // ── 3. Drain until BUFFER_FLAG_END_OF_STREAM (bounded by drain-loop deadline) ─
        try {
            audioDrainThread?.join(AUDIO_DRAIN_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (audioDrainThread?.isAlive == true) {
            Log.w(TAG, "Audio drain thread still alive after ${AUDIO_DRAIN_JOIN_TIMEOUT_MS}ms")
        }
        try {
            videoDrainThread?.join(6000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            separateMicDrainThread?.join(AUDIO_DRAIN_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (separateMicDrainThread?.isAlive == true) {
            Log.w(TAG, "Separate mic drain thread still alive after ${AUDIO_DRAIN_JOIN_TIMEOUT_MS}ms")
        }

        // If the drain threads exited via the deadline rather than observing EOS, the muxer
        // may be missing trailing samples. We still stop it cleanly, but flag the condition
        // so the warning is not silent.
        if (videoEncoder != null && !videoEosReached.get()) {
            Log.w(TAG, "Video drain exited WITHOUT observing EOS — muxer may lose trailing frames")
        }
        if (audioEncoder != null && mainMuxAudioMode != AudioMode.NONE && !audioEosReached.get()) {
            Log.w(TAG, "Audio drain exited WITHOUT observing EOS — muxer may lose trailing samples")
        }
        if (separateMicEncoder != null && !separateMicEosReached.get()) {
            Log.w(TAG, "Separate-mic drain exited WITHOUT observing EOS")
        }

        // ── 4 + 5. Stop and release the main muxer ────────────────────────────
        // Under muxerLock so any drain-thread leftover (if join timed out) cannot
        // writeSampleData / start() concurrently with stop/release.
        //
        // Guard on (videoSamplesWritten || audioSamplesWritten): calling muxer.stop() when
        // a track was added but never received a sample is exactly what triggers
        // MPEG4Writer's "Stop() called but track is not started". In that case the output
        // file is empty anyway, so we skip stop() and release() the muxer directly —
        // [hadOutput] will report false and the service will discard the file.
        synchronized(muxerLock) {
            muxerReleased = true
            val m = muxer
            if (m != null) {
                val anySamples = videoSamplesWritten || audioSamplesWritten
                if (isMuxerStarted && anySamples) {
                    try {
                        m.stop()
                    } catch (e: IllegalStateException) {
                        // Should be unreachable now that we gate on anySamples, but keep
                        // the loud log so any OEM regression surfaces instead of corrupting
                        // output silently.
                        Log.e(
                            TAG,
                            "Muxer stop() rejected by MPEG4Writer — tracks started=$isMuxerStarted " +
                                "videoTrack=$videoTrackIndex audioTrack=$audioTrackIndex " +
                                "videoSamples=$videoSamplesWritten audioSamples=$audioSamplesWritten " +
                                "videoEos=${videoEosReached.get()} audioEos=${audioEosReached.get()}",
                            e,
                        )
                    }
                } else if (isMuxerStarted) {
                    Log.w(
                        TAG,
                        "Skipping muxer.stop(): no samples written " +
                            "(videoTrack=$videoTrackIndex audioTrack=$audioTrackIndex " +
                            "videoSamples=$videoSamplesWritten audioSamples=$audioSamplesWritten)",
                    )
                } else {
                    Log.d(TAG, "Muxer never started (no tracks ready) — skipping stop(), release only")
                }
                try {
                    m.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer release failed: ${e.message}")
                }
            }
            isMuxerStarted = false
            muxer = null
        }

        synchronized(separateMicLock) {
            separateMicMuxerReleased = true
            val m = separateMicMuxer
            if (m != null) {
                if (isSeparateMicMuxerStarted && separateMicSamplesWritten) {
                    try {
                        m.stop()
                    } catch (e: IllegalStateException) {
                        Log.e(
                            TAG,
                            "Separate-mic muxer stop() rejected — track=$separateMicTrackIndex " +
                                "samples=$separateMicSamplesWritten eos=${separateMicEosReached.get()}",
                            e,
                        )
                    }
                } else if (isSeparateMicMuxerStarted) {
                    Log.w(
                        TAG,
                        "Skipping separate-mic muxer.stop(): no samples written " +
                            "(track=$separateMicTrackIndex)",
                    )
                }
                try {
                    m.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Separate-mic muxer release failed: ${e.message}")
                }
            }
            isSeparateMicMuxerStarted = false
            separateMicMuxer = null
        }

        logAudioCaptureStopDiagnostics()

        // Encoders/surface/audio sources can be released only after the muxer is down.
        try {
            videoEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "videoEncoder.release() failed: ${e.message}")
        }
        videoEncoder = null
        configuredVideoEncoder = null

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "inputSurface.release() failed: ${e.message}")
        }
        inputSurface = null

        try {
            audioEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "audioEncoder.release() failed: ${e.message}")
        }
        try {
            separateMicEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "separateMicEncoder.release() failed: ${e.message}")
        }
        audioEncoder = null
        separateMicEncoder = null

        try {
            micRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "micRecord.release() failed: ${e.message}")
        }
        try {
            internalRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "internalRecord.release() failed: ${e.message}")
        }
        try {
            internalMonitorRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "internalMonitorRecord.release() failed: ${e.message}")
        }
        micRecord = null
        internalRecord = null
        internalMonitorRecord = null

        Log.d(TAG, "Recorder stopped cleanly")
    }

    /**
     * True only if at least one video sample was actually written. Gated by [muxerLock] so the
     * read is consistent with drain threads, including the final drain that runs inside [stop].
     *
     * Audio-only output is not a usable screen recording. A muxer that was `start()`ed but never
     * received a video sample produces an empty / non-playable recording for CatRec's workflow, so
     * the service must treat that case as "no output" and discard the file.
     */
    override fun hadOutput(): Boolean {
        synchronized(muxerLock) {
            return videoSamplesWritten
        }
    }

    /**
     * Grabs the next composited frame after [requestScreenshot] is called (same pipeline as video).
     * Callback may run on the frame-relay thread; post to main if needed for UI.
     */
    override fun requestScreenshot(onBitmap: (Bitmap?) -> Unit) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) {
            if (performanceScreenshotBlockedLogged.compareAndSet(false, true)) {
                Log.w(TAG, "Screenshot while recording unavailable in Performance engine")
            }
            onBitmap(null)
            return
        }
        val relay = frameRelay
        if (!isRecording.get() || relay == null) {
            onBitmap(null)
            return
        }
        relay.requestScreenshot(onBitmap)
    }

    override fun pause() {
        if (!isPaused.getAndSet(true)) {
            pauseStartTimeUs = System.nanoTime() / 1000
        }
    }

    override fun resume() {
        if (isPaused.getAndSet(false)) {
            val now = System.nanoTime() / 1000
            totalPausedTimeUs.addAndGet(now - pauseStartTimeUs)
            requestSyncFrame()
        }
    }

    override fun mute() {
        isMuted.set(true)
    }

    override fun unmute() {
        isMuted.set(false)
    }

    // ── Analytics / diagnostics ────────────────────────────────────────────────

    /**
     * Fires a Firebase Analytics event with optional string parameters.
     * Also writes a Crashlytics breadcrumb so the event appears in crash logs.
     * Swallows any exception — analytics must never crash the recorder.
     */
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

    private fun requestSyncFrame() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            videoEncoder?.setParameters(params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request sync frame", e)
        }
    }

    override fun applyAdaptiveVideoBitrateBps(targetBps: Int): Boolean {
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

    override fun setAdaptiveSkipModulo(modulo: Int) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) return
        frameRelay?.adaptiveSkipModulo = modulo
    }

    /**
     * Resizes the capture [VirtualDisplay] + [ImageReader] to [newW]×[newH] without changing
     * the encoder output resolution.  Call this when the OS reports a content-size change
     * (rotation, fold) so stale pixels no longer contaminate the captured frames.
     */
    override fun resizeCaptureSource(
        newW: Int,
        newH: Int,
    ) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) {
            resizePerformanceCaptureSource(newW, newH)
            return
        }
        frameRelay?.resizeCaptureSource(newW, newH)
    }

    private fun resizePerformanceCaptureSource(
        newW: Int,
        newH: Int,
    ) {
        val requested = RecordingResolutionSize(newW, newH)
        val aligned =
            alignedPerformanceResizeSize(
                newW = newW,
                newH = newH,
                encoderType = encoderType,
                fps = fps,
            )
        val oldW = captureWidth
        val oldH = captureHeight
        val virtualDisplay = directVirtualDisplay

        if (aligned.width == oldW && aligned.height == oldH) {
            logPerformanceResizeRequest(
                requested = requested,
                aligned = aligned,
                oldW = oldW,
                oldH = oldH,
                path = "early-return",
                error = null,
            )
            return
        }

        if (virtualDisplay == null) {
            logPerformanceResizeRequest(
                requested = requested,
                aligned = aligned,
                oldW = oldW,
                oldH = oldH,
                path = "early-return-no-virtual-display",
                error = null,
            )
            return
        }

        try {
            performanceVirtualDisplayResizeStrategy.resize(
                virtualDisplay = virtualDisplay,
                newWidth = aligned.width,
                newHeight = aligned.height,
                densityDpi = dpi,
            )
            captureWidth = aligned.width
            captureHeight = aligned.height
            logPerformanceResizeRequest(
                requested = requested,
                aligned = aligned,
                oldW = oldW,
                oldH = oldH,
                path = "virtualDisplay.resize",
                error = null,
            )
            pendingPerformanceResizeSampleLog.set(
                PerformanceResizeSampleLog(
                    requested = requested,
                    aligned = aligned,
                    oldW = oldW,
                    oldH = oldH,
                    resizeElapsedMs = SystemClock.elapsedRealtime(),
                ),
            )
            // TODO: Track the first post-resize keyframe if clip saves ever show a stale-frame edge
            // immediately after a resize. Sync-frame requests are advisory on some encoders.
            requestSyncFrame()
        } catch (e: Exception) {
            logPerformanceResizeRequest(
                requested = requested,
                aligned = aligned,
                oldW = oldW,
                oldH = oldH,
                path = "surface-recreate-unavailable",
                error = e,
            )
            /*
             * A full surface-recreate path needs the full-recording pipeline to switch from a
             * single MediaMuxer/FileDescriptor to segment files plus a final merge. Releasing and
             * reconfiguring MediaCodec against the existing muxer would corrupt the MP4 because
             * MediaMuxer cannot accept a second video format/track after start().
             */
        }
    }

    private fun logFirstVideoSampleAfterPerformanceResizeIfNeeded(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        val pending = pendingPerformanceResizeSampleLog.getAndSet(null) ?: return
        val formatSize =
            try {
                encoder.outputFormat.mediaFormatSize()
            } catch (e: Exception) {
                "unavailable:${e.javaClass.simpleName}"
            }
        val message =
            "performance_resize_first_video_sample requested=${pending.requested.setting} " +
                "aligned=${pending.aligned.setting} oldEncoder=${pending.oldW}x${pending.oldH} " +
                "currentEncoder=${captureWidth}x$captureHeight outputFormat=$formatSize " +
                "sampleBytes=${bufferInfo.size} samplePtsUs=${bufferInfo.presentationTimeUs} " +
                "flags=${bufferInfo.flags} elapsedSinceResizeMs=" +
                "${SystemClock.elapsedRealtime() - pending.resizeElapsedMs}"
        Log.i(TAG, message)
        FirebaseCrashlytics.getInstance().log(message)
    }

    private fun MediaFormat.mediaFormatSize(): String {
        val width = if (containsKey(MediaFormat.KEY_WIDTH)) getInteger(MediaFormat.KEY_WIDTH) else null
        val height = if (containsKey(MediaFormat.KEY_HEIGHT)) getInteger(MediaFormat.KEY_HEIGHT) else null
        return if (width != null && height != null) "${width}x$height" else "unknown"
    }

    private fun logPerformanceResizeRequest(
        requested: RecordingResolutionSize,
        aligned: RecordingResolutionSize,
        oldW: Int,
        oldH: Int,
        path: String,
        error: Exception?,
    ) {
        val message =
            "performance_resize requested=${requested.setting} aligned=${aligned.setting} " +
                "oldEncoder=${oldW}x$oldH densityDpi=$dpi path=$path"
        if (error == null) {
            Log.i(TAG, message)
            FirebaseCrashlytics.getInstance().log(message)
        } else {
            Log.e(TAG, message, error)
            FirebaseCrashlytics.getInstance().log("$message error=${error.javaClass.simpleName}: ${error.message}")
        }
    }

    override fun attachAdaptivePerformance(
        sink: AdaptiveRecordingSignalSink?,
        signalsEnabled: Boolean,
        adaptiveTierSupplier: (() -> Int)?,
    ) {
        if (engineMode == RecordingEngineMode.PERFORMANCE) return
        frameRelay?.adaptiveSignalSink = sink
        frameRelay?.adaptiveSignalsEnabled = signalsEnabled
        frameRelay?.adaptiveTierSupplier = adaptiveTierSupplier
    }

    private fun signalAudioEOS() {
        val encoder = audioEncoder ?: return
        if (mainMuxAudioMode == AudioMode.NONE) return
        queueAudioInputEos(
            encoder = encoder,
            queuedFlag = audioInputEosQueued,
            failedFlag = audioInputEosSignalFailed,
            label = "main",
        )
    }

    private fun signalSeparateMicEOS() {
        val encoder = separateMicEncoder ?: return
        queueAudioInputEos(
            encoder = encoder,
            queuedFlag = separateMicInputEosQueued,
            failedFlag = separateMicInputEosSignalFailed,
            label = "separate_mic",
        )
    }

    private fun queueAudioInputEos(
        encoder: MediaCodec,
        queuedFlag: AtomicBoolean,
        failedFlag: AtomicBoolean,
        label: String,
    ): Boolean {
        if (queuedFlag.get()) return true
        val deadline = SystemClock.elapsedRealtime() + AUDIO_EOS_QUEUE_TIMEOUT_MS
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline && !queuedFlag.get()) {
            attempts++
            try {
                val index = encoder.dequeueInputBuffer(100_000L)
                if (index >= 0) {
                    val ptsUs = System.nanoTime() / 1000
                    encoder.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queuedFlag.set(true)
                    Log.i(TAG, "Queued $label audio input EOS attempts=$attempts ptsUs=$ptsUs")
                    return true
                }
            } catch (e: Exception) {
                failedFlag.set(true)
                Log.w(TAG, "Audio input EOS signal failed label=$label attempts=$attempts", e)
                return false
            }
        }
        failedFlag.set(true)
        Log.w(TAG, "Timed out queueing $label audio input EOS after ${AUDIO_EOS_QUEUE_TIMEOUT_MS}ms attempts=$attempts")
        return false
    }

    private fun queueSilentAudioFrame() {
        val encoder = audioEncoder ?: return
        try {
            val index = encoder.dequeueInputBuffer(10000)
            if (index >= 0) {
                val buffer = encoder.getInputBuffer(index)
                buffer?.clear()
                val size = 2048
                buffer?.put(ByteArray(size))
                encoder.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent frame queue failed", e)
        }
    }

    private fun prepareVideoEncoder(
        avcOnly: Boolean = false,
        safeStartFallback: Boolean = false,
    ) {
        val forceAvcHint =
            !avcOnly &&
                !safeStartFallback &&
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
                safeStartFallback = safeStartFallback,
            )
        videoEncoder = result.codec
        inputSurface = result.inputSurface
        configuredVideoEncoder = result
        configuredVideoMime = result.mime
        captureWidth = result.encodedWidth
        captureHeight = result.encodedHeight
        Log.d(
            TAG,
            "Video encoder configured codec=${result.codecName} mime=$configuredVideoMime avcOnly=$avcOnly " +
                "safeStartFallback=$safeStartFallback size=${captureWidth}x$captureHeight " +
                "fps=${result.fps} bitrate=${result.bitrate} profile=${result.profile} level=${result.level}",
        )
    }

    private fun shouldRetryVideoStart(error: Exception): Boolean =
        configuredVideoMime == MediaFormat.MIMETYPE_VIDEO_HEVC ||
            (Build.VERSION.SDK_INT >= 36 && VideoEncoderConfigurator.isCodecException(error))

    @SuppressLint("MissingPermission")
    private fun prepareAudioEncoder() {
        if (mAudioMode == AudioMode.NONE) return

        val wantStereo = audioChannelCount == 2

        // Use stereo channel config if requested; each AudioRecord may fall back individually.
        val stereoConfig = AudioFormat.CHANNEL_IN_STEREO
        val monoConfig = AudioFormat.CHANNEL_IN_MONO
        val channelConfig = if (wantStereo) stereoConfig else monoConfig

        val minBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(
                TAG,
                "prepareAudioEncoder: mode=$mAudioMode sampleRate=$audioSampleRate " +
                    "channels=$audioChannelCount bufferSize=$bufferSize " +
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

                    val playbackConfig = PlaybackCaptureConfig.build(mediaProjection)

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

                    val audioFormat =
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(audioSampleRate)
                            .setChannelMask(channelConfig)
                            .build()

                    if (verboseAudioDiagnosticsEnabled) {
                        Log.d(
                            TAG,
                            "AudioFormat: encoding=PCM_16BIT sampleRate=$audioSampleRate " +
                                "channelMask=${if (wantStereo) "STEREO" else "MONO"} bufferSize=$bufferSize",
                        )
                    }

                    internalRecord =
                        AudioRecord
                            .Builder()
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufferSize)
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .build()

                    val state = internalRecord?.state
                    internalAudioHealth.noteAudioRecordCreated(
                        state = state ?: -1,
                        sampleRate = internalRecord?.sampleRate ?: audioSampleRate,
                        channelCount = internalRecord?.channelCount ?: audioChannelCount,
                        encoding = AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeBytes = bufferSize,
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
                    internalPlaybackBufferBytesConfigured = bufferSize
                    internalPlaybackChannelMaskConfigured = channelConfig
                } catch (e: Exception) {
                    Log.e(TAG, "${AUDIO_DIAG_MARKER} AudioRecord_CREATION_FAIL internal: ${e.javaClass.simpleName}: ${e.message}", e)
                    Log.e(TAG, "System audio init failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    AppLogger.e(TAG, "System audio init failed: ${e.message}")
                    AudioRecordingCrashlyticsReporter.noteCreationFailureInternal(e)
                    internalAudioHealth.noteAudioRecordCreated(
                        state = -1,
                        sampleRate = audioSampleRate,
                        channelCount = 0,
                        encoding = AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeBytes = bufferSize,
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
                    try {
                        internalRecord?.release()
                    } catch (_: Exception) {
                    }
                    internalRecord = null
                    internalPlaybackBufferBytesConfigured = 0
                    if (mAudioMode == AudioMode.INTERNAL) mAudioMode = AudioMode.MIC
                }
            } else if (mAudioMode == AudioMode.INTERNAL || mAudioMode == AudioMode.MIXED) {
                Log.w(
                    TAG,
                    "AudioPlaybackCaptureConfiguration requires API 29+; running on API ${Build.VERSION.SDK_INT} — internal audio unavailable",
                )
                logAnalyticsEvent(
                    "capture_denied_or_unsupported",
                    mapOf(
                        "reason" to "api_below_29",
                        "api" to Build.VERSION.SDK_INT.toString(),
                    ),
                )
                if (mAudioMode == AudioMode.INTERNAL) mAudioMode = AudioMode.MIC
            }

            if (mAudioMode == AudioMode.MIC || mAudioMode == AudioMode.MIXED) {
                // Try stereo mic first; if the device doesn't support it, fall back to mono.
                fun tryMicRecord(cfg: Int): AudioRecord? {
                    return try {
                        val recBufSize =
                            AudioRecord
                                .getMinBufferSize(audioSampleRate, cfg, AudioFormat.ENCODING_PCM_16BIT)
                                .coerceAtLeast(4096)
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            audioSampleRate,
                            cfg,
                            AudioFormat.ENCODING_PCM_16BIT,
                            recBufSize,
                        ).also { ar ->
                            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                                ar.release()
                                return null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "${AUDIO_DIAG_MARKER} AudioRecord_CREATION_FAIL mic: ${e.javaClass.simpleName}: ${e.message}", e)
                        AudioRecordingCrashlyticsReporter.noteCreationFailureMic(e)
                        null
                    }
                }
                micRecord =
                    if (wantStereo) {
                        tryMicRecord(stereoConfig) ?: tryMicRecord(monoConfig)
                    } else {
                        tryMicRecord(monoConfig)
                    }
                if (verboseAudioDiagnosticsEnabled) {
                    Log.d(TAG, "Mic AudioRecord: ${if (micRecord != null) "OK (ch=${micRecord!!.channelCount})" else "FAILED"}")
                }
                if (micRecord == null) {
                    Log.e(TAG, "Mic AudioRecord init failed")
                    AppLogger.e(TAG, "Mic AudioRecord init failed")
                    if (mAudioMode == AudioMode.MIC || mAudioMode == AudioMode.MIXED) {
                        AudioRecordingCrashlyticsReporter.noteCreationFailureMic(null, "mic_uninitialized_after_open")
                    }
                }
            }

            if (internalRecord == null && micRecord == null) {
                Log.e(TAG, "No audio sources available. Disabling audio.")
                AppLogger.e(TAG, "No audio sources available")
                mAudioMode = AudioMode.NONE
                mainMuxAudioMode = AudioMode.NONE
                return
            }

            mainMuxAudioMode = computeMainMuxAudioMode()
            Log.d(
                TAG,
                "Audio resolved: captureMode=$mAudioMode mainMux=$mainMuxAudioMode " +
                    "separateMicFile=$routeMicToSeparateFile " +
                    "internalRecord=${internalRecord != null} micRecord=${micRecord != null}",
            )

            if (mainMuxAudioMode == AudioMode.NONE) {
                // e.g. mic-only with separate file — no AAC track in the main MP4.
                return
            }

            val resolvedChannelCount =
                resolveMainMuxChannelCount(
                    mainMuxAudioMode,
                    wantStereo,
                    internalRecord,
                    micRecord,
                )
            effectiveChannelCount.set(resolvedChannelCount)
            configuredMainAacChannels = resolvedChannelCount
            if (verboseAudioDiagnosticsEnabled) {
                Log.d(TAG, "Main mux audio channel count requested=$audioChannelCount resolved=$resolvedChannelCount")
            }

            val aacProfile = resolveAacProfile()
            val format =
                MediaFormat
                    .createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        audioSampleRate,
                        resolvedChannelCount,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                        setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                        // Omit KEY_MAX_INPUT_SIZE: letting the codec pick its own buffer avoids
                        // the CCodec "requested max input size is in reasonable range" warning.
                        // Force Constant Bit Rate so the actual encoded rate matches what the user set.
                        setInteger(
                            MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        )
                    }
            if (verboseAudioDiagnosticsEnabled) {
                Log.d(
                    TAG,
                    "AAC encoder: profile=$aacProfile bitrate=$audioBitrate sampleRate=$audioSampleRate channels=$resolvedChannelCount",
                )
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(TAG, "Audio init error: ${e.javaClass.simpleName}: ${e.message}", e)
            AppLogger.e(TAG, "Audio init error: ${e.message}")
            AudioRecordingCrashlyticsReporter.noteCreationFailureMic(e, "prepare_audio_encoder_outer")
            mAudioMode = AudioMode.NONE
            mainMuxAudioMode = AudioMode.NONE
            micRecord?.release()
            micRecord = null
            internalRecord?.release()
            internalRecord = null
            audioEncoder?.release()
            audioEncoder = null
        }
    }

    /**
     * Opens a microphone [AudioRecord] using the same policy as [prepareAudioEncoder] (mono/stereo).
     * [RECORD_AUDIO] must already be granted. Returns null on failure.
     */
    @SuppressLint("MissingPermission")
    private fun openMicAudioRecordForSession(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val wantStereo = audioChannelCount == 2
        val stereoConfig = AudioFormat.CHANNEL_IN_STEREO
        val monoConfig = AudioFormat.CHANNEL_IN_MONO

        fun tryMicRecord(cfg: Int): AudioRecord? {
            return try {
                val recBufSize =
                    AudioRecord
                        .getMinBufferSize(audioSampleRate, cfg, AudioFormat.ENCODING_PCM_16BIT)
                        .coerceAtLeast(4096)
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    audioSampleRate,
                    cfg,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recBufSize,
                ).also { ar ->
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        ar.release()
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "${AUDIO_DIAG_MARKER} openMicAudioRecordForSession fail: ${e.message}", e)
                null
            }
        }
        return if (wantStereo) {
            tryMicRecord(stereoConfig) ?: tryMicRecord(monoConfig)
        } else {
            tryMicRecord(monoConfig)
        }
    }

    /**
     * Drops internal playback capture and continues with microphone PCM into the existing AAC encoder.
     * Only safe when channel layout matches [configuredMainAacChannels]. When separate mic is
     * enabled, the already-running mic leg can also be mirrored into the main video after fallback.
     * Must run on the audio capture thread.
     */
    @SuppressLint("MissingPermission")
    private fun performMicFallbackSwitch(): Boolean {
        if (micFallbackHotSwapApplied.get()) {
            Log.i(TAG, "${AUDIO_DIAG_MARKER} mic_fallback_skip already_applied=true")
            return true
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "${AUDIO_DIAG_MARKER} mic_fallback_hot_swap_blocked permission=denied")
            return false
        }

        val wantStereo = audioChannelCount == 2
        val reuseExistingMic = micRecord != null
        if (!reuseExistingMic) {
            val created =
                openMicAudioRecordForSession() ?: run {
                    Log.e(TAG, "${AUDIO_DIAG_MARKER} mic_fallback_hot_swap_blocked mic_open_failed")
                    return false
                }
            try {
                created.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "${AUDIO_DIAG_MARKER} mic_fallback new mic start failed", e)
                try {
                    created.release()
                } catch (_: Exception) {
                }
                return false
            }
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "${AUDIO_DIAG_MARKER} mic_fallback_hot_swap_blocked mic not recording after start")
                try {
                    created.release()
                } catch (_: Exception) {
                }
                return false
            }
            micRecord = created
        }

        val simulatedMainMux = AudioMode.MIC
        val resolvedAfter =
            resolveMainMuxChannelCount(
                simulatedMainMux,
                wantStereo,
                null,
                micRecord,
            )
        if (resolvedAfter != configuredMainAacChannels) {
            Log.e(
                TAG,
                "${AUDIO_DIAG_MARKER} mic_fallback_hot_swap_blocked aac_ch=$configuredMainAacChannels mic_ch=$resolvedAfter",
            )
            if (!reuseExistingMic) {
                micRecord?.let { m ->
                    try {
                        if (m.recordingState == AudioRecord.RECORDSTATE_RECORDING) m.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        m.release()
                    } catch (_: Exception) {
                    }
                }
                micRecord = null
            }
            return false
        }

        internalAudioHealth.markFallbackTriggered()
        internalRecord?.let { ir ->
            val monitorUntil = captureSessionStartElapsedMs + INTERNAL_MONITOR_AFTER_FALLBACK_MS
            val nowElapsed = SystemClock.elapsedRealtime()
            if (Build.VERSION.SDK_INT >= 29 &&
                nowElapsed < monitorUntil &&
                internalAudioHealth.firstAudiblePcmElapsedMs < 0L
            ) {
                internalMonitorRecord = ir
                internalMonitorUntilElapsedMs = monitorUntil
                Log.i(
                    TAG,
                    "${AUDIO_DIAG_MARKER} mic_fallback keeping internal monitor until=${monitorUntil - captureSessionStartElapsedMs}ms",
                )
            } else {
                try {
                    if (ir.recordingState == AudioRecord.RECORDSTATE_RECORDING) ir.stop()
                } catch (_: Exception) {
                }
                try {
                    ir.release()
                } catch (_: Exception) {
                }
            }
        }
        internalRecord = null

        mAudioMode = AudioMode.MIC
        micFallbackHotSwapApplied.set(true)
        mainMuxAudioMode = computeMainMuxAudioMode()
        effectiveChannelCount.set(resolvedAfter)
        Log.i(
            TAG,
            "${AUDIO_DIAG_MARKER} mic_fallback_hot_swap_applied capture=$mAudioMode mainMux=$mainMuxAudioMode effCh=$resolvedAfter",
        )
        return true
    }

    /** Called from main / service thread; audio loop performs the actual switch. */
    override fun requestMicFallbackFromSilentInternal() {
        pendingMicFallback.set(true)
        Log.i(TAG, "${AUDIO_DIAG_MARKER} mic_fallback_requested pending=true")
    }

    internal fun shouldSwitchMixedSeparateMicMainMuxEarly(
        routeMicToSeparateFile: Boolean,
        currentAudioMode: AudioMode,
        currentMainMuxAudioMode: AudioMode,
        internalHealth: InternalAudioHealthTracker.Snapshot,
        micStats: PcmSignalStats?,
        earlySwitchAlreadyAttempted: Boolean,
    ): Boolean =
        routeMicToSeparateFile &&
            currentAudioMode == AudioMode.MIXED &&
            currentMainMuxAudioMode == AudioMode.INTERNAL &&
            !earlySwitchAlreadyAttempted &&
            internalHealth.stage1SilentAt4s &&
            internalHealth.internalAudioRecordStarted &&
            internalHealth.positiveReadCount > 0L &&
            internalHealth.totalBytesRead > 0L &&
            internalHealth.firstAudiblePcmElapsedMs < 0L &&
            internalHealth.peakAbs == 0 &&
            internalHealth.rmsEstimate == 0 &&
            micStats?.audible == true

    private fun maybeSwitchMixedSeparateMicMainMuxEarly(
        silentElapsedMs: Long,
        micStats: PcmSignalStats?,
    ) {
        val health =
            internalAudioHealth.snapshot(
                muxerStarted = isMuxerStarted,
                muxerAudioSamplesWritten = audioSamplesWritten,
            )
        if (!shouldSwitchMixedSeparateMicMainMuxEarly(
                routeMicToSeparateFile = routeMicToSeparateFile,
                currentAudioMode = mAudioMode,
                currentMainMuxAudioMode = mainMuxAudioMode,
                internalHealth = health,
                micStats = micStats,
                earlySwitchAlreadyAttempted = earlySeparateMicMainMuxSwitchAttempted,
            )
        ) {
            return
        }

        earlySeparateMicMainMuxSwitchAttempted = true
        val switched = performMicFallbackSwitch()
        val crash = FirebaseCrashlytics.getInstance()
        crash.setCustomKey("arc_early_mix_sep_elapsed", silentElapsedMs.toString())
        crash.setCustomKey("arc_early_mix_sep_sw", switched.toString())
        crash.setCustomKey("arc_early_mix_sep_int", "bytes=${health.totalBytesRead},reads=${health.positiveReadCount},rms=${health.rmsEstimate},peak=${health.peakAbs}")
        crash.setCustomKey("arc_early_mix_sep_mic", "bytes=${micStats?.byteCount ?: 0},rms=${micStats?.rms ?: 0},peak=${micStats?.peakAbs ?: 0}")
        crash.log(
            "${AUDIO_DIAG_MARKER} mixed_separate_mic_early_main_mux_switch " +
                "elapsedMs=$silentElapsedMs switched=$switched " +
                "intBytes=${health.totalBytesRead} intReads=${health.positiveReadCount} intRms=${health.rmsEstimate} intPeak=${health.peakAbs} " +
                "micBytes=${micStats?.byteCount ?: 0} micRms=${micStats?.rms ?: 0} micPeak=${micStats?.peakAbs ?: 0}",
        )
        AudioRecordingCrashlyticsReporter.logInternalAudioStage(
            AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
            if (switched) "stage1_separate_mic_main_mux_switched" else "stage1_separate_mic_main_mux_switch_failed",
            internalAudioHealth.snapshot(
                muxerStarted = isMuxerStarted,
                muxerAudioSamplesWritten = audioSamplesWritten,
            ),
        )
        if (switched) {
            onInternalPlaybackSilenceSessionNotice?.invoke(
                InternalPlaybackSilenceSessionNotice.MIC_FALLBACK_APPLIED,
            )
        }
    }

    /**
     * Audio muxed into the main video file. When saving the mic to a separate file, the main file
     * normally gets internal/system audio only, except after internal silence fallback when the
     * existing mic leg is mirrored into the main video.
     */
    private fun computeMainMuxAudioMode(): AudioMode {
        if (!routeMicToSeparateFile) return mAudioMode
        return when (mAudioMode) {
            AudioMode.NONE -> AudioMode.NONE
            AudioMode.MIC ->
                if (micFallbackHotSwapApplied.get() && micRecord != null) {
                    AudioMode.MIC
                } else {
                    AudioMode.NONE
                }
            AudioMode.INTERNAL -> AudioMode.INTERNAL
            AudioMode.MIXED ->
                when {
                    internalRecord != null -> AudioMode.INTERNAL
                    micFallbackHotSwapApplied.get() && micRecord != null -> AudioMode.MIC
                    micRecord != null -> AudioMode.NONE
                    else -> AudioMode.NONE
                }
        }
    }

    private fun resolveMainMuxChannelCount(
        mainMux: AudioMode,
        wantStereo: Boolean,
        internal: AudioRecord?,
        mic: AudioRecord?,
    ): Int =
        when (mainMux) {
            AudioMode.INTERNAL -> {
                (internal?.channelCount ?: 1).coerceIn(1, 2)
            }
            AudioMode.MIC -> {
                (mic?.channelCount ?: 1).coerceIn(1, 2)
            }
            AudioMode.MIXED -> {
                // Align encoder channel layout with whichever sources actually feed the mix:
                // user stereo → use max actual channel count (handles mono fallback on one side);
                // user mono → single channel encode.
                if (wantStereo) {
                    maxOf(internal?.channelCount ?: 1, mic?.channelCount ?: 1).coerceIn(1, 2)
                } else {
                    1
                }
            }
            else -> {
                1
            }
        }

    @SuppressLint("MissingPermission")
    private fun prepareSeparateMicEncoder() {
        val pfd = separateMicFileDescriptor ?: return
        try {
            val micCh = (micRecord?.channelCount ?: if (audioChannelCount == 2) 2 else 1).coerceIn(1, 2)
            val chanCfg = if (micCh == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, chanCfg, AudioFormat.ENCODING_PCM_16BIT)
            // If mic is already captured in main mode, we reuse micRecord; otherwise create separate
            if (micRecord == null && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                micRecord =
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        audioSampleRate,
                        chanCfg,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize * 2,
                    )
                if (micRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    micRecord = null
                }
            }

            val aacProfile = resolveAacProfile()
            val chCount = (micRecord?.channelCount ?: micCh).coerceIn(1, 2)
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, chCount).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                    setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    )
                }
            separateMicEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            separateMicEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            separateMicMuxer = MediaMuxer(pfd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "Separate mic encoder init failed", e)
            separateMicEncoder?.release()
            separateMicEncoder = null
            separateMicMuxer?.release()
            separateMicMuxer = null
        }
    }

    private fun resolveAacProfile(): Int =
        when (audioEncoderType) {
            "AAC-HE" -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
            "AAC-HE v2" -> MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS
            "AAC-ELD" -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
            else -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
        }

    private fun prepareMuxer() {
        muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    // ── Audio Capture ──────────────────────────────────────────────────────────

    private fun captureAudioLoop() {
        // Scale buffer by channel count so each read covers the same time-window regardless of
        // mono/stereo, and the encoder always receives correctly-sized interleaved frames.
        val pcmChannels =
            maxOf(
                internalRecord?.channelCount ?: 0,
                micRecord?.channelCount ?: 0,
                1,
            ).coerceIn(1, 2)
        val bufferSize = 2048 * pcmChannels
        val mainBuffer = ByteArray(bufferSize)
        val mixBuffer = ByteArray(bufferSize)

        Log.d(
            TAG,
            "captureAudioLoop started: mode=$mAudioMode bufferSize=$bufferSize " +
                "internalRecord=${internalRecord != null} micRecord=${micRecord != null}",
        )

        // ── Silence / diagnostics state ───────────────────────────────────────
        var loopCount = 0L
        // First buffer diagnostics
        var firstInternalNonZeroLogged = false
        var firstAnyNonZeroLogged = false
        // Silence-timeout tracking for observed positive-but-silent internal reads.
        var internalSilentStartMs = -1L
        var silenceCallbackFired = false
        var recoveredAfterRebuildTelemetryReported = false
        var lateRecoveryTelemetryReported = false
        var readErrorAlreadyRecorded = false

        fun safeReadAudioRecord(
            record: AudioRecord,
            buffer: ByteArray,
            source: String,
        ): Int =
            try {
                record.read(buffer, 0, bufferSize)
            } catch (e: Exception) {
                val nr = audioReadNegativeErrorCount.incrementAndGet()
                readErrorAlreadyRecorded = true
                if (nr == 1L || nr % READ_ERROR_LOG_EVERY_N == 0L) {
                    Log.w(
                        TAG,
                        "${AUDIO_DIAG_MARKER} AUDIO_READ_EXCEPTION count=$nr source=$source loop=$loopCount mode=$mAudioMode",
                        e,
                    )
                }
                AudioRecord.ERROR_INVALID_OPERATION
            }

        while (isRecording.get()) {
            readErrorAlreadyRecorded = false
            if (isPaused.get()) {
                // Reset silence timer while paused so we don't spuriously timeout on resume.
                internalSilentStartMs = -1L
                try {
                    Thread.sleep(10)
                } catch (_: Exception) {
                }
                continue
            }

            if (pendingMicFallback.compareAndSet(true, false)) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "${AUDIO_DIAG_MARKER} mic_fallback_hot_swap_blocked permission=denied")
                    onInternalPlaybackSilenceSessionNotice?.invoke(
                        InternalPlaybackSilenceSessionNotice.MIC_FALLBACK_NEEDS_PERMISSION,
                    )
                } else {
                    val ok = performMicFallbackSwitch()
                    Log.i(
                        TAG,
                        "${AUDIO_DIAG_MARKER} mic_fallback_from_pending ok=$ok " +
                            "loop=$loopCount mode=$mAudioMode",
                    )
                    if (ok) {
                        onInternalPlaybackSilenceSessionNotice?.invoke(
                            InternalPlaybackSilenceSessionNotice.MIC_FALLBACK_APPLIED,
                        )
                    } else {
                        Log.w(TAG, "${AUDIO_DIAG_MARKER} mic_fallback_hot_swap_failed recording_continues_internal_silent")
                    }
                }
            }

            loopCount++
            var readCount = 0
            // Bytes actually read from internalRecord this iteration (before mix).
            var internalReadCount = 0
            var internalHadAudibleThisIteration = false
            var separateMicStatsThisIteration: PcmSignalStats? = null

            when {
                mAudioMode == AudioMode.MIXED && internalRecord != null && micRecord != null -> {
                    if (routeMicToSeparateFile) {
                        val r1 = safeReadAudioRecord(internalRecord!!, mainBuffer, "internal")
                        internalReadCount = r1
                        internalHadAudibleThisIteration =
                            internalAudioHealth.observeRead(mainBuffer, internalReadCount, SystemClock.elapsedRealtime())
                        if (r1 > 0 && audioEncoder != null) {
                            queueInputBufferToEncoder(mainBuffer, r1, System.nanoTime() / 1000)
                        }
                        val r2 = safeReadAudioRecord(micRecord!!, mixBuffer, "mic")
                        if (r2 > 0) {
                            separateMicStatsThisIteration = analyzePcm16Signal(mixBuffer, r2)
                            if (separateMicStatsThisIteration.audible) {
                                micLegEverAudibleThisSession = true
                            }
                        }
                        if (r2 > 0 && separateMicEncoder != null) {
                            if (isMuted.get()) mixBuffer.fill(0, 0, r2)
                            queueInputToEncoder(separateMicEncoder, mixBuffer, r2, System.nanoTime() / 1000, isSeparateMicPath = true)
                        }
                        readCount = if (r1 > 0) r1 else r2
                    } else {
                        val r1 = safeReadAudioRecord(internalRecord!!, mainBuffer, "internal")
                        internalReadCount = r1
                        internalHadAudibleThisIteration =
                            internalAudioHealth.observeRead(mainBuffer, internalReadCount, SystemClock.elapsedRealtime())
                        val r2 = safeReadAudioRecord(micRecord!!, mixBuffer, "mic")
                        if (r2 > 0) {
                            val micStats = analyzePcm16Signal(mixBuffer, r2)
                            if (micStats.audible) {
                                micLegEverAudibleThisSession = true
                            }
                        }
                        when {
                            r1 > 0 && r2 > 0 -> {
                                mixAudio(
                                    mainBuffer,
                                    mixBuffer,
                                    minOf(r1, r2),
                                    effectiveChannelCount.get(),
                                )
                                readCount = minOf(r1, r2)
                            }
                            r1 > 0 -> readCount = r1
                            r2 > 0 -> {
                                System.arraycopy(mixBuffer, 0, mainBuffer, 0, r2)
                                readCount = r2
                            }
                        }
                        if (readCount > 0) {
                            if (isMuted.get()) mainBuffer.fill(0, 0, readCount)
                            queueInputBufferToEncoder(mainBuffer, readCount, System.nanoTime() / 1000)
                        }
                    }
                }
                mAudioMode == AudioMode.MIXED && routeMicToSeparateFile && internalRecord == null && micRecord != null -> {
                    val r2 = safeReadAudioRecord(micRecord!!, mixBuffer, "mic")
                    readCount = r2
                    if (r2 > 0 && separateMicEncoder != null) {
                        if (isMuted.get()) mixBuffer.fill(0, 0, r2)
                        queueInputToEncoder(separateMicEncoder, mixBuffer, r2, System.nanoTime() / 1000, isSeparateMicPath = true)
                    }
                }
                internalRecord != null -> {
                    readCount = safeReadAudioRecord(internalRecord!!, mainBuffer, "internal")
                    internalReadCount = readCount
                    internalHadAudibleThisIteration =
                        internalAudioHealth.observeRead(mainBuffer, internalReadCount, SystemClock.elapsedRealtime())
                    if (readCount > 0) {
                        if (isMuted.get()) mainBuffer.fill(0, 0, readCount)
                        queueInputBufferToEncoder(mainBuffer, readCount, System.nanoTime() / 1000)
                    }
                }
                micRecord != null -> {
                    readCount = safeReadAudioRecord(micRecord!!, mainBuffer, "mic")
                    if (readCount > 0) {
                        if (isMuted.get()) mainBuffer.fill(0, 0, readCount)
                        val ptsUs = System.nanoTime() / 1000
                        if (mainMuxAudioMode != AudioMode.NONE) {
                            queueInputBufferToEncoder(mainBuffer, readCount, ptsUs)
                        }
                        if (routeMicToSeparateFile && separateMicEncoder != null) {
                            queueInputToEncoder(separateMicEncoder, mainBuffer, readCount, ptsUs, isSeparateMicPath = true)
                        }
                    }
                }
            }

            // ── Diagnostic: log first non-zero buffers ────────────────────────
            if (verboseAudioDiagnosticsEnabled && !firstAnyNonZeroLogged && readCount > 0 &&
                mainBuffer.asSequence().take(readCount).any { it != 0.toByte() }
            ) {
                firstAnyNonZeroLogged = true
                Log.d(TAG, "First non-zero audio buffer from any source at loop=$loopCount")
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

            // ── Internal PCM diagnostics + silence handling ─────────────────────
            // Counters MUST run for the whole session (not gated on silenceCallbackFired) so
            // The verbose intPCM summary correctly reflects "any non-zero ever", even if
            // internal playback starts silent and becomes audible later.
            if (internalRecord != null) {
                val internalAudible = internalHadAudibleThisIteration

                if (internalReadCount > 0 && internalPlaybackPcmCountersActive) {
                    internalPlaybackPcmReadsPositive.incrementAndGet()
                    if (internalAudible) {
                        internalPlaybackPcmNonZeroBuffers.incrementAndGet()
                        if (internalPlaybackPcmFirstNonZeroLoop.compareAndSet(-1L, loopCount)) {
                            internalPlaybackPcmFirstNonZeroElapsedMs = SystemClock.elapsedRealtime()
                            if (!firstInternalNonZeroLogged) {
                                firstInternalNonZeroLogged = true
                                val delayMs =
                                    if (captureSessionStartElapsedMs > 0L) {
                                        internalPlaybackPcmFirstNonZeroElapsedMs - captureSessionStartElapsedMs
                                    } else {
                                        -1L
                                    }
                                if (verboseAudioDiagnosticsEnabled) {
                                    Log.d(
                                        TAG,
                                        "First audible internal audio at loop=$loopCount delayMs=$delayMs " +
                                            "(post-recreate=${internalPlaybackRecreateSucceeded.get()})",
                                    )
                                }
                                if (internalPlaybackSilenceDiagFired.get() &&
                                    internalPlaybackRecoveredAfterSilenceLogged.compareAndSet(false, true)
                                ) {
                                    Log.i(
                                        TAG,
                                        "${AUDIO_DIAG_MARKER} internal playback recovered after initial silence delayMs=$delayMs mode=$mAudioMode",
                                    )
                                }
                            }
                        }
                    } else {
                        internalPlaybackPcmSilentOnlyBuffers.incrementAndGet()
                    }
                }

                // Silence detection / staged recovery (skipped while muted).
                if (isMuted.get()) {
                    internalSilentStartMs = -1L
                } else {
                    val now = SystemClock.elapsedRealtime()
                    val internalStillSilent =
                        internalReadCount > 0 &&
                            internalAudioHealth.firstAudiblePcmElapsedMs < 0L

                    fun completeInternalSilenceTimeout() {
                        if (silenceCallbackFired) return
                        silenceCallbackFired = true
                        val silentMs = now - internalSilentStartMs
                        internalAudioHealth.markPersistentSilent()
                        val health =
                            internalAudioHealth.snapshot(
                                muxerStarted = isMuxerStarted,
                                muxerAudioSamplesWritten = audioSamplesWritten,
                            )
                        val posReads = internalPlaybackPcmReadsPositive.get()
                        Log.w(
                            TAG,
                            "${AUDIO_DIAG_MARKER} internal playback remained silent for ${silentMs}ms; recording continues mode=$mAudioMode",
                        )
                        if (verboseAudioDiagnosticsEnabled) {
                            val sBuf = internalPlaybackPcmSilentOnlyBuffers.get()
                            val nsBuf = internalPlaybackPcmNonZeroBuffers.get()
                            Log.d(
                                TAG,
                                "${AUDIO_DIAG_MARKER} internal_playback_silence_verbose " +
                                    "internalReadBytes=$internalReadCount posReads=$posReads silentBufs=$sBuf nonSilentBufs=$nsBuf " +
                                    "mixedMicAudible=$micLegEverAudibleThisSession brand=${Build.BRAND} model=${Build.MODEL} API=${Build.VERSION.SDK_INT}",
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
                                "recording_type" to "normal",
                            ),
                            releaseSafe = true,
                        )
                        internalPlaybackSilenceDiagFired.set(true)

                        val suppressedMixedMic =
                            mAudioMode == AudioMode.MIXED &&
                                micLegEverAudibleThisSession &&
                                !routeMicToSeparateFile
                        if (suppressedMixedMic) {
                            if (verboseAudioDiagnosticsEnabled) {
                                Log.d(
                                    TAG,
                                    "${AUDIO_DIAG_MARKER} internal playback silent in mixed mode; mic leg has audio",
                                )
                            }
                            return
                        }
                        val canReuseRequestedMicForMain =
                            routeMicToSeparateFile && micRecord != null
                        if (autoMicFallbackWhenInternalSilent || canReuseRequestedMicForMain) {
                            Log.i(
                                TAG,
                                "${AUDIO_DIAG_MARKER} microphone fallback requested after internal silence " +
                                    "auto=$autoMicFallbackWhenInternalSilent reuseSeparateMic=$canReuseRequestedMicForMain",
                            )
                            val fallbackApplied = performMicFallbackSwitch()
                            if (fallbackApplied) {
                                AudioRecordingCrashlyticsReporter.logInternalAudioStage(
                                    AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                                    "persistent_fallback_applied",
                                    internalAudioHealth.snapshot(
                                        muxerStarted = isMuxerStarted,
                                        muxerAudioSamplesWritten = audioSamplesWritten,
                                    ),
                                )
                                onInternalPlaybackSilenceSessionNotice?.invoke(
                                    InternalPlaybackSilenceSessionNotice.MIC_FALLBACK_APPLIED,
                                )
                                return
                            }
                            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                onInternalPlaybackSilenceSessionNotice?.invoke(
                                    InternalPlaybackSilenceSessionNotice.MIC_FALLBACK_NEEDS_PERMISSION,
                                )
                            }
                        }
                        AudioRecordingCrashlyticsReporter.reportInternalAudioPersistentSilence(
                            AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                            health,
                        )
                        if (verboseAudioDiagnosticsEnabled) {
                            Log.d(TAG, "${AUDIO_DIAG_MARKER} notifying user that internal audio is unavailable")
                        }
                        onInternalPlaybackSilenceSessionNotice?.invoke(
                            InternalPlaybackSilenceSessionNotice.NO_INTERNAL_AUDIO_CONTINUE_SILENT,
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
                                    muxerStarted = isMuxerStarted,
                                    muxerAudioSamplesWritten = audioSamplesWritten,
                                )
                            if (shouldReportRecoveredAfterRebuild) {
                                recoveredAfterRebuildTelemetryReported = true
                                AudioRecordingCrashlyticsReporter.reportInternalAudioRecoveredAfterRebuild(
                                    AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                                    health,
                                )
                            }
                            if (shouldReportLateRecovery) {
                                lateRecoveryTelemetryReported = true
                                internalPlaybackRecoveredAfterSilenceLogged.compareAndSet(false, true)
                                AudioRecordingCrashlyticsReporter.reportInternalAudioLateRecovery(
                                    AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
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
                                AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                                "stage1_4s",
                                internalAudioHealth.snapshot(
                                    muxerStarted = isMuxerStarted,
                                    muxerAudioSamplesWritten = audioSamplesWritten,
                                ),
                            )
                            val rebuildSucceeded =
                                attemptInternalAudioRecordRecreation(reason = "stage1_4s_silence")
                            if (!rebuildSucceeded) {
                                maybeSwitchMixedSeparateMicMainMuxEarly(
                                    silentElapsedMs = silentElapsedMs,
                                    micStats = separateMicStatsThisIteration,
                                )
                            }
                        } else if (!internalAudioHealth.stage2SilentAt10s && silentElapsedMs >= INTERNAL_SILENCE_STAGE2_MS) {
                            internalAudioHealth.markStage2Silent()
                            AudioRecordingCrashlyticsReporter.logInternalAudioStage(
                                AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                                "stage2_10s",
                                internalAudioHealth.snapshot(
                                    muxerStarted = isMuxerStarted,
                                    muxerAudioSamplesWritten = audioSamplesWritten,
                                ),
                            )
                            attemptInternalAudioRecordRecreation(reason = "stage2_10s_silence")
                        } else if (!silenceCallbackFired && silentElapsedMs >= INTERNAL_SILENCE_PERSISTENT_MS) {
                            completeInternalSilenceTimeout()
                        }
                    } else {
                        internalSilentStartMs = -1L
                    }
                }
            }

            internalMonitorRecord?.let { monitor ->
                val now = SystemClock.elapsedRealtime()
                if (now <= internalMonitorUntilElapsedMs && internalAudioHealth.firstAudiblePcmElapsedMs < 0L) {
                    val monitorRead = monitor.read(mixBuffer, 0, bufferSize, AudioRecord.READ_NON_BLOCKING)
                    val monitorAudible = internalAudioHealth.observeRead(mixBuffer, monitorRead, now)
                    if (monitorAudible &&
                        internalPlaybackRecoveredAfterSilenceLogged.compareAndSet(false, true)
                    ) {
                        val health =
                            internalAudioHealth.snapshot(
                                muxerStarted = isMuxerStarted,
                                muxerAudioSamplesWritten = audioSamplesWritten,
                            )
                        Log.i(
                            TAG,
                            "${AUDIO_DIAG_MARKER} internal playback recovered in post-fallback monitor " +
                                "firstAudibleMs=${health.firstAudiblePcmElapsedMs}",
                        )
                        lateRecoveryTelemetryReported = true
                        AudioRecordingCrashlyticsReporter.reportInternalAudioLateRecovery(
                            AudioRecordingCrashlyticsReporter.RecordingKind.FULL,
                            health,
                        )
                    }
                } else {
                    try {
                        if (monitor.recordingState == AudioRecord.RECORDSTATE_RECORDING) monitor.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        monitor.release()
                    } catch (_: Exception) {
                    }
                    internalMonitorRecord = null
                    internalMonitorUntilElapsedMs = 0L
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
                        "internalRead=$internalReadCount mode=$mAudioMode " +
                        "silenceCallbackFired=$silenceCallbackFired " +
                        "intPosReads=${internalPlaybackPcmReadsPositive.get()} " +
                        "intSilentBufs=${internalPlaybackPcmSilentOnlyBuffers.get()} " +
                        "intNonSilentBufs=${internalPlaybackPcmNonZeroBuffers.get()} " +
                        "internalNearSilenceAccumMs=$silentMs",
                )
            }

            if (readCount < 0 && !readErrorAlreadyRecorded) {
                val nr = audioReadNegativeErrorCount.incrementAndGet()
                if (nr == 1L || nr % READ_ERROR_LOG_EVERY_N == 0L) {
                    Log.w(
                        TAG,
                        "${AUDIO_DIAG_MARKER} AUDIO_READ_ERROR count=$nr code=$readCount loop=$loopCount mode=$mAudioMode",
                    )
                }
                try {
                    Thread.sleep(5)
                } catch (_: Exception) {
                }
            }
        }

        if (verboseAudioDiagnosticsEnabled) {
            Log.d(TAG, "captureAudioLoop exited after $loopCount iterations")
        }
    }

    /** Mixes interleaved PCM16; [channelCount] 1 = mono (2 bytes/frame), 2 = stereo (4 bytes/frame). */
    private fun mixAudio(
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

    private fun analyzePcm16Signal(
        buffer: ByteArray,
        byteLen: Int,
    ): PcmSignalStats {
        if (byteLen <= 0) return PcmSignalStats(byteLen.coerceAtLeast(0), 0, 0, false)
        val n = minOf(byteLen, buffer.size)
        val frameBytes = n - (n % 2)
        if (frameBytes <= 0) return PcmSignalStats(n, 0, 0, false)

        var peak = 0
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < frameBytes) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (lo or (hi shl 8)).toShort().toInt()
            val absSample = abs(sample)
            if (absSample > peak) peak = absSample
            val sampleDouble = sample.toDouble()
            sumSquares += sampleDouble * sampleDouble
            samples++
            i += 2
        }

        val rms = if (samples > 0) sqrt(sumSquares / samples).toInt() else 0
        val audible =
            peak >= InternalPlaybackPcmSilenceAnalyzer.NEAR_SILENCE_PEAK_ABS ||
                rms >= InternalPlaybackPcmSilenceAnalyzer.NEAR_SILENCE_RMS
        return PcmSignalStats(n, peak, rms, audible)
    }

    private fun queueInputBufferToEncoder(
        input: ByteArray,
        length: Int,
        pts: Long,
    ) {
        queueInputToEncoder(audioEncoder, input, length, pts)
    }

    private fun queueInputToEncoder(
        encoder: MediaCodec?,
        input: ByteArray,
        length: Int,
        pts: Long,
        isSeparateMicPath: Boolean = false,
    ) {
        val enc = encoder ?: return
        if (audioCaptureStopping.get()) return
        try {
            val index = enc.dequeueInputBuffer(0)
            if (index >= 0) {
                enc.getInputBuffer(index)?.apply {
                    clear()
                    put(input, 0, length)
                }
                enc.queueInputBuffer(index, 0, length, pts, 0)
                return
            }
            val counter = if (isSeparateMicPath) pcmDropSeparateMicInputCount else pcmDropMainAacInputCount
            val dropN = counter.incrementAndGet()
            if (verboseAudioDiagnosticsEnabled && (dropN == 1L || dropN % PCM_DROP_LOG_EVERY_N == 0L)) {
                Log.d(
                    TAG,
                    "${AUDIO_DIAG_MARKER} AAC_INPUT_BACKPRESSURE drops=$dropN separate_mic=$isSeparateMicPath dequeue=try_again/no_buffer",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Queue audio input failed", e)
        }
    }

    // ── Drain Loops ────────────────────────────────────────────────────────────

    private fun drainVideoLoop() {
        while (isRecording.get()) {
            drainEncoder(videoEncoder, isVideo = true)
            try {
                Thread.sleep(VIDEO_DRAIN_SLEEP_MS)
            } catch (_: Exception) {
            }
        }
        // Drain until the encoder signals EOS or until 3 s have elapsed, whichever comes first.
        // Using a timed loop instead of a fixed repeat() count ensures the muxer is only
        // stopped after all buffered frames have been written — critical on Samsung where the
        // OS can revoke the projection mid-flight (single-app capture mode).
        val deadline = System.currentTimeMillis() + 3_000L
        while (!videoEosReached.get() && System.currentTimeMillis() < deadline) {
            drainEncoder(videoEncoder, isVideo = true)
            try {
                Thread.sleep(VIDEO_DRAIN_SLEEP_MS)
            } catch (_: Exception) {
            }
        }
    }

    private fun drainAudioLoop() {
        while (isRecording.get()) {
            drainEncoder(audioEncoder, isVideo = false)
            try {
                Thread.sleep(4)
            } catch (_: Exception) {
            }
        }
        waitForAudioInputEosQueued(
            queuedFlag = audioInputEosQueued,
            failedFlag = audioInputEosSignalFailed,
            label = "main",
        ) {
            drainEncoder(audioEncoder, isVideo = false)
        }
        val deadline = SystemClock.elapsedRealtime() + AUDIO_EOS_DRAIN_TIMEOUT_MS
        while (!audioEosReached.get() && SystemClock.elapsedRealtime() < deadline) {
            drainEncoder(audioEncoder, isVideo = false)
            try {
                Thread.sleep(4)
            } catch (_: Exception) {
            }
        }
        if (audioEncoder != null && mainMuxAudioMode != AudioMode.NONE && !audioEosReached.get()) {
            Log.w(
                TAG,
                "Audio drain timeout after ${AUDIO_EOS_DRAIN_TIMEOUT_MS}ms " +
                    "inputEosQueued=${audioInputEosQueued.get()} inputEosSignalFailed=${audioInputEosSignalFailed.get()}",
            )
        }
    }

    private fun drainSeparateMicLoop() {
        while (isRecording.get()) {
            drainSeparateMicEncoder()
            try {
                Thread.sleep(4)
            } catch (_: Exception) {
            }
        }
        waitForAudioInputEosQueued(
            queuedFlag = separateMicInputEosQueued,
            failedFlag = separateMicInputEosSignalFailed,
            label = "separate_mic",
        ) {
            drainSeparateMicEncoder()
        }
        val deadline = SystemClock.elapsedRealtime() + AUDIO_EOS_DRAIN_TIMEOUT_MS
        while (!separateMicEosReached.get() && SystemClock.elapsedRealtime() < deadline) {
            drainSeparateMicEncoder()
            try {
                Thread.sleep(4)
            } catch (_: Exception) {
            }
        }
        if (separateMicEncoder != null && !separateMicEosReached.get()) {
            Log.w(
                TAG,
                "Separate mic drain timeout after ${AUDIO_EOS_DRAIN_TIMEOUT_MS}ms " +
                    "inputEosQueued=${separateMicInputEosQueued.get()} inputEosSignalFailed=${separateMicInputEosSignalFailed.get()}",
            )
        }
    }

    private fun waitForAudioInputEosQueued(
        queuedFlag: AtomicBoolean,
        failedFlag: AtomicBoolean,
        label: String,
        drainOnce: () -> Unit,
    ) {
        val deadline = SystemClock.elapsedRealtime() + AUDIO_EOS_QUEUE_TIMEOUT_MS + 1_000L
        while (!queuedFlag.get() && !failedFlag.get() && SystemClock.elapsedRealtime() < deadline) {
            drainOnce()
            try {
                Thread.sleep(4)
            } catch (_: Exception) {
            }
        }
        if (!queuedFlag.get()) {
            Log.w(
                TAG,
                "Audio drain proceeding without $label input EOS queued " +
                    "failed=${failedFlag.get()} waitedMs=${AUDIO_EOS_QUEUE_TIMEOUT_MS + 1_000L}",
            )
        }
    }

    private fun reportFatalVideoEncodeIfNeeded(
        e: Exception,
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
            "VideoEncFatal mime=$configuredVideoMime encoder=$encoderType singleShot detail=$detail",
            e,
        )
        FirebaseCrashlytics.getInstance().log("VideoEncFatal record $detail mime=$configuredVideoMime")
        onFatalVideoEncodeError?.invoke(detail)
    }

    private fun drainEncoder(
        encoder: MediaCodec?,
        isVideo: Boolean,
    ) {
        if (encoder == null) return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            try {
                val status = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> break

                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            // Never touch the muxer once teardown has claimed it, even if the
                            // codec fires a late format-changed event on its output thread.
                            val activeMuxer = muxer
                            if (!isMuxerStarted && !muxerReleased && activeMuxer != null) {
                                val newFormat = encoder.outputFormat
                                val index = activeMuxer.addTrack(newFormat)
                                if (isVideo) videoTrackIndex = index else audioTrackIndex = index

                                val videoReady = videoTrackIndex >= 0
                                val audioReady = if (mainMuxAudioMode != AudioMode.NONE) audioTrackIndex >= 0 else true

                                // Contract: muxer.start() is called ONLY after every track we
                                // plan to mux has been added via addTrack(). After start(),
                                // addTrack() is no longer permitted by MediaMuxer.
                                if (videoReady && audioReady) {
                                    activeMuxer.start()
                                    isMuxerStarted = true
                                    Log.d(
                                        TAG,
                                        "Muxer started: videoTrack=$videoTrackIndex audioTrack=$audioTrackIndex " +
                                            "mainMuxAudioMode=$mainMuxAudioMode",
                                    )
                                }
                            }
                        }
                    }

                    status >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(status)
                        if (outputBuffer == null) {
                            encoder.releaseOutputBuffer(status, false)
                            continue
                        }
                        val encodedData: ByteBuffer = outputBuffer

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0) {
                            var wroteVideoSample = false
                            synchronized(muxerLock) {
                                val activeMuxer = muxer
                                if (isMuxerStarted && !muxerReleased && activeMuxer != null && !isPaused.get()) {
                                    bufferInfo.presentationTimeUs -= totalPausedTimeUs.get()
                                    val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                                    if (trackIndex >= 0) {
                                        encodedData.position(bufferInfo.offset)
                                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                        activeMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                        if (isVideo) {
                                            videoSamplesWritten = true
                                            wroteVideoSample = true
                                        } else {
                                            audioSamplesWritten = true
                                        }
                                    }
                                }
                            }
                            if (wroteVideoSample) {
                                logFirstVideoSampleAfterPerformanceResizeIfNeeded(encoder, bufferInfo)
                            }
                        }

                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (isVideo) {
                                if (videoEosReached.compareAndSet(false, true)) {
                                    Log.i(TAG, "Observed video output EOS")
                                }
                            } else if (audioEosReached.compareAndSet(false, true)) {
                                Log.i(TAG, "Observed main audio output EOS")
                            }
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Drain error (video=$isVideo): ${e.message}", e)
                if (isVideo) {
                    reportFatalVideoEncodeIfNeeded(e, phase = "dequeue_or_output")
                }
                break
            }
        }
    }

    private fun drainSeparateMicEncoder() {
        val encoder = separateMicEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            try {
                val status = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> break

                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(separateMicLock) {
                            val activeMuxer = separateMicMuxer
                            if (!isSeparateMicMuxerStarted && !separateMicMuxerReleased && activeMuxer != null) {
                                val newFormat = encoder.outputFormat
                                separateMicTrackIndex = activeMuxer.addTrack(newFormat)
                                if (separateMicTrackIndex >= 0) {
                                    activeMuxer.start()
                                    isSeparateMicMuxerStarted = true
                                }
                            }
                        }
                    }

                    status >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(status)
                        if (outputBuffer == null) {
                            encoder.releaseOutputBuffer(status, false)
                            continue
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0

                        if (bufferInfo.size != 0) {
                            synchronized(separateMicLock) {
                                val activeMuxer = separateMicMuxer
                                if (isSeparateMicMuxerStarted && !separateMicMuxerReleased && activeMuxer != null && !isPaused.get()) {
                                    if (separateMicTrackIndex >= 0) {
                                        outputBuffer.position(bufferInfo.offset)
                                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                        activeMuxer.writeSampleData(separateMicTrackIndex, outputBuffer, bufferInfo)
                                        separateMicSamplesWritten = true
                                    }
                                }
                            }
                        }

                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (separateMicEosReached.compareAndSet(false, true)) {
                                Log.i(TAG, "Observed separate mic output EOS")
                            }
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Separate mic drain error: ${e.message}")
                break
            }
        }
    }
}

/**
 * Thin wrapper around [VirtualDisplay.resize] so JVM tests can verify Performance-engine resize
 * behavior without constructing framework display resources.
 */
internal fun interface PerformanceVirtualDisplayResizeStrategy {
    /** Applies a new logical size to the active Performance-engine [VirtualDisplay]. */
    fun resize(
        virtualDisplay: VirtualDisplay,
        newWidth: Int,
        newHeight: Int,
        densityDpi: Int,
    )
}

/**
 * Applies the same capture-size policy used at session start to a Performance-engine resize event.
 */
internal fun alignedPerformanceResizeSize(
    newW: Int,
    newH: Int,
    encoderType: String,
    fps: Int,
): RecordingResolutionSize =
    RecordingResolutionSupport.getEncoderCaptureResolution(
        requestedSize = RecordingResolutionSize(newW, newH),
        videoEncoder = encoderType,
        fps = fps,
    )
