package com.ibbie.catrec_screenrecorcer.service

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class RecordingResolutionSize(
    val width: Int,
    val height: Int,
) {
    val setting: String get() = "${width}x$height"
    val pixels: Long get() = width.toLong() * height.toLong()
}

internal enum class RecordingResolutionPresetKind {
    NATIVE,
    UHD_4K,
    QHD_2K,
    TIER_1080,
    TIER_720,
    TIER_480,
}

internal data class RecordingResolutionPreset(
    val kind: RecordingResolutionPresetKind,
    val size: RecordingResolutionSize,
) {
    val setting: String
        get() =
            if (kind == RecordingResolutionPresetKind.NATIVE) {
                RecordingResolutionSupport.NATIVE_SETTING
            } else {
                size.setting
            }
}

internal sealed class RecordingResolutionValidation {
    data class Valid(val size: RecordingResolutionSize) : RecordingResolutionValidation()
    data class Invalid(val reason: RecordingResolutionInvalidReason) : RecordingResolutionValidation()
}

internal enum class RecordingResolutionInvalidReason {
    REQUIRED,
    POSITIVE_NUMBERS,
    EVEN_DIMENSIONS,
    BELOW_MINIMUM,
    EXCEEDS_SAFE_MAXIMUM,
    UNSUPPORTED_BY_ENCODER,
    TOO_LARGE_FOR_PROFILE,
}

internal object RecordingResolutionSupport {
    const val NATIVE_SETTING = "Native"
    const val CUSTOM_OPTION = "__custom_resolution__"

    private const val MIN_DIMENSION = 100
    private const val DEFAULT_ALIGNMENT = 16
    private const val MAX_SAFE_SIDE = 7680
    private const val MAX_SAFE_PIXELS = 7680L * 4320L
    private const val LOW_END_PROFILE_MAX_PIXELS = 2560L * 1440L
    /**
     * MediaProjection + overlay windows compete for GPU buffer-queue slots. Crashlytics ANRs on
     * Adreno (Nothing Phone 3a, etc.) showed RenderThread blocked in [Surface.dequeueBuffer] while
     * capturing ~4K+ panel sizes (e.g. 3840×2384) with floating controls enabled.
     *
     * Encoder/stack limits remain governed by [MAX_SAFE_SIDE] / [MAX_SAFE_PIXELS]; this cap is only
     * for **capture surface** sizing to reduce SurfaceFlinger pressure.
     */
    private const val MAX_VIRTUAL_DISPLAY_LONG_EDGE = 2560
    private const val MAX_VIRTUAL_DISPLAY_PIXELS = 2560L * 1440L
    private const val DUPLICATE_PIXEL_RATIO = 0.08f
    private const val SIXTEEN_NINE = 16f / 9f

    private val resolutionRegex = Regex("""^\s*(\d+)\s*[xX\u00D7]\s*(\d+)""")
    private val strictResolutionRegex = Regex("""^\s*(-?\d+)\s*[xX\u00D7]\s*(-?\d+)\s*$""")

    fun parseSize(value: String?): RecordingResolutionSize? {
        val match = resolutionRegex.find(value.orEmpty()) ?: return null
        val width = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val height = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return RecordingResolutionSize(width, height)
    }

    fun normalizeSavedSetting(value: String?): String {
        if (value == NATIVE_SETTING || value.isNullOrBlank()) return NATIVE_SETTING
        return parseSize(value)?.setting ?: NATIVE_SETTING
    }

    /**
     * Downscales [width]×[height] (preserving aspect) when they exceed [MAX_VIRTUAL_DISPLAY_LONG_EDGE]
     * or [MAX_VIRTUAL_DISPLAY_PIXELS]. No-op when already within bounds.
     */
    fun clampCaptureForVirtualDisplay(
        width: Int,
        height: Int,
    ): RecordingResolutionSize {
        val wIn = width.coerceAtLeast(MIN_DIMENSION)
        val hIn = height.coerceAtLeast(MIN_DIMENSION)
        val maxLong = maxOf(wIn, hIn).toDouble()
        val pixels = wIn.toDouble() * hIn.toDouble()
        var scale = 1.0
        val longCap = MAX_VIRTUAL_DISPLAY_LONG_EDGE.toDouble()
        if (maxLong > longCap) {
            scale = min(scale, longCap / maxLong)
        }
        val pixelCap = MAX_VIRTUAL_DISPLAY_PIXELS.toDouble()
        if (pixels > pixelCap) {
            scale = min(scale, sqrt(pixelCap / pixels))
        }
        if (scale >= 1.0) {
            return RecordingResolutionSize(
                alignFloor(wIn, DEFAULT_ALIGNMENT),
                alignFloor(hIn, DEFAULT_ALIGNMENT),
            )
        }
        val wOut = (wIn * scale).roundToInt().coerceAtLeast(MIN_DIMENSION)
        val hOut = (hIn * scale).roundToInt().coerceAtLeast(MIN_DIMENSION)
        return RecordingResolutionSize(
            alignFloor(wOut, DEFAULT_ALIGNMENT),
            alignFloor(hOut, DEFAULT_ALIGNMENT),
        )
    }

