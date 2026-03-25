package com.ibbie.catrec_screenrecorcer.data

/**
 * Session-scoped ad gate. Tracks which premium features have been unlocked by watching
 * an ad during the current app process. The set is intentionally NOT persisted — it resets
 * every time the app process is killed (closed, force-stopped, restarted, etc.).
 *
 * Each feature requires its own separate ad to unlock.
 */
object AdGate {
    private val unlocked = mutableSetOf<String>()

    const val SEPARATE_MIC    = "separate_mic"
    const val CAMERA_SETTINGS = "camera_settings"
    const val HIGH_FPS        = "high_fps"

    fun isUnlocked(feature: String): Boolean = unlocked.contains(feature)

    fun unlock(feature: String) { unlocked.add(feature) }
}
