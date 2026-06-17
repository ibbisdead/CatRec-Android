package com.ibbie.catrec_screenrecorcer.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.ibbie.catrec_screenrecorcer.BuildConfig
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

/** Why the native preset's recording size differs from the panel-native label. */
internal enum class NativeRecordingSizeNoteKind {
    DISPLAY_SCALING,
    STABILITY,
    DISPLAY_SCALING_AND_STABILITY,
}

internal data class RecordingResolutionPreset(
    val kind: RecordingResolutionPresetKind,
    /** Label size: panel-native for [RecordingResolutionPresetKind.NATIVE]; tier target otherwise. */
    val size: RecordingResolutionSize,
    /** Final encoder-safe capture size when it differs from [size] (typically native only). */
    val encoderCaptureSize: RecordingResolutionSize? = null,
    /** Explains [encoderCaptureSize] vs panel label for [RecordingResolutionPresetKind.NATIVE]. */
    val nativeRecordingNoteKind: NativeRecordingSizeNoteKind? = null,
) {
    val setting: String
        get() =
            if (kind == RecordingResolutionPresetKind.NATIVE) {
                RecordingResolutionSupport.NATIVE_SETTING
            } else {
                size.setting
            }

    /** Size shown in preset lists for non-native tiers (encoder output). */
    val displayCaptureSize: RecordingResolutionSize
        get() = encoderCaptureSize ?: size
}

/** Full sizing pipeline snapshot for debug logging at recording start. */
internal data class CaptureSizingTrace(
    val panelNative: RecordingResolutionSize,
    val logicalDisplay: RecordingResolutionSize,
    val requested: RecordingResolutionSize,
    val afterVirtualDisplayClamp: RecordingResolutionSize,
    val finalEncoder: RecordingResolutionSize,
    val recordingOrientation: String,
    val displayRotation: Int,
) {
    fun toLogLine(): String =
        "CaptureSizing panel=${panelNative.setting} logical=${logicalDisplay.setting} " +
            "requested=${requested.setting} vdClamp=${afterVirtualDisplayClamp.setting} " +
            "final=${finalEncoder.setting} orientation=$recordingOrientation rotation=$displayRotation"
}

internal sealed class RecordingResolutionValidation {
    data class Valid(
        val size: RecordingResolutionSize,
    ) : RecordingResolutionValidation()

    data class Invalid(
        val reason: RecordingResolutionInvalidReason,
    ) : RecordingResolutionValidation()
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
    private const val LOG_TAG = "RecordingResolution"

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
     * Panel / current physical display-mode resolution for the Settings **Native** label only.
     * Uses [Display.Mode] physical dimensions (API 23+) oriented by [Display.getRotation].
     * Not VirtualDisplay-clamped and not encoder-aligned.
     */
    fun getPanelNativeResolution(
        context: Context,
        recordingOrientation: String,
    ): RecordingResolutionSize {
        val display = defaultDisplay(context)
        val physical = readPhysicalModeSizePx(display)
        val rotationOriented =
            physical?.let { (w, h) ->
                orientPhysicalByRotation(w, h, displayRotation(display))
            } ?: readLogicalMetricsSizePx(display)
        return applyRecordingOrientationPreference(rotationOriented, recordingOrientation)
    }

    /**
     * Current logical display size (rotation-aware [Display.getRealMetrics]).
     * Basis for native **capture** requests and capture-resize listeners.
     */
    fun getCurrentLogicalDisplayResolution(
        context: Context,
        recordingOrientation: String,
    ): RecordingResolutionSize {
        val display = defaultDisplay(context)
        val logical = readLogicalMetricsSizePx(display)
        return applyRecordingOrientationPreference(logical, recordingOrientation)
    }

    /**
     * Requested capture size from a saved preset string — no VirtualDisplay clamp or encoder alignment.
     */
    fun resolveRequestedCaptureSize(
        context: Context,
        resolution: String,
        recordingOrientation: String,
    ): RecordingResolutionSize {
        val logicalBase = getCurrentLogicalDisplayResolution(context, recordingOrientation)
        val aspectRatio = logicalBase.width.toFloat() / logicalBase.height.toFloat()
        val explicitSize = parseSize(resolution)
        return when {
            resolution == NATIVE_SETTING -> logicalBase
            explicitSize != null -> explicitSize
            else -> {
                val targetHeight =
                    when {
                        resolution.contains("2160") || resolution.contains("4K") -> 2160
                        resolution.contains("1440") || resolution.contains("2K") -> 1440
                        resolution.contains("1080") -> 1080
                        resolution.contains("720") -> 720
                        resolution.contains("480") -> 480
                        resolution.contains("360") -> 360
                        else -> logicalBase.height
                    }
                val targetWidth = (targetHeight * aspectRatio).roundToInt()
                RecordingResolutionSize(targetWidth, targetHeight)
            }
        }
    }

