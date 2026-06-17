package com.ibbie.catrec_screenrecorcer.utils

import com.ibbie.catrec_screenrecorcer.service.InternalAudioHealthTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecordingCrashlyticsReporterTest {
    @Test
    fun `suppresses zero main mux audio when startup cleanup ran before internal audio started`() {
        val health =
            internalHealthSnapshot(
                internalAudioRecordCreated = true,
                internalAudioRecordStarted = false,
            )

        assertTrue(
            AudioRecordingCrashlyticsReporter.shouldSuppressZeroMainMuxAudioAsStartupCleanup(
                needMainWritten = true,
                mainMuxSamplesWritten = false,
                audioInputEosQueued = false,
                audioOutputEosObserved = false,
                internalAudioHealth = health,
            ),
        )
    }

    @Test
    fun `does not suppress zero main mux audio after internal audio started`() {
        val health =
            internalHealthSnapshot(
                internalAudioRecordCreated = true,
                internalAudioRecordStarted = true,
            )

        assertFalse(
            AudioRecordingCrashlyticsReporter.shouldSuppressZeroMainMuxAudioAsStartupCleanup(
                needMainWritten = true,
                mainMuxSamplesWritten = false,
                audioInputEosQueued = false,
                audioOutputEosObserved = false,
                internalAudioHealth = health,
            ),
        )
    }

    @Test
    fun `does not suppress zero main mux audio when reads happened`() {
        val health =
            internalHealthSnapshot(
                internalAudioRecordCreated = true,
                internalAudioRecordStarted = false,
                positiveReadCount = 1L,
                totalBytesRead = 1024L,
            )

        assertFalse(
            AudioRecordingCrashlyticsReporter.shouldSuppressZeroMainMuxAudioAsStartupCleanup(
                needMainWritten = true,
                mainMuxSamplesWritten = false,
                audioInputEosQueued = false,
                audioOutputEosObserved = false,
                internalAudioHealth = health,
            ),
        )
    }

    @Test
    fun `does not suppress zero main mux audio when eos was queued`() {
        val health =
            internalHealthSnapshot(
                internalAudioRecordCreated = true,
                internalAudioRecordStarted = false,
            )

        assertFalse(
            AudioRecordingCrashlyticsReporter.shouldSuppressZeroMainMuxAudioAsStartupCleanup(
                needMainWritten = true,
                mainMuxSamplesWritten = false,
                audioInputEosQueued = true,
                audioOutputEosObserved = false,
                internalAudioHealth = health,
            ),
        )
    }

    private fun internalHealthSnapshot(
        internalAudioRecordCreated: Boolean,
        internalAudioRecordStarted: Boolean,
        positiveReadCount: Long = 0L,
        zeroReadCount: Long = 0L,
        negativeReadCount: Long = 0L,
        totalBytesRead: Long = 0L,
    ) = InternalAudioHealthTracker.Snapshot(
        recordingType = "FULL",
        engineModeName = "PERFORMANCE",
        requestedAudioModeName = "INTERNAL",
        micRequested = false,
        internalRequested = true,
        separateMicRequested = false,
        internalAudioRecordCreated = internalAudioRecordCreated,
        internalAudioRecordStarted = internalAudioRecordStarted,
        audioRecordStateAfterCreate = 1,
        recordingStateAfterStart = -1,
        sampleRate = 44_100,
        channelCount = 1,
        encoding = 2,
        bufferSizeBytes = 7168,
        firstPositiveReadElapsedMs = -1L,
        firstNonZeroPcmElapsedMs = -1L,
        firstAudiblePcmElapsedMs = -1L,
        totalBytesRead = totalBytesRead,
        positiveReadCount = positiveReadCount,
        zeroReadCount = zeroReadCount,
        negativeReadCount = negativeReadCount,
        peakAbs = 0,
        rmsEstimate = 0,
        stage1SilentAt4s = false,
        stage2SilentAt10s = false,
        persistentSilentAt20s = false,
        rebuildAttemptCount = 0,
        rebuildSuccessCount = 0,
        recoveredAfterRebuild = false,
        recoveredLate = false,
        fallbackTriggered = false,
        finalHadInternalAudioSamples = false,
        muxerStarted = false,
        muxerAudioSamplesWritten = false,
    )
}
