package com.ibbie.catrec_screenrecorcer.utils

import java.util.Locale

/** Formats milliseconds as H:MM:SS or M:SS (stable [Locale.US] for numeric timestamps). */
fun formatDurationMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/** Total elapsed whole minutes and seconds (M:SS), not H:MM:SS — for trim handles on long sources. */
fun formatElapsedMinutesSecondsMs(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val r = s % 60
    return String.format(Locale.US, "%d:%02d", m, r)
}