    /**
     * Single source of truth for final VirtualDisplay / [MediaCodec] capture dimensions.
     * Applies VirtualDisplay safety clamp then encoder floor alignment.
     */
    fun getEncoderCaptureResolution(
        requestedSize: RecordingResolutionSize,
        videoEncoder: String,
        fps: Int,
    ): RecordingResolutionSize {
        val clamped = clampForVirtualDisplaySafety(requestedSize.width, requestedSize.height)
        val caps = encoderVideoCapabilities(videoEncoder, clamped.width, clamped.height, fps)
        val widthAlignment = caps?.widthAlignment?.coerceAtLeast(2) ?: DEFAULT_ALIGNMENT
        val heightAlignment = caps?.heightAlignment?.coerceAtLeast(2) ?: DEFAULT_ALIGNMENT
        return alignSize(clamped.width, clamped.height, widthAlignment, heightAlignment)
    }

    fun getEncoderCaptureResolutionFromSetting(
        context: Context,
        resolutionSetting: String,
        recordingOrientation: String,
        videoEncoder: String,
        fps: Int,
    ): RecordingResolutionSize {
        val requested =
            resolveRequestedCaptureSize(
                context = context,
                resolution = resolutionSetting,
                recordingOrientation = recordingOrientation,
            )
        return getEncoderCaptureResolution(requested, videoEncoder, fps)
    }

    fun buildCaptureSizingTrace(
        context: Context,
        resolutionSetting: String,
        recordingOrientation: String,
        videoEncoder: String,
        fps: Int,
    ): CaptureSizingTrace {
        val display = defaultDisplay(context)
        val panelNative = getPanelNativeResolution(context, recordingOrientation)
        val logicalDisplay = getCurrentLogicalDisplayResolution(context, recordingOrientation)
        val requested =
            resolveRequestedCaptureSize(context, resolutionSetting, recordingOrientation)
        val afterClamp = clampForVirtualDisplaySafety(requested.width, requested.height)
        val finalEncoder = getEncoderCaptureResolution(requested, videoEncoder, fps)
        return CaptureSizingTrace(
            panelNative = panelNative,
            logicalDisplay = logicalDisplay,
            requested = requested,
            afterVirtualDisplayClamp = afterClamp,
            finalEncoder = finalEncoder,
            recordingOrientation = recordingOrientation,
            displayRotation = displayRotation(display),
        )
    }

