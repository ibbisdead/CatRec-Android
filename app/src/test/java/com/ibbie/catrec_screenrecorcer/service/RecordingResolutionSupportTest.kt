package com.ibbie.catrec_screenrecorcer.service

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingResolutionSupportTest {
    @Test
    fun `orientPhysicalByRotation swaps at 90 and 270`() {
        assertEquals(
            RecordingResolutionSize(2400, 1080),
            RecordingResolutionSupport.orientPhysicalByRotation(1080, 2400, Surface.ROTATION_90),
        )
        assertEquals(
            RecordingResolutionSize(2400, 1080),
            RecordingResolutionSupport.orientPhysicalByRotation(1080, 2400, Surface.ROTATION_270),
        )
        assertEquals(
            RecordingResolutionSize(1080, 2400),
            RecordingResolutionSupport.orientPhysicalByRotation(1080, 2400, Surface.ROTATION_0),
        )
    }

    @Test
    fun `applyRecordingOrientationPreference portrait lock`() {
        val input = RecordingResolutionSize(2400, 1080)
        assertEquals(
            RecordingResolutionSize(1080, 2400),
            RecordingResolutionSupport.applyRecordingOrientationPreference(input, "Portrait"),
        )
    }

    @Test
    fun `applyRecordingOrientationPreference landscape lock`() {
        val input = RecordingResolutionSize(1080, 2400)
        assertEquals(
            RecordingResolutionSize(2400, 1080),
            RecordingResolutionSupport.applyRecordingOrientationPreference(input, "Landscape"),
        )
    }

    @Test
    fun `clampForVirtualDisplaySafety downscales 3840x2384 panel within caps`() {
        val clamped =
            RecordingResolutionSupport.clampForVirtualDisplaySafety(3840, 2384)
        assertTrue(clamped.pixels < 3840L * 2384L)
        assertTrue(maxOf(clamped.width, clamped.height) <= 2560)
        assertTrue(clamped.pixels <= 2560L * 1440L + 2560L)
    }

    @Test
    fun `getEncoderCaptureResolution never exceeds requested and stays encoder-safe`() {
        val final =
            RecordingResolutionSupport.getEncoderCaptureResolution(
                requestedSize = RecordingResolutionSize(1220, 2712),
                videoEncoder = "H.264",
                fps = 30,
            )
        assertTrue(final.width <= 1220)
        assertTrue(final.height <= 2712)
        assertEquals(0, final.width % 2)
        assertEquals(0, final.height % 2)
    }

    @Test
    fun `classifyNativeRecordingNote stability when panel equals logical and VD clamp applies`() {
        val panel = RecordingResolutionSize(3840, 2384)
        val logical = RecordingResolutionSize(3840, 2384)
        val afterClamp = RecordingResolutionSupport.clampForVirtualDisplaySafety(3840, 2384)
        val final = RecordingResolutionSize(2432, 1504)
        assertEquals(
            NativeRecordingSizeNoteKind.STABILITY,
            RecordingResolutionSupport.classifyNativeRecordingNote(panel, logical, afterClamp, final),
        )
    }

    @Test
    fun `classifyNativeRecordingNote scaling when panel differs and no VD clamp`() {
        val panel = RecordingResolutionSize(1440, 3200)
        val logical = RecordingResolutionSize(1080, 2400)
        val afterClamp = RecordingResolutionSupport.clampForVirtualDisplaySafety(1080, 2400)
        val final = RecordingResolutionSize(1080, 2400)
        assertEquals(
            NativeRecordingSizeNoteKind.DISPLAY_SCALING,
            RecordingResolutionSupport.classifyNativeRecordingNote(panel, logical, afterClamp, final),
        )
    }

    @Test
    fun `classifyNativeRecordingNote null when final matches panel`() {
        val panel = RecordingResolutionSize(1080, 2400)
        val logical = RecordingResolutionSize(1080, 2400)
        val afterClamp = logical
        val final = logical
        assertEquals(
            null,
            RecordingResolutionSupport.classifyNativeRecordingNote(panel, logical, afterClamp, final),
        )
    }
}
