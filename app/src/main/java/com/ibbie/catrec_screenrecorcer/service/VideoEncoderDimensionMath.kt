package com.ibbie.catrec_screenrecorcer.service

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ÷16 alignment and aspect-preserving scale helpers shared by [VideoEncoderConfigurator].
 *
 * Any future **adaptive resolution** step (e.g. extra tiers on [RecordingPerformanceController])
 * should use [scalePreservingAspectCeil16] before re-running encoder setup so dimensions stay
 * encoder-safe and match the VirtualDisplay / surface size policy.
 */
internal object VideoEncoderDimensionMath {
    fun alignCeil16(value: Int): Int = ((value + 15) / 16) * 16

    /**
     * Uniform scale of [width]×[height] by [scaleFactor] (clamped to 0.01…1), then ÷16 sizing.
     * The **long edge** is scaled and ceil-aligned first; the short edge is derived from the
     * original aspect ratio so width and height are not rounded independently (avoids visible warp).
     *
     * @param minEdgePx minimum width and height after scaling (at least 16).
     */
    fun scalePreservingAspectCeil16(
        width: Int,
        height: Int,
        scaleFactor: Float,
        minEdgePx: Int = 16,
    ): Pair<Int, Int> {
        val f = scaleFactor.coerceIn(0.01f, 1f)
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val aspect = safeW.toFloat() / safeH.toFloat()
        val minE = minEdgePx.coerceIn(16, 4096)
        return if (safeW >= safeH) {
            val w = max(minE, alignCeil16((safeW * f).roundToInt()))
            val h = max(minE, alignCeil16((w / aspect).roundToInt()))
            w to h
        } else {
            val h = max(minE, alignCeil16((safeH * f).roundToInt()))
            val w = max(minE, alignCeil16((h * aspect).roundToInt()))
            w to h
        }
    }
}
