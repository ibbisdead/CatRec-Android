package com.ibbie.catrec_screenrecorcer.ui.recordings

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public video collections to query (primary + SD / other volumes where supported). */
private fun mediaStoreVideoCollections(context: Context): List<Uri> =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            MediaStore.getExternalVolumeNames(context).map { vol ->
                MediaStore.Video.Media.getContentUri(vol)
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            listOf(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        }
        else -> listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }

private fun mediaStoreAudioCollections(context: Context): List<Uri> =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            MediaStore.getExternalVolumeNames(context).map { vol ->
                MediaStore.Audio.Media.getContentUri(vol)
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            listOf(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        }
        else -> listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
    }

/**
 * Re-scan public CatRec folders so MediaStore picks up files after reinstall or side loads.
 * Files in Movies/CatRec (and screenshot folder) may exist on disk but be missing from MediaStore
 * until scanned. Best-effort scan when the process can see the public dirs (works on many devices;
 * no-op if blocked by scoped storage).
 * Waits (on the calling thread, use IO) for scan callbacks so a follow-up query sees new rows.
 */
internal fun ensureCatRecMediaIndexed(context: Context) {
    val app = context.applicationContext
    val paths = mutableListOf<String>()
    val mimes = mutableListOf<String>()
    try {
        val movies = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CatRec")
        if (movies.isDirectory) {
            movies
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".mp4", ignoreCase = true) }
                ?.forEach { f ->
                    paths.add(f.absolutePath)
                    mimes.add("video/mp4")
                }
        }
    } catch (_: Exception) {
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val shots =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "CatRec${File.separator}Screenshots",
                )
            if (shots.isDirectory) {
                shots
                    .listFiles()
                    ?.filter {
                        it.isFile &&
                            (
                                it.name.endsWith(".jpg", ignoreCase = true) ||
                                    it.name.endsWith(".jpeg", ignoreCase = true) ||
                                    it.name.endsWith(".png", ignoreCase = true) ||
                                    it.name.endsWith(".webp", ignoreCase = true)
                            )
                    }?.forEach { f ->
                        paths.add(f.absolutePath)
                        mimes.add(
                            when {
                                f.name.endsWith(".png", ignoreCase = true) -> "image/png"
                                f.name.endsWith(".webp", ignoreCase = true) -> "image/webp"
                                else -> "image/jpeg"
                            },
                        )
                    }
            }
        } catch (_: Exception) {
        }
    }
    if (paths.isEmpty()) return
    val pathArr = paths.toTypedArray()
    val mimeArr = mimes.toTypedArray()
    val latch = CountDownLatch(pathArr.size)
    MediaScannerConnection.scanFile(
        app,
        pathArr,
        mimeArr,
    ) { _, _ ->
        latch.countDown()
    }
    try {
        latch.await(8, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private fun videoPrimarySelection(): Pair<String, Array<String>> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val base = "${Environment.DIRECTORY_MOVIES}/CatRec"
        val sel =
            "(" +
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Video.Media.RELATIVE_PATH} = ? OR " +
                "${MediaStore.Video.Media.RELATIVE_PATH} = ?" +
                ") AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        val args = arrayOf("$base/%", base, "$base/")
        sel to args
    } else {
        "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/Movies/CatRec/%")
    }

/** Catches rows where path encoding differs but files are still under the CatRec bucket. */
private fun videoFallbackSelection(): Pair<String, Array<String>>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val sel =
        "(${MediaStore.Video.Media.IS_PENDING} = 0) AND (" +
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?" +
            ") AND (${MediaStore.Video.Media.MIME_TYPE} LIKE ?) AND (" +
            "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ? OR " +
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" +
            ")"
    val args =
        arrayOf(
            "CatRec_%",
            "Record_%",
            "Clip_%",
            "video/%",
            "CatRec",
            "${Environment.DIRECTORY_MOVIES}/CatRec%",
            "%/CatRec/%",
        )
    return sel to args
}

private fun queryVideosInto(
    context: Context,
    collection: Uri,
    selection: String,
    selectionArgs: Array<String>,
    projection: Array<String>,
    byUri: MutableMap<String, RecordingEntry>,
) {
    try {
        context.contentResolver
            .query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val volumeUri = ContentUris.withAppendedId(collection, id)
                    val key = volumeUri.toString()
                    if (byUri.containsKey(key)) continue
                    byUri[key] =
                        RecordingEntry(
                            uri = volumeUri,
                            displayName = name,
                            sizeBytes = cursor.getLong(sizeCol),
                            dateMs = cursor.getLong(dateCol) * 1000L,
                            durationMs = cursor.getLong(durCol),
                            hasSeparateAudio = false,
                        )
                }
            }
    } catch (e: Exception) {
        Log.e("AppRecordingsLoader", "MediaStore query failed for $collection", e)
    }
}

