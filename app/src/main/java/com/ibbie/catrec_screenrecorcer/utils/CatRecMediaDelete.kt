package com.ibbie.catrec_screenrecorcer.utils

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import java.io.File

private const val TAG = "CatRecMediaDelete"

/**
 * Outcome of a **silent** delete attempt (no user UI). Callers may still use
 * [createDeleteRequestPendingIntent] when this returns false for MediaStore-style URIs.
 */
enum class CatRecMediaDeleteResult {
    /** Row/file removed without user confirmation. */
    DELETED,

    /** Nothing removed; caller may try [createDeleteRequestPendingIntent] on API 30+. */
    FAILED,
}

/**
 * Best-effort delete for CatRec recordings (MediaStore / SAF / public Movies/CatRec) and
 * screenshots (public Pictures/CatRec/Screenshots).
 *
 * - **MediaStore** (`content://media/...`): [ContentResolver.delete] and optional direct file
 *   under known CatRec paths — **never** [DocumentFile] (avoids `deleteDocument` on non-documents).
 * - **SAF document** ([DocumentsContract.isDocumentUri]): [ContentResolver.delete] first, then
 *   [DocumentFile.fromSingleUri] only when the URI is a real document URI.
 * - Scoped delete requiring user confirmation is **not** performed here; returns false so callers
 *   can launch [MediaStore.createDeleteRequest].
 */
fun trySilentDeleteMedia(
    context: Context,
    uri: Uri,
): Boolean = trySilentDeleteMediaDetailed(context, uri) == CatRecMediaDeleteResult.DELETED

fun trySilentDeleteMediaDetailed(
    context: Context,
    uri: Uri,
): CatRecMediaDeleteResult {
    val cr = context.contentResolver
    return when {
        DocumentsContract.isTreeUri(uri) -> {
            Log.d(TAG, "path=TREE_URI uri=$uri")
            if (tryContentResolverDelete(cr, uri, "tree")) {
                CatRecMediaDeleteResult.DELETED
            } else {
                CatRecMediaDeleteResult.FAILED
            }
        }
        DocumentsContract.isDocumentUri(context, uri) -> deleteSafDocumentUri(context, cr, uri)
        isMediaStoreStyleContentUri(uri) -> deleteMediaStoreStyleUri(cr, uri)
        ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true) -> {
            Log.d(TAG, "path=GENERIC_CONTENT uri=$uri")
            when {
                tryContentResolverDelete(cr, uri, "generic_content") -> CatRecMediaDeleteResult.DELETED
                tryDeleteCatRecPublicFile(cr, uri) -> CatRecMediaDeleteResult.DELETED
                else -> CatRecMediaDeleteResult.FAILED
            }
        }
        else -> {
            Log.d(TAG, "path=OTHER scheme=${uri.scheme} uri=$uri")
            if (tryContentResolverDelete(cr, uri, "other")) {
                CatRecMediaDeleteResult.DELETED
            } else {
                CatRecMediaDeleteResult.FAILED
            }
        }
    }
}

private fun deleteSafDocumentUri(
    context: Context,
    cr: ContentResolver,
    uri: Uri,
): CatRecMediaDeleteResult {
    Log.d(TAG, "path=SAF_DOCUMENT uri=$uri")
    if (tryContentResolverDelete(cr, uri, "saf_document_resolver")) {
        return CatRecMediaDeleteResult.DELETED
    }
    return tryDeleteSafWithDocumentFile(context, uri)
}

private fun tryDeleteSafWithDocumentFile(
    context: Context,
    uri: Uri,
): CatRecMediaDeleteResult {
    if (!DocumentsContract.isDocumentUri(context, uri)) {
        Log.d(TAG, "skip DocumentFile: not a document URI uri=$uri")
        return CatRecMediaDeleteResult.FAILED
    }
    return try {
        val doc = DocumentFile.fromSingleUri(context, uri)
        when {
            doc == null -> {
                Log.d(TAG, "DocumentFile.fromSingleUri null uri=$uri")
                CatRecMediaDeleteResult.FAILED
            }
            doc.delete() -> {
                Log.d(TAG, "SAF DocumentFile.delete ok uri=$uri")
                CatRecMediaDeleteResult.DELETED
            }
            else -> {
                Log.d(TAG, "SAF DocumentFile.delete returned false uri=$uri")
                CatRecMediaDeleteResult.FAILED
            }
        }
    } catch (e: UnsupportedOperationException) {
        Log.d(TAG, "SAF DocumentFile.delete unsupported (expected for some providers) uri=$uri msg=${e.message}")
        CatRecMediaDeleteResult.FAILED
    } catch (e: Exception) {
        Log.d(TAG, "SAF DocumentFile.delete error uri=$uri msg=${e.message}")
        CatRecMediaDeleteResult.FAILED
    }
}

