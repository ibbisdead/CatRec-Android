package com.ibbie.catrec_screenrecorcer.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Returns true if [ContentResolver.openFileDescriptor] can open [uri] for read access.
 * Use before ExoPlayer / MediaExtractor to avoid opaque "Source error" when the MediaStore row is gone.
 */
fun contentUriReadableForPlayback(
    context: Context,
    uri: Uri,
): Boolean =
    runCatching {
        if (isDirectoryDocumentUri(context, uri)) return@runCatching false
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

private fun isDirectoryDocumentUri(
    context: Context,
    uri: Uri,
): Boolean {
    if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
    val isDocument = DocumentsContract.isDocumentUri(context, uri)
    if (DocumentsContract.isTreeUri(uri) && !isDocument) return true
    if (!isDocument) return false
    return runCatching {
        context.contentResolver
            .query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                mimeIndex >= 0 &&
                    cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR
            } ?: false
    }.getOrDefault(false)
}
