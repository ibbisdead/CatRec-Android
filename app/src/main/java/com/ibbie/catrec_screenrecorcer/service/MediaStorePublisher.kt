package com.ibbie.catrec_screenrecorcer.service

import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.utils.MediaStorePublishDiagnostics
import java.io.File
import java.io.FileInputStream

internal class MediaStorePublisher(
    private val context: Context,
) {
    fun createRecordingVideoUri(
        fileName: String,
        saveLocationUri: String?,
    ): Uri? =
        if (!saveLocationUri.isNullOrEmpty()) {
            createSafFileUri(saveLocationUri, "video/mp4", fileName, "recording")
        } else {
            try {
                val contentValues =
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "CatRec")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "MediaStore insert failed", e)
                null
            }
        }

    fun createSeparateMicAudioUri(
        fileName: String,
        saveLocationUri: String?,
    ): Uri? =
        if (!saveLocationUri.isNullOrEmpty()) {
            createSafFileUri(saveLocationUri, "audio/mp4", fileName, "mic")
        } else {
            // Audio MediaStore only allows certain primary directories (Alarms, Music, Recordings,
            // Ringtones, etc.). Movies/ throws IllegalArgumentException on Android 10+.
            // We try Music/CatRec first, then Recordings/CatRec (API 31+) as an alternative.
            fun buildAudioContentValues(relPath: String?): ContentValues =
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    if (Build.VERSION.SDK_INT >= 29 && relPath != null) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                }
            try {
                val musicPath =
                    if (Build.VERSION.SDK_INT >= 29) {
                        Environment.DIRECTORY_MUSIC + File.separator + "CatRec"
                    } else {
                        null
                    }

                var uri: Uri? = null
                try {
                    uri =
                        context.contentResolver.insert(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            buildAudioContentValues(musicPath),
                        )
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Music/CatRec mic insert failed, trying Recordings/CatRec", e)
                }

                // Fallback to Recordings/CatRec (API 31+, explicitly allowed by AOSP)
                if (uri == null && Build.VERSION.SDK_INT >= 31) {
                    try {
                        val recPath = Environment.DIRECTORY_RECORDINGS + File.separator + "CatRec"
                        uri =
                            context.contentResolver.insert(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                buildAudioContentValues(recPath),
                            )
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Recordings/CatRec mic insert failed", e)
                    }
                }

                uri
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Mic MediaStore insert failed", e)
                null
            }
        }

    fun createClipVideoUri(
        fileName: String,
        saveLocationUri: String?,
    ): Uri? {
        if (!saveLocationUri.isNullOrEmpty()) {
            return createSafFileUri(saveLocationUri, "video/mp4", fileName, "clip")
        }
        val contentValues =
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + File.separator + "CatRec",
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    private fun createSafFileUri(
        saveLocationUri: String,
        mimeType: String,
        fileName: String,
        label: String,
    ): Uri? =
        try {
            val treeUri = saveLocationUri.toUri()
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            val file = dir?.createFile(mimeType, fileName)
            val uri = file?.uri
            when {
                file == null || uri == null -> {
                    Log.w(LOG_TAG, "SAF $label destination unavailable")
                    null
                }
                uri == treeUri ||
                    (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) ||
                    file.isDirectory -> {
                    Log.w(LOG_TAG, "SAF $label destination was not a file document")
                    null
                }
                else -> uri
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "SAF $label destination failed", e)
            null
        }

    fun commitTempFileToUri(
        source: File,
        destUri: Uri,
        diagLabel: String = "commit",
    ): Boolean {
        val api = Build.VERSION.SDK_INT
        val tempLen = runCatching { if (source.exists()) source.length() else -1L }.getOrDefault(-1L)
        MediaStorePublishDiagnostics.log(
            diagLabel,
            "api=$api dest=${destUri.toString().take(160)} temp=${source.absolutePath} tempLen=$tempLen",
        )
        val ok =
            try {
                if (!source.exists() || source.length() == 0L) return false
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    FileInputStream(source).use { it.copyTo(out) }
                } ?: return false
                true
            } catch (e: Exception) {
                Log.e(LOG_TAG, "commitTempFileToUri failed", e)
                false
            }
        MediaStorePublishDiagnostics.log(diagLabel, "success=$ok api=$api")
        if (ok && Build.VERSION.SDK_INT >= 29) {
            MediaStorePublishDiagnostics.logPostPublishVideo(
                context.contentResolver,
                destUri,
                expectedMinBytes = source.length().coerceAtLeast(1L),
                api = api,
            )
        }
        return ok
    }

    fun finalizeVideoUri(uri: Uri): Boolean {
        if (isSafDocumentUri(uri)) {
            MediaStorePublishDiagnostics.log("finalize_video", "saf_document no_pending_update uri=$uri")
            return true
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context.applicationContext, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                val values =
                    ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        if (durationMs != null && durationMs > 0) put(MediaStore.Video.Media.DURATION, durationMs)
                    }
                val n = context.contentResolver.update(uri, values, null, null)
                val ok = n > 0
                MediaStorePublishDiagnostics.log("finalize_video", "api=${Build.VERSION.SDK_INT} uri=$uri rows=$n ok=$ok")
                ok
            } catch (e: Exception) {
                Log.e(LOG_TAG, "MediaStore finalize failed", e)
                val n =
                    runCatching {
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    }.getOrDefault(0)
                MediaStorePublishDiagnostics.log("finalize_video", "fallback_clear_pending rows=$n")
                n > 0
            }
        } else {
            return try {
                val path = uri.path
                if (path != null) {
                    MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), arrayOf("video/mp4"), null)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun finalizeAudioUri(uri: Uri): Boolean {
        if (isSafDocumentUri(uri)) {
            MediaStorePublishDiagnostics.log("finalize_audio", "saf_document no_pending_update uri=$uri")
            return true
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context.applicationContext, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                val values =
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                        if (durationMs != null && durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                    }
                val n = context.contentResolver.update(uri, values, null, null)
                val ok = n > 0
                MediaStorePublishDiagnostics.log("finalize_audio", "api=${Build.VERSION.SDK_INT} uri=$uri rows=$n ok=$ok")
                ok
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Audio MediaStore finalize failed", e)
                val n =
                    runCatching {
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    }.getOrDefault(0)
                n > 0
            }
        } else {
            return try {
                val path = uri.path
                if (path != null) {
                    MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), arrayOf("audio/mp4"), null)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isSafDocumentUri(uri: Uri): Boolean =
        ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true) &&
            DocumentsContract.isDocumentUri(context, uri) &&
            uri.authority != MediaStore.AUTHORITY

    fun saveScreenshotBitmap(
        bitmap: Bitmap,
        screenshotFormat: String,
        screenshotQuality: Int,
    ): Uri? {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val (extension, mimeType) =
            when (screenshotFormat) {
                "PNG" -> Pair("png", "image/png")
                "WebP" -> Pair("webp", "image/webp")
                else -> Pair("jpg", "image/jpeg")
            }
        val compressFormat =
            when (screenshotFormat) {
                "PNG" -> Bitmap.CompressFormat.PNG
                "WebP" ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                else -> Bitmap.CompressFormat.JPEG
            }
        val fileName = "Screenshot_$timestamp.$extension"

        val contentValues =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + File.separator + "CatRec" + File.separator + "Screenshots",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

        var savedUri: Uri? = null
        try {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                var compressedOk = false
                val streamOk =
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        compressedOk = bitmap.compress(compressFormat, screenshotQuality, out)
                        true
                    } == true
                if (!streamOk || !compressedOk) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                } else if (Build.VERSION.SDK_INT >= 29) {
                    val n =
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    if (n <= 0) {
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (_: Exception) {
                        }
                    } else {
                        savedUri = uri
                        RecordingState.onScreenshotSaved()
                        MediaStorePublishDiagnostics.log(
                            "screenshot",
                            "api=${Build.VERSION.SDK_INT} finalize_rows=$n",
                        )
                    }
                } else {
                    savedUri = uri
                    RecordingState.onScreenshotSaved()
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Screenshot save failed", e)
        } finally {
            bitmap.recycle()
        }
        return savedUri
    }

    private companion object {
        private const val LOG_TAG = "ScreenRecordService"
    }
}
