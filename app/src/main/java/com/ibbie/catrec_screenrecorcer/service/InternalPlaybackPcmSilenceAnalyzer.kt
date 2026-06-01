package com.ibbie.catrec_screenrecorcer.service

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Threshold-based near-silence for PCM16 playback-capture diagnostics.
 * Conservative so quiet UI / fade-outs do not instantly reset the silence window,
 * while sustained blocked-game silence (zeros or tiny dither) still counts as silent.
 */
object InternalPlaybackPcmSilenceAnalyzer {

    /** Max absolute PCM16 sample in the window must stay below this. */
    const val NEAR_SILENCE_PEAK_ABS: Int = 128

    /** RMS must stay below this (PCM16 units, ~0.4% FS typical noise floor). */
    const val NEAR_SILENCE_RMS: Double = 48.0

    fun pcm16BufferIsNearSilent(
        buffer: ByteArray,
        byteLen: Int,
        peakThreshold: Int = NEAR_SILENCE_PEAK_ABS,
        rmsThreshold: Double = NEAR_SILENCE_RMS,
    ): Boolean {
        val n = minOf(byteLen, buffer.size)
        if (n < 2) return true
        val frameBytes = n - (n % 2)
        var peak = 0
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < frameBytes) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val s = (lo or (hi shl 8)).toShort().toInt()
            val a = abs(s)
            if (a > peak) peak = a
            val sf = s.toDouble()
            sumSq += sf * sf
            samples++
            i += 2
        }
        if (samples == 0) return true
        val rms = sqrt(sumSq / samples)
        return peak < peakThreshold && rms < rmsThreshold
    }

    fun pcm16BufferHasAudibleSignal(buffer: ByteArray, byteLen: Int): Boolean =
        byteLen > 0 && !pcm16BufferIsNearSilent(buffer, byteLen)
}
