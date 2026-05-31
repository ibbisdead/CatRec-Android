package com.ibbie.catrec_screenrecorcer.data.recording

import org.junit.Assert.assertFalse
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
    }

    @Test
    fun `screenshot while recording is allowed in compatibility engine`() {
        val result =
            RecordingFeatureCompatibility.evaluate(
                RecordingEngineMode.COMPATIBILITY,
                RecordingFeature.SCREENSHOT_WHILE_RECORDING,
            )

        assertTrue(result.available)
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
}
