package com.ibbie.catrec_screenrecorcer.service

import android.graphics.Bitmap

internal interface ActiveRecordingEngine {
    fun start()

    fun stop()

    fun hadOutput(): Boolean

    fun requestScreenshot(onBitmap: (Bitmap?) -> Unit) {
        onBitmap(null)
    }

    fun pause() = Unit

    fun resume() = Unit

    fun mute() = Unit

    fun unmute() = Unit

    fun requestMicFallbackFromSilentInternal() = Unit

    fun applyAdaptiveVideoBitrateBps(targetBps: Int): Boolean = false

    fun setAdaptiveSkipModulo(modulo: Int) = Unit

    fun resizeCaptureSource(
        newW: Int,
        newH: Int,
    ) = Unit

    fun attachAdaptivePerformance(
        sink: AdaptiveRecordingSignalSink?,
        signalsEnabled: Boolean,
        adaptiveTierSupplier: (() -> Int)? = null,
    ) = Unit
}
