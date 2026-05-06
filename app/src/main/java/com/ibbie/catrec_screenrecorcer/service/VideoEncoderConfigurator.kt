package com.ibbie.catrec_screenrecorcer.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.data.ColorMode
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
    val codecName: String,
    /** Dimensions passed to [MediaCodec.configure] and the encoder input surface (may differ from the request after hardware fallback). */
    val encodedWidth: Int,
    val encodedHeight: Int,
    val fps: Int,
    val bitrate: Int,
    val iFrameIntervalSeconds: Int,
    val profile: Int?,
    val level: Int?,
    val bitrateMode: Int?,
)

internal object VideoEncoderConfigurator {
    private const val TAG = "VideoEncCfg"
    private const val I_FRAME_INTERVAL_SECONDS = 1

    private data class NormalizedVideoConfig(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
    )

    private data class CodecProfileSelection(
        val profile: Int?,
        val level: Int? = null,
    )

    private fun alignFloor(
        value: Int,
        alignment: Int,
    ): Int {
        val safeAlignment = alignment.coerceAtLeast(2)
        val safeValue = value.coerceAtLeast(safeAlignment)
        return (safeValue / safeAlignment) * safeAlignment
    }

    private fun alignForCodec(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
    ): Pair<Int, Int> =
        alignFloor(width, videoCaps.widthAlignment) to alignFloor(height, videoCaps.heightAlignment)

    private fun normalizeFrameRate(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        requestedFps: Int,
        safeStartFallback: Boolean,
    ): Int {
        val target = requestedFps.coerceAtLeast(1).let { if (safeStartFallback) minOf(it, 30) else it }
        fun supports(candidate: Int): Boolean =
            try {
                videoCaps.areSizeAndRateSupported(width, height, candidate.toDouble())
            } catch (_: Throwable) {
                false
            }

        if (supports(target)) return target

        val range =
            try {
                videoCaps.getSupportedFrameRatesFor(width, height)
            } catch (_: Throwable) {
                null
            }
        val upper = range?.upper?.toInt()?.coerceAtLeast(1) ?: target
        val lower = range?.lower?.toInt()?.coerceAtLeast(1) ?: 1
        val capped = target.coerceIn(lower, upper)
        for (candidate in capped downTo lower) {
            if (supports(candidate)) return candidate
        }
        for (candidate in listOf(30, 24, 20, 15)) {
            if (candidate <= target && candidate >= lower && supports(candidate)) {
                return candidate
            }
        }
        throw IllegalArgumentException(
            "No supported frame rate for ${width}x$height requested=$requestedFps target=$target range=$range",
        )
    }

    private fun normalizeBitrate(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        requestedBitrate: Int,
        width: Int,
        height: Int,
        fps: Int,
        safeStartFallback: Boolean,
    ): Int {
        val requested = requestedBitrate.coerceAtLeast(250_000)
        val conservativeCap =
            if (safeStartFallback) {
                val pixelsPerSecond = width.toLong() * height.toLong() * fps.coerceAtLeast(1)
                (pixelsPerSecond * 16L / 100L).coerceIn(4_000_000L, 80_000_000L).toInt()
            } else {
                Int.MAX_VALUE
        }
        val target = minOf(requested, conservativeCap)
        val range = videoCaps.bitrateRange
        return target.coerceIn(range.lower, range.upper)
    }

    private fun resolveSupportedEncoderSize(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        fun alignWidth(value: Int) = alignFloor(value, videoCaps.widthAlignment)
        fun alignHeight(value: Int) = alignFloor(value, videoCaps.heightAlignment)

        val primary = alignForCodec(videoCaps, width, height)
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
        fun addCandidate(
            w: Int,
            h: Int,
        ) {
            if (w > 0 && h > 0 && w <= primary.first && h <= primary.second) {
                candidates.add(w to h)
            }
        }
        addCandidate(primary.first, primary.second)
        for (th in ladderHeights) {
            val h = alignHeight(th)
            val w = alignWidth((h * aspect).roundToInt().coerceAtLeast(1))
            addCandidate(w, h)
            addCandidate(h, w)
        }
        for ((bw, bh) in standardBoxes) {
            val w = alignWidth(bw)
            val h = alignHeight(bh)
            addCandidate(w, h)
            addCandidate(h, w)
        }

        for ((w, h) in candidates.distinct()) {
            if (w > 0 && h > 0 && videoCaps.isSizeSupported(w, h)) return w to h
        }

        // Uniform scale (aspect-preserving); avoids independent W/H shrink which warps aspect ratio.
        var factor = 0.9f
        repeat(40) {
            val (scaledW, scaledH) =
                VideoEncoderDimensionMath.scalePreservingAspectCeil16(
                    width,
                    height,
                    factor,
                    minEdgePx = 160,
                )
            val w = alignWidth(scaledW).coerceAtMost(primary.first)
            val h = alignHeight(scaledH).coerceAtMost(primary.second)
            if (w > 0 && h > 0 && videoCaps.isSizeSupported(w, h)) return w to h
            factor *= 0.9f
            if (factor < 0.04f) return@repeat
        }
        return primary
    }

