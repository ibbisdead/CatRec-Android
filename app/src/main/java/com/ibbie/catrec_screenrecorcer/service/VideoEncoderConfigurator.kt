package com.ibbie.catrec_screenrecorcer.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlin.math.roundToInt

/**
 * Shared screen-capture video encoder setup: tiered [MediaCodec.configure] attempts,
 * optional HEVC-first then **automatic AVC** fallback when HEVC is unavailable at configure time.
 *
 * [MediaCodec.start] retry for codec failure after configure is handled in the engine so
 * encoder start order relative to muxer/audio stays unchanged.
 */
internal data class ConfiguredVideoEncoder(
    val codec: MediaCodec,
    val inputSurface: Surface,
    val mime: String,
    /** Dimensions passed to [MediaCodec.configure] and the encoder input surface (may differ from the request after hardware fallback). */
    val encodedWidth: Int,
    val encodedHeight: Int,
)

internal object VideoEncoderConfigurator {
    private const val TAG = "VideoEncCfg"

    private fun resolveSupportedEncoderSize(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val align = VideoEncoderDimensionMath::alignCeil16
        val primary = align(width) to align(height)
        if (primary.first > 0 &&
            primary.second > 0 &&
            videoCaps.isSizeSupported(primary.first, primary.second)
        ) {
            return primary
        }

        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val aspect = safeW.toFloat() / safeH.toFloat()
        val ladderHeights = listOf(2160, 1440, 1080, 720, 540, 480, 360)
        val standardBoxes =
            listOf(
                1920 to 1080,
                1280 to 720,
                854 to 480,
                640 to 360,
            )

        val candidates = ArrayList<Pair<Int, Int>>(24)
        candidates.add(primary)
        for (th in ladderHeights) {
            val h = align(th)
            val w = align((h * aspect).roundToInt().coerceAtLeast(1))
            candidates.add(w to h)
            candidates.add(h to w)
        }
        for ((bw, bh) in standardBoxes) {
            val w = align(bw)
            val h = align(bh)
            candidates.add(w to h)
            candidates.add(h to w)
        }

        for ((w, h) in candidates.distinct()) {
            if (w > 0 && h > 0 && videoCaps.isSizeSupported(w, h)) return w to h
        }

        // Uniform scale (aspect-preserving); avoids independent W/H shrink which warps aspect ratio.
        var factor = 0.9f
        repeat(40) {
            val (w, h) =
                VideoEncoderDimensionMath.scalePreservingAspectCeil16(
                    width,
                    height,
                    factor,
                    minEdgePx = 160,
                )
            if (w > 0 && h > 0 && videoCaps.isSizeSupported(w, h)) return w to h
            factor *= 0.9f
            if (factor < 0.04f) return@repeat
        }
        return primary
    }

    private fun logConfigureFailureToCrashlytics(
        mimeType: String,
        codecInfo: MediaCodecInfo?,
        format: MediaFormat,
    ) {
        try {
            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey("video_configure_codec_name", codecInfo?.name ?: "unknown")
            crash.setCustomKey("video_configure_mime", mimeType)
            val formatDump = format.toString()
            crash.setCustomKey("video_configure_media_format", formatDump.take(1024))
            crash.log("MediaCodec.configure failure MediaFormat=$formatDump")
        } catch (_: Exception) {
        }
    }

    private fun isEmulator(): Boolean =
        Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.MODEL.contains("google_sdk", ignoreCase = true)

    private data class ConfigureAttempt(
        val withProfile: Boolean,
        val withAdvancedHints: Boolean,
        val codecName: String?,
    )

