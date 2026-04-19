package com.ibbie.catrec_screenrecorcer.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.ibbie.catrec_screenrecorcer.data.GifPaletteDither
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

object GifExportPipeline {
    private const val TAG = "GifExportPipeline"

    /**
     * Converts [mp4Uri] to an animated GIF via FFmpeg using a 2-pass palettegen + paletteuse pipeline.
     * Writes the final output to Pictures/CatRec/GIFs in the gallery.
     * This function is suspendable and will cancel FFmpeg execution if the coroutine is cancelled.
     */
    suspend fun transcodeMp4ToGif(
        context: Context,
        mp4Uri: Uri,
        scaleWidth: Int,
        outputFps: Int,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
        maxColors: Int = 256,
        paletteDither: GifPaletteDither = GifPaletteDither.BAYER_MEDIUM,
    ): Boolean {
        val cacheDir = context.cacheDir
        val inputFile = copyInputToCacheIfNeeded(context, mp4Uri) ?: return false
        val paletteFile = File(cacheDir, "palette_${System.currentTimeMillis()}.png")
        val outFile = File(cacheDir, "gif_enc_${System.currentTimeMillis()}.gif")

        return try {
            val startSec = (startMs.coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.0)
            val fullDurMs = resolveDurationMs(context, mp4Uri)
            val endMsClamped = if (endMs == Long.MAX_VALUE) fullDurMs else endMs.coerceAtMost(fullDurMs)
            val useTrim = endMs != Long.MAX_VALUE || startMs > 0L
            val durationSec =
                if (useTrim) {
                    ((endMsClamped - startMs.coerceAtLeast(0L)).coerceAtLeast(1L)) / 1000.0
                } else {
                    null
                }

            val timeArgs =
                buildString {
                    if (useTrim && durationSec != null) {
                        if (startMs > 0L) {
                            append("-ss ")
                            append(String.format(Locale.US, "%.3f", startSec))
                            append(" ")
                        }
                        append("-t ")
                        append(String.format(Locale.US, "%.3f", durationSec))
                        append(" ")
                    }
                }

            val w = scaleWidth.coerceIn(160, 1920)
            val fps = outputFps.coerceIn(1, 60)
            val colors = maxColors.coerceIn(2, 256)

            // Pass 1: generate palette
            val pass1Filter = "fps=$fps,scale=min(iw\\,$w):-2:flags=lanczos,palettegen=max_colors=$colors:stats_mode=full"
            val pass1Cmd = "-y -i \"${inputFile.absolutePath}\" $timeArgs -vf \"$pass1Filter\" \"${paletteFile.absolutePath}\""

            Log.d(TAG, "Pass 1: $pass1Cmd")
            val pass1Ok = executeFfmpegAsync(pass1Cmd)
            if (!pass1Ok || !paletteFile.exists() || paletteFile.length() == 0L) {
                Log.e(TAG, "Pass 1 failed or palette not generated.")
                return false
            }

            // Pass 2: generate gif
            val paletteUse = paletteUseOptions(paletteDither)
            val pass2Filter = "fps=$fps,scale=min(iw\\,$w):-2:flags=lanczos [x]; [x][1:v] paletteuse=$paletteUse"
            val pass2Cmd = "-y -i \"${inputFile.absolutePath}\" -i \"${paletteFile.absolutePath}\" $timeArgs -lavfi \"$pass2Filter\" -f gif -loop 0 \"${outFile.absolutePath}\""

            Log.d(TAG, "Pass 2: $pass2Cmd")
            val pass2Ok = executeFfmpegAsync(pass2Cmd)
            if (!pass2Ok || !outFile.exists() || outFile.length() == 0L) {
                Log.e(TAG, "Pass 2 failed or gif not generated.")
                return false
            }

            saveGifToGallery(context, outFile)
        } catch (e: Exception) {
            Log.e(TAG, "transcode failed", e)
            false
        } finally {
            try {
                outFile.delete()
            } catch (_: Exception) {
            }
            try {
                paletteFile.delete()
            } catch (_: Exception) {
            }
            if (inputFile.absolutePath.startsWith(context.cacheDir.absolutePath) &&
                inputFile.name.startsWith("ffmpeg_gif_in_")
            ) {
                try {
                    inputFile.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun executeFfmpegAsync(command: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val session =
                FFmpegKit.executeAsync(command, { session ->
                    val rc = session.returnCode
                    val ok = rc != null && ReturnCode.isSuccess(rc)
                    if (!ok) {
                        Log.e(TAG, "FFmpeg failed: ${session.failStackTrace ?: session.output}")
                    }
                    if (cont.isActive) {
                        cont.resume(ok)
                    }
                }, { log -> }, { stat -> })

            cont.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }

    private fun paletteUseOptions(dither: GifPaletteDither): String =
        when (dither) {
            GifPaletteDither.BAYER_LIGHT ->
                "dither=bayer:bayer_scale=2:diff_mode=rectangle"
            GifPaletteDither.BAYER_MEDIUM ->
                "dither=bayer:bayer_scale=4:diff_mode=rectangle"
            GifPaletteDither.FLOYD_STEINBERG ->
                "dither=floyd_steinberg:diff_mode=rectangle"
        }

    private fun copyInputToCacheIfNeeded(
        context: Context,
        uri: Uri,
    ): File? {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            return f.takeIf { it.exists() && it.canRead() }
        }
        val out = File(context.cacheDir, "ffmpeg_gif_in_${System.currentTimeMillis()}.mp4")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            if (!out.exists() || out.length() == 0L) null else out
        } catch (e: Exception) {
            Log.e(TAG, "copy input failed", e)
            try {
                out.delete()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun resolveDurationMs(
        context: Context,
        uri: Uri,
    ): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val dur =
                retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            dur.coerceAtLeast(1L)
        } catch (_: Exception) {
            1L
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    retriever.release()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveGifToGallery(
        context: Context,
        gifFile: File,
    ): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "GIF_$timestamp.gif"
        val cv =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + File.separator + "CatRec" + File.separator + "GIFs",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
        return try {
            val resolver = context.contentResolver
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(gifFile).use { it.copyTo(out) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= 29) {
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
