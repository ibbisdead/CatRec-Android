package com.ibbie.catrec_screenrecorcer.service

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingEngineMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.FileDescriptor

class ScreenRecorderEngineResizeTest {
    /** Installs JVM-safe mocks for Android and Firebase logging APIs used by the resize path. */
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any<Throwable>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns mockk(relaxed = true)
    }

    /** Clears static mocks so other JVM tests see their normal environment. */
    @After
    fun tearDown() {
        unmockkAll()
    }

    /** Verifies Performance resize requests are encoder-aligned and applied to the active VirtualDisplay. */
    @Test
    fun `performance resize aligns request and resizes virtual display`() {
        val engine =
            ScreenRecorderEngine(
                context = mockk<Context>(relaxed = true),
                width = 1088,
                height = 2400,
                dpi = 440,
                bitrate = 8_000_000,
                fps = 30,
                audioMode = ScreenRecorderEngine.AudioMode.NONE,
                mediaProjection = mockk<MediaProjection>(relaxed = true),
                outputFileDescriptor = FileDescriptor(),
                encoderType = "H.264",
                engineMode = RecordingEngineMode.PERFORMANCE,
            )
        val virtualDisplay = mockk<VirtualDisplay>(relaxed = true)
        val mediaCodec = mockk<MediaCodec>(relaxed = true)
        setPrivateField(engine, "directVirtualDisplay", virtualDisplay)
        setPrivateField(engine, "videoEncoder", mediaCodec)
        setPrivateField(engine, "captureWidth", 1088)
        setPrivateField(engine, "captureHeight", 2400)

        val expected =
            alignedPerformanceResizeSize(
                newW = 1301,
                newH = 777,
                encoderType = "H.264",
                fps = 30,
            )
        var observedVirtualDisplay: VirtualDisplay? = null
        var observedWidth = -1
        var observedHeight = -1
        var observedDpi = -1
        engine.performanceVirtualDisplayResizeStrategy =
            PerformanceVirtualDisplayResizeStrategy { display, width, height, densityDpi ->
                observedVirtualDisplay = display
                observedWidth = width
                observedHeight = height
                observedDpi = densityDpi
            }

        engine.resizeCaptureSource(1301, 777)

        assertSame(virtualDisplay, observedVirtualDisplay)
        assertEquals(expected.width, observedWidth)
        assertEquals(expected.height, observedHeight)
        assertEquals(440, observedDpi)
        assertEquals(expected.width, getPrivateIntField(engine, "captureWidth"))
        assertEquals(expected.height, getPrivateIntField(engine, "captureHeight"))
    }

    /** Verifies repeated same-size callbacks do not churn the active Performance VirtualDisplay. */
    @Test
    fun `performance resize returns early when aligned size is unchanged`() {
        val expected =
            alignedPerformanceResizeSize(
                newW = 1088,
                newH = 2400,
                encoderType = "H.264",
                fps = 30,
            )
        val engine =
            ScreenRecorderEngine(
                context = mockk<Context>(relaxed = true),
                width = expected.width,
                height = expected.height,
                dpi = 440,
                bitrate = 8_000_000,
                fps = 30,
                audioMode = ScreenRecorderEngine.AudioMode.NONE,
                mediaProjection = mockk<MediaProjection>(relaxed = true),
                outputFileDescriptor = FileDescriptor(),
                encoderType = "H.264",
                engineMode = RecordingEngineMode.PERFORMANCE,
            )
        setPrivateField(engine, "directVirtualDisplay", mockk<VirtualDisplay>(relaxed = true))
        setPrivateField(engine, "captureWidth", expected.width)
        setPrivateField(engine, "captureHeight", expected.height)

        var resizeCalls = 0
        engine.performanceVirtualDisplayResizeStrategy =
            PerformanceVirtualDisplayResizeStrategy { _, _, _, _ ->
                resizeCalls += 1
            }

        engine.resizeCaptureSource(1088, 2400)

        assertEquals(0, resizeCalls)
        assertEquals(expected.width, getPrivateIntField(engine, "captureWidth"))
        assertEquals(expected.height, getPrivateIntField(engine, "captureHeight"))
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

    private fun getPrivateIntField(
        target: Any,
        fieldName: String,
    ): Int {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getInt(target)
    }
}
