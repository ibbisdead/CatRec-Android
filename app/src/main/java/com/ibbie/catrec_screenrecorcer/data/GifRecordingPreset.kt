package com.ibbie.catrec_screenrecorcer.data

import androidx.annotation.StringRes
import com.ibbie.catrec_screenrecorcer.R

/**
 * How [com.ibbie.catrec_screenrecorcer.service.FfmpegGifTranscoder] applies [paletteuse] dithering.
 * Low tiers use light Bayer (less noise on flat UI); High uses Floyd–Steinberg for smoother gradients
 * at 256 colors (still GIF-limited—never as clean as full video).
 */
enum class GifPaletteDither {
    /** `paletteuse=dither=bayer` with low `bayer_scale`. */
    BAYER_LIGHT,

    /** `paletteuse=dither=bayer` with moderate `bayer_scale`. */
    BAYER_MEDIUM,

    /** `paletteuse=dither=floyd_steinberg` (no Bayer scale). */
    FLOYD_STEINBERG,
    ;

    companion object {
        fun fromSerialized(name: String?): GifPaletteDither = entries.find { it.name == name } ?: BAYER_MEDIUM
    }
}

/**
 * GIF mode records H.264 MP4 first (full-color, compressed video), then FFmpeg converts that file
 * to GIF. **GIF export must use lower width, FPS, and color count than video** because GIF is
 * limited to 256 indexed colors per frame, has no inter-frame compression like H.264, and huge
 * dimensions × FPS make files enormous with ugly temporal noise.
 *
 * Export uses **palettegen + paletteuse** (see [com.ibbie.catrec_screenrecorcer.service.FfmpegGifTranscoder]):
 * one palette is optimized for the whole clip, then applied consistently.
 *
 * [resolutionSetting] / [bitrateMbps] / [fps] apply to the **MP4 capture**; GIF limits are
 * [maxWidth], [maxColors], [paletteDither], and the FPS passed into the transcoder (aligned with [fps]
 * so the intermediate file is not recorded far faster than the GIF will display).
 */
data class GifRecordingPreset(
    val id: String,
    @param:StringRes val titleRes: Int,
    /** Max width of each GIF frame (height follows aspect ratio). */
    val maxWidth: Int,
    /** Used for MP4 capture and for GIF output FPS (keep aligned with export). */
    val fps: Int,
    val bitrateMbps: Float,
    val maxDurationSec: Int,
    val resolutionSetting: String,
    /** FFmpeg palettegen max colors. */
    val maxColors: Int,
    val paletteDither: GifPaletteDither,
    /** Video → GIF slider upper bound for this tier. */
    val gifFpsSliderMax: Int,
) {
    val bitrateBitsPerSec: Int get() = (bitrateMbps * 1_000_000f).toInt()
}

object GifRecordingPresets {
    val all: List<GifRecordingPreset> =
        listOf(
            GifRecordingPreset(
                id = "gif_small",
                titleRes = R.string.gif_preset_small,
                maxWidth = 720,
                fps = 30,
                bitrateMbps = 8f,
                maxDurationSec = 60,
                resolutionSetting = "720p",
                maxColors = 128,
                paletteDither = GifPaletteDither.BAYER_LIGHT,
                gifFpsSliderMax = 30,
            ),
            GifRecordingPreset(
                id = "gif_medium",
                titleRes = R.string.gif_preset_medium,
                maxWidth = 1080,
                fps = 45,
                bitrateMbps = 12f,
                maxDurationSec = 90,
                resolutionSetting = "1080p",
                maxColors = 192,
                paletteDither = GifPaletteDither.BAYER_MEDIUM,
                gifFpsSliderMax = 45,
            ),
            GifRecordingPreset(
                id = "gif_hd",
                titleRes = R.string.gif_preset_hd,
                maxWidth = 1080,
                fps = 60,
                bitrateMbps = 16f,
                maxDurationSec = 120,
                resolutionSetting = "1080p",
                maxColors = 256,
                paletteDither = GifPaletteDither.FLOYD_STEINBERG,
                gifFpsSliderMax = 60,
            ),
        )

    val default: GifRecordingPreset get() = all.first()

    fun byId(id: String): GifRecordingPreset = all.find { it.id == id } ?: default

    /** Tools → Video to GIF: same export caps as [all] indices 0..2. */
    fun forVideoToGifTier(tier: Int): GifRecordingPreset = all.getOrElse(tier.coerceIn(0, 2)) { default }
}
