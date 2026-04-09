package com.ibbie.catrec_screenrecorcer.service

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object EditorVideoTransform {
    private const val TAG = "EditorVideoTransform"

    fun getVideoDisplaySize(context: Context, uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            Pair(w.coerceAtLeast(1), h.coerceAtLeast(1))
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    fun estimateInputVideoBitrateBps(sizeBytes: Long, durationMs: Long): Long {
        if (durationMs <= 0) return 2_500_000L
        val total = sizeBytes * 8L * 1000L / durationMs
        val audioGuess = 128_000L
        return (total - audioGuess).coerceIn(200_000L, 80_000_000L)
    }

    fun bitrateCapForHeight(outHeight: Int): Int = when {
        outHeight >= 1400 -> 10_000_000
        outHeight >= 1000 -> 6_000_000
        outHeight >= 700 -> 3_500_000
        outHeight >= 520 -> 2_000_000
        else -> 1_200_000
    }

    /** qualityTier: 0 = low, 1 = medium, 2 = high */
    fun targetBitrateForQuality(baseBps: Long, qualityTier: Int, outHeight: Int): Int {
        val cap = bitrateCapForHeight(outHeight)
        val factor = when (qualityTier) {
            2 -> 0.88
            1 -> 0.52
            else -> 0.30
        }
        return (baseBps * factor).toInt().coerceIn(400_000, cap)
    }

    fun estimateOutputBytes(videoBps: Int, durationMs: Long, audioBps: Int = 128_000): Long {
        val sec = durationMs.coerceAtLeast(1) / 1000.0
        return ((videoBps + audioBps) * sec / 8).toLong()
    }

    suspend fun compressVideo(
        context: Context,
        inputUri: Uri,
        outputDisplayName: String,
        targetHeight: Int?,
        videoBitrate: Int,
    ): Uri? {
        val cacheOut = File(context.cacheDir, "compress_${System.currentTimeMillis()}.mp4")
        val videoEffects = if (targetHeight != null && targetHeight > 0) {
            listOf(Presentation.createForHeight(targetHeight))
        } else {
            emptyList()
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(listOf(), videoEffects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(videoBitrate)
                    .build(),
            )
            .build()

        // Media3 Transformer requires start() on the main application thread.
        val done = withContext(Dispatchers.Main.immediate) {
            runTransformer(context, encoderFactory) { transformer ->
                transformer.start(edited, cacheOut.absolutePath)
            }
        }

        return withContext(Dispatchers.IO) {
            if (!done || !cacheOut.exists() || cacheOut.length() == 0L) {
                cacheOut.delete()
                return@withContext null
            }

            insertVideoToCatRecMovies(context, outputDisplayName, cacheOut)?.also {
                cacheOut.delete()
            }
        }
    }

    suspend fun mergeVideos(
        context: Context,
        uris: List<Uri>,
        outputDisplayName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (uris.size < 2) return@withContext null
        val cacheInputs = uris.mapIndexed { i, u ->
            val f = File(context.cacheDir, "merge_in_${i}_${System.nanoTime()}.mp4")
            if (!copyUriToFile(context, u, f)) return@withContext null
            f
        }
        val outFile = File(context.cacheDir, "merge_out_${System.currentTimeMillis()}.mp4")
        val merged = try {
            ClipMerger.merge(cacheInputs, outFile)
        } finally {
            cacheInputs.forEach { runCatching { it.delete() } }
        }
        if (merged && outFile.exists() && outFile.length() > 0L) {
            val uri = insertVideoToCatRecMovies(context, outputDisplayName, outFile)
            outFile.delete()
            return@withContext uri
        }
        outFile.delete()

        val editedItems = uris.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val sequence = EditedMediaItemSequence.Builder(editedItems).build()
        val composition: Composition = Composition.Builder(sequence).build()
        val cacheOut2 = File(context.cacheDir, "merge_tc_${System.currentTimeMillis()}.mp4")
        val ok = withContext(Dispatchers.Main.immediate) {
            runTransformer(context, encoderFactory = null) { transformer ->
                transformer.start(composition, cacheOut2.absolutePath)
            }
        }
        if (!ok || !cacheOut2.exists() || cacheOut2.length() == 0L) {
            cacheOut2.delete()
            return@withContext null
        }
        insertVideoToCatRecMovies(context, outputDisplayName, cacheOut2)?.also { cacheOut2.delete() }
    }

    private suspend fun runTransformer(
        context: Context,
        encoderFactory: DefaultEncoderFactory?,
        startBlock: (Transformer) -> Unit,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        fun finish(ok: Boolean) {
            if (finished.compareAndSet(false, true)) {
                cont.resume(ok)
            }
        }
        val builder = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
        if (encoderFactory != null) {
            builder.setEncoderFactory(encoderFactory)
        }
        val transformer = builder
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finish(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        Log.e(TAG, "transformer error", exportException)
                        finish(false)
                    }
                },
            )
            .build()
        cont.invokeOnCancellation { runCatching { transformer.cancel() } }
        try {
            startBlock(transformer)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            finish(false)
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, out: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(out).use { inp.copyTo(it) }
            } ?: return false
            out.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "copyUriToFile", e)
            false
        }
    }

    private fun insertVideoToCatRecMovies(context: Context, displayName: String, file: File): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + File.separator + "CatRec",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "insertVideo", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
