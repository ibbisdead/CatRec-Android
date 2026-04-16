package com.ibbie.catrec_screenrecorcer.service

/**
 * Process-wide floating-controls flag for notification / shade UI.
 * [ScreenRecordService] is the session source of truth via [update]; other components read [peek].
 * [initialized] is false until first [update] — callers may fall back to DataStore once on cold start.
 */
internal object FloatingControlsNotificationCache {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var initialized: Boolean = false
        private set

    fun update(value: Boolean) {
        enabled = value
        initialized = true
    }

    fun peek(): Boolean = enabled
}
