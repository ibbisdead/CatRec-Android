package com.ibbie.catrec_screenrecorcer.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

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
)

internal object VideoEncoderConfigurator {
    private const val TAG = "VideoEncCfg"

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
                    val format =
                        buildConfigFormat(
                            mimeType,
                            width,
                            height,
                            fps,
                            bitrate,
                            attempt.withProfile,
                            attempt.withAdvancedHints,
                        )
                    codec.configure(
                        format,
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
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
                        "[$logTag] Video encoder ready mime=$mimeType tier=$tierIdx",
                    )
                    return ConfiguredVideoEncoder(codec, surface, mimeType)
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
        throw err
    }
}
