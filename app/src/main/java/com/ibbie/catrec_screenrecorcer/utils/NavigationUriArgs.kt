package com.ibbie.catrec_screenrecorcer.utils

import android.net.Uri
import androidx.core.net.toUri

/**
 * Converts a URI passed through a navigation route back to a [Uri] without double-decoding SAF
 * document ids. Navigation normally decodes the route argument once; decoding again turns embedded
 * `%2F` in document ids into path separators and breaks ContentResolver access.
 */
fun navigationUriArgToUri(value: String): Uri {
    val direct = value.toUri()
    if (!direct.scheme.isNullOrBlank()) return direct
    return Uri.decode(value).toUri()
}
