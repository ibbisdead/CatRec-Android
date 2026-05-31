package com.ibbie.catrec_screenrecorcer.service

import android.os.SystemClock
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.BuildConfig

/**
 * Batched Crashlytics keys + throttled debug logs for [EncoderFrameRelay] (no per-frame noise).
 */
internal object RecordingRelayDiagnostics {
    private const val TAG = "RelayDiag"
    private const val FLUSH_INTERVAL_MS = 5_000L

    private var lastFlushElapsedMs: Long = 0L

    fun resetSessionClock() {
        lastFlushElapsedMs = SystemClock.elapsedRealtime()
    }

    fun flushIfDue(
        targetFps: Int,
        measuredFpsSnapshot: Float,
        adaptiveTier: Int,
        adaptiveSkipModulo: Int,
        skippedByPacingTotal: Long,
        skippedByAdaptiveTotal: Long,
        framesProcessedInWindow: Int,
        windowDurationMs: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushElapsedMs < FLUSH_INTERVAL_MS) return
        lastFlushElapsedMs = now

        val fc = runCatching { FirebaseCrashlytics.getInstance() }.getOrNull() ?: return
        try {
            fc.setCustomKey("relay_target_fps", targetFps)
            fc.setCustomKey("relay_measured_fps_window", measuredFpsSnapshot)
            fc.setCustomKey("relay_window_frames", framesProcessedInWindow)
            fc.setCustomKey("relay_window_ms", windowDurationMs.toInt().coerceAtLeast(0))
            fc.setCustomKey("relay_adaptive_tier", adaptiveTier)
            fc.setCustomKey("relay_skip_modulo", adaptiveSkipModulo)
            fc.setCustomKey("relay_skipped_pacing_total", skippedByPacingTotal)
            fc.setCustomKey("relay_skipped_adaptive_total", skippedByAdaptiveTotal)
        } catch (_: Exception) {
        }

        if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "targetFps=$targetFps measuredFps=${"%.1f".format(measuredFpsSnapshot)} " +
                    "windowFrames=$framesProcessedInWindow windowMs=$windowDurationMs " +
                    "tier=$adaptiveTier skipMod=$adaptiveSkipModulo " +
                    "skipPace=$skippedByPacingTotal skipAdaptive=$skippedByAdaptiveTotal",
            )
        }
    }
}
