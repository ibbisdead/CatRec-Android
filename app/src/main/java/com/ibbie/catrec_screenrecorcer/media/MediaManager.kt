package com.ibbie.catrec_screenrecorcer.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.ui.recordings.RecordingEntry
import com.ibbie.catrec_screenrecorcer.ui.recordings.ensureCatRecMediaIndexed
import com.ibbie.catrec_screenrecorcer.ui.recordings.loadAppRecordings
import com.ibbie.catrec_screenrecorcer.utils.CatRecMediaDeleteResult
import com.ibbie.catrec_screenrecorcer.utils.trySilentDeleteMediaDetailed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MediaManager"

enum class MediaDeleteResult {
    DELETED,
    FAILED,
}

/**
 * Stateless entry points for loading and deleting CatRec media. Queries and paths match
 * previous [ScreenshotsScreen] / [loadAppRecordings] behavior; deletion delegates to [trySilentDeleteMediaDetailed].
 */
object MediaManager {

    suspend fun loadScreenshots(context: Context): List<MediaItem> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "loadScreenshots start")
            val list = queryScreenshots(context)
            Log.d(TAG, "loadScreenshots done count=${list.size}")
            list
        }

    suspend fun loadRecordings(context: Context): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val saveUri = SettingsRepository(context).saveLocationUri.first()
            Log.d(TAG, "loadRecordings start")
            val entries = loadAppRecordings(context, saveUri)
            val list = entries.map { it.toMediaItem() }
            Log.d(TAG, "loadRecordings done count=${list.size}")
            list
        }

    suspend fun delete(
        context: Context,
        mediaItem: MediaItem,
    ): MediaDeleteResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "delete type=${mediaItem.type} uri=${mediaItem.uri}")
            val result =
                when (trySilentDeleteMediaDetailed(context, mediaItem.uri)) {
                    CatRecMediaDeleteResult.DELETED -> MediaDeleteResult.DELETED
                    CatRecMediaDeleteResult.FAILED -> MediaDeleteResult.FAILED
                }
            Log.d(TAG, "delete result=$result uri=${mediaItem.uri}")
            result
        }
}

private fun RecordingEntry.toMediaItem(): MediaItem =
    MediaItem(
        uri = uri,
        type = MediaType.VIDEO,
        name = displayName,
        duration = durationMs,
        aspectRatio = null,
        sizeBytes = sizeBytes,
        dateModifiedMs = dateMs,
        hasSeparateAudio = hasSeparateAudio,
    )

private const val FALLBACK_SCREENSHOT_ASPECT_RATIO = 9f / 16f

private fun aspectRatioFromImageMetadata(
    width: Int,
    height: Int,
    orientationDegrees: Int,
): Float? {
    if (width <= 0 || height <= 0) return null
    val norm = ((orientationDegrees % 360) + 360) % 360
    val (dw, dh) =
        when (norm) {
            90, 270 -> height to width
            else -> width to height
        }
    if (dw <= 0 || dh <= 0) return null
    return (dw.toFloat() / dh.toFloat()).coerceIn(0.15f, 6f)
}

private fun readPositiveInt(
    cursor: Cursor,
    columnIndex: Int,
): Int {
    if (columnIndex < 0) return 0
    return try {
        when (cursor.getType(columnIndex)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getInt(columnIndex).coerceAtLeast(0)
            Cursor.FIELD_TYPE_NULL -> 0
            else -> cursor.getLong(columnIndex).toInt().coerceAtLeast(0)
        }
    } catch (_: Exception) {
        0
    }
}

private fun queryScreenshots(context: Context): List<MediaItem> {
    ensureCatRecMediaIndexed(context)

    val collections =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                MediaStore.getExternalVolumeNames(context).map { vol ->
                    MediaStore.Images.Media.getContentUri(vol)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            }
            else -> listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }

    val projection =
        arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION,
        )

    val (selection, selectionArgs) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val base = "${Environment.DIRECTORY_PICTURES}/CatRec/Screenshots"
            val sel =
                "(" +
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" +
                    ") AND ${MediaStore.Images.Media.IS_PENDING} = 0"
            val args = arrayOf("$base/%", base, "$base/", "%CatRec%Screenshots%")
            sel to args
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%CatRec%Screenshots%")
        }

    val byUri = LinkedHashMap<String, MediaItem>()
    for (collection in collections) {
        try {
            context.contentResolver
                .query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val orientCol = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)
                        val key = uri.toString()
                        if (byUri.containsKey(key)) continue
                        val name = cursor.getString(nameCol) ?: "Screenshot"
                        val dateMs = cursor.getLong(dateCol) * 1000L
                        val sizeBytes = cursor.getLong(sizeCol)
                        val w = readPositiveInt(cursor, widthCol)
                        val h = readPositiveInt(cursor, heightCol)
                        val orient = if (orientCol >= 0) cursor.getInt(orientCol) else 0
                        val aspect = aspectRatioFromImageMetadata(w, h, orient)
                        byUri[key] =
                            MediaItem(
                                uri = uri,
                                type = MediaType.IMAGE,
                                name = name,
                                duration = null,
                                aspectRatio = aspect,
                                sizeBytes = sizeBytes,
                                dateModifiedMs = dateMs,
                            )
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load screenshots from $collection", e)
        }
    }

    return byUri.values.sortedByDescending { it.name ?: "" }
}

/** Formatted date label for screenshot grid (same pattern as before MediaManager). */
fun MediaItem.screenshotDateLabel(): String {
    val ms = dateModifiedMs ?: return ""
    return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(ms))
}

fun MediaItem.screenshotSizeKb(): Long = (sizeBytes ?: 0L) / 1024L

/** Aspect for thumbnail cell; matches prior [FALLBACK_SCREENSHOT_ASPECT_RATIO] behavior. */
fun MediaItem.screenshotDisplayAspect(): Float = aspectRatio ?: FALLBACK_SCREENSHOT_ASPECT_RATIO
