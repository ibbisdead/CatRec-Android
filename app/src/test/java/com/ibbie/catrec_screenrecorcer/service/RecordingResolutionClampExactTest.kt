package com.ibbie.catrec_screenrecorcer.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Documents exact clamp + default-16 encoder-align outputs for manual test checklist. */
class RecordingResolutionClampExactTest {
    @Test
    fun `3840x2384 VD clamp is 2437x1513 then encoder default align is 2432x1504`() {
        val clamped = RecordingResolutionSupport.clampForVirtualDisplaySafety(3840, 2384)
        assertEquals(2437, clamped.width)
        assertEquals(1513, clamped.height)
        val final =
            RecordingResolutionSupport.getEncoderCaptureResolution(
                RecordingResolutionSize(3840, 2384),
                videoEncoder = "H.264",
                fps = 30,
            )
        assertEquals(2432, final.width)
        assertEquals(1504, final.height)
    }

    @Test
    fun `1220x2712 VD clamp is 1152x2560 long-edge cap only`() {
        val clamped = RecordingResolutionSupport.clampForVirtualDisplaySafety(1220, 2712)
        assertEquals(1152, clamped.width)
        assertEquals(2560, clamped.height)
        val final =
            RecordingResolutionSupport.getEncoderCaptureResolution(
                RecordingResolutionSize(1220, 2712),
                videoEncoder = "H.264",
                fps = 30,
            )
        assertEquals(1152, final.width)
        assertEquals(2560, final.height)
    }

    @Test
    fun `1080x2400 screenshot clamp unchanged recording may floor width to codec alignment`() {
        val clamped = RecordingResolutionSupport.clampForVirtualDisplaySafety(1080, 2400)
        assertEquals(1080, clamped.width)
        assertEquals(2400, clamped.height)
        val final =
            RecordingResolutionSupport.getEncoderCaptureResolution(
                RecordingResolutionSize(1080, 2400),
                videoEncoder = "H.264",
                fps = 30,
            )
        // 1080 is not divisible by 16; floor align → 1072 on typical H.264 caps
        assertEquals(1072, final.width)
        assertEquals(2400, final.height)
    }
}