    fun displaySizeForRecording(
        context: Context,
        recordingOrientation: String,
    ): RecordingResolutionSize {
        val (rawWidth, rawHeight) = currentDisplaySizePx(context)
        val (width, height) =
            when (recordingOrientation) {
                "Portrait" -> minOf(rawWidth, rawHeight) to maxOf(rawWidth, rawHeight)
                "Landscape" -> maxOf(rawWidth, rawHeight) to minOf(rawWidth, rawHeight)
                else -> rawWidth to rawHeight
            }
        return RecordingResolutionSize(
            alignFloor(width, DEFAULT_ALIGNMENT),
            alignFloor(height, DEFAULT_ALIGNMENT),
        )
    }

    fun generatePresets(
        context: Context,
        recordingOrientation: String,
        videoEncoder: String,
        fps: Int,
        lowEndDeviceProfile: Boolean,
    ): List<RecordingResolutionPreset> {
        val base = displaySizeForRecording(context, recordingOrientation)
        val caps = encoderVideoCapabilities(videoEncoder, base.width, base.height, fps)
        val widthAlignment = caps?.widthAlignment?.coerceAtLeast(2) ?: DEFAULT_ALIGNMENT
        val heightAlignment = caps?.heightAlignment?.coerceAtLeast(2) ?: DEFAULT_ALIGNMENT
        val native = alignSize(base.width, base.height, widthAlignment, heightAlignment)
        val presets = mutableListOf(RecordingResolutionPreset(RecordingResolutionPresetKind.NATIVE, native))

        val aspect = native.width.toFloat() / native.height.toFloat()
        val tiers =
            listOf(
                QualityTier(RecordingResolutionPresetKind.UHD_4K, longEdge = 3840, shortEdge = 2160),
                QualityTier(RecordingResolutionPresetKind.QHD_2K, longEdge = 2560, shortEdge = 1440),
                QualityTier(RecordingResolutionPresetKind.TIER_1080, longEdge = 1920, shortEdge = 1080),
                QualityTier(RecordingResolutionPresetKind.TIER_720, longEdge = 1280, shortEdge = 720),
                QualityTier(RecordingResolutionPresetKind.TIER_480, longEdge = 854, shortEdge = 480),
            )

        for (tier in tiers) {
            val generated = sizeForTier(tier, aspect, native.width >= native.height)
            val aligned = alignSize(generated.width, generated.height, widthAlignment, heightAlignment)
            if (isWithinAppSafeMaximum(aligned) &&
                !isTooLargeForProfile(aligned, lowEndDeviceProfile) &&
                supportsEncoderSize(aligned, caps, fps) &&
                presets.none { existing -> tooClose(existing.size, aligned) }
            ) {
                presets += RecordingResolutionPreset(tier.kind, aligned)
            }
        }

        return presets
    }

    fun validateCustomResolution(
        input: String,
        context: Context,
        videoEncoder: String,
        fps: Int,
        lowEndDeviceProfile: Boolean,
    ): RecordingResolutionValidation {
        val size = parseStrictSize(input) ?: return RecordingResolutionValidation.Invalid(RecordingResolutionInvalidReason.REQUIRED)
        if (size.width <= 0 || size.height <= 0) {
            return RecordingResolutionValidation.Invalid(RecordingResolutionInvalidReason.POSITIVE_NUMBERS)
        }
        if (size.width < MIN_DIMENSION || size.height < MIN_DIMENSION) {
            return RecordingResolutionValidation.Invalid(RecordingResolutionInvalidReason.BELOW_MINIMUM)
        }
        if (size.width % 2 != 0 || size.height % 2 != 0) {
            return RecordingResolutionValidation.Invalid(RecordingResolutionInvalidReason.EVEN_DIMENSIONS)
        }
        if (!isWithinAppSafeMaximum(size)) {
            return RecordingResolutionValidation.Invalid(RecordingResolutionInvalidReason.EXCEEDS_SAFE_MAXIMUM)
        }
        if (isTooLargeForProfile(size, lowEndDeviceProfile)) {
            return RecordingResolutionValidation.Invalid(RecordingResolutionInvalidReason.TOO_LARGE_FOR_PROFILE)
        }

        val caps = encoderVideoCapabilities(videoEncoder, size.width, size.height, fps)
        if (caps != null && !supportsEncoderSize(size, caps, fps)) {
            return RecordingResolutionValidation.Invalid(RecordingResolutionInvalidReason.UNSUPPORTED_BY_ENCODER)
        }
        return RecordingResolutionValidation.Valid(size)
    }

