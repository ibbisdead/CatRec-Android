package com.ibbie.catrec_screenrecorcer.ui.recordings

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

fun loadAppRecordings(context: Context, saveLocationUri: String?): List<RecordingEntry> {
    val results = mutableListOf<RecordingEntry>()
    val micTimestamps = loadMicFileTimestamps(context)

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DURATION,
    )

    val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0" to
            arrayOf("${Environment.DIRECTORY_MOVIES}/CatRec/%")
    } else {
        "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/Movies/CatRec/%")
    }

    try {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
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
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val ts = extractTimestampFromVideoName(name)
                results.add(
                    RecordingEntry(
                        uri = uri,
                        displayName = name,
                        sizeBytes = cursor.getLong(sizeCol),
                        dateMs = cursor.getLong(dateCol) * 1000L,
                        durationMs = cursor.getLong(durCol),
                        hasSeparateAudio = ts != null && micTimestamps.contains(ts),
                    ),
                )
            }
        }
    } catch (e: Exception) {
        Log.e("AppRecordingsLoader", "MediaStore query failed", e)
    }

    if (!saveLocationUri.isNullOrEmpty()) {
        try {
            val existingNames = results.map { it.displayName }.toSet()
            val safDir = DocumentFile.fromTreeUri(context, Uri.parse(saveLocationUri))
            val safMicNames = safDir?.listFiles()
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
    val base = name.removeSuffix(".mp4")
        .removePrefix("CatRec_")
        .removePrefix("Record_")
    return if (base.matches(Regex("\\d{8}_\\d{6}"))) base else null
}

private fun loadMicFileTimestamps(context: Context): Set<String> {
    val timestamps = mutableSetOf<String>()
    val audioProjection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
    try {
        val (audioSel, audioArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val paths = buildList {
                add("${Environment.DIRECTORY_MUSIC}${File.separator}CatRec${File.separator}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add("${Environment.DIRECTORY_RECORDINGS}${File.separator}CatRec${File.separator}")
                }
            }
            val placeholders = paths.joinToString(" OR ") {
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            }
            "($placeholders) AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0" to
                (paths.map { "$it%" } + listOf("Mic_%")).toTypedArray()
        } else {
            "${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?" to
                arrayOf("%/CatRec/%Mic_%.m4a", "Mic_%")
        }

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            audioProjection, audioSel, audioArgs, null,
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val micName = cursor.getString(nameCol) ?: continue
                val ts = micName.removePrefix("Mic_").removeSuffix(".m4a")
                if (ts.matches(Regex("\\d{8}_\\d{6}"))) timestamps.add(ts)
            }
        }
    } catch (e: Exception) {
        Log.e("AppRecordingsLoader", "Mic timestamps query failed", e)
    }

    return timestamps
}
