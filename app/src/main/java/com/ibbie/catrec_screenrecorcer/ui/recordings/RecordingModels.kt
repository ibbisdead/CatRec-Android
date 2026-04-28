package com.ibbie.catrec_screenrecorcer.ui.recordings

import android.net.Uri

data class RecordingEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateMs: Long,
    val durationMs: Long,
    val hasSeparateAudio: Boolean = false,
)
