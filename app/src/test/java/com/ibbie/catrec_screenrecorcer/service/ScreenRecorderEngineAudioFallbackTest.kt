package com.ibbie.catrec_screenrecorcer.service

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.util.Log
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import com.ibbie.catrec_screenrecorcer.utils.AudioRecordingCrashlyticsReporter
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException

class ScreenRecorderEngineAudioFallbackTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0

        mockkObject(AudioRecordingCrashlyticsReporter)
        every { AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `mixed separate mic fallback keeps sidecar mic and disables main audio`() {
        val engine = newEngine(separateMic = true, audioMode = ScreenRecorderEngine.AudioMode.MIXED)
        val mic = mockAudioRecord(channelCount = 1)
        val internal = mockAudioRecord(channelCount = 1)
        val mainEncoder = mockk<MediaCodec>(relaxed = true)
        val releasedRecords = mutableListOf<AudioRecord?>()
        val downgradeNotices = mutableListOf<Int>()
        engine.audioDowngradeNoticeDispatcher = { resId -> downgradeNotices += resId }
        setPrivateField(engine, "mAudioMode", ScreenRecorderEngine.AudioMode.MIXED)
        setPrivateField(engine, "mainMuxAudioMode", ScreenRecorderEngine.AudioMode.INTERNAL)
        setPrivateField(engine, "micRecord", mic)
        setPrivateField(engine, "internalRecord", internal)
        setPrivateField(engine, "audioEncoder", mainEncoder)

        invokeResolveMixedInternalStartFailure(engine) { record ->
            releasedRecords += record
        }

        assertSame(internal, releasedRecords.single())
        assertNull(getPrivateField(engine, "internalRecord"))
        assertEquals(ScreenRecorderEngine.AudioMode.MIC, getPrivateField(engine, "mAudioMode"))
        assertEquals(ScreenRecorderEngine.AudioMode.NONE, getPrivateField(engine, "mainMuxAudioMode"))
        assertNull(getPrivateField(engine, "audioEncoder"))
        assertEquals(listOf(R.string.toast_audio_downgraded_internal_unavailable_mic_only), downgradeNotices)
        verify { mainEncoder.stop() }
        verify { mainEncoder.release() }
        verify {
            AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable(
                "internal_leg_failed_kept_separate_mic",
            )
        }
    }

    @Test
    fun `mixed separate mic fallback still throws when mic is missing`() {
        val engine = newEngine(separateMic = true, audioMode = ScreenRecorderEngine.AudioMode.MIXED)
        setPrivateField(engine, "mAudioMode", ScreenRecorderEngine.AudioMode.MIXED)
        setPrivateField(engine, "micRecord", null)
        setPrivateField(engine, "internalRecord", mockAudioRecord())

        assertThrows(IllegalStateException::class.java) {
            invokeResolveMixedInternalStartFailure(engine)
        }
        verify(exactly = 0) { AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable(any()) }
    }

    @Test
    fun `internal only still throws when internal startup fails`() {
        val engine = newEngine(separateMic = false, audioMode = ScreenRecorderEngine.AudioMode.INTERNAL)
        setPrivateField(engine, "mAudioMode", ScreenRecorderEngine.AudioMode.INTERNAL)
        setPrivateField(engine, "micRecord", null)
        setPrivateField(engine, "internalRecord", mockAudioRecord())

        assertThrows(IllegalStateException::class.java) {
            invokeResolveMixedInternalStartFailure(engine)
        }
        verify(exactly = 0) { AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable(any()) }
    }

    @Test
    fun `mixed without separate mic preserves existing main mux downgrade`() {
        val engine = newEngine(separateMic = false, audioMode = ScreenRecorderEngine.AudioMode.MIXED)
        val mic = mockAudioRecord(channelCount = 1)
        val internal = mockAudioRecord(channelCount = 1)
        val mainEncoder = mockk<MediaCodec>(relaxed = true)
        val releasedRecords = mutableListOf<AudioRecord?>()
        val downgradeNotices = mutableListOf<Int>()
        engine.audioDowngradeNoticeDispatcher = { resId -> downgradeNotices += resId }
        setPrivateField(engine, "mAudioMode", ScreenRecorderEngine.AudioMode.MIXED)
        setPrivateField(engine, "mainMuxAudioMode", ScreenRecorderEngine.AudioMode.MIXED)
        setPrivateField(engine, "configuredMainAacChannels", 1)
        setPrivateField(engine, "micRecord", mic)
        setPrivateField(engine, "internalRecord", internal)
        setPrivateField(engine, "audioEncoder", mainEncoder)

        invokeResolveMixedInternalStartFailure(engine) { record ->
            releasedRecords += record
        }

        assertSame(internal, releasedRecords.single())
        assertNull(getPrivateField(engine, "internalRecord"))
        assertEquals(ScreenRecorderEngine.AudioMode.MIC, getPrivateField(engine, "mAudioMode"))
        assertEquals(ScreenRecorderEngine.AudioMode.MIC, getPrivateField(engine, "mainMuxAudioMode"))
        assertSame(mainEncoder, getPrivateField(engine, "audioEncoder"))
        assertEquals(listOf(R.string.toast_audio_downgraded_internal_unavailable_mic_only), downgradeNotices)
        verify(exactly = 0) { mainEncoder.stop() }
        verify(exactly = 0) { mainEncoder.release() }
        verify { AudioRecordingCrashlyticsReporter.noteMixedDowngradeRecoverable("internal_leg_failed_kept_mic") }
    }

    @Test
    fun `mixed separate mic internal zero pcm switches main mux to mic after confirmed stage1 silence`() {
        val engine = newEngine(separateMic = true, audioMode = ScreenRecorderEngine.AudioMode.MIXED)

        val shouldSwitch =
            engine.shouldSwitchMixedSeparateMicMainMuxEarly(
                routeMicToSeparateFile = true,
                currentAudioMode = ScreenRecorderEngine.AudioMode.MIXED,
                currentMainMuxAudioMode = ScreenRecorderEngine.AudioMode.INTERNAL,
                internalHealth = internalHealthSnapshot(
                    stage1SilentAt4s = true,
                    firstAudiblePcmElapsedMs = -1L,
                    totalBytesRead = 839_680L,
                    positiveReadCount = 410L,
                    peakAbs = 0,
                    rmsEstimate = 0,
                ),
                micStats = ScreenRecorderEngine.PcmSignalStats(
                    byteCount = 4096,
                    peakAbs = 3200,
                    rms = 700,
                    audible = true,
                ),
                earlySwitchAlreadyAttempted = false,
            )

        assertEquals(true, shouldSwitch)
    }

    @Test
    fun `normal internal audio does not switch mixed separate mic main mux to mic`() {
        val engine = newEngine(separateMic = true, audioMode = ScreenRecorderEngine.AudioMode.MIXED)

        val shouldSwitch =
            engine.shouldSwitchMixedSeparateMicMainMuxEarly(
                routeMicToSeparateFile = true,
                currentAudioMode = ScreenRecorderEngine.AudioMode.MIXED,
                currentMainMuxAudioMode = ScreenRecorderEngine.AudioMode.INTERNAL,
                internalHealth = internalHealthSnapshot(
                    stage1SilentAt4s = false,
                    firstAudiblePcmElapsedMs = 1200L,
                    totalBytesRead = 128_000L,
                    positiveReadCount = 80L,
                    peakAbs = 2400,
                    rmsEstimate = 500,
                ),
                micStats = ScreenRecorderEngine.PcmSignalStats(
                    byteCount = 4096,
                    peakAbs = 3200,
                    rms = 700,
                    audible = true,
                ),
                earlySwitchAlreadyAttempted = false,
            )

        assertEquals(false, shouldSwitch)
    }

    private fun newEngine(
        separateMic: Boolean,
        audioMode: ScreenRecorderEngine.AudioMode,
    ): ScreenRecorderEngine =
        ScreenRecorderEngine(
            context = mockk<Context>(relaxed = true),
            width = 1080,
            height = 1920,
            dpi = 420,
            bitrate = 8_000_000,
            fps = 30,
            audioMode = audioMode,
            mediaProjection = mockk<MediaProjection>(relaxed = true),
            outputFileDescriptor = FileDescriptor(),
            encoderType = "H.264",
            engineMode = RecordingEngineMode.PERFORMANCE,
            separateMicFileDescriptor = if (separateMic) FileDescriptor() else null,
        )

    private fun mockAudioRecord(channelCount: Int = 1): AudioRecord =
        mockk<AudioRecord>(relaxed = true).also { record ->
            every { record.channelCount } returns channelCount
        }

    private fun internalHealthSnapshot(
        stage1SilentAt4s: Boolean,
        firstAudiblePcmElapsedMs: Long,
        totalBytesRead: Long,
        positiveReadCount: Long,
        peakAbs: Int,
        rmsEstimate: Int,
    ) = InternalAudioHealthTracker.Snapshot(
        recordingType = "normal",
        engineModeName = "PERFORMANCE",
        requestedAudioModeName = "MIXED",
        micRequested = true,
        internalRequested = true,
        separateMicRequested = true,
        internalAudioRecordCreated = true,
        internalAudioRecordStarted = true,
        audioRecordStateAfterCreate = 1,
        recordingStateAfterStart = 3,
        sampleRate = 48_000,
        channelCount = 1,
        encoding = 2,
        bufferSizeBytes = 7680,
        firstPositiveReadElapsedMs = 1000L,
        firstNonZeroPcmElapsedMs = if (peakAbs > 0) firstAudiblePcmElapsedMs else -1L,
        firstAudiblePcmElapsedMs = firstAudiblePcmElapsedMs,
        totalBytesRead = totalBytesRead,
        positiveReadCount = positiveReadCount,
        zeroReadCount = 0L,
        negativeReadCount = 0L,
        peakAbs = peakAbs,
        rmsEstimate = rmsEstimate,
        stage1SilentAt4s = stage1SilentAt4s,
        stage2SilentAt10s = false,
        persistentSilentAt20s = false,
        rebuildAttemptCount = 0,
        rebuildSuccessCount = 0,
        recoveredAfterRebuild = false,
        recoveredLate = false,
        fallbackTriggered = false,
        finalHadInternalAudioSamples = firstAudiblePcmElapsedMs >= 0L,
        muxerStarted = false,
        muxerAudioSamplesWritten = false,
    )

    private fun invokeResolveMixedInternalStartFailure(
        engine: ScreenRecorderEngine,
        releaseQuiet: (AudioRecord?) -> Unit = {},
    ) {
        val method =
            ScreenRecorderEngine::class.java.getDeclaredMethod(
                "resolveMixedInternalStartFailureContinueOrThrow",
                Throwable::class.java,
                Boolean::class.javaPrimitiveType,
                Function1::class.java,
            )
        method.isAccessible = true
        try {
            method.invoke(
                engine,
                IllegalStateException("internal recordingState=1 after startRecording()"),
                false,
                releaseQuiet,
            )
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun setPrivateField(
        target: Any,
        fieldName: String,
        value: Any?,
    ) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getPrivateField(
        target: Any,
        fieldName: String,
    ): Any? {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target)
    }
}
