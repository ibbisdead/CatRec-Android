package com.ibbie.catrec_screenrecorcer.service

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Caches [MediaCodecList.REGULAR_CODECS] for the process (expensive to build) and resolves
 * a video encoder name with the same rules as [ScreenRecorderEngine]:
 * prefer non-Google hardware encoders, then [MediaCodecList.findEncoderForFormat] using a
 * discovery format **without** [MediaFormat.KEY_BIT_RATE] (including bitrate there can make
 * [findEncoderForFormat] return null and push devices onto software encoders).
 */
internal object VideoEncoderResolver {
    private val listLock = Any()

    @Volatile
    private var cachedList: MediaCodecList? = null

    fun regularCodecList(): MediaCodecList {
        cachedList?.let { return it }
        synchronized(listLock) {
            cachedList?.let { return it }
            val fresh = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            cachedList = fresh
            return fresh
        }
    }

    /** Matches a live [MediaCodec] instance to its [MediaCodecInfo] (for [MediaCodecInfo.getCapabilitiesForType]). */
    fun findEncoderInfo(codecName: String): MediaCodecInfo? =
        regularCodecList().codecInfos.firstOrNull { it.isEncoder && it.name == codecName }

    fun resolveVideoEncoderName(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int,
    ): String? {
        val mediaCodecList = regularCodecList()
        val hardware =
            mediaCodecList.codecInfos.firstOrNull { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                    !info.name.contains("google", ignoreCase = true) &&
                    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else true
            }
                ?.name
        if (hardware != null) return hardware
        val discoveryFormat =
            MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        return mediaCodecList.findEncoderForFormat(discoveryFormat)
    }
}
