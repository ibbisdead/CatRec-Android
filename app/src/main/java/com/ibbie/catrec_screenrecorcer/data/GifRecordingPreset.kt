package com.ibbie.catrec_screenrecorcer.data

import androidx.annotation.StringRes
import com.ibbie.catrec_screenrecorcer.R

/**
 * GIF output is produced by recording MP4 with these caps, then FFmpeg palette transcode.
 * [resolutionSetting] is passed to [com.ibbie.catrec_screenrecorcer.service.ScreenRecordService] as [EXTRA_RESOLUTION].
 */
data class GifRecordingPreset(
    val id: String,
    @param:StringRes val titleRes: Int,
    /** Max width for H.264 encode and FFmpeg scale (short side cap in practice via resolution string). */
    val maxWidth: Int,
    val fps: Int,
    val bitrateMbps: Float,
    val maxDurationSec: Int,
    val resolutionSetting: String,
) {
    val bitrateBitsPerSec: Int get() = (bitrateMbps * 1_000_000f).toInt()
}

object GifRecordingPresets {
    val all: List<GifRecordingPreset> =
        listOf(
            GifRecordingPreset(
                id = "gif_small",
                titleRes = R.string.gif_preset_small,
                maxWidth = 320,
                fps = 8,
                bitrateMbps = 2f,
                maxDurationSec = 15,
                resolutionSetting = "360p",
            ),
            GifRecordingPreset(
                id = "gif_medium",
                titleRes = R.string.gif_preset_medium,
                maxWidth = 480,
                fps = 10,
                bitrateMbps = 4f,
                maxDurationSec = 20,
                resolutionSetting = "480p",
            ),
            GifRecordingPreset(
                id = "gif_hd",
                titleRes = R.string.gif_preset_hd,
                maxWidth = 720,
                fps = 12,
                bitrateMbps = 6f,
                maxDurationSec = 30,
                resolutionSetting = "720p",
            ),
        )

    val default: GifRecordingPreset get() = all.first()

    fun byId(id: String): GifRecordingPreset = all.find { it.id == id } ?: default
}
