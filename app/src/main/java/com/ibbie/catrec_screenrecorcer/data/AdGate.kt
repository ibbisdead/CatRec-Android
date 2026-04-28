package com.ibbie.catrec_screenrecorcer.data

/**
 * Ad-gated “premium” features: high FPS, separate mic track, camera overlay settings, etc.
 *
 * - [adsDisabled]: when true (remove-ads purchase), **every** ad-gated feature is accessible
 *   without watching an ad — add new gated UI by checking [isUnlocked] with the same flag.
 * - Session unlocks: watching a rewarded ad adds the feature to [unlocked] until process death.
 */
object AdGate {
    private val unlocked = mutableSetOf<String>()

    const val SEPARATE_MIC = "separate_mic"
    const val CAMERA_SETTINGS = "camera_settings"
    const val HIGH_FPS = "high_fps"

    fun isUnlocked(
        feature: String,
        adsDisabled: Boolean,
    ): Boolean = adsDisabled || unlocked.contains(feature)

    fun unlock(feature: String) {
        unlocked.add(feature)
    }
}
