package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.utils.AppLogger
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val audioBitrate: Int = 128_000,
    private val audioSampleRate: Int = 48_000,
    private val audioChannelCount: Int = 1,
    private val audioEncoderType: String = "AAC-LC",
    private val separateMicFileDescriptor: FileDescriptor? = null,
    /** Invoked on the audio-capture thread when internal audio stays silent for SILENCE_TIMEOUT_MS. */
    private val onInternalAudioSilence: (() -> Unit)? = null,
    /** Invoked at most once when the video encoder drain path hits a fatal error (encoder thread). */
    private val onFatalVideoEncodeError: ((String) -> Unit)? = null,
    /** When true with user HEVC, first [prepareVideoEncoder] uses AVC only (adaptive tier 4 hint). */
    private val adaptivePreferAvcForPrepare: Boolean = false,
) {
    enum class AudioMode { NONE, MIC, INTERNAL, MIXED }

    companion object {
        private const val TAG = "RecorderEngine"

        /** Milliseconds of all-zero PCM from internal capture before we fire the fallback. */
        private const val SILENCE_TIMEOUT_MS = 5_000L

        /** Video drain pacing — lower than audio to pull encoded frames sooner (less encoder backpressure). */
        private const val VIDEO_DRAIN_SLEEP_MS = 2L
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

    /** MIME from [VideoEncoderConfigurator]; used for HEVC→AVC recovery on [MediaCodec.start] failure. */
    private var configuredVideoMime: String = ""
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var frameRelay: EncoderFrameRelay? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private val muxerLock = object {}

    // Separate mic muxer/encoder
    private var separateMicEncoder: MediaCodec? = null
    private var separateMicMuxer: MediaMuxer? = null
    private var separateMicTrackIndex = -1
    private var isSeparateMicMuxerStarted = false
    private val separateMicLock = object {}

    private val isRecording = AtomicBoolean(false)

    // Set to true by drain loops once the encoder signals end-of-stream.
    // Used to gate muxer.stop() so it is never called while draining is still in progress.
    private val videoEosReached = AtomicBoolean(false)
    private val audioEosReached = AtomicBoolean(false)
    private val separateMicEosReached = AtomicBoolean(false)

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null

    private var audioThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var videoDrainThread: Thread? = null
    private var separateMicDrainThread: Thread? = null

    private val isPaused = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val totalPausedTimeUs = AtomicLong(0)
    private var pauseStartTimeUs = 0L

    /** Ensures [onFatalVideoEncodeError] runs at most once for this engine instance. */
    private val videoEncodeFatalSignaled = AtomicBoolean(false)

    fun start() {
        mAudioMode = audioMode

        try {
            prepareVideoEncoder()
            prepareAudioEncoder()
            if (separateMicFileDescriptor != null) prepareSeparateMicEncoder()
            prepareMuxer()

            // Start encoders before any frame hits the input Surface. Feeding the encoder surface
            // while the codec is still Configured breaks some OEM stacks (CodecException in start()
            // or native_start) on newer Android — e.g. Nothing Phone + API 36.
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
                        "Video encoder start failed on HEVC (${e.javaClass.simpleName}: ${e.message}); " +
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
                    prepareVideoEncoder(avcOnly = true)
                    Log.i(TAG, "Retrying video encoder start with AVC after HEVC start failure")
                    videoEncoder?.start()
                } else {
                    throw e
                }
            }
            if (mainMuxAudioMode != AudioMode.NONE) {
                audioEncoder?.start()
                queueSilentAudioFrame()
            }
            separateMicEncoder?.start()

            // Single VirtualDisplay → ImageReader → canvas/GLES → encoder surface (screenshots share this path).
            frameRelay =
                EncoderFrameRelay(
                    mediaProjection,
                    inputSurface!!,
                    width,
                    height,
                    dpi,
                    "CatRecEngine",
                ).also { it.start() }

            isRecording.set(true)

            videoDrainThread = Thread { drainVideoLoop() }.apply { name = "CatRec-VideoDrain" }
            videoDrainThread?.start()

            if (mAudioMode != AudioMode.NONE) {
                try {
                    micRecord?.startRecording()
                    internalRecord?.startRecording()
                    audioThread = Thread { captureAudioLoop() }.apply { name = "CatRec-AudioCapture" }
                    audioThread?.start()
                    if (mainMuxAudioMode != AudioMode.NONE) {
                        audioDrainThread = Thread { drainAudioLoop() }.apply { name = "CatRec-AudioDrain" }
                        audioDrainThread?.start()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start AudioRecord", e)
                }
            }

            if (separateMicEncoder != null) {
                separateMicDrainThread = Thread { drainSeparateMicLoop() }.apply { name = "CatRec-SeparateMicDrain" }
                separateMicDrainThread?.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            stop()
            throw e
        }
    }

    fun stop() {
        val wasRecording = isRecording.getAndSet(false)
        val hasOpenResources =
            frameRelay != null ||
                videoEncoder != null ||
                audioEncoder != null ||
                separateMicEncoder != null ||
                muxer != null ||
                separateMicMuxer != null ||
                micRecord != null ||
                internalRecord != null ||
                audioThread != null ||
                videoDrainThread != null ||
                audioDrainThread != null ||
                separateMicDrainThread != null
        // [start] can throw before [isRecording] is set; we must still release muxer/codec/VD.
        if (!wasRecording && !hasOpenResources) return
        Log.d(TAG, "Stopping recorder…")

        try {
            frameRelay?.stop()
        } catch (_: Exception) {
        }
        frameRelay = null

        try {
            micRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            internalRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioThread?.join(3000)
        } catch (_: Exception) {
        }

        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
        }
        signalAudioEOS()
        signalSeparateMicEOS()

        try {
            audioDrainThread?.join(6000)
        } catch (_: Exception) {
        }
        try {
            videoDrainThread?.join(6000)
        } catch (_: Exception) {
        }
        try {
            separateMicDrainThread?.join(6000)
        } catch (_: Exception) {
        }

        synchronized(muxerLock) {
            try {
                muxer?.takeIf { isMuxerStarted }?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Muxer stop failed: ${e.message}")
            } finally {
                try {
                    muxer?.release()
                } catch (_: Exception) {
                }
                isMuxerStarted = false
                muxer = null
            }
        }

        synchronized(separateMicLock) {
            try {
                separateMicMuxer?.takeIf { isSeparateMicMuxerStarted }?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Separate mic muxer stop failed: ${e.message}")
            } finally {
                try {
                    separateMicMuxer?.release()
                } catch (_: Exception) {
                }
                isSeparateMicMuxerStarted = false
                separateMicMuxer = null
            }
        }

        try {
            videoEncoder?.release()
        } catch (_: Exception) {
        }
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        try {
            separateMicEncoder?.release()
        } catch (_: Exception) {
        }
        videoEncoder = null
        audioEncoder = null
        separateMicEncoder = null

        try {
            micRecord?.release()
        } catch (_: Exception) {
        }
        try {
            internalRecord?.release()
        } catch (_: Exception) {
        }
        micRecord = null
        internalRecord = null

        Log.d(TAG, "Recorder stopped cleanly")
    }

    /** Returns true if the muxer was successfully started, meaning at least one video track
     *  was written. A false result means the output file is empty and should be discarded. */
    fun hadOutput(): Boolean {
        synchronized(muxerLock) { return isMuxerStarted }
    }

    /**
     * Grabs the next composited frame after [requestScreenshot] is called (same pipeline as video).
     * Callback may run on the frame-relay thread; post to main if needed for UI.
     */
    fun requestScreenshot(onBitmap: (Bitmap?) -> Unit) {
        val relay = frameRelay
        if (!isRecording.get() || relay == null) {
            onBitmap(null)
            return
        }
        relay.requestScreenshot(onBitmap)
    }

    fun pause() {
        if (!isPaused.getAndSet(true)) {
            pauseStartTimeUs = System.nanoTime() / 1000
        }
    }

    fun resume() {
        if (isPaused.getAndSet(false)) {
            val now = System.nanoTime() / 1000
            totalPausedTimeUs.addAndGet(now - pauseStartTimeUs)
            requestSyncFrame()
        }
    }

    fun mute() {
        isMuted.set(true)
    }

    fun unmute() {
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
    ) {
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
        frameRelay?.adaptiveSkipModulo = modulo
    }

    fun attachAdaptivePerformance(
        sink: AdaptiveRecordingSignalSink?,
        signalsEnabled: Boolean,
    ) {
        frameRelay?.adaptiveSignalSink = sink
        frameRelay?.adaptiveSignalsEnabled = signalsEnabled
    }

    private fun signalAudioEOS() {
        val encoder = audioEncoder ?: return
        try {
            val index = encoder.dequeueInputBuffer(5000L)
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio EOS signal failed", e)
        }
    }

    private fun signalSeparateMicEOS() {
        val encoder = separateMicEncoder ?: return
        try {
            val index = encoder.dequeueInputBuffer(5000L)
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Separate mic EOS signal failed", e)
        }
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
            )
        videoEncoder = result.codec
        inputSurface = result.inputSurface
        configuredVideoMime = result.mime
        Log.d(TAG, "Video encoder configured mime=$configuredVideoMime avcOnly=$avcOnly")
    }

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

        Log.d(
            TAG,
            "prepareAudioEncoder: mode=$mAudioMode sampleRate=$audioSampleRate " +
                "channels=$audioChannelCount bufferSize=$bufferSize " +
                "API=${Build.VERSION.SDK_INT} brand=${Build.BRAND} model=${Build.MODEL}",
        )

        try {
            val audioPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                )
            val permGranted = audioPermission == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "RECORD_AUDIO permission: ${if (permGranted) "GRANTED" else "DENIED"}")
            if (!permGranted) throw SecurityException("RECORD_AUDIO permission denied")

            if (Build.VERSION.SDK_INT >= 29 &&
                (mAudioMode == AudioMode.INTERNAL || mAudioMode == AudioMode.MIXED)
            ) {
                try {
                    // Log every field of the capture config so we can see exactly what is
                    // presented to the audio server — crucial for OEM capture-policy debugging.
                    Log.d(
                        TAG,
                        "Building AudioPlaybackCaptureConfiguration: " +
                            "matchingUsages=[USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN] " +
                            "projection=$mediaProjection",
                    )

                    val playbackConfig =
                        AudioPlaybackCaptureConfiguration
                            .Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build()

                    Log.d(TAG, "AudioPlaybackCaptureConfiguration built OK")
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

                    Log.d(
                        TAG,
                        "AudioFormat: encoding=PCM_16BIT sampleRate=$audioSampleRate " +
                            "channelMask=${if (wantStereo) "STEREO" else "MONO"} bufferSize=$bufferSize",
                    )

                    internalRecord =
                        AudioRecord
                            .Builder()
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufferSize)
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .build()

                    val state = internalRecord?.state
                    Log.d(
                        TAG,
                        "Internal AudioRecord state=$state " +
                            "(INITIALIZED=${AudioRecord.STATE_INITIALIZED}) " +
                            "channelCount=${internalRecord?.channelCount} " +
                            "sampleRate=${internalRecord?.sampleRate}",
                    )

                    if (state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("Internal AudioRecord not initialized (state=$state)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "System audio init failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    AppLogger.e(TAG, "System audio init failed: ${e.message}")
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
                    } catch (_: Exception) {
                        null
                    }
                }
                micRecord =
                    if (wantStereo) {
                        tryMicRecord(stereoConfig) ?: tryMicRecord(monoConfig)
                    } else {
                        tryMicRecord(monoConfig)
                    }
                Log.d(TAG, "Mic AudioRecord: ${if (micRecord != null) "OK (ch=${micRecord!!.channelCount})" else "FAILED"}")
                if (micRecord == null) {
                    Log.e(TAG, "Mic AudioRecord init failed")
                    AppLogger.e(TAG, "Mic AudioRecord init failed")
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
            Log.d(TAG, "Main mux audio channel count requested=$audioChannelCount resolved=$resolvedChannelCount")

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
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                        // Force Constant Bit Rate so the actual encoded rate matches what the user set.
                        setInteger(
                            MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        )
                    }
            Log.d(TAG, "AAC encoder: profile=$aacProfile bitrate=$audioBitrate sampleRate=$audioSampleRate channels=$resolvedChannelCount")
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(TAG, "Audio init error: ${e.javaClass.simpleName}: ${e.message}", e)
            AppLogger.e(TAG, "Audio init error: ${e.message}")
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
     * Audio muxed into the main video file. When saving the mic to a separate file, the main file
     * gets internal/system audio only (MIXED → INTERNAL), or no main audio track (MIC-only).
     */
    private fun computeMainMuxAudioMode(): AudioMode {
        if (!routeMicToSeparateFile) return mAudioMode
        return when (mAudioMode) {
            AudioMode.NONE -> AudioMode.NONE
            AudioMode.MIC -> AudioMode.NONE
            AudioMode.INTERNAL -> AudioMode.INTERNAL
            AudioMode.MIXED ->
                when {
                    internalRecord != null -> AudioMode.INTERNAL
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
                if (wantStereo && true) {
                    maxOf(internal?.channelCount ?: 0, mic?.channelCount ?: 0).coerceIn(1, 2)
                } else if (wantStereo) {
                    val cfg = mic?.channelConfiguration ?: internal?.channelConfiguration
                    if (cfg == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
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
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
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
        // Silence-timeout tracking for internal audio (only while internalRecord is active)
        var internalSilentStartMs = System.currentTimeMillis()
        var silenceCallbackFired = false

        while (isRecording.get()) {
            if (isPaused.get()) {
                // Reset silence timer while paused so we don't spuriously timeout on resume.
                internalSilentStartMs = System.currentTimeMillis()
                try {
                    Thread.sleep(10)
                } catch (_: Exception) {
                }
                continue
            }

            loopCount++
            var readCount = 0
            // Bytes actually read from internalRecord this iteration (before mix).
            var internalReadCount = 0

            when {
                mAudioMode == AudioMode.MIXED && internalRecord != null && micRecord != null -> {
                    if (routeMicToSeparateFile) {
                        val r1 = internalRecord!!.read(mainBuffer, 0, bufferSize)
                        internalReadCount = r1
                        if (r1 > 0 && audioEncoder != null) {
                            queueInputBufferToEncoder(mainBuffer, r1, System.nanoTime() / 1000)
                        }
                        val r2 = micRecord!!.read(mixBuffer, 0, bufferSize)
                        if (r2 > 0 && separateMicEncoder != null) {
                            if (isMuted.get()) mixBuffer.fill(0, 0, r2)
                            queueInputToEncoder(separateMicEncoder, mixBuffer, r2, System.nanoTime() / 1000)
                        }
                        readCount = if (r1 > 0) r1 else r2
                    } else {
                        val r1 = internalRecord!!.read(mainBuffer, 0, bufferSize)
                        internalReadCount = r1
                        val r2 = micRecord!!.read(mixBuffer, 0, bufferSize)
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
                    val r2 = micRecord!!.read(mixBuffer, 0, bufferSize)
                    readCount = r2
                    if (r2 > 0 && separateMicEncoder != null) {
                        if (isMuted.get()) mixBuffer.fill(0, 0, r2)
                        queueInputToEncoder(separateMicEncoder, mixBuffer, r2, System.nanoTime() / 1000)
                    }
                }
                internalRecord != null -> {
                    readCount = internalRecord!!.read(mainBuffer, 0, bufferSize)
                    internalReadCount = readCount
                    if (readCount > 0) {
                        if (isMuted.get()) mainBuffer.fill(0, 0, readCount)
                        queueInputBufferToEncoder(mainBuffer, readCount, System.nanoTime() / 1000)
                    }
                }
                micRecord != null -> {
                    readCount = micRecord!!.read(mainBuffer, 0, bufferSize)
                    if (readCount > 0) {
                        if (isMuted.get()) mainBuffer.fill(0, 0, readCount)
                        if (routeMicToSeparateFile && separateMicEncoder != null) {
                            queueInputToEncoder(separateMicEncoder, mainBuffer, readCount, System.nanoTime() / 1000)
                        } else {
                            queueInputBufferToEncoder(mainBuffer, readCount, System.nanoTime() / 1000)
                        }
                    }
                }
            }

            // ── Diagnostic: log first non-zero buffers ────────────────────────
            if (!firstAnyNonZeroLogged && readCount > 0 &&
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

            // ── Silence timeout for internal audio only ────────────────────────
            // We only run this when internalRecord is active and the callback hasn't fired yet.
            // The check is skipped while muted so a muted recording doesn't trigger the toast.
            if (internalRecord != null && !silenceCallbackFired && !isMuted.get()) {
                val now = System.currentTimeMillis()
                if (internalReadCount > 0 &&
                    mainBuffer.asSequence().take(internalReadCount).any { it != 0.toByte() }
                ) {
                    // Got real audio — reset timer.
                    if (!firstInternalNonZeroLogged) {
                        firstInternalNonZeroLogged = true
                        Log.d(TAG, "First non-zero internal audio at loop=$loopCount")
                    }
                    internalSilentStartMs = now
                } else if (now - internalSilentStartMs >= SILENCE_TIMEOUT_MS) {
                    silenceCallbackFired = true
                    val silentMs = now - internalSilentStartMs
                    Log.w(
                        TAG,
                        "Internal audio silent for ${silentMs}ms — " +
                            "brand=${Build.BRAND} model=${Build.MODEL} API=${Build.VERSION.SDK_INT}. " +
                            "Likely capture policy block or OEM restriction.",
                    )
                    AppLogger.w(
                        TAG,
                        "Internal audio silence timeout after ${silentMs}ms " +
                            "(${Build.BRAND} ${Build.MODEL} API ${Build.VERSION.SDK_INT})",
                    )
                    logAnalyticsEvent(
                        "silent_timeout",
                        mapOf(
                            "silent_ms" to silentMs.toString(),
                            "api" to Build.VERSION.SDK_INT.toString(),
                            "brand" to Build.BRAND,
                            "model" to Build.MODEL,
                            "mode" to mAudioMode.name,
                        ),
                    )
                    onInternalAudioSilence?.invoke()
                }
            }

            // ── Periodic read-count log (every 500 loops ≈ every ~5 s at 48 kHz/2048) ───
            if (loopCount % 500L == 0L) {
                Log.d(
                    TAG,
                    "captureAudioLoop: loop=$loopCount readCount=$readCount " +
                        "internalRead=$internalReadCount mode=$mAudioMode " +
                        "silenceCallbackFired=$silenceCallbackFired",
                )
            }

            if (readCount < 0) {
                Log.e(TAG, "Audio read error code=$readCount at loop=$loopCount")
                try {
                    Thread.sleep(5)
                } catch (_: Exception) {
                }
            }
        }

        Log.d(TAG, "captureAudioLoop exited after $loopCount iterations")
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
    ) {
        val enc = encoder ?: return
        try {
            val index = enc.dequeueInputBuffer(0)
            if (index >= 0) {
                enc.getInputBuffer(index)?.apply {
                    clear()
                    put(input, 0, length)
                }
                enc.queueInputBuffer(index, 0, length, pts, 0)
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
        val deadline = System.currentTimeMillis() + 3_000L
        while (!audioEosReached.get() && System.currentTimeMillis() < deadline) {
            drainEncoder(audioEncoder, isVideo = false)
            try {
                Thread.sleep(4)
            } catch (_: Exception) {
            }
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
        val deadline = System.currentTimeMillis() + 3_000L
        while (!separateMicEosReached.get() && System.currentTimeMillis() < deadline) {
            drainSeparateMicEncoder()
            try {
                Thread.sleep(4)
            } catch (_: Exception) {
            }
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
                            if (!isMuxerStarted) {
                                val newFormat = encoder.outputFormat
                                val index = muxer?.addTrack(newFormat) ?: -1
                                if (isVideo) videoTrackIndex = index else audioTrackIndex = index

                                val videoReady = videoTrackIndex >= 0
                                val audioReady = if (mainMuxAudioMode != AudioMode.NONE) audioTrackIndex >= 0 else true

                                if (videoReady && audioReady) {
                                    muxer?.start()
                                    isMuxerStarted = true
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
                            synchronized(muxerLock) {
                                if (isMuxerStarted && !isPaused.get()) {
                                    bufferInfo.presentationTimeUs -= totalPausedTimeUs.get()
                                    val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                                    if (trackIndex >= 0) {
                                        encodedData.position(bufferInfo.offset)
                                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                        muxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                                    }
                                }
                            }
                        }

                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (isVideo) videoEosReached.set(true) else audioEosReached.set(true)
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
                            if (!isSeparateMicMuxerStarted) {
                                val newFormat = encoder.outputFormat
                                separateMicTrackIndex = separateMicMuxer?.addTrack(newFormat) ?: -1
                                if (separateMicTrackIndex >= 0) {
                                    separateMicMuxer?.start()
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
                                if (isSeparateMicMuxerStarted && !isPaused.get()) {
                                    if (separateMicTrackIndex >= 0) {
                                        outputBuffer.position(bufferInfo.offset)
                                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                        separateMicMuxer?.writeSampleData(separateMicTrackIndex, outputBuffer, bufferInfo)
                                    }
                                }
                            }
                        }

                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            separateMicEosReached.set(true)
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
