package com.ibbie.catrec_screenrecorcer.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View

/**
 * Hosts an invisible full-screen window so the system notification shade collapses before
 * [ScreenRecordService.ACTION_TAKE_SCREENSHOT] runs. Capturing immediately from a notification
 * [PendingIntent] often includes the shade in the virtual display; taking focus first avoids that.
 *
 * Scheduling uses [onWindowFocusChanged] plus one [Choreographer] frame (not a fixed sleep). A
 * delayed fallback exists only for devices where focus may not be delivered reliably.
 */
class ScreenshotAfterShadeActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val safetyFallback =
        Runnable {
            triggerCaptureAndFinish()
        }

    private val triggerLock = Any()
    private var triggered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        setContentView(
            View(this).apply {
                setBackgroundColor(0x00000000)
            },
        )
        // Best-effort: ask the system to collapse any open shade / panels. On Android 12+
        // this broadcast is restricted to privileged apps, so we treat success as optional.
        // The real guarantees come from [onWindowFocusChanged] + the Choreographer frame
        // below; the fallback timer covers devices that never deliver focus events.
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {
        }
        mainHandler.postDelayed(safetyFallback, SAFETY_FALLBACK_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            triggerCaptureAndFinish()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(safetyFallback)
        super.onDestroy()
    }

    private fun triggerCaptureAndFinish() {
        synchronized(triggerLock) {
            if (triggered) return
            triggered = true
        }
        mainHandler.removeCallbacks(safetyFallback)
        if (isFinishing) return
        // One frame after the shade should be gone; avoids capturing mid-animation when possible.
        Choreographer.getInstance().postFrameCallback { _ ->
            window.decorView.post {
                if (isFinishing) return@post
                try {
                    startService(
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
                        },
                    )
                } finally {
                    finish()
                    if (Build.VERSION.SDK_INT >= 34) {
                        overrideActivityTransition(
                            OVERRIDE_TRANSITION_CLOSE,
                            0,
                            0,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                }
            }
        }
    }

    companion object {
        private const val SAFETY_FALLBACK_MS = 600L
    }
}
