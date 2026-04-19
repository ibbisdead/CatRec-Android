package com.ibbie.catrec_screenrecorcer.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExitUiCoordinatorAndroidTest {
    private lateinit var app: Context

    @Before
    fun setup() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        app.getSharedPreferences(ExitUiCoordinator.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun consume_returns_false_when_never_marked() {
        assertFalse(ExitUiCoordinator.consumePendingFinishAffinity(app))
    }

    @Test
    fun mark_then_consume_returns_true_once() {
        ExitUiCoordinator.markPendingFinishAffinity(app)
        assertTrue(ExitUiCoordinator.consumePendingFinishAffinity(app))
        assertFalse(ExitUiCoordinator.consumePendingFinishAffinity(app))
    }
}
