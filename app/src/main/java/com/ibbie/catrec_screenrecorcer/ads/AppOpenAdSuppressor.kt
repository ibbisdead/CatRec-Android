package com.ibbie.catrec_screenrecorcer.ads

import android.util.Log

enum class AppOpenAdSuppressionReason(
    val logName: String,
) {
    FIRST_LAUNCH("first_launch"),
    FIRST_RUN_PERMISSIONS("first_run_permissions"),
    RUNTIME_PERMISSION_REQUEST("runtime_permission_request"),
    ANDROID_SETTINGS("android_settings"),
    MEDIA_PROJECTION("media_projection"),
    REWARDED_AD("rewarded_ad"),
    INTERSTITIAL_AD("interstitial_ad"),
    BILLING("billing"),
    ROUTED_RECORDING_ACTION("routed_recording_action"),
}

object AppOpenAdSuppressor {
    private const val TAG = "AppOpenAdSuppressor"
    const val POST_FLOW_GRACE_MS = 30_000L

    private val lock = Any()
    private val activeReasons = linkedSetOf<AppOpenAdSuppressionReason>()
    private var graceUntilMs: Long = 0L
    private var graceReason: AppOpenAdSuppressionReason? = null

    fun enter(reason: AppOpenAdSuppressionReason) {
        synchronized(lock) {
            if (activeReasons.add(reason)) {
                Log.d(TAG, "enter ${reason.logName}; active=${activeReasons.logNames()}")
            }
        }
    }

    fun exit(
        reason: AppOpenAdSuppressionReason,
        applyGrace: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val removed = activeReasons.remove(reason)
            if (removed && applyGrace) {
                graceUntilMs = maxOf(graceUntilMs, now + POST_FLOW_GRACE_MS)
                graceReason = reason
            }
            if (removed) {
                Log.d(
                    TAG,
                    "exit ${reason.logName}; grace=$applyGrace " +
                        "graceRemainingMs=${(graceUntilMs - now).coerceAtLeast(0L)} active=${activeReasons.logNames()}",
                )
            }
        }
    }

    fun suppressFor(
        reason: AppOpenAdSuppressionReason,
        durationMs: Long = POST_FLOW_GRACE_MS,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            graceUntilMs = maxOf(graceUntilMs, now + durationMs)
            graceReason = reason
            Log.d(TAG, "suppressFor ${reason.logName} durationMs=$durationMs")
        }
    }

    fun activeBlockReason(nowMs: Long = System.currentTimeMillis()): String? =
        synchronized(lock) {
            activeReasons.firstOrNull()?.logName
                ?: graceReason
                    ?.takeIf { nowMs < graceUntilMs }
                    ?.let { "${it.logName}_grace_${graceUntilMs - nowMs}ms" }
        }

    fun clear(reason: AppOpenAdSuppressionReason) {
        synchronized(lock) {
            if (activeReasons.remove(reason)) {
                Log.d(TAG, "clear ${reason.logName}; active=${activeReasons.logNames()}")
            }
        }
    }

    private fun Collection<AppOpenAdSuppressionReason>.logNames(): String = joinToString(prefix = "[", postfix = "]") { it.logName }
}
