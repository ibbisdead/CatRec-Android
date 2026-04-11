package com.ibbie.catrec_screenrecorcer.data

/**
 * Stable keys persisted in DataStore and sent to [com.ibbie.catrec_screenrecorcer.service.ScreenRecordService].
 * UI labels are localized via string resources; these values must not be translated.
 */
object StopBehaviorKeys {
    const val NOTIFICATION = "notification"
    const val SHAKE = "shake"
    const val SCREEN_OFF = "screen_off"
    const val PAUSE_ON_SCREEN_OFF = "pause_on_screen_off"

    val ALL = setOf(NOTIFICATION, SHAKE, SCREEN_OFF, PAUSE_ON_SCREEN_OFF)

    /** Migrates legacy English labels saved in older app versions. */
    fun migrateFromLegacy(value: String): String =
        when (value) {
            "Notification" -> NOTIFICATION
            "Shake Device" -> SHAKE
            "Screen Off" -> SCREEN_OFF
            "Pause on Screen Off" -> PAUSE_ON_SCREEN_OFF
            else -> value
        }

    fun migrateSet(raw: Set<String>?): Set<String> {
        val s = raw ?: emptySet()
        if (s.isEmpty()) return setOf(NOTIFICATION)
        val migrated = s.map { migrateFromLegacy(it) }.filter { it in ALL }.toSet()
        return migrated.ifEmpty { setOf(NOTIFICATION) }
    }
}
