package com.ibbie.catrec_screenrecorcer.utils

import android.content.Context
import android.net.Uri

/**
 * Returns true if [ContentResolver.openFileDescriptor] can open [uri] for read access.
 * Use before ExoPlayer / MediaExtractor to avoid opaque "Source error" when the MediaStore row is gone.
 */
fun contentUriReadableForPlayback(
    context: Context,
    uri: Uri,
): Boolean =
    runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
