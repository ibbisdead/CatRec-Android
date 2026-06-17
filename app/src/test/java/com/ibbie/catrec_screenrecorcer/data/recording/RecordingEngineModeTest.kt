package com.ibbie.catrec_screenrecorcer.data.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingEngineModeTest {
    @Test
    fun `missing storage value defaults to performance`() {
        assertEquals(
            RecordingEngineMode.PERFORMANCE,
            RecordingEngineMode.fromStorageValue(null),
        )
    }

    @Test
    fun `unknown storage value defaults to performance`() {
        assertEquals(
            RecordingEngineMode.PERFORMANCE,
            RecordingEngineMode.fromStorageValue("future-engine"),
        )
    }

    @Test
    fun `storage values parse safely`() {
        assertEquals(
            RecordingEngineMode.PERFORMANCE,
            RecordingEngineMode.fromStorageValue("performance"),
        )
        assertEquals(
            RecordingEngineMode.PERFORMANCE,
            RecordingEngineMode.fromStorageValue("compatibility"),
        )
    }
}
