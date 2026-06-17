package com.ibbie.catrec_screenrecorcer.ui.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StartupRuntimePermissionLaunchTest {
    @Test
    fun `optional bluetooth launcher exception is reported and continues startup flow`() {
        var optionalSkipped = false
        var mandatoryBlocked = false
        val reported = mutableListOf<Throwable>()

        val launched =
            safeLaunchStartupRuntimePermission(
                permissionName = "android.permission.BLUETOOTH_CONNECT",
                flowStepName = "NEARBY_DEVICES",
                optional = true,
                launch = { throw RuntimeException("system permission launch failed") },
                onOptionalLaunchFailed = { optionalSkipped = true },
                onMandatoryLaunchFailed = { mandatoryBlocked = true },
                reportNonFatal = { reported += it },
            )

        assertFalse(launched)
        assertEquals(true, optionalSkipped)
        assertEquals(false, mandatoryBlocked)
        assertEquals(1, reported.size)
    }

    @Test
    fun `mandatory permission launcher exception is reported and blocks safely`() {
        var optionalSkipped = false
        var mandatoryBlocked = false
        val reported = mutableListOf<Throwable>()

        val launched =
            safeLaunchStartupRuntimePermission(
                permissionName = "android.permission.POST_NOTIFICATIONS",
                flowStepName = "NOTIFICATIONS",
                optional = false,
                launch = { throw RuntimeException("system permission launch failed") },
                onOptionalLaunchFailed = { optionalSkipped = true },
                onMandatoryLaunchFailed = { mandatoryBlocked = true },
                reportNonFatal = { reported += it },
            )

        assertFalse(launched)
        assertEquals(false, optionalSkipped)
        assertEquals(true, mandatoryBlocked)
        assertEquals(1, reported.size)
    }
}
