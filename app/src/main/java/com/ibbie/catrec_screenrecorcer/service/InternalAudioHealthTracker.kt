package com.ibbie.catrec_screenrecorcer.service

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Allocation-light health counters for AudioPlaybackCapture PCM.
 *
 * This never stores PCM and scans only the current read buffer. Snapshots are allocated only at
 * milestone/final-report points.
 */
class InternalAudioHealthTracker(
    private val recordingType: String,
    private val engineModeName: String,
    private val requestedAudioModeName: String,
    private val micRequested: Boolean,
    private val internalRequested: Boolean,
    private val separateMicRequested: Boolean,
    private val requestedSampleRate: Int,
) {
    private var sessionStartElapsedMs = 0L

    var internalAudioRecordCreated: Boolean = false
        private set
    var internalAudioRecordStarted: Boolean = false
        private set
    var audioRecordStateAfterCreate: Int = -1
        private set
    var recordingStateAfterStart: Int = -1
        private set
    var sampleRate: Int = requestedSampleRate
        private set
    var channelCount: Int = 0
        private set
    var encoding: Int = 0
        private set
    var bufferSizeBytes: Int = 0
        private set

    var firstPositiveReadElapsedMs: Long = -1L
        private set
    var firstNonZeroPcmElapsedMs: Long = -1L
        private set
    var firstAudiblePcmElapsedMs: Long = -1L
        private set
    var totalBytesRead: Long = 0L
        private set
    var positiveReadCount: Long = 0L
        private set
    var zeroReadCount: Long = 0L
        private set
    var negativeReadCount: Long = 0L
        private set
    var peakAbs: Int = 0
        private set

    private var totalSumSquares = 0.0
    private var totalSampleCount = 0L

    var stage1SilentAt4s: Boolean = false
        private set
    var stage2SilentAt10s: Boolean = false
        private set
    var persistentSilentAt20s: Boolean = false
        private set
    var rebuildAttemptCount: Int = 0
        private set
    var rebuildSuccessCount: Int = 0
        private set
    var recoveredAfterRebuild: Boolean = false
        private set
    var recoveredLate: Boolean = false
        private set
    var fallbackTriggered: Boolean = false
        private set

    fun reset(startElapsedMs: Long) {
        sessionStartElapsedMs = startElapsedMs
        internalAudioRecordCreated = false
        internalAudioRecordStarted = false
        audioRecordStateAfterCreate = -1
        recordingStateAfterStart = -1
        sampleRate = requestedSampleRate
        channelCount = 0
        encoding = 0
        bufferSizeBytes = 0
        firstPositiveReadElapsedMs = -1L
        firstNonZeroPcmElapsedMs = -1L
        firstAudiblePcmElapsedMs = -1L
        totalBytesRead = 0L
        positiveReadCount = 0L
        zeroReadCount = 0L
        negativeReadCount = 0L
        peakAbs = 0
        totalSumSquares = 0.0
        totalSampleCount = 0L
        stage1SilentAt4s = false
        stage2SilentAt10s = false
        persistentSilentAt20s = false
        rebuildAttemptCount = 0
        rebuildSuccessCount = 0
        recoveredAfterRebuild = false
        recoveredLate = false
        fallbackTriggered = false
    }

    fun noteAudioRecordCreated(
        state: Int,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int,
        bufferSizeBytes: Int,
    ) {
        internalAudioRecordCreated = state == android.media.AudioRecord.STATE_INITIALIZED
        audioRecordStateAfterCreate = state
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.encoding = encoding
        this.bufferSizeBytes = bufferSizeBytes
    }

    fun noteAudioRecordStart(recordingState: Int) {
        internalAudioRecordStarted = recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING
        recordingStateAfterStart = recordingState
    }

    fun noteRebuildAttempt() {
        rebuildAttemptCount++
    }

    fun noteRebuildSucceeded() {
        rebuildSuccessCount++
    }

    fun markStage1Silent() {
        stage1SilentAt4s = true
    }

    fun markStage2Silent() {
        stage2SilentAt10s = true
    }

    fun markPersistentSilent() {
        persistentSilentAt20s = true
    }

    fun markFallbackTriggered() {
        fallbackTriggered = true
    }

    /**
     * @return true when this read contains audible PCM per the same threshold used by CatRec's
     * internal-silence detector.
     */
    fun observeRead(
        buffer: ByteArray,
        byteLen: Int,
        nowElapsedMs: Long,
    ): Boolean {
        if (!internalRequested) return false
        when {
            byteLen < 0 -> {
                negativeReadCount++
                return false
            }
            byteLen == 0 -> {
                zeroReadCount++
                return false
            }
        }

        positiveReadCount++
        val elapsed = elapsedSinceStart(nowElapsedMs)
        if (firstPositiveReadElapsedMs < 0L) firstPositiveReadElapsedMs = elapsed

        val n = minOf(byteLen, buffer.size)
        totalBytesRead += n.toLong()
        if (n < 2) return false

        val frameBytes = n - (n % 2)
        var localPeak = 0
        var localSumSq = 0.0
        var localSamples = 0
        var sawNonZero = false
        var i = 0
        while (i + 1 < frameBytes) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val s = (lo or (hi shl 8)).toShort().toInt()
            if (s != 0) sawNonZero = true
            val a = abs(s)
            if (a > localPeak) localPeak = a
            val sf = s.toDouble()
            localSumSq += sf * sf
            localSamples++
            i += 2
        }

        if (sawNonZero && firstNonZeroPcmElapsedMs < 0L) {
            firstNonZeroPcmElapsedMs = elapsed
        }
        if (localPeak > peakAbs) peakAbs = localPeak
        totalSumSquares += localSumSq
        totalSampleCount += localSamples.toLong()

        if (localSamples == 0) return false
        val localRms = sqrt(localSumSq / localSamples)
        val audible =
            localPeak >= InternalPlaybackPcmSilenceAnalyzer.NEAR_SILENCE_PEAK_ABS ||
                localRms >= InternalPlaybackPcmSilenceAnalyzer.NEAR_SILENCE_RMS
        if (audible && firstAudiblePcmElapsedMs < 0L) {
            firstAudiblePcmElapsedMs = elapsed
            recoveredAfterRebuild = rebuildSuccessCount > 0
            recoveredLate = stage1SilentAt4s || elapsed >= INTERNAL_AUDIO_STAGE1_SILENCE_MS
        }
        return audible
    }

    fun snapshot(
        muxerStarted: Boolean,
        muxerAudioSamplesWritten: Boolean,
    ): Snapshot =
        Snapshot(
            recordingType = recordingType,
            engineModeName = engineModeName,
            requestedAudioModeName = requestedAudioModeName,
            micRequested = micRequested,
            internalRequested = internalRequested,
            separateMicRequested = separateMicRequested,
            internalAudioRecordCreated = internalAudioRecordCreated,
            internalAudioRecordStarted = internalAudioRecordStarted,
            audioRecordStateAfterCreate = audioRecordStateAfterCreate,
            recordingStateAfterStart = recordingStateAfterStart,
            sampleRate = sampleRate,
            channelCount = channelCount,
            encoding = encoding,
            bufferSizeBytes = bufferSizeBytes,
            firstPositiveReadElapsedMs = firstPositiveReadElapsedMs,
            firstNonZeroPcmElapsedMs = firstNonZeroPcmElapsedMs,
            firstAudiblePcmElapsedMs = firstAudiblePcmElapsedMs,
            totalBytesRead = totalBytesRead,
            positiveReadCount = positiveReadCount,
            zeroReadCount = zeroReadCount,
            negativeReadCount = negativeReadCount,
            peakAbs = peakAbs,
            rmsEstimate = rmsEstimate(),
            stage1SilentAt4s = stage1SilentAt4s,
            stage2SilentAt10s = stage2SilentAt10s,
            persistentSilentAt20s = persistentSilentAt20s,
            rebuildAttemptCount = rebuildAttemptCount,
            rebuildSuccessCount = rebuildSuccessCount,
            recoveredAfterRebuild = recoveredAfterRebuild,
            recoveredLate = recoveredLate,
            fallbackTriggered = fallbackTriggered,
            finalHadInternalAudioSamples = firstAudiblePcmElapsedMs >= 0L,
            muxerStarted = muxerStarted,
            muxerAudioSamplesWritten = muxerAudioSamplesWritten,
        )

    private fun rmsEstimate(): Int =
        if (totalSampleCount <= 0L) {
            0
        } else {
            sqrt(totalSumSquares / totalSampleCount).toInt()
        }

    private fun elapsedSinceStart(nowElapsedMs: Long): Long =
        if (sessionStartElapsedMs > 0L) {
            (nowElapsedMs - sessionStartElapsedMs).coerceAtLeast(0L)
        } else {
            -1L
        }

    data class Snapshot(
        val recordingType: String,
        val engineModeName: String,
        val requestedAudioModeName: String,
        val micRequested: Boolean,
        val internalRequested: Boolean,
        val separateMicRequested: Boolean,
        val internalAudioRecordCreated: Boolean,
        val internalAudioRecordStarted: Boolean,
        val audioRecordStateAfterCreate: Int,
        val recordingStateAfterStart: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int,
        val bufferSizeBytes: Int,
        val firstPositiveReadElapsedMs: Long,
        val firstNonZeroPcmElapsedMs: Long,
        val firstAudiblePcmElapsedMs: Long,
        val totalBytesRead: Long,
        val positiveReadCount: Long,
        val zeroReadCount: Long,
        val negativeReadCount: Long,
        val peakAbs: Int,
        val rmsEstimate: Int,
        val stage1SilentAt4s: Boolean,
        val stage2SilentAt10s: Boolean,
        val persistentSilentAt20s: Boolean,
        val rebuildAttemptCount: Int,
        val rebuildSuccessCount: Int,
        val recoveredAfterRebuild: Boolean,
        val recoveredLate: Boolean,
        val fallbackTriggered: Boolean,
        val finalHadInternalAudioSamples: Boolean,
        val muxerStarted: Boolean,
        val muxerAudioSamplesWritten: Boolean,
    ) {
        fun compactSummary(): String =
            "type=$recordingType engine=$engineModeName mode=$requestedAudioModeName " +
                "created=$internalAudioRecordCreated started=$internalAudioRecordStarted " +
                "ar=$audioRecordStateAfterCreate rec=$recordingStateAfterStart sr=$sampleRate ch=$channelCount " +
                "buf=$bufferSizeBytes firstPos=$firstPositiveReadElapsedMs firstNz=$firstNonZeroPcmElapsedMs " +
                "firstAud=$firstAudiblePcmElapsedMs bytes=$totalBytesRead reads=$positiveReadCount/$zeroReadCount/$negativeReadCount " +
                "peak=$peakAbs rms=$rmsEstimate stages=$stage1SilentAt4s/$stage2SilentAt10s/$persistentSilentAt20s " +
                "rebuild=$rebuildAttemptCount/$rebuildSuccessCount recRebuild=$recoveredAfterRebuild late=$recoveredLate " +
                "fallback=$fallbackTriggered mux=$muxerStarted/$muxerAudioSamplesWritten"
    }

    companion object {
        const val INTERNAL_AUDIO_STAGE1_SILENCE_MS = 4_000L
        const val INTERNAL_AUDIO_STAGE2_SILENCE_MS = 10_000L
        const val INTERNAL_AUDIO_PERSISTENT_SILENCE_MS = 20_000L
        const val INTERNAL_AUDIO_LATE_MONITOR_MS = 30_000L
    }
}