private fun deleteMediaStoreStyleUri(
    cr: ContentResolver,
    uri: Uri,
): CatRecMediaDeleteResult {
    Log.d(TAG, "path=MEDIA_STORE uri=$uri")
    if (tryContentResolverDelete(cr, uri, "media_store_resolver")) {
        return CatRecMediaDeleteResult.DELETED
    }
    return if (tryDeleteCatRecPublicFile(cr, uri)) {
        Log.d(TAG, "MediaStore: public CatRec file delete ok uri=$uri")
        CatRecMediaDeleteResult.DELETED
    } else {
        Log.d(TAG, "MediaStore: silent delete failed (may need createDeleteRequest) uri=$uri")
        CatRecMediaDeleteResult.FAILED
    }
}

private fun tryContentResolverDelete(
    cr: ContentResolver,
    uri: Uri,
    logSuffix: String,
): Boolean =
    try {
        val n = cr.delete(uri, null, null)
        if (n > 0) {
            Log.d(TAG, "ContentResolver.delete ok ($logSuffix) rows=$n uri=$uri")
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.d(TAG, "ContentResolver.delete threw ($logSuffix) uri=$uri msg=${e.message}")
        false
    }

/** `content://media/...` image/video/audio entries (not DocumentsContract picker URIs). */
private fun isMediaStoreStyleContentUri(uri: Uri): Boolean {
    if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
    val auth = uri.authority ?: return false
    if (auth == MediaStore.AUTHORITY) return true
    val path = uri.path ?: return false
    return path.contains("/video/media/") ||
        path.contains("/images/media/") ||
        path.contains("/audio/media/")
}

@RequiresApi(Build.VERSION_CODES.R)
fun createDeleteRequestPendingIntent(
    context: Context,
    uris: Collection<Uri>,
): PendingIntent? {
    if (uris.isEmpty()) return null
    return try {
        MediaStore.createDeleteRequest(context.contentResolver, uris.toList())
    } catch (e: Throwable) {
        Log.d(TAG, "createDeleteRequest failed: ${e.message}")
        null
    }
}

private data class MediaPathHints(
    val displayName: String?,
    val relativePath: String?,
    @Suppress("DEPRECATION") val legacyDataPath: String?,
)

private fun queryMediaPathHints(
    cr: ContentResolver,
    uri: Uri,
): MediaPathHints? {
    val projection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        } else {
            arrayOf(OpenableColumns.DISPLAY_NAME, "_data")
        }
    return try {
        cr.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val nameIdxOpen = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val nameIdxMedia = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val name =
                when {
                    nameIdxMedia >= 0 -> c.getString(nameIdxMedia)
                    nameIdxOpen >= 0 -> c.getString(nameIdxOpen)
                    else -> null
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val rel = if (relIdx >= 0) c.getString(relIdx) else null
                return MediaPathHints(name, rel, null)
            }
            val dataIdx = c.getColumnIndex("_data")
            val data = if (dataIdx >= 0) c.getString(dataIdx) else null
            MediaPathHints(name, null, data)
        }
    } catch (_: Throwable) {
        null
    }
}

private fun normalizedRelPath(rel: String): String = rel.trim().trimEnd('/', '\\').replace('\\', '/').lowercase()

private fun relMatchesMoviesCatRec(rel: String): Boolean {
    val n = normalizedRelPath(rel)
    return n == "movies/catrec" || n.startsWith("movies/catrec/")
}

private fun relMatchesScreenshots(rel: String): Boolean {
    val n = normalizedRelPath(rel)
    return n.contains("catrec") && n.contains("screenshots")
}

private fun tryDeleteCatRecPublicFile(
    cr: ContentResolver,
    uri: Uri,
): Boolean {
    val hints = queryMediaPathHints(cr, uri) ?: return false
    val name = hints.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return false

    val candidates = LinkedHashSet<File>()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val path = hints.legacyDataPath
        if (!path.isNullOrEmpty()) candidates.add(File(path))
    } else {
        val rel = hints.relativePath
        if (!rel.isNullOrEmpty()) {
            if (relMatchesMoviesCatRec(rel)) {
                candidates.add(
                    File(
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CatRec"),
                        name,
                    ),
                )
            }
            if (relMatchesScreenshots(rel)) {
                candidates.add(
                    File(
                        File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "CatRec${File.separator}Screenshots",
                        ),
                        name,
                    ),
                )
            }
        }
    }

    candidates.add(
        File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CatRec"),
            name,
        ),
    )
    candidates.add(
        File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "CatRec${File.separator}Screenshots",
            ),
            name,
        ),
    )

    for (f in candidates) {
        try {
            if (f.exists() && f.isFile && f.delete()) {
                try {
                    cr.delete(uri, null, null)
                } catch (_: Throwable) {
                }
                return true
            }
        } catch (_: Throwable) {
        }
    }
    return false
}
