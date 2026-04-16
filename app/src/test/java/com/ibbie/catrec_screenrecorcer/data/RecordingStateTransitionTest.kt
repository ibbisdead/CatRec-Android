package com.ibbie.catrec_screenrecorcer.data

import io.mockk.mockk
import io.mockk.verifySequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RecordingState] is a process-wide singleton; tests reset flags in @[After] so they stay hermetic.
 * MockK drives a small [RecordingPhaseListener] so we exercise **verifySequence** against phases
 * derived from the same booleans the UI observes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStateTransitionTest {
    interface RecordingPhaseListener {
        fun onPhase(phase: RecordingSessionPhase)
    }

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        resetRecordingState()
    }

    @After
    fun tearDown() {
        resetRecordingState()
        Dispatchers.resetMain()
    }

    private fun resetRecordingState() {
        RecordingState.setPrepared(false)
        RecordingState.setRecording(false)
        RecordingState.setBuffering(false)
        RecordingState.setRecordingPaused(false)
        RecordingState.updateDuration(0L)
    }

    @Test
    fun `idle to preparing to recording to saved sequence matches flows`() =
        runTest {
            val listener = mockk<RecordingPhaseListener>(relaxed = true)
            var hadActiveCapture = false

            fun snapshotPhase(): RecordingSessionPhase {
                val p = RecordingState.isPrepared.value
                val r = RecordingState.isRecording.value
                val b = RecordingState.isBuffering.value
                return RecordingPhaseMapper.derive(p, r, b, hadActiveCapture)
            }

            fun emitPhase() {
                listener.onPhase(snapshotPhase())
            }

            // Idle
            assertEquals(RecordingSessionPhase.Idle, snapshotPhase())
            emitPhase()

            // Preparing (MediaProjection “ready”, overlay path)
            RecordingState.setPrepared(true)
            assertTrue(RecordingState.isPrepared.value)
            assertFalse(RecordingState.isRecording.value)
            assertEquals(RecordingSessionPhase.Preparing, snapshotPhase())
            emitPhase()

            // Recording
            RecordingState.setRecording(true)
            hadActiveCapture = true
            assertTrue(RecordingState.isRecording.value)
            assertEquals(RecordingSessionPhase.Recording, snapshotPhase())
            emitPhase()

            // Saved (capture ended; same mapper as post-stop UX — not “Preparing” because we clear prepare for this test path)
            RecordingState.setRecording(false)
            RecordingState.setPrepared(false)
            assertEquals(RecordingSessionPhase.Saved, snapshotPhase())
            emitPhase()

            verifySequence {
                listener.onPhase(RecordingSessionPhase.Idle)
                listener.onPhase(RecordingSessionPhase.Preparing)
                listener.onPhase(RecordingSessionPhase.Recording)
                listener.onPhase(RecordingSessionPhase.Saved)
            }
        }
}
