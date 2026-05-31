package com.ibbie.catrec_screenrecorcer.data.recording

/**
 * User-selected screen-recording video pipeline.
 *
 * Stored as [storageValue], never as enum ordinal. Keep [fromStorageValue] tolerant so older
 * persisted values and future unknown values fall back to the safest default.
 */
enum class RecordingEngineMode(
    val storageValue: String,
) {
    PERFORMANCE("performance"),
    COMPATIBILITY("compatibility"),
    ;

    companion object {
        val DEFAULT: RecordingEngineMode = PERFORMANCE

        fun fromStorageValue(value: String?): RecordingEngineMode {
            if (value.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { mode ->
                mode.storageValue.equals(value, ignoreCase = true) ||
                    mode.name.equals(value, ignoreCase = true)
            } ?: DEFAULT
        }
    }
}
