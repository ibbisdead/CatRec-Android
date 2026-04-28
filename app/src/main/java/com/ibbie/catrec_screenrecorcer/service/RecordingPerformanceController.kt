package com.ibbie.catrec_screenrecorcer.service

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Relay → controller signals without exposing [RecordingPerformanceController] on public APIs. */
interface AdaptiveRecordingSignalSink {
    fun onRelayBackpressure()

    fun onSlowFrame()
}

/**
 * Session-scoped adaptive mitigations for screen recording / rolling buffer.
 * Signals: [onRelayBackpressure], [onSlowFrame] (relay-throttled). Tier steps are cooldown-gated.
 * Decay and tier logic run on a dedicated background thread ([tickHandler]); not on the main thread.
 *
 * **Resolution:** this controller does **not** change capture width/height. Mitigations are
 * bitrate reduction ([applyAdaptiveVideoBitrateBps]), frame skipping ([setRelaySkipModulo]), and
 * HEVC→AVC preference for the next encoder prepare ([onPreferAvc]). Encoder-safe dimensions and
 * [MediaCodecInfo.VideoCapabilities.isSizeSupported] are enforced once at configure time in
 * [VideoEncoderConfigurator] (including aspect-preserving fallback via [VideoEncoderDimensionMath]).
 */
internal class RecordingPerformanceController(
    private val sessionBaselineBitrateBps: Int,
    private val applyAdaptiveVideoBitrateBps: (Int) -> Boolean,
    private val setRelaySkipModulo: (Int) -> Unit,
    private val onPreferAvc: () -> Unit,
) : AdaptiveRecordingSignalSink {
    private val sessionActive = AtomicBoolean(false)
    private val stressScore = AtomicInteger(0)
    private val currentTier = AtomicInteger(0)
    private val lastAdjustmentWallMs = AtomicLong(0L)
    private val cumulativeBitrateFailures = AtomicInteger(0)

    @Volatile
    private var adaptiveBitrateBps: Int = sessionBaselineBitrateBps

    private var tickThread: HandlerThread? = null
    private var tickHandler: Handler? = null

    private val tickRunnable =
        Runnable {
            if (!sessionActive.get()) return@Runnable
            tick()
            scheduleNextTick()
        }

    fun startSession() {
        if (!sessionActive.compareAndSet(false, true)) return
        val thread =
            HandlerThread(TICK_THREAD_NAME).also {
                it.start()
            }
        tickThread = thread
        tickHandler = Handler(thread.looper)
        scheduleNextTick()
    }

    fun stopSession() {
        sessionActive.set(false)
        val h = tickHandler
        val t = tickThread
        tickHandler = null
        tickThread = null
        try {
            h?.removeCallbacks(tickRunnable)
        } catch (_: Exception) {
        }
        try {
            t?.quitSafely()
        } catch (_: Exception) {
        }
        try {
            t?.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        stressScore.set(0)
        currentTier.set(0)
        lastAdjustmentWallMs.set(0L)
        cumulativeBitrateFailures.set(0)
        adaptiveBitrateBps = sessionBaselineBitrateBps
        setRelaySkipModulo(1)
    }

    override fun onRelayBackpressure() {
        if (!sessionActive.get()) return
        stressScore.addAndGet(STRESS_INC_BACKPRESSURE)
    }

    override fun onSlowFrame() {
        if (!sessionActive.get()) return
        stressScore.addAndGet(STRESS_INC_SLOW)
    }

    private fun scheduleNextTick() {
        if (!sessionActive.get()) return
        val h = tickHandler ?: return
        h.removeCallbacks(tickRunnable)
        h.postDelayed(tickRunnable, TICK_MS)
    }

    private fun tick() {
        stressScore.updateAndGet { v -> (v - DECAY_PER_TICK).coerceAtLeast(0) }
        tryAdvanceTier()
    }

    private fun tryAdvanceTier() {
        val tierNow = currentTier.get()
        if (tierNow >= MAX_TIER) return
        if (stressScore.get() < STRESS_THRESHOLD) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdjustmentWallMs.get() < COOLDOWN_MS) return

        val nextTier = currentTier.incrementAndGet()
        lastAdjustmentWallMs.set(now)
        stressScore.addAndGet(-STRESS_THRESHOLD)
        applyTier(nextTier)
    }

    private fun applyTier(tier: Int) {
        val baseline = sessionBaselineBitrateBps
        val floorBps = (baseline * BITRATE_FLOOR_FRACTION).toInt().coerceAtLeast(MIN_VIDEO_BPS)
        when (tier) {
            1 -> {
                val target = (baseline * 0.85).toInt().coerceIn(floorBps, baseline)
                val ok = applyAdaptiveVideoBitrateBps(target)
                if (!ok) cumulativeBitrateFailures.incrementAndGet()
                adaptiveBitrateBps = target
                Log.i(TAG, "adaptive tier=$tier action=bitrate_85% ok=$ok targetBps=$target")
            }
            2 -> {
                if (cumulativeBitrateFailures.get() >= 2) {
                    setRelaySkipModulo(2)
                    Log.i(TAG, "adaptive tier=$tier action=relay_skip_2 (bitrate_failures>=2)")
                } else {
                    val target = (baseline * 0.70).toInt().coerceIn(floorBps, baseline)
                    val ok = applyAdaptiveVideoBitrateBps(target)
                    if (!ok) cumulativeBitrateFailures.incrementAndGet()
                    adaptiveBitrateBps = target
                    Log.i(TAG, "adaptive tier=$tier action=bitrate_70% ok=$ok targetBps=$target")
                }
            }
            3 -> {
                val target = (baseline * 0.55).toInt().coerceIn(floorBps, baseline)
                val atFloor = target <= floorBps
                val ok = applyAdaptiveVideoBitrateBps(target)
                if (!ok) cumulativeBitrateFailures.incrementAndGet()
                if (!ok || atFloor) {
                    setRelaySkipModulo(3)
                    Log.i(TAG, "adaptive tier=$tier action=relay_skip_3 atFloor=$atFloor ok=$ok")
                } else {
                    adaptiveBitrateBps = target
                    Log.i(TAG, "adaptive tier=$tier action=bitrate_55% ok=$ok targetBps=$target")
                }
            }
            4 -> {
                onPreferAvc()
                Log.i(TAG, "adaptive tier=$tier action=prefer_avc_next_prepare")
            }
        }
    }

    private companion object {
        const val TAG = "RecPerfAdaptive"

        private const val TICK_THREAD_NAME = "CatRec-RecPerfAdaptive"
        private const val JOIN_TIMEOUT_MS = 2_000L

        private const val MAX_TIER = 4
        private const val STRESS_THRESHOLD = 5
        private const val COOLDOWN_MS = 8_000L
        private const val TICK_MS = 2_000L
        private const val DECAY_PER_TICK = 2
        private const val STRESS_INC_BACKPRESSURE = 2
        private const val STRESS_INC_SLOW = 3
        private const val BITRATE_FLOOR_FRACTION = 0.20
        private const val MIN_VIDEO_BPS = 200_000
    }
}
