package com.ibbie.catrec_screenrecorcer.service

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.ibbie.catrec_screenrecorcer.BuildConfig
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Relay -> controller signals without exposing [RecordingPerformanceController] on public APIs. */
interface AdaptiveRecordingSignalSink {
    fun onRelayBackpressure()

    fun onSlowFrame()
}

/**
 * Session-scoped adaptive mitigations for screen recording / rolling buffer.
 *
 * Compatibility-engine sessions receive [EncoderFrameRelay] stress signals: [onRelayBackpressure]
 * and [onSlowFrame]. Performance-engine sessions do not have a relay, so relay-only signals and
 * relay skip tiers are disabled there.
 *
 * Decay and tier logic run on a dedicated background thread ([tickHandler]); not on the main thread.
 *
 * This controller does not change capture width/height. Mitigations are Compatibility-only relay
 * frame skipping ([setRelaySkipModulo]), bitrate reduction ([applyAdaptiveVideoBitrateBps]), and
 * HEVC->AVC preference for the next encoder prepare ([onPreferAvc]). Relay workload is reduced in
 * early Compatibility tiers before deeper bitrate cuts.
 *
 * Encoder-safe dimensions and [MediaCodecInfo.VideoCapabilities.isSizeSupported] are enforced once
 * at configure time in [VideoEncoderConfigurator] (including aspect-preserving fallback via
 * [VideoEncoderDimensionMath]).
 */
internal class RecordingPerformanceController(
    private val engineMode: RecordingEngineMode,
    private val sessionBaselineBitrateBps: Int,
    private val applyAdaptiveVideoBitrateBps: (Int) -> Boolean,
    private val setRelaySkipModulo: (Int) -> Unit,
    private val onPreferAvc: () -> Unit,
) : AdaptiveRecordingSignalSink {
    private val sessionActive = AtomicBoolean(false)
    private val ignoredRelaySignalLogged = AtomicBoolean(false)
    private val stressScore = AtomicInteger(0)
    private val tierIndex = AtomicInteger(0)
    private val lastAdjustmentWallMs = AtomicLong(0L)
    private val cumulativeBitrateFailures = AtomicInteger(0)
    private val relayAdaptiveEnabled = false

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
        tierIndex.set(0)
        lastAdjustmentWallMs.set(0L)
        cumulativeBitrateFailures.set(0)
        adaptiveBitrateBps = sessionBaselineBitrateBps
        if (relayAdaptiveEnabled) {
            setRelaySkipModulo(1)
        }
    }

    /** Current adaptive tier (0 = baseline, 1..MAX_TIER = stepped mitigations). For diagnostics only. */
    fun currentTier(): Int = tierIndex.get()

    override fun onRelayBackpressure() {
        if (!sessionActive.get()) return
        if (!relayAdaptiveEnabled) {
            logIgnoredRelaySignalOnce("backpressure")
            return
        }
        stressScore.addAndGet(STRESS_INC_BACKPRESSURE)
    }

    override fun onSlowFrame() {
        if (!sessionActive.get()) return
        if (!relayAdaptiveEnabled) {
            logIgnoredRelaySignalOnce("slow_frame")
            return
        }
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
        val tierNow = tierIndex.get()
        if (tierNow >= MAX_TIER) return
        if (stressScore.get() < STRESS_THRESHOLD) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdjustmentWallMs.get() < COOLDOWN_MS) return

        val nextTier = tierIndex.incrementAndGet()
        lastAdjustmentWallMs.set(now)
        stressScore.addAndGet(-STRESS_THRESHOLD)
        applyTier(nextTier)
    }

    private fun applyTier(tier: Int) {
        val baseline = sessionBaselineBitrateBps
        val floorBps = (baseline * BITRATE_FLOOR_FRACTION).toInt().coerceAtLeast(MIN_VIDEO_BPS)
        when (tier) {
            1 -> {
                applyRelaySkipModulo(tier, modulo = 2)
            }
            2 -> {
                applyRelaySkipModulo(tier, modulo = 3)
            }
            3 -> {
                val target = (baseline * 0.94).toInt().coerceIn(floorBps, baseline)
                val ok = applyAdaptiveVideoBitrateBps(target)
                if (!ok) cumulativeBitrateFailures.incrementAndGet()
                if (ok) adaptiveBitrateBps = target
                Log.i(TAG, "adaptive engine=${engineMode.storageValue} tier=$tier action=bitrate_94% ok=$ok targetBps=$target")
            }
            4 -> {
                applyRelaySkipModulo(tier, modulo = 4)
            }
            5 -> {
                val target = (baseline * 0.85).toInt().coerceIn(floorBps, baseline)
                val ok = applyAdaptiveVideoBitrateBps(target)
                if (!ok) cumulativeBitrateFailures.incrementAndGet()
                if (ok) adaptiveBitrateBps = target
                Log.i(TAG, "adaptive engine=${engineMode.storageValue} tier=$tier action=bitrate_85% ok=$ok targetBps=$target")
            }
            6 -> {
                applyRelaySkipModulo(tier, modulo = 5)
            }
            7 -> {
                val target = (baseline * 0.72).toInt().coerceIn(floorBps, baseline)
                val ok = applyAdaptiveVideoBitrateBps(target)
                if (!ok) cumulativeBitrateFailures.incrementAndGet()
                if (ok) {
                    adaptiveBitrateBps = target
                    Log.i(TAG, "adaptive engine=${engineMode.storageValue} tier=$tier action=bitrate_72% ok=$ok targetBps=$target")
                } else if (cumulativeBitrateFailures.get() >= 2) {
                    applyRelaySkipModulo(tier, modulo = 6, reason = "bitrate_failures")
                } else {
                    Log.w(TAG, "adaptive engine=${engineMode.storageValue} tier=$tier action=bitrate_72% failed ok=$ok targetBps=$target")
                }
            }
            8 -> {
                onPreferAvc()
                Log.i(TAG, "adaptive engine=${engineMode.storageValue} tier=$tier action=prefer_avc_next_prepare")
            }
        }
    }

    private fun applyRelaySkipModulo(
        tier: Int,
        modulo: Int,
        reason: String? = null,
    ) {
        if (!relayAdaptiveEnabled) return
        setRelaySkipModulo(modulo)
        val reasonPart = reason?.let { " reason=$it" }.orEmpty()
        Log.i(TAG, "adaptive engine=compatibility tier=$tier action=relay_skip_$modulo$reasonPart")
    }

    private fun logIgnoredRelaySignalOnce(signal: String) {
        if (!ignoredRelaySignalLogged.compareAndSet(false, true)) return
        if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "ignored relay-only adaptive signal=$signal engine=${engineMode.storageValue}")
        }
    }

    private companion object {
        const val TAG = "RecPerfAdaptive"

        private const val TICK_THREAD_NAME = "CatRec-RecPerfAdaptive"
        private const val JOIN_TIMEOUT_MS = 2_000L

        private const val MAX_TIER = 8
        private const val STRESS_THRESHOLD = 3
        private const val COOLDOWN_MS = 5_000L
        private const val TICK_MS = 1_500L
        private const val DECAY_PER_TICK = 1
        private const val STRESS_INC_BACKPRESSURE = 2
        private const val STRESS_INC_SLOW = 2
        private const val BITRATE_FLOOR_FRACTION = 0.20
        private const val MIN_VIDEO_BPS = 200_000
    }
}
