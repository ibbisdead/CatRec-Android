package com.ibbie.catrec_screenrecorcer.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFeatureCompatibilityTest {
    @Test
    fun `screenshot while recording is blocked in performance engine`() {
        val result =
            RecordingFeatureCompatibility.evaluate(
                RecordingEngineMode.PERFORMANCE,
                RecordingFeature.SCREENSHOT_WHILE_RECORDING,
            )

        assertFalse(result.available)
        assertSame(
            RecordingFeatureUnavailableReason.SCREENSHOT_WHILE_RECORDING_UNAVAILABLE,
            result.reason,
        )
        assertEquals(
            RecordingFeatureUnavailableReason.SCREENSHOT_WHILE_RECORDING_UNAVAILABLE.messageResId,
            result.reasonMessageResId,
        )
    }

    @Test
    fun `screenshot while recording is blocked for legacy compatibility engine`() {
        val result =
            RecordingFeatureCompatibility.evaluate(
                RecordingEngineMode.COMPATIBILITY,
                RecordingFeature.SCREENSHOT_WHILE_RECORDING,
            )

        assertFalse(result.available)
        assertSame(
            RecordingFeatureUnavailableReason.SCREENSHOT_WHILE_RECORDING_UNAVAILABLE,
            result.reason,
        )
        assertEquals(
            RecordingFeatureUnavailableReason.SCREENSHOT_WHILE_RECORDING_UNAVAILABLE.messageResId,
            result.reasonMessageResId,
        )
    }

    @Test
    fun `normal recording is allowed in both engines`() {
        assertTrue(
            RecordingFeatureCompatibility.isAvailable(
                RecordingEngineMode.PERFORMANCE,
                RecordingFeature.NORMAL_RECORDING,
            ),
        )
        assertTrue(
            RecordingFeatureCompatibility.isAvailable(
                RecordingEngineMode.COMPATIBILITY,
                RecordingFeature.NORMAL_RECORDING,
            ),
        )
    }

    @Test
    fun `audio capture and separate mic are allowed in both engines`() {
        for (mode in RecordingEngineMode.entries) {
            assertTrue(
                RecordingFeatureCompatibility.isAvailable(
                    mode,
                    RecordingFeature.AUDIO_CAPTURE,
                ),
            )
            assertTrue(
                RecordingFeatureCompatibility.isAvailable(
                    mode,
                    RecordingFeature.SEPARATE_MIC_TRACK,
                ),
            )
        }
    }

    @Test
    fun `rolling buffer is allowed by model compatibility in both engines`() {
        for (mode in RecordingEngineMode.entries) {
            assertTrue(
                RecordingFeatureCompatibility.isAvailable(
                    mode,
                    RecordingFeature.ROLLING_BUFFER,
                ),
            )
        }
    }

    @Test
    fun `overlay gif and adaptive features are allowed in both engines`() {
        val features =
            listOf(
                RecordingFeature.OVERLAY_CONTROLS,
                RecordingFeature.CAMERA_OVERLAY,
                RecordingFeature.WATERMARK_OVERLAY,
                RecordingFeature.GIF_MODE,
                RecordingFeature.ADAPTIVE_PERFORMANCE,
            )

        for (mode in RecordingEngineMode.entries) {
            for (feature in features) {
                assertTrue(
                    RecordingFeatureCompatibility.isAvailable(
                        mode,
                        feature,
                    ),
                )
            }
        }
    }
}