    private data class QualityTier(
        val kind: RecordingResolutionPresetKind,
        val longEdge: Int,
        val shortEdge: Int,
    )

    private fun currentDisplaySizePx(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun parseStrictSize(value: String?): RecordingResolutionSize? {
        val match = strictResolutionRegex.find(value.orEmpty()) ?: return null
        val width = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val height = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return RecordingResolutionSize(width, height)
    }

    private fun sizeForTier(
        tier: QualityTier,
        aspect: Float,
        landscape: Boolean,
    ): RecordingResolutionSize {
        val wideAspect = if (aspect >= 1f) aspect else 1f / aspect
        val useShortEdge =
            tier.kind == RecordingResolutionPresetKind.TIER_480 ||
                wideAspect > SIXTEEN_NINE
        return if (useShortEdge) {
            sizeForShortEdge(tier.shortEdge, aspect, landscape)
        } else {
            sizeForLongEdge(tier.longEdge, aspect, landscape)
        }
    }

    private fun sizeForLongEdge(
        longEdge: Int,
        aspect: Float,
        landscape: Boolean,
    ): RecordingResolutionSize =
        if (landscape) {
            RecordingResolutionSize(longEdge, (longEdge / aspect).roundToInt())
        } else {
            RecordingResolutionSize((longEdge * aspect).roundToInt(), longEdge)
        }

    private fun sizeForShortEdge(
        shortEdge: Int,
        aspect: Float,
        landscape: Boolean,
    ): RecordingResolutionSize =
        if (landscape) {
            RecordingResolutionSize((shortEdge * aspect).roundToInt(), shortEdge)
        } else {
            RecordingResolutionSize(shortEdge, (shortEdge / aspect).roundToInt())
        }

    private fun alignSize(
        width: Int,
        height: Int,
        widthAlignment: Int,
        heightAlignment: Int,
    ): RecordingResolutionSize =
        RecordingResolutionSize(
            alignFloor(width, widthAlignment),
            alignFloor(height, heightAlignment),
        )

    private fun alignFloor(
        value: Int,
        alignment: Int,
    ): Int {
        val safeAlignment = alignment.coerceAtLeast(2)
        val safeValue = value.coerceAtLeast(safeAlignment)
        return (safeValue / safeAlignment) * safeAlignment
    }

    private fun isWithinAppSafeMaximum(size: RecordingResolutionSize): Boolean =
        size.width <= MAX_SAFE_SIDE &&
            size.height <= MAX_SAFE_SIDE &&
            size.pixels <= MAX_SAFE_PIXELS

    private fun isTooLargeForProfile(
        size: RecordingResolutionSize,
        lowEndDeviceProfile: Boolean,
    ): Boolean = lowEndDeviceProfile && size.pixels > LOW_END_PROFILE_MAX_PIXELS

    private fun tooClose(
        a: RecordingResolutionSize,
        b: RecordingResolutionSize,
    ): Boolean {
        val larger = maxOf(a.pixels, b.pixels).coerceAtLeast(1L)
        val delta = abs(a.pixels - b.pixels).toFloat() / larger.toFloat()
        return delta < DUPLICATE_PIXEL_RATIO
    }

    private fun supportsEncoderSize(
        size: RecordingResolutionSize,
        caps: MediaCodecInfo.VideoCapabilities?,
        fps: Int,
    ): Boolean {
        if (caps == null) return true
        return try {
            val sizeSupported = caps.isSizeSupported(size.width, size.height)
            val rateSupported =
                try {
                    caps.areSizeAndRateSupported(size.width, size.height, fps.coerceAtLeast(1).toDouble())
                } catch (_: Throwable) {
                    true
                }
            sizeSupported && rateSupported
        } catch (_: Throwable) {
            false
        }
    }

    private fun encoderVideoCapabilities(
        videoEncoder: String,
        width: Int,
        height: Int,
        fps: Int,
    ): MediaCodecInfo.VideoCapabilities? {
        val mimeType =
            if (videoEncoder.contains("265") || videoEncoder.contains("HEVC", ignoreCase = true)) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }
        return try {
            val codecName = VideoEncoderResolver.resolveVideoEncoderName(mimeType, width, height, fps)
            val codecInfo = codecName?.let(VideoEncoderResolver::findEncoderInfo) ?: return null
            codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
        } catch (_: Throwable) {
            null
        }
    }
}
