package com.ibbie.catrec_screenrecorcer.media

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val type: MediaType,
    val name: String?,
    val duration: Long?,
    val aspectRatio: Float?,
    /** Raw file size when known (list UI, sorting). */
    val sizeBytes: Long? = null,
    /** Last modified time in ms when known (list UI). */
    val dateModifiedMs: Long? = null,
    /** Video: separate mic track exists. */
    val hasSeparateAudio: Boolean = false,
)
