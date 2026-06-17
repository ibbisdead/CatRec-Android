package com.ibbie.catrec_screenrecorcer.service

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.ibbie.catrec_screenrecorcer.data.ColorMode
import com.ibbie.catrec_screenrecorcer.data.Rec709CompatBrightnessCorrection
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

object Rec709MetadataRepair {
    private const val TAG = "Rec709MetadataRepair"

    suspend fun repairIfNeeded(
        context: Context,
        inputFile: File,
        colorMode: String,
        forceRec709Compatibility: Boolean,
        brightnessCorrection: String = Rec709CompatBrightnessCorrection.OFF,
    ): File {
        if (colorMode != ColorMode.STANDARD || !forceRec709Compatibility) return inputFile
        if (!inputFile.exists() || inputFile.length() == 0L) return inputFile

        val videoMime = resolveVideoMime(inputFile)
        val bitstreamFilter =
            bitstreamFilterFor(videoMime) ?: run {
                Log.w(TAG, "Rec.709 compatibility: unsupported video mime for metadata repair: $videoMime")
                return inputFile
            }

        val repairedFile =
            repairMetadata(
                context = context,
                inputFile = inputFile,
                bitstreamFilter = bitstreamFilter,
                suffix = "rec709",
            )
        if (repairedFile == null) {
            Log.e(
                TAG,
                "Rec.709 compatibility: FFmpeg metadata repair failed; falling back to original MP4",
            )
            return inputFile
        }

        return applyBrightnessCorrectionIfNeeded(
            context = context,
            inputFile = repairedFile,
            fallbackFile = repairedFile,
            sourceVideoMime = videoMime,
            bitstreamFilter = bitstreamFilter,
            brightnessCorrection = brightnessCorrection,
        )
    }

    private suspend fun repairMetadata(
        context: Context,
        inputFile: File,
        bitstreamFilter: String,
        suffix: String,
    ): File? {
        val repairedFile =
            File(
                inputFile.parentFile ?: context.cacheDir,
                "${inputFile.nameWithoutExtension}_${suffix}_${System.currentTimeMillis()}.mp4",
            )

        Log.i(TAG, "Rec.709 compatibility: repairing video range metadata via FFmpeg stream copy")
        val command =
            "-y -i ${quote(inputFile.absolutePath)} -map 0 -map_metadata 0 -map_chapters 0 " +
                "-c copy -bsf:v $bitstreamFilter " +
                "-color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709 " +
                quote(repairedFile.absolutePath)

        val ok = executeFfmpegAsync(command)
        if (!ok || !repairedFile.exists() || repairedFile.length() == 0L) {
            try {
                repairedFile.delete()
            } catch (_: Exception) {
            }
            return null
        }
        return repairedFile
    }

    private suspend fun applyBrightnessCorrectionIfNeeded(
        context: Context,
        inputFile: File,
        fallbackFile: File,
        sourceVideoMime: String?,
        bitstreamFilter: String,
        brightnessCorrection: String,
    ): File {
        val gamma =
            when (Rec709CompatBrightnessCorrection.resolve(brightnessCorrection)) {
                Rec709CompatBrightnessCorrection.LOW -> "1.03"
                Rec709CompatBrightnessCorrection.MEDIUM -> "1.06"
                else -> return fallbackFile
            }
        val videoEncoder =
            when (sourceVideoMime) {
                MediaFormat.MIMETYPE_VIDEO_HEVC -> "libx265"
                else -> "libx264"
            }
        // Warning: this keeps the existing behavior but HEVC gamma correction uses a software
        // encoder in FFmpeg, so it can be significantly slower than the metadata-only passes.
        if (sourceVideoMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            Log.w(TAG, "Rec.709 brightness correction is using software HEVC encode and may be slow")
        }
        val correctedFile =
            File(
                inputFile.parentFile ?: context.cacheDir,
                "${inputFile.nameWithoutExtension}_gamma_${System.currentTimeMillis()}.mp4",
            )

        // Unlike the metadata repair above, this optional advanced correction intentionally
        // re-encodes video so FFmpeg can apply a very mild midtone gamma lift. Audio is stream-copied.
        Log.i(TAG, "Rec.709 compatibility: applying $brightnessCorrection brightness correction (gamma=$gamma)")
        val command =
            "-y -i ${quote(inputFile.absolutePath)} -map 0 -map_metadata 0 -map_chapters 0 " +
                "-vf eq=gamma=$gamma -c:v $videoEncoder -crf 18 -preset veryfast " +
                "-c:a copy -c:s copy -color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709 " +
                quote(correctedFile.absolutePath)

        val ok = executeFfmpegAsync(command)
        if (!ok || !correctedFile.exists() || correctedFile.length() == 0L) {
            Log.e(
                TAG,
                "Rec.709 compatibility: brightness correction failed; falling back to metadata-repaired MP4",
            )
            try {
                correctedFile.delete()
            } catch (_: Exception) {
            }
            return fallbackFile
        }

        val finalRepairedFile =
            repairMetadata(
                context = context,
                inputFile = correctedFile,
                bitstreamFilter = bitstreamFilter,
                suffix = "rec709_final",
            )
        if (finalRepairedFile == null) {
            Log.e(
                TAG,
                "Rec.709 compatibility: final metadata repair after brightness correction failed; " +
                    "falling back to gamma-corrected MP4",
            )
            try {
                inputFile.delete()
            } catch (_: Exception) {
            }
            return correctedFile
        }

        try {
            inputFile.delete()
        } catch (_: Exception) {
        }
        try {
            correctedFile.delete()
        } catch (_: Exception) {
        }
        return finalRepairedFile
    }

    private fun bitstreamFilterFor(videoMime: String?): String? =
        when (videoMime) {
            MediaFormat.MIMETYPE_VIDEO_AVC ->
                "h264_metadata=video_full_range_flag=0:colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1"
            MediaFormat.MIMETYPE_VIDEO_HEVC ->
                "hevc_metadata=video_full_range_flag=0:colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1"
            else -> null
        }

    private fun resolveVideoMime(file: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) return mime
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Rec.709 compatibility: could not inspect video mime", e)
            null
        } finally {
            extractor.release()
        }
    }

    private suspend fun executeFfmpegAsync(command: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val session =
                FFmpegKit.executeAsync(command) { session ->
                    val rc = session.returnCode
                    val ok = rc != null && ReturnCode.isSuccess(rc)
                    if (!ok) {
                        Log.e(TAG, "Rec.709 compatibility FFmpeg failed: ${session.failStackTrace ?: session.output}")
                    }
                    if (cont.isActive) {
                        cont.resume(ok)
                    }
                }

            cont.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""
}
