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
 * • The segment deque keeps at most MAX_SEGMENTS files.  Older files are deleted.
 * • saveClip() snapshots the current deque + the active partial segment and merges them.
 */
class RollingBufferEngine(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val audioMode: AudioMode,
    private val mediaProjection: MediaProjection,
    private val encoderType: String,
    private val audioBitrate: Int = 128_000,
    private val audioSampleRate: Int = 48_000,
    private val audioChannelCount: Int = 1,
    private val audioEncoderType: String = "AAC-LC"
) {
    enum class AudioMode { NONE, MIC, INTERNAL, MIXED }

    companion object {
        private const val TAG = "RollingBufferEngine"
        const val SEGMENT_DURATION_MS = 10_000L
        const val MAX_SEGMENTS = 6          // 6 × 10 s = 60 s rolling window
    }

    // ── Encoder objects ────────────────────────────────────────────────────────
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mAudioMode = audioMode

    // ── Muxer state (guarded by muxerLock) ────────────────────────────────────
    private val muxerLock = Object()
    private var currentMuxer: MediaMuxer? = null
    private var currentSegFile: File? = null  // file currently being written
    private var muxerVideoTrack = -1
    private var muxerAudioTrack = -1
    private var isMuxerReady = false
    private var storedVideoFmt: MediaFormat? = null
    private var storedAudioFmt: MediaFormat? = null
    // Timestamp base for the current segment (encoder time when the segment started)
    private var segStartPtsUs = AtomicLong(-1L)

    // ── Completed segment queue ────────────────────────────────────────────────
    private val segments = ArrayDeque<File>()   // oldest-first; guarded by muxerLock
    private val segmentDir = File(context.cacheDir, "rolling_segments").also { it.mkdirs() }

    // ── Control flags ──────────────────────────────────────────────────────────
    private val isRunning      = AtomicBoolean(false)
    private val pendingRotate  = AtomicBoolean(false)

    // ── Audio ──────────────────────────────────────────────────────────────────
    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private val effectiveChannelCount = AtomicInteger(audioChannelCount.coerceIn(1, 2))
    private val isMuted = AtomicBoolean(false)

    // ── Threads ────────────────────────────────────────────────────────────────
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var rotationScheduler: ScheduledExecutorService? = null

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    fun start() {
        clearSegmentDir()
        prepareVideoEncoder()
        prepareAudioEncoder()
        openNewSegment()          // prepare the first muxer before we start encoding

        videoEncoder?.start()
        if (mAudioMode != AudioMode.NONE) audioEncoder?.start()

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CatRecBuffer", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        isRunning.set(true)

        videoThread = Thread({ drainVideoLoop() }, "CatRec-Buffer-Video").also { it.start() }

        if (mAudioMode != AudioMode.NONE) {
            try { micRecord?.startRecording() } catch (_: Exception) {}
            try { internalRecord?.startRecording() } catch (_: Exception) {}
            audioThread = Thread({ captureAudioLoop() }, "CatRec-Buffer-AudioCap").also { it.start() }
            audioDrainThread = Thread({ drainAudioLoop() }, "CatRec-Buffer-AudioDrain").also { it.start() }
        }

        // Schedule I-frame + rotation every SEGMENT_DURATION_MS
        rotationScheduler = Executors.newSingleThreadScheduledExecutor()
        rotationScheduler?.scheduleAtFixedRate(
            { requestRotation() },
            SEGMENT_DURATION_MS, SEGMENT_DURATION_MS, TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "Stopping buffer engine…")

        rotationScheduler?.shutdown()
        rotationScheduler = null

        try { micRecord?.stop() } catch (_: Exception) {}
        try { internalRecord?.stop() } catch (_: Exception) {}
        audioThread?.join(2000)

        try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        signalAudioEOS()

        videoThread?.join(2000)
        audioDrainThread?.join(2000)

        virtualDisplay?.release()
        virtualDisplay = null

        synchronized(muxerLock) {
            finalizeMuxer()
        }

        videoEncoder?.release(); videoEncoder = null
        audioEncoder?.release(); audioEncoder = null
        micRecord?.release();    micRecord = null
        internalRecord?.release(); internalRecord = null
        Log.d(TAG, "Buffer engine stopped.")
    }

    fun mute()   { isMuted.set(true) }
    fun unmute() { isMuted.set(false) }

    /**
     * Returns a snapshot of all completed segments plus the current partial segment.
     * Call this before stop() to ensure the active segment is included.
     */
    fun getSegmentSnapshot(): List<File> = synchronized(muxerLock) {
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

    // ══════════════════════════════════════════════════════════════════════════
    //  Internal — segment management
    // ══════════════════════════════════════════════════════════════════════════

    private fun requestRotation() {
        pendingRotate.set(true)
        // Hint the encoder to produce an I-frame so the rotation is seamless
        try {
            val p = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            videoEncoder?.setParameters(p)
        } catch (_: Exception) {}
    }

    /** Called from the video drain thread at an I-frame boundary. */
    private fun rotateSegment() {
        synchronized(muxerLock) {
            // Finalise the current muxer
            finalizeMuxer()

            // Push completed file to the deque; evict oldest if over limit
            currentSegFile?.takeIf { it.exists() && it.length() > 0 }?.let { done ->
                segments.addLast(done)
                while (segments.size > MAX_SEGMENTS) segments.removeFirst().delete()
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
        Log.d(TAG, "New segment: ${file.name}  immediate=${isMuxerReady}")
    }

    private fun finalizeMuxer() {
        if (isMuxerReady) {
            try { currentMuxer?.stop() } catch (_: Exception) {}
            isMuxerReady = false
        }
        try { currentMuxer?.release() } catch (_: Exception) {}
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
            try { Thread.sleep(4) } catch (_: InterruptedException) {}
        }
        // Drain remaining frames
        repeat(60) {
            drainVideoOnce(info)
            try { Thread.sleep(4) } catch (_: InterruptedException) {}
        }
    }

    private fun drainVideoOnce(info: MediaCodec.BufferInfo) {
        val enc = videoEncoder ?: return
        loop@ while (true) {
            val status = try { enc.dequeueOutputBuffer(info, 0) } catch (_: Exception) { break@loop }
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
                    if (buf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0
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
                                val adjInfo = MediaCodec.BufferInfo().apply {
                                    set(info.offset, info.size, adjPts.coerceAtLeast(0L), info.flags)
                                }
                                currentMuxer?.writeSampleData(muxerVideoTrack, buf, adjInfo)
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
    //  Internal — audio capture + drain
    // ══════════════════════════════════════════════════════════════════════════

    private fun captureAudioLoop() {
        val bufSize = 2048 * effectiveChannelCount.get()
        val main = ByteArray(bufSize)
        val mix  = ByteArray(bufSize)

        while (isRunning.get()) {
            var readCount = 0
            when {
                mAudioMode == AudioMode.MIXED && internalRecord != null && micRecord != null -> {
                    val r1 = internalRecord!!.read(main, 0, bufSize)
                    val r2 = micRecord!!.read(mix, 0, bufSize)
                    when {
                        r1 > 0 && r2 > 0 -> { mixPcm(main, mix, minOf(r1, r2)); readCount = minOf(r1, r2) }
                        r1 > 0 -> readCount = r1
                        r2 > 0 -> { System.arraycopy(mix, 0, main, 0, r2); readCount = r2 }
                    }
                }
                internalRecord != null -> readCount = internalRecord!!.read(main, 0, bufSize)
                micRecord != null      -> readCount = micRecord!!.read(main, 0, bufSize)
            }

            if (readCount > 0) {
                if (isMuted.get()) main.fill(0, 0, readCount)
                feedAudioEncoder(main, readCount)
            } else if (readCount < 0) {
                try { Thread.sleep(5) } catch (_: Exception) {}
            }
        }
    }

    private fun drainAudioLoop() {
        val info = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            drainAudioOnce(info)
            try { Thread.sleep(4) } catch (_: InterruptedException) {}
        }
        repeat(60) {
            drainAudioOnce(info)
            try { Thread.sleep(4) } catch (_: InterruptedException) {}
        }
    }

    private fun drainAudioOnce(info: MediaCodec.BufferInfo) {
        val enc = audioEncoder ?: return
        loop@ while (true) {
            val status = try { enc.dequeueOutputBuffer(info, 0) } catch (_: Exception) { break@loop }
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
                    if (buf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0
                    ) {
                        synchronized(muxerLock) {
                            if (isMuxerReady && muxerAudioTrack >= 0) {
                                val base = segStartPtsUs.get()
                                val adjPts = if (base < 0) 0L else (info.presentationTimeUs - base).coerceAtLeast(0L)
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val adjInfo = MediaCodec.BufferInfo().apply {
                                    set(info.offset, info.size, adjPts, info.flags)
                                }
                                currentMuxer?.writeSampleData(muxerAudioTrack, buf, adjInfo)
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

    private fun feedAudioEncoder(data: ByteArray, length: Int) {
        val enc = audioEncoder ?: return
        try {
            val idx = enc.dequeueInputBuffer(0)
            if (idx >= 0) {
                enc.getInputBuffer(idx)?.apply { clear(); put(data, 0, length) }
                enc.queueInputBuffer(idx, 0, length, System.nanoTime() / 1000, 0)
            }
        } catch (_: Exception) {}
    }

    private fun signalAudioEOS() {
        val enc = audioEncoder ?: return
        try {
            val idx = enc.dequeueInputBuffer(5000L)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (_: Exception) {}
    }

    private fun mixPcm(base: ByteArray, overlay: ByteArray, size: Int) {
        for (i in 0 until size step 2) {
            val s1 = ((base[i].toInt() and 0xFF) or (base[i + 1].toInt() shl 8)).toShort()
            val s2 = ((overlay[i].toInt() and 0xFF) or (overlay[i + 1].toInt() shl 8)).toShort()
            val mixed = (s1.toInt() + s2.toInt()).coerceIn(-32768, 32767).toShort().toInt()
            base[i]     = (mixed and 0xFF).toByte()
            base[i + 1] = ((mixed shr 8) and 0xFF).toByte()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Encoder setup
    // ══════════════════════════════════════════════════════════════════════════

    private fun prepareVideoEncoder() {
        val isEmulator = Build.MODEL.contains("sdk_gphone", true) ||
                Build.MODEL.contains("google_sdk", true)
        val mime = if (encoderType == "H.265 (HEVC)" && !isEmulator)
            MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val discoveryFmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val name = list.findEncoderForFormat(discoveryFmt)

        val configFmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        videoEncoder = if (name != null) MediaCodec.createByCodecName(name)
                       else              MediaCodec.createEncoderByType(mime)
        videoEncoder?.configure(configFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder?.createInputSurface()
    }

    @SuppressLint("MissingPermission")
    private fun prepareAudioEncoder() {
        if (mAudioMode == AudioMode.NONE) return

        val wantStereo = audioChannelCount == 2
        val stereoMask = AudioFormat.CHANNEL_IN_STEREO
        val monoMask   = AudioFormat.CHANNEL_IN_MONO
        val chanMask   = if (wantStereo) stereoMask else monoMask
        val minBuf     = AudioRecord.getMinBufferSize(audioSampleRate, chanMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)

        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) throw SecurityException("RECORD_AUDIO permission denied")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (mAudioMode == AudioMode.INTERNAL || mAudioMode == AudioMode.MIXED)
            ) {
                try {
                    val capture = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audioSampleRate)
                        .setChannelMask(chanMask)
                        .build()
                    internalRecord = AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(minBuf * 2)
                        .setAudioPlaybackCaptureConfig(capture)
                        .build()
                    if (internalRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("Internal AudioRecord not initialized")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Internal audio init failed: ${e.message}")
                    internalRecord = null
                    if (mAudioMode == AudioMode.INTERNAL) mAudioMode = AudioMode.MIC
                }
            }

            if (mAudioMode == AudioMode.MIC || mAudioMode == AudioMode.MIXED) {
                fun tryMic(mask: Int): AudioRecord? = try {
                    val b = AudioRecord.getMinBufferSize(audioSampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                        .coerceAtLeast(4096)
                    AudioRecord(MediaRecorder.AudioSource.MIC, audioSampleRate, mask,
                        AudioFormat.ENCODING_PCM_16BIT, b).also {
                        if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); return null }
                    }
                } catch (_: Exception) { null }
                micRecord = if (wantStereo) tryMic(stereoMask) ?: tryMic(monoMask)
                            else             tryMic(monoMask)
            }

            if (internalRecord == null && micRecord == null) {
                mAudioMode = AudioMode.NONE; return
            }

            val resolvedCh = if (wantStereo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                maxOf(micRecord?.channelCount ?: 0, internalRecord?.channelCount ?: 0).coerceIn(1, 2)
            } else if (wantStereo) {
                val cfg = micRecord?.channelConfiguration ?: internalRecord?.channelConfiguration
                if (cfg == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            } else 1
            effectiveChannelCount.set(resolvedCh)

            val aacProfile = when (audioEncoderType) {
                "AAC-HE"    -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
                "AAC-HE v2" -> MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS
                "AAC-ELD"   -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
                else        -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
            }
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, resolvedCh).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        } catch (e: Exception) {
            Log.e(TAG, "Audio init error: ${e.message}")
            mAudioMode = AudioMode.NONE
            micRecord?.release();      micRecord = null
            internalRecord?.release(); internalRecord = null
            audioEncoder?.release();   audioEncoder = null
        }
    }
}
