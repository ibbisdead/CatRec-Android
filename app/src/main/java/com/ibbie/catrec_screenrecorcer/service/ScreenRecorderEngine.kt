package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
    private val separateMicFileDescriptor: FileDescriptor? = null
) {
    enum class AudioMode { NONE, MIC, INTERNAL, MIXED }

    private var mAudioMode = audioMode

    // Actual channel count resolved after AudioRecord creation (may differ from audioChannelCount
    // if the device silently falls back to mono for playback capture).
    private val effectiveChannelCount = AtomicInteger(audioChannelCount.coerceIn(1, 2))

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private val muxerLock = Object()

    // Separate mic muxer/encoder
    private var separateMicEncoder: MediaCodec? = null
    private var separateMicMuxer: MediaMuxer? = null
    private var separateMicTrackIndex = -1
    private var isSeparateMicMuxerStarted = false
    private val separateMicLock = Object()

    private val isRecording = AtomicBoolean(false)

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

    fun start() {
        mAudioMode = audioMode

        try {
            prepareVideoEncoder()
            prepareAudioEncoder()
            if (separateMicFileDescriptor != null) prepareSeparateMicEncoder()
            prepareMuxer()

            videoEncoder?.start()
            if (mAudioMode != AudioMode.NONE) {
                audioEncoder?.start()
                queueSilentAudioFrame()
            }
            separateMicEncoder?.start()

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "CatRecEngine",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            isRecording.set(true)

            videoDrainThread = Thread { drainVideoLoop() }.apply { name = "CatRec-VideoDrain" }
            videoDrainThread?.start()

            if (mAudioMode != AudioMode.NONE) {
                try {
                    micRecord?.startRecording()
                    internalRecord?.startRecording()
                    audioThread = Thread { captureAudioLoop() }.apply { name = "CatRec-AudioCapture" }
                    audioThread?.start()
                    audioDrainThread = Thread { drainAudioLoop() }.apply { name = "CatRec-AudioDrain" }
                    audioDrainThread?.start()
                } catch (e: Exception) {
                    Log.e("RecorderEngine", "Failed to start AudioRecord", e)
                }
            }

            if (separateMicEncoder != null) {
                separateMicDrainThread = Thread { drainSeparateMicLoop() }.apply { name = "CatRec-SeparateMicDrain" }
                separateMicDrainThread?.start()
            }
        } catch (e: Exception) {
            Log.e("RecorderEngine", "Start failed", e)
            stop()
            throw e
        }
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        Log.d("RecorderEngine", "Stopping recorder…")

        try { micRecord?.stop() } catch (_: Exception) {}
        try { internalRecord?.stop() } catch (_: Exception) {}

        try { audioThread?.join(1500) } catch (_: Exception) {}

        try { videoEncoder?.signalEndOfInputStream() } catch (e: Exception) {
            Log.w("RecorderEngine", "signalEndOfInputStream failed", e)
        }
        signalAudioEOS()
        signalSeparateMicEOS()

        try { audioDrainThread?.join(2000) } catch (_: Exception) {}
        try { videoDrainThread?.join(2000) } catch (_: Exception) {}
        try { separateMicDrainThread?.join(2000) } catch (_: Exception) {}

        virtualDisplay?.release()
        virtualDisplay = null

        synchronized(muxerLock) {
            try {
                if (isMuxerStarted) muxer?.stop()
            } catch (e: Exception) {
                Log.e("RecorderEngine", "Muxer stop failed: ${e.message}")
            } finally {
                try { muxer?.release() } catch (_: Exception) {}
                isMuxerStarted = false
                muxer = null
            }
        }

        synchronized(separateMicLock) {
            try {
                if (isSeparateMicMuxerStarted) separateMicMuxer?.stop()
            } catch (e: Exception) {
                Log.e("RecorderEngine", "Separate mic muxer stop failed: ${e.message}")
            } finally {
                try { separateMicMuxer?.release() } catch (_: Exception) {}
                isSeparateMicMuxerStarted = false
                separateMicMuxer = null
            }
        }

        try { videoEncoder?.release() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        try { separateMicEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null
        audioEncoder = null
        separateMicEncoder = null

        try { micRecord?.release() } catch (_: Exception) {}
        try { internalRecord?.release() } catch (_: Exception) {}
        micRecord = null
        internalRecord = null

        Log.d("RecorderEngine", "Recorder stopped cleanly")
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

    fun mute() { isMuted.set(true) }
    fun unmute() { isMuted.set(false) }

    private fun requestSyncFrame() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            videoEncoder?.setParameters(params)
        } catch (e: Exception) {
            Log.e("RecorderEngine", "Failed to request sync frame", e)
        }
    }

    private fun signalAudioEOS() {
        val encoder = audioEncoder ?: return
        try {
            val index = encoder.dequeueInputBuffer(5000L)
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: Exception) {
            Log.w("RecorderEngine", "Audio EOS signal failed", e)
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
            Log.w("RecorderEngine", "Separate mic EOS signal failed", e)
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
            Log.w("RecorderEngine", "Silent frame queue failed", e)
        }
    }

    private fun prepareVideoEncoder() {
        val isEmulator = Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
                Build.MODEL.contains("google_sdk", ignoreCase = true)

        val mimeType = if (encoderType == "H.265 (HEVC)" && !isEmulator)
            MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        // ── Encoder selection ──────────────────────────────────────────────────
        // CRITICAL: do NOT include KEY_BIT_RATE in the discovery format.
        // Hardware encoders advertise much lower maximum bitrates than what users may
        // request (e.g. 100 Mbps). Including the requested bitrate causes
        // findEncoderForFormat() to return null, triggering a silent fallback to the
        // Google software encoder (OMX.google.h264.encoder) which produces terrible
        // quality regardless of the requested bitrate.
        val mediaCodecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)

        // Scan explicitly for a hardware encoder: prefer non-Google/non-software codecs.
        val hardwareEncoderName = mediaCodecList.codecInfos
            .filter { info ->
                info.isEncoder &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                !info.name.contains("google", ignoreCase = true) &&
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else true
            }
            .firstOrNull()?.name

        // Fallback: let the system pick via the discovery format (without bitrate).
        val fallbackName = if (hardwareEncoderName == null) {
            val discoveryFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            mediaCodecList.findEncoderForFormat(discoveryFormat)
        } else null

        val encoderName = hardwareEncoderName ?: fallbackName
        Log.d("RecorderEngine", "Video encoder selected: $encoderName (hardware=$hardwareEncoderName)")

        // ── Configuration format ───────────────────────────────────────────────
        // Build with profile; if the encoder rejects it, retry without profile keys.
        fun buildConfigFormat(withProfile: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // VBR: the encoder may burst above the target bitrate in complex scenes,
                // which avoids artefacts. CBR would force quality-damaging bit-stuffing
                // in simple scenes and bit-starvation in complex ones.
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                // On API 31+, explicitly permit the encoder to reach the full bitrate.
                // Use the raw key string to avoid a compile-time dependency on API 31+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setInteger("max-bitrate", bitrate)
                }
                if (withProfile) {
                    // High Profile (H.264) / Main Profile (HEVC) = better compression
                    // efficiency and fewer artefacts vs Baseline/Main defaults.
                    if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                        setInteger(MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setInteger(MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    }
                }
            }

        videoEncoder = try {
            if (encoderName != null) MediaCodec.createByCodecName(encoderName)
            else MediaCodec.createEncoderByType(mimeType)
        } catch (e: Exception) {
            Log.w("RecorderEngine", "Named encoder create failed, using type fallback: ${e.message}")
            MediaCodec.createEncoderByType(mimeType)
        }

        // Try configure with profile; some encoders reject profile hints → fall back cleanly.
        try {
            videoEncoder?.configure(buildConfigFormat(withProfile = true), null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w("RecorderEngine", "Profile configure failed, retrying without: ${e.message}")
            try {
                videoEncoder?.configure(buildConfigFormat(withProfile = false), null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e2: Exception) {
                Log.e("RecorderEngine", "Video encoder configure failed entirely", e2)
                throw e2
            }
        }

        inputSurface = videoEncoder?.createInputSurface()
    }

    @SuppressLint("MissingPermission")
    private fun prepareAudioEncoder() {
        if (mAudioMode == AudioMode.NONE) return

        val wantStereo = audioChannelCount == 2

        // Use stereo channel config if requested; each AudioRecord may fall back individually.
        val stereoConfig = AudioFormat.CHANNEL_IN_STEREO
        val monoConfig   = AudioFormat.CHANNEL_IN_MONO
        val channelConfig = if (wantStereo) stereoConfig else monoConfig

        val minBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) throw SecurityException("RECORD_AUDIO permission denied")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (mAudioMode == AudioMode.INTERNAL || mAudioMode == AudioMode.MIXED)
            ) {
                try {
                    val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audioSampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                    internalRecord = AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(playbackConfig)
                        .build()
                    if (internalRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("Internal AudioRecord not initialized")
                    }
                } catch (e: Exception) {
                    Log.e("RecorderEngine", "System audio init failed: ${e.message}")
                    internalRecord = null
                    if (mAudioMode == AudioMode.INTERNAL) mAudioMode = AudioMode.MIC
                }
            }

            if (mAudioMode == AudioMode.MIC || mAudioMode == AudioMode.MIXED) {
                // Try stereo mic first; if the device doesn't support it, fall back to mono.
                fun tryMicRecord(cfg: Int): AudioRecord? {
                    return try {
                        val recBufSize = AudioRecord.getMinBufferSize(audioSampleRate, cfg, AudioFormat.ENCODING_PCM_16BIT)
                            .coerceAtLeast(4096)
                        AudioRecord(MediaRecorder.AudioSource.MIC, audioSampleRate, cfg,
                            AudioFormat.ENCODING_PCM_16BIT, recBufSize).also { ar ->
                            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                                ar.release(); return null
                            }
                        }
                    } catch (_: Exception) { null }
                }
                micRecord = if (wantStereo) tryMicRecord(stereoConfig) ?: tryMicRecord(monoConfig)
                            else             tryMicRecord(monoConfig)
                if (micRecord == null) Log.e("RecorderEngine", "Mic AudioRecord init failed")
            }

            if (internalRecord == null && micRecord == null) {
                Log.e("RecorderEngine", "No audio sources available. Disabling audio.")
                mAudioMode = AudioMode.NONE
                return
            }

            // Resolve the actual channel count from whichever AudioRecord succeeded.
            val resolvedChannelCount = if (wantStereo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val internalCh = internalRecord?.channelCount ?: 0
                val micCh      = micRecord?.channelCount      ?: 0
                // Use the highest channel count available; if both are 1, stick to mono.
                maxOf(internalCh, micCh).coerceIn(1, 2)
            } else if (wantStereo) {
                // API < 24: inspect channel mask instead
                val cfg = micRecord?.channelConfiguration ?: internalRecord?.channelConfiguration
                if (cfg == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            } else 1

            effectiveChannelCount.set(resolvedChannelCount)
            Log.d("RecorderEngine", "Audio channel count requested=$audioChannelCount resolved=$resolvedChannelCount")

            val aacProfile = resolveAacProfile()
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, resolvedChannelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                // Force Constant Bit Rate so the actual encoded rate matches what the user set.
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        } catch (e: Exception) {
            Log.e("RecorderEngine", "Audio init error: ${e.message}")
            mAudioMode = AudioMode.NONE
            micRecord?.release(); micRecord = null
            internalRecord?.release(); internalRecord = null
            audioEncoder?.release(); audioEncoder = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun prepareSeparateMicEncoder() {
        val pfd = separateMicFileDescriptor ?: return
        try {
            val chanCfg = if (effectiveChannelCount.get() == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, chanCfg, AudioFormat.ENCODING_PCM_16BIT)
            // If mic is already captured in main mode, we reuse micRecord; otherwise create separate
            if (micRecord == null && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                micRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    audioSampleRate, chanCfg,
                    AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2
                )
                if (micRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    micRecord = null
                }
            }

            val aacProfile = resolveAacProfile()
            val chCount = effectiveChannelCount.get()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, chCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            separateMicEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            separateMicEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                separateMicMuxer = MediaMuxer(pfd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
        } catch (e: Exception) {
            Log.e("RecorderEngine", "Separate mic encoder init failed", e)
            separateMicEncoder?.release(); separateMicEncoder = null
            separateMicMuxer?.release(); separateMicMuxer = null
        }
    }

    private fun resolveAacProfile(): Int {
        return when (audioEncoderType) {
            "AAC-HE" -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
            "AAC-HE v2" -> MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS
            "AAC-ELD" -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
            else -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
        }
    }

    private fun prepareMuxer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    // ── Audio Capture ──────────────────────────────────────────────────────────

    private fun captureAudioLoop() {
        // Scale buffer by channel count so each read covers the same time-window regardless of
        // mono/stereo, and the encoder always receives correctly-sized interleaved frames.
        val bufferSize = 2048 * effectiveChannelCount.get()
        val mainBuffer = ByteArray(bufferSize)
        val mixBuffer = ByteArray(bufferSize)

        while (isRecording.get()) {
            if (isPaused.get()) {
                try { Thread.sleep(10) } catch (_: Exception) {}
                continue
            }

            var readCount = 0

            when {
                mAudioMode == AudioMode.MIXED && internalRecord != null && micRecord != null -> {
                    val r1 = internalRecord!!.read(mainBuffer, 0, bufferSize)
                    val r2 = micRecord!!.read(mixBuffer, 0, bufferSize)
                    when {
                        r1 > 0 && r2 > 0 -> { mixAudio(mainBuffer, mixBuffer, minOf(r1, r2)); readCount = minOf(r1, r2) }
                        r1 > 0 -> readCount = r1
                        r2 > 0 -> { System.arraycopy(mixBuffer, 0, mainBuffer, 0, r2); readCount = r2 }
                    }
                    // Also feed mic to separate encoder if configured
                    if (separateMicEncoder != null && r2 > 0) {
                        queueInputToEncoder(separateMicEncoder, mixBuffer, r2, System.nanoTime() / 1000)
                    }
                }
                internalRecord != null -> readCount = internalRecord!!.read(mainBuffer, 0, bufferSize)
                micRecord != null -> {
                    readCount = micRecord!!.read(mainBuffer, 0, bufferSize)
                    // Feed mic to separate encoder if configured
                    if (separateMicEncoder != null && readCount > 0) {
                        queueInputToEncoder(separateMicEncoder, mainBuffer, readCount, System.nanoTime() / 1000)
                    }
                }
            }

            if (readCount > 0) {
                if (isMuted.get()) mainBuffer.fill(0, 0, readCount)
                queueInputBufferToEncoder(mainBuffer, readCount, System.nanoTime() / 1000)
            } else if (readCount < 0) {
                Log.e("RecorderEngine", "Audio read error: $readCount")
                try { Thread.sleep(5) } catch (_: Exception) {}
            }
        }
    }

    private fun mixAudio(base: ByteArray, overlay: ByteArray, size: Int) {
        for (i in 0 until size step 2) {
            val s1 = ((base[i].toInt() and 0xFF) or (base[i + 1].toInt() shl 8)).toShort()
            val s2 = ((overlay[i].toInt() and 0xFF) or (overlay[i + 1].toInt() shl 8)).toShort()
            val mixed = (s1 + s2).coerceIn(-32768, 32767).toShort().toInt()
            base[i] = (mixed and 0xFF).toByte()
            base[i + 1] = ((mixed shr 8) and 0xFF).toByte()
        }
    }

    private fun queueInputBufferToEncoder(input: ByteArray, length: Int, pts: Long) {
        queueInputToEncoder(audioEncoder, input, length, pts)
    }

    private fun queueInputToEncoder(encoder: MediaCodec?, input: ByteArray, length: Int, pts: Long) {
        val enc = encoder ?: return
        try {
            val index = enc.dequeueInputBuffer(0)
            if (index >= 0) {
                enc.getInputBuffer(index)?.apply { clear(); put(input, 0, length) }
                enc.queueInputBuffer(index, 0, length, pts, 0)
            }
        } catch (e: Exception) {
            Log.e("RecorderEngine", "Queue audio input failed", e)
        }
    }

    // ── Drain Loops ────────────────────────────────────────────────────────────

    private fun drainVideoLoop() {
        while (isRecording.get()) {
            drainEncoder(videoEncoder, isVideo = true)
            try { Thread.sleep(4) } catch (_: Exception) {}
        }
        repeat(50) {
            drainEncoder(videoEncoder, isVideo = true)
            try { Thread.sleep(4) } catch (_: Exception) {}
        }
    }

    private fun drainAudioLoop() {
        while (isRecording.get()) {
            drainEncoder(audioEncoder, isVideo = false)
            try { Thread.sleep(4) } catch (_: Exception) {}
        }
        repeat(50) {
            drainEncoder(audioEncoder, isVideo = false)
            try { Thread.sleep(4) } catch (_: Exception) {}
        }
    }

    private fun drainSeparateMicLoop() {
        while (isRecording.get()) {
            drainSeparateMicEncoder()
            try { Thread.sleep(4) } catch (_: Exception) {}
        }
        repeat(50) {
            drainSeparateMicEncoder()
            try { Thread.sleep(4) } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(encoder: MediaCodec?, isVideo: Boolean) {
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
                                val audioReady = if (mAudioMode != AudioMode.NONE) audioTrackIndex >= 0 else true

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
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            } catch (e: Exception) {
                Log.e("RecorderEngine", "Drain error (video=$isVideo): ${e.message}")
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
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            } catch (e: Exception) {
                Log.e("RecorderEngine", "Separate mic drain error: ${e.message}")
                break
            }
        }
    }
}