    private fun normalizeConfigForCodec(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        safeStartFallback: Boolean,
    ): NormalizedVideoConfig {
        val (encW, encH) = resolveSupportedEncoderSize(videoCaps, width, height)
        val encFps = normalizeFrameRate(videoCaps, encW, encH, fps, safeStartFallback)
        val encBitrate = normalizeBitrate(videoCaps, bitrate, encW, encH, encFps, safeStartFallback)
        return NormalizedVideoConfig(encW, encH, encFps, encBitrate)
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

    /**
     * Applies SDR color metadata to [format] so the encoded bitstream carries explicit signal-
     * range and colour-space information.  Without these tags, most hardware encoders silently
     * produce full-range output (0–255 luma) while players assume limited range (16–235),
     * causing a washed-out / low-contrast appearance.
     *
     * Keys are available from API 24 onwards; on older devices the calls are no-ops because the
     * encoder will simply ignore unknown integer keys rather than throwing.
     *
     * [colorMode] "Standard" → Rec.709 limited range (corrects the gray/washed look; default).
     * [colorMode] "Full"     → Rec.709 full range  (use when target player expects full-range).
     */
    private fun applyColorMetadata(format: MediaFormat, colorMode: String) {
        if (Build.VERSION.SDK_INT < 24) return
        try {
            // BT.709 primaries — standard for SDR screen content on every modern display.
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)

            // SDR transfer function — no HDR / PQ / HLG.
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)

            // Range: limited (16–235) corrects the washed-out look; full (0–255) for edge cases.
            val range = if (colorMode == ColorMode.FULL) {
                MediaFormat.COLOR_RANGE_FULL
            } else {
                MediaFormat.COLOR_RANGE_LIMITED
            }
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, range)
        } catch (e: Exception) {
            // Some encoders reject unknown keys at configure time; log and continue gracefully.
            Log.w(TAG, "applyColorMetadata: key rejected by encoder (ignored): ${e.message}")
        }
    }

    private fun buildConfigFormat(
        mimeType: String,
        config: NormalizedVideoConfig,
        colorMode: String,
        profileSelection: CodecProfileSelection,
        withAdvancedHints: Boolean,
        bitrateMode: Int?,
    ): MediaFormat =
        MediaFormat.createVideoFormat(mimeType, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            // Explicitly tag colour space so players render correct contrast and saturation.
            applyColorMetadata(this, colorMode)
            if (withAdvancedHints) {
                setFloat(MediaFormat.KEY_OPERATING_RATE, config.fps.toFloat())
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (bitrateMode != null) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    setInteger("max-bitrate", config.bitrate)
                }
            }
            profileSelection.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            profileSelection.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
        }

    private fun selectProfile(
        codecCaps: MediaCodecInfo.CodecCapabilities?,
        mimeType: String,
        withProfile: Boolean,
    ): CodecProfileSelection {
        if (!withProfile || codecCaps == null) return CodecProfileSelection(null)
        val profileLevels = codecCaps.profileLevels ?: return CodecProfileSelection(null)
        val preferredProfiles =
            when (mimeType) {
                MediaFormat.MIMETYPE_VIDEO_AVC ->
                    listOf(
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                    )
                MediaFormat.MIMETYPE_VIDEO_HEVC ->
                    listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                else -> emptyList()
            }
        for (profile in preferredProfiles) {
            if (profileLevels.any { it.profile == profile }) {
                // Do not pin KEY_LEVEL. A hard-coded AVCLevel41 is invalid for 1080p60,
                // 1440p, and 4K captures and Android 16 encoders may reject it at start().
                return CodecProfileSelection(profile)
            }
        }
        return CodecProfileSelection(null)
    }

    private fun selectBitrateMode(
        encoderCaps: MediaCodecInfo.EncoderCapabilities?,
        withAdvancedHints: Boolean,
    ): Int? {
        if (!withAdvancedHints || encoderCaps == null) return null
        return when {
            encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            else -> null
        }
    }

    private fun supportsSurfaceInput(codecCaps: MediaCodecInfo.CodecCapabilities?): Boolean {
        if (codecCaps == null) return true
        return codecCaps.colorFormats.any {
            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        }
    }

    private fun logCodecExceptionDetails(
        logTag: String,
        phase: String,
        codecName: String?,
        mimeType: String,
        config: NormalizedVideoConfig?,
        profileSelection: CodecProfileSelection?,
        bitrateMode: Int?,
        error: Exception,
    ) {
        val codecDiagnostic =
            if (Build.VERSION.SDK_INT >= 21 && error is MediaCodec.CodecException) {
                " diagnostic=${error.diagnosticInfo} recoverable=${error.isRecoverable} transient=${error.isTransient}"
            } else {
                ""
            }
        Log.w(
            TAG,
            "[$logTag] $phase failed codec=${codecName ?: "unknown"} mime=$mimeType " +
                "size=${config?.width}x${config?.height} bitrate=${config?.bitrate} fps=${config?.fps} " +
                "iFrame=$I_FRAME_INTERVAL_SECONDS profile=${profileSelection?.profile} " +
                "level=${profileSelection?.level} bitrateMode=$bitrateMode " +
                "device=${Build.MANUFACTURER}/${Build.BRAND}/${Build.MODEL} " +
                "api=${Build.VERSION.SDK_INT}${codecDiagnostic} " +
                "error=${error.javaClass.simpleName}: ${error.message}",
            error,
        )
        try {
            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey("video_encoder_phase", phase)
            crash.setCustomKey("video_encoder_codec_name", codecName ?: "unknown")
            crash.setCustomKey("video_encoder_mime", mimeType)
            crash.setCustomKey("video_encoder_size", "${config?.width}x${config?.height}")
            crash.setCustomKey("video_encoder_bitrate", config?.bitrate ?: -1)
            crash.setCustomKey("video_encoder_fps", config?.fps ?: -1)
            crash.setCustomKey("video_encoder_profile", profileSelection?.profile ?: -1)
            crash.setCustomKey("video_encoder_level", profileSelection?.level ?: -1)
            if (Build.VERSION.SDK_INT >= 21 && error is MediaCodec.CodecException) {
                crash.setCustomKey("video_encoder_diagnostic", error.diagnosticInfo)
            }
        } catch (_: Exception) {
        }
    }

    fun describeStartFailure(
        logTag: String,
        configured: ConfiguredVideoEncoder?,
        error: Exception,
        phase: String = "start",
    ) {
        val config =
            configured?.let {
                NormalizedVideoConfig(it.encodedWidth, it.encodedHeight, it.fps, it.bitrate)
            }
        val profile = configured?.let { CodecProfileSelection(it.profile, it.level) }
        logCodecExceptionDetails(
            logTag,
            phase,
            configured?.codecName,
            configured?.mime ?: "unknown",
            config,
            profile,
            configured?.bitrateMode,
            error,
        )
    }

    fun isCodecException(error: Exception): Boolean =
        Build.VERSION.SDK_INT >= 21 && error is MediaCodec.CodecException

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

    private fun buildAttempts(
        resolvedEncoderName: String?,
        safeStartFallback: Boolean,
    ): List<ConfigureAttempt> {
        val formatTiers =
            if (safeStartFallback) {
                listOf(false to false)
            } else {
                listOf(
                    true to true,
                    false to true,
                    false to false,
                )
            }
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
     * @param avcOnly   when true, skips HEVC entirely (e.g. after [MediaCodec.start] failed on HEVC).
     * @param safeStartFallback when true, uses a conservative AVC config for a single retry after
     *                  an encoder accepted configure() but rejected start().
     * @param colorMode [ColorMode.STANDARD] (Rec.709 limited-range, default) or [ColorMode.FULL]
     *                  (full-range). Controls [MediaFormat] colour-metadata keys applied before
     *                  [MediaCodec.configure] so the bitstream carries correct SDR tagging.
     */
    fun configureScreenCaptureVideoEncoder(
        logTag: String,
        userEncoderType: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        avcOnly: Boolean,
        colorMode: String = ColorMode.STANDARD,
        safeStartFallback: Boolean = false,
    ): ConfiguredVideoEncoder {
        val wantHevcFirst =
            !safeStartFallback &&
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

            val attempts = buildAttempts(resolvedName, safeStartFallback)
            var codec: MediaCodec? = null
            var surface: Surface? = null
            for ((tierIdx, attempt) in attempts.withIndex()) {
                var normalizedConfig: NormalizedVideoConfig? = null
                var profileSelection: CodecProfileSelection? = null
                var bitrateMode: Int? = null
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
                    val codecCaps =
                        try {
                            codecInfo?.getCapabilitiesForType(mimeType)
                        } catch (_: Throwable) {
                            null
                        }
                    if (codecCaps == null) {
                        throw IllegalArgumentException("No codec capabilities for ${codec.name} mime=$mimeType")
                    }
                    if (!supportsSurfaceInput(codecCaps)) {
                        throw IllegalArgumentException("Encoder ${codec.name} does not support COLOR_FormatSurface")
                    }
                    val videoCaps = codecCaps.videoCapabilities
                    val encoderCaps = codecCaps.encoderCapabilities
                    if (videoCaps == null || encoderCaps == null) {
                        throw IllegalArgumentException("Missing video/encoder capabilities for ${codec.name} mime=$mimeType")
                    }
                    val config =
                        normalizeConfigForCodec(
                            videoCaps,
                            width,
                            height,
                            fps,
                            bitrate,
                            safeStartFallback,
                        )
                    normalizedConfig = config
                    if (config.width != width ||
                        config.height != height ||
                        config.fps != fps ||
                        config.bitrate != bitrate
                    ) {
                        Log.w(
                            TAG,
                            "[$logTag] Encoder config normalized for hardware support " +
                                "request=${width}x${height}@${fps}fps/${bitrate}bps -> " +
                                "${config.width}x${config.height}@${config.fps}fps/${config.bitrate}bps " +
                                "mime=$mimeType codec=${codec.name} safeStartFallback=$safeStartFallback",
                        )
                    }
                    val selectedProfile = selectProfile(codecCaps, mimeType, attempt.withProfile)
                    val selectedBitrateMode = selectBitrateMode(encoderCaps, attempt.withAdvancedHints)
                    profileSelection = selectedProfile
                    bitrateMode = selectedBitrateMode
                    val format =
                        buildConfigFormat(
                            mimeType,
                            config,
                            colorMode,
                            selectedProfile,
                            attempt.withAdvancedHints,
                            selectedBitrateMode,
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
                    val input = codec.createInputSurface()
                    surface = input
                    if (tierIdx > 0) {
                        Log.i(
                            TAG,
                            "[$logTag] Video encoder configured on fallback tier $tierIdx " +
                                "mime=$mimeType profile=${selectedProfile.profile} " +
                                "level=${selectedProfile.level} " +
                                "advanced=${attempt.withAdvancedHints} " +
                                "codec=${attempt.codecName ?: "by-type"} " +
                                "brand=${Build.BRAND} model=${Build.MODEL}",
                        )
                    }
                    Log.i(
                        TAG,
                        "[$logTag] Video encoder ready codec=${codec.name} mime=$mimeType tier=$tierIdx " +
                            "size=${config.width}x${config.height} fps=${config.fps} bitrate=${config.bitrate} " +
                            "profile=${selectedProfile.profile} level=${selectedProfile.level} " +
                            "bitrateMode=$selectedBitrateMode safeStartFallback=$safeStartFallback",
                    )
                    return ConfiguredVideoEncoder(
                        codec = codec,
                        inputSurface = input,
                        mime = mimeType,
                        codecName = codec.name,
                        encodedWidth = config.width,
                        encodedHeight = config.height,
                        fps = config.fps,
                        bitrate = config.bitrate,
                        iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
                        profile = selectedProfile.profile,
                        level = selectedProfile.level,
                        bitrateMode = selectedBitrateMode,
                    )
                } catch (e: Exception) {
                    lastError = e
                    logCodecExceptionDetails(
                        logTag = logTag,
                        phase = "configure tier $tierIdx",
                        codecName = codec?.name ?: attempt.codecName,
                        mimeType = mimeType,
                        config = normalizedConfig,
                        profileSelection = profileSelection,
                        bitrateMode = bitrateMode,
                        error = e,
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
