package com.ibbie.catrec_screenrecorcer.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ibbie.catrec_screenrecorcer.gifencode.AnimatedGifEncoder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GifTranscodeHelper {
    private const val TAG = "GifTranscode"

    /**
     * Samples frames from [mp4Uri] with [MediaMetadataRetriever], encodes an animated GIF with
     * [AnimatedGifEncoder] (pure Java), writes to Pictures/CatRec/GIFs.
     * (FFmpeg Kit binaries are no longer on Maven Central as of 2025; this path avoids native FFmpeg.)
     */
    fun transcodeMp4ToGif(
        context: Context,
        mp4Uri: Uri,
        scaleWidth: Int,
        outputFps: Int,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
    ): Boolean {
        val sw = scaleWidth.coerceIn(160, 1280)
        val fps = outputFps.coerceIn(1, 30)
        val delayMs = (1000 / fps).coerceAtLeast(1)
        val frameStepUs = 1_000_000L / fps

        val outFile = File(context.cacheDir, "gif_enc_${System.currentTimeMillis()}.gif")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, mp4Uri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0L) return false

            val startUs = startMs.coerceAtLeast(0L) * 1000L
            val endUsExclusive = (if (endMs == Long.MAX_VALUE) durMs else endMs.coerceAtMost(durMs)) * 1000L
            if (endUsExclusive <= startUs) return false
            val spanUs = endUsExclusive - startUs

            FileOutputStream(outFile).use { fos ->
                val enc = AnimatedGifEncoder()
                enc.start(fos)
                enc.setRepeat(0)
                enc.setDelay(delayMs)

                var tUs = startUs
                val maxFrames = ((spanUs / frameStepUs).toInt() + 2).coerceAtMost(4500)

                var count = 0
                while (tUs < endUsExclusive && count < maxFrames) {
                    val frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val h = (sw * frame.height / frame.width.toFloat()).toInt().coerceAtLeast(1)
                        val bmp = if (frame.width == sw && frame.height == h) {
                            frame
                        } else {
                            val scaled = Bitmap.createScaledBitmap(frame, sw, h, true)
                            if (scaled != frame) frame.recycle()
                            scaled
                        }
                        enc.addFrame(bmp)
                        count++
                    }
                    tUs += frameStepUs
                }
                enc.finish()
            }

            if (!outFile.exists() || outFile.length() == 0L) return false
            return saveGifToGallery(context, outFile)
        } catch (e: Exception) {
            Log.e(TAG, "transcode failed", e)
            return false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
            try {
                outFile.delete()
            } catch (_: Exception) {}
        }
    }

    private fun saveGifToGallery(context: Context, gifFile: File): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "GIF_$timestamp.gif"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "CatRec" + File.separator + "GIFs",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return try {
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(gifFile).use { it.copyTo(out) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveGifToGallery failed", e)
            false
        }
    }
}