    private fun buildConfigFormat(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        withProfile: Boolean,
        withAdvancedHints: Boolean,
    ): MediaFormat =
        MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (withAdvancedHints) {
                setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    setInteger("max-bitrate", bitrate)
                }
            }
            if (withProfile) {
                if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    )
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel41,
                    )
                } else if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                    true
                ) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                    )
                }
            }
        }

    private fun openEncoderInstance(
        mimeType: String,
        byCodecName: String?,
        logTag: String,
    ): MediaCodec =
        try {
            if (byCodecName != null) {
                MediaCodec.createByCodecName(byCodecName)
            } else {
                MediaCodec.createEncoderByType(mimeType)
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "[$logTag] Named encoder create failed, using type fallback: ${e.message}",
            )
            MediaCodec.createEncoderByType(mimeType)
        }

    private fun buildAttempts(resolvedEncoderName: String?): List<ConfigureAttempt> {
        val formatTiers =
            listOf(
                Pair(true, true),
                Pair(false, true),
                Pair(false, false),
            )
        return buildList {
            if (resolvedEncoderName != null) {
                for ((wp, adv) in formatTiers) {
                    add(ConfigureAttempt(wp, adv, resolvedEncoderName))
                }
                for ((wp, adv) in formatTiers) {
                    add(ConfigureAttempt(wp, adv, null))
                }
            } else {
                for ((wp, adv) in formatTiers) {
                    add(ConfigureAttempt(wp, adv, null))
                }
            }
        }
    }

    /**
     * @param avcOnly when true, skips HEVC entirely (e.g. after [MediaCodec.start] failed on HEVC).
     */
    fun configureScreenCaptureVideoEncoder(
        logTag: String,
        userEncoderType: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        avcOnly: Boolean,
    ): ConfiguredVideoEncoder {
        val wantHevcFirst =
            !avcOnly &&
                userEncoderType == "H.265 (HEVC)" &&
                !isEmulator()

        val mimeCandidates =
            if (wantHevcFirst) {
                listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            } else {
                listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
            }

        var lastError: Exception? = null
        for ((mimeIdx, mimeType) in mimeCandidates.withIndex()) {
            val resolvedName = VideoEncoderResolver.resolveVideoEncoderName(mimeType, width, height, fps)
            Log.d(
                TAG,
                "[$logTag] mime candidate ${mimeIdx + 1}/${mimeCandidates.size}: $mimeType " +
                    "resolvedEncoder=$resolvedName brand=${Build.BRAND} model=${Build.MODEL}",
            )

            val attempts = buildAttempts(resolvedName)
            var codec: MediaCodec? = null
            var surface: Surface? = null
            for ((tierIdx, attempt) in attempts.withIndex()) {
                try {
                    try {
                        codec?.release()
                    } catch (_: Exception) {
                    }
                    try {
                        surface?.release()
                    } catch (_: Exception) {
                    }

                    codec = openEncoderInstance(mimeType, attempt.codecName, logTag)
                    val codecInfo = VideoEncoderResolver.findEncoderInfo(codec.name)
                    val videoCaps =
                        try {
                            codecInfo?.getCapabilitiesForType(mimeType)?.videoCapabilities
                        } catch (_: Throwable) {
                            null
                        }
                    val (encW, encH) =
                        if (videoCaps != null) {
                            resolveSupportedEncoderSize(videoCaps, width, height)
                        } else {
                            VideoEncoderDimensionMath.alignCeil16(width) to VideoEncoderDimensionMath.alignCeil16(height)
                        }
                    if (encW != VideoEncoderDimensionMath.alignCeil16(width) ||
                        encH != VideoEncoderDimensionMath.alignCeil16(height)
                    ) {
                        Log.w(
                            TAG,
                            "[$logTag] Encoder size adjusted for hardware support " +
                                "request=${width}x${height} -> ${encW}x${encH} mime=$mimeType codec=${codec.name}",
                        )
                    }
                    val format =
                        buildConfigFormat(
                            mimeType,
                            encW,
                            encH,
                            fps,
                            bitrate,
                            attempt.withProfile,
                            attempt.withAdvancedHints,
                        )
                    try {
                        codec.configure(
                            format,
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE,
                        )
                    } catch (e: Exception) {
                        logConfigureFailureToCrashlytics(mimeType, codecInfo, format)
                        throw e
                    }
                    surface = codec.createInputSurface()
                    if (tierIdx > 0) {
                        Log.i(
                            TAG,
                            "[$logTag] Video encoder configured on fallback tier $tierIdx " +
                                "mime=$mimeType profile=${attempt.withProfile} " +
                                "advanced=${attempt.withAdvancedHints} " +
                                "codec=${attempt.codecName ?: "by-type"} " +
                                "brand=${Build.BRAND} model=${Build.MODEL}",
                        )
                    }
                    Log.i(
                        TAG,
                        "[$logTag] Video encoder ready mime=$mimeType tier=$tierIdx size=${encW}x${encH}",
                    )
                    return ConfiguredVideoEncoder(codec, surface, mimeType, encW, encH)
                } catch (e: Exception) {
                    lastError = e
                    Log.w(
                        TAG,
                        "[$logTag] configure tier $tierIdx failed for mime=$mimeType " +
                            "(${e.javaClass.simpleName}): ${e.message} " +
                            "profile=${attempt.withProfile} advanced=${attempt.withAdvancedHints} " +
                            "codec=${attempt.codecName ?: "by-type"}",
                    )
                }
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                surface?.release()
            } catch (_: Exception) {
            }
            val hasMore = mimeIdx < mimeCandidates.lastIndex
            Log.e(
                TAG,
                "[$logTag] All configure tiers failed for mime=$mimeType — " +
                    if (hasMore) "trying next mime fallback" else "no more candidates",
            )
        }
        val err = lastError ?: IllegalStateException("Video encoder configure failed")
        Log.e(TAG, "[$logTag] Video encoder configure failed after all mime candidates", err)
        try {
            FirebaseCrashlytics.getInstance().recordException(err)
        } catch (_: Exception) {
        }
        throw err
    }
}
