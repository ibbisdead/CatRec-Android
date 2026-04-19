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
 * [onWindowFocusChanged] can still fire while the shade is animating closed, so we wait several
 * vsyncs plus a short post-delay before starting the capture service. A delayed fallback exists
 * for devices where focus may not be delivered reliably.
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
        val ch = Choreographer.getInstance()
        fun postChainedFrames(
            remaining: Int,
            done: () -> Unit,
        ) {
            if (remaining <= 0) {
                done()
            } else {
                ch.postFrameCallback { postChainedFrames(remaining - 1, done) }
            }
        }
        postChainedFrames(VSYNCS_BEFORE_CAPTURE) {
            window.decorView.post {
                if (isFinishing) return@post
                // One more hop after layout; then a short delay so the display pipeline shows the
                // content under the shade (not the last shade frame).
                mainHandler.postDelayed({
                    if (isFinishing) return@postDelayed
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
                }, POST_CAPTURE_DELAY_MS)
            }
        }
    }

    companion object {
        private const val SAFETY_FALLBACK_MS = 600L
        /** Wait this many vsyncs after window focus before scheduling capture. */
        private const val VSYNCS_BEFORE_CAPTURE = 5
        private const val POST_CAPTURE_DELAY_MS = 120L
    }
}
