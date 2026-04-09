package com.ibbie.catrec_screenrecorcer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CatRecControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            ACTION_RECORD_TOGGLE -> handleRecordToggle(app)
            ACTION_SCREENSHOT_OR_PAUSE -> handleScreenshotOrPause(app)
            ACTION_OVERLAY_TOGGLE -> handleOverlayToggle(app)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            AppControlNotification.refresh(app)
        }, 350L)
    }

    private fun handleRecordToggle(app: Context) {
        when {
            RecordingState.isBuffering.value -> {
                app.startService(Intent(app, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_STOP_BUFFER
                })
            }
            RecordingState.isRecording.value -> {
                app.startService(Intent(app, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_STOP
                })
            }
            RecordingState.isPrepared.value -> {
                val mode = RecordingState.currentMode.value
                val action = if (mode == CaptureMode.CLIPPER) {
                    ScreenRecordService.ACTION_START_BUFFER_FROM_OVERLAY
                } else {
                    ScreenRecordService.ACTION_START_FROM_OVERLAY
                }
                app.startService(Intent(app, ScreenRecordService::class.java).apply { this.action = action })
            }
            else -> {
                app.startActivity(
                    Intent(app, MainActivity::class.java).apply {
                        action = "ACTION_START_RECORDING_FROM_OVERLAY"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                )
            }
        }
    }

    private fun handleScreenshotOrPause(app: Context) {
        when {
            RecordingState.isRecording.value -> {
                app.startService(Intent(app, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_TOGGLE_PAUSE
                })
            }
            else -> {
                app.startService(Intent(app, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
                })
            }
        }
    }

    private fun handleOverlayToggle(app: Context) {
        val floatingOn = runBlocking { SettingsRepository(app).floatingControls.first() }
        if (!floatingOn) {
            app.startActivity(
                Intent(app, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
            )
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            app.startActivity(
                Intent(app, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
            )
            return
        }
        if (OverlayService.idleControlsBubbleVisible) {
            app.startService(Intent(app, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CLOSE_OVERLAY
            })
        } else {
            app.startService(Intent(app, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_IDLE_CONTROLS
            })
        }
    }

    companion object {
        const val ACTION_RECORD_TOGGLE =
            "com.ibbie.catrec_screenrecorcer.CONTROL_RECORD_TOGGLE"
        const val ACTION_SCREENSHOT_OR_PAUSE =
            "com.ibbie.catrec_screenrecorcer.CONTROL_SCREENSHOT_OR_PAUSE"
        const val ACTION_OVERLAY_TOGGLE =
            "com.ibbie.catrec_screenrecorcer.CONTROL_OVERLAY_TOGGLE"
    }
}