    /**
     * Downscales [width]×[height] (preserving aspect) when they exceed VirtualDisplay safety caps.
     * Does **not** apply encoder alignment — use [getEncoderCaptureResolution] for the full pipeline.
     */
    fun clampForVirtualDisplaySafety(
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
            return RecordingResolutionSize(wIn, hIn)
        }
        val wOut = (wIn * scale).roundToInt().coerceAtLeast(MIN_DIMENSION)
        val hOut = (hIn * scale).roundToInt().coerceAtLeast(MIN_DIMENSION)
        return RecordingResolutionSize(wOut, hOut)
    }

    fun generatePresets(
        context: Context,
        recordingOrientation: String,
        videoEncoder: String,
        fps: Int,
        lowEndDeviceProfile: Boolean,
    ): List<RecordingResolutionPreset> {
        val panelNative = getPanelNativeResolution(context, recordingOrientation)
        val logical = getCurrentLogicalDisplayResolution(context, recordingOrientation)
        val afterVdClamp = clampForVirtualDisplaySafety(logical.width, logical.height)
        val nativeEncoder =
            getEncoderCaptureResolutionFromSetting(
                context = context,
                resolutionSetting = NATIVE_SETTING,
                recordingOrientation = recordingOrientation,
                videoEncoder = videoEncoder,
                fps = fps,
            )
        val nativeNote =
            classifyNativeRecordingNote(
                panelNative = panelNative,
                logical = logical,
                afterVdClamp = afterVdClamp,
                finalEncoder = nativeEncoder,
            )
        val presets =
            mutableListOf(
                RecordingResolutionPreset(
                    kind = RecordingResolutionPresetKind.NATIVE,
                    size = panelNative,
                    encoderCaptureSize = nativeNote?.let { nativeEncoder },
                    nativeRecordingNoteKind = nativeNote,
                ),
            )

        val caps = encoderVideoCapabilities(videoEncoder, logical.width, logical.height, fps)
        val widthAlignment = caps?.widthAlignment?.coerceAtLeast(2) ?: DEFAULT_ALIGNMENT
        val heightAlignment = caps?.heightAlignment?.coerceAtLeast(2) ?: DEFAULT_ALIGNMENT
        val aspect = logical.width.toFloat() / logical.height.toFloat()
        val tiers =
            listOf(
                QualityTier(RecordingResolutionPresetKind.UHD_4K, longEdge = 3840, shortEdge = 2160),
                QualityTier(RecordingResolutionPresetKind.QHD_2K, longEdge = 2560, shortEdge = 1440),
                QualityTier(RecordingResolutionPresetKind.TIER_1080, longEdge = 1920, shortEdge = 1080),
                QualityTier(RecordingResolutionPresetKind.TIER_720, longEdge = 1280, shortEdge = 720),
                QualityTier(RecordingResolutionPresetKind.TIER_480, longEdge = 854, shortEdge = 480),
            )

        for (tier in tiers) {
            val requested = sizeForTier(tier, aspect, logical.width >= logical.height)
            val finalCapture = getEncoderCaptureResolution(requested, videoEncoder, fps)
            val alignedLabel = alignSize(requested.width, requested.height, widthAlignment, heightAlignment)
            if (isWithinAppSafeMaximum(finalCapture) &&
                !isTooLargeForProfile(finalCapture, lowEndDeviceProfile) &&
                supportsEncoderSize(finalCapture, caps, fps) &&
                presets.none { existing -> tooClose(existing.size, alignedLabel) }
            ) {
                presets +=
                    RecordingResolutionPreset(
                        kind = tier.kind,
                        size = alignedLabel,
                        encoderCaptureSize =
                            if (finalCapture.setting != alignedLabel.setting) finalCapture else null,
                    )
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

    fun logCaptureSizingIfDebug(
        trace: CaptureSizingTrace,
        deviceModel: String,
        apiLevel: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            LOG_TAG,
            "${trace.toLogLine()} api=$apiLevel model=$deviceModel",
        )
    }

    // ── Pure helpers (unit-testable) ───────────────────────────────────────────

    internal fun orientPhysicalByRotation(
        physicalWidth: Int,
        physicalHeight: Int,
        rotation: Int,
    ): RecordingResolutionSize {
        val rotated =
            rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        return if (rotated) {
            RecordingResolutionSize(physicalHeight, physicalWidth)
        } else {
            RecordingResolutionSize(physicalWidth, physicalHeight)
        }
    }

    internal fun applyRecordingOrientationPreference(
        size: RecordingResolutionSize,
        recordingOrientation: String,
    ): RecordingResolutionSize {
        val (width, height) =
            when (recordingOrientation) {
                "Portrait" -> minOf(size.width, size.height) to maxOf(size.width, size.height)
                "Landscape" -> maxOf(size.width, size.height) to minOf(size.width, size.height)
                else -> size.width to size.height
            }
        return RecordingResolutionSize(width, height)
    }

    internal fun classifyNativeRecordingNote(
        panelNative: RecordingResolutionSize,
        logical: RecordingResolutionSize,
        afterVdClamp: RecordingResolutionSize,
        finalEncoder: RecordingResolutionSize,
    ): NativeRecordingSizeNoteKind? {
        if (finalEncoder.setting == panelNative.setting) return null
        val scaling = panelNative.setting != logical.setting
        val vdClamp = afterVdClamp.setting != logical.setting
        return when {
            scaling && vdClamp -> NativeRecordingSizeNoteKind.DISPLAY_SCALING_AND_STABILITY
            scaling -> NativeRecordingSizeNoteKind.DISPLAY_SCALING
            else -> NativeRecordingSizeNoteKind.STABILITY
        }
    }

    // ── Display reads ────────────────────────────────────────────────────────

    private fun defaultDisplay(context: Context): Display =
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: run {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                wm.defaultDisplay
            }

    private fun displayRotation(display: Display): Int {
        @Suppress("DEPRECATION")
        return display.rotation
    }

    /**
     * Current active mode physical pixel size (natural orientation, not rotation-adjusted).
     * [Display.getSupportedModes] is intentionally not scanned: the active [Display.mode] reflects
     * the panel mode the user is in; picking a higher mode could mis-label refresh-rate variants.
     */
    private fun readPhysicalModeSizePx(display: Display): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < 23) return null
        val mode = display.mode ?: return null
        val w = mode.physicalWidth
        val h = mode.physicalHeight
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    private fun readLogicalMetricsSizePx(display: Display): RecordingResolutionSize {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return RecordingResolutionSize(metrics.widthPixels, metrics.heightPixels)
    }

    private data class QualityTier(
        val kind: RecordingResolutionPresetKind,
        val longEdge: Int,
        val shortEdge: Int,
    )

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