fun loadAppRecordings(
    context: Context,
    saveLocationUri: String?,
): List<RecordingEntry> {
    ensureCatRecMediaIndexed(context)

    val byUri = LinkedHashMap<String, RecordingEntry>()
    val micTimestamps = loadMicFileTimestamps(context)

    val projection =
        arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
        )

    val collections = mediaStoreVideoCollections(context)
    val (primarySel, primaryArgs) = videoPrimarySelection()
    for (c in collections) {
        queryVideosInto(context, c, primarySel, primaryArgs, projection, byUri)
    }
    videoFallbackSelection()?.let { (fSel, fArgs) ->
        for (c in collections) {
            queryVideosInto(context, c, fSel, fArgs, projection, byUri)
        }
    }

    val results = byUri.values.toMutableList()
    for (i in results.indices) {
        val e = results[i]
        val ts = extractTimestampFromVideoName(e.displayName)
        results[i] =
            e.copy(hasSeparateAudio = ts != null && micTimestamps.contains(ts))
    }

    if (!saveLocationUri.isNullOrEmpty()) {
        try {
            val existingNames = results.map { it.displayName }.toSet()
            val safDir = DocumentFile.fromTreeUri(context, Uri.parse(saveLocationUri))
            val safMicNames =
                safDir
                    ?.listFiles()
                    ?.mapNotNull { it.name }
                    ?.filter { it.startsWith("Mic_") && it.endsWith(".m4a") }
                    ?.toSet() ?: emptySet()
            safDir
                ?.listFiles()
                ?.filter { it.name?.endsWith(".mp4") == true && it.name !in existingNames }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { doc ->
                    val name = doc.name ?: "Unknown"
                    val ts = extractTimestampFromVideoName(name)
                    val expectedMic = if (ts != null) "Mic_$ts.m4a" else null
                    results.add(
                        RecordingEntry(
                            uri = doc.uri,
                            displayName = name,
                            sizeBytes = doc.length(),
                            dateMs = doc.lastModified(),
                            durationMs = 0L,
                            hasSeparateAudio = expectedMic != null && safMicNames.contains(expectedMic),
                        ),
                    )
                }
        } catch (e: Exception) {
            Log.e("AppRecordingsLoader", "SAF query failed", e)
        }
    }

    return results.sortedByDescending { it.dateMs }
}

internal fun extractTimestampFromVideoName(name: String): String? {
    val base =
        name
            .removeSuffix(".mp4")
            .removePrefix("CatRec_")
            .removePrefix("Record_")
    return if (base.matches(Regex("\\d{8}_\\d{6}"))) base else null
}

private fun loadMicFileTimestamps(context: Context): Set<String> {
    val timestamps = mutableSetOf<String>()
    val audioProjection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media._ID)
    val collections = mediaStoreAudioCollections(context)

    val (audioSel, audioArgs) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val paths =
                buildList {
                    add("${Environment.DIRECTORY_MUSIC}${File.separator}CatRec${File.separator}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add("${Environment.DIRECTORY_RECORDINGS}${File.separator}CatRec${File.separator}")
                    }
                }
            val placeholders =
                paths.joinToString(" OR ") {
                    "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                }
            "($placeholders) AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0" to
                (paths.map { "$it%" } + listOf("Mic_%")).toTypedArray()
        } else {
            "${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?" to
                arrayOf("%/CatRec/%Mic_%.m4a", "Mic_%")
        }

    val seenKeys = mutableSetOf<String>()
    for (collection in collections) {
        try {
            context.contentResolver
                .query(
                    collection,
                    audioProjection,
                    audioSel,
                    audioArgs,
                    null,
                )?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        if (!seenKeys.add("$collection|$id")) continue
                        val micName = cursor.getString(nameCol) ?: continue
                        val ts = micName.removePrefix("Mic_").removeSuffix(".m4a")
                        if (ts.matches(Regex("\\d{8}_\\d{6}"))) timestamps.add(ts)
                    }
                }
        } catch (e: Exception) {
            Log.e("AppRecordingsLoader", "Mic timestamps query failed for $collection", e)
        }
    }

    return timestamps
}
