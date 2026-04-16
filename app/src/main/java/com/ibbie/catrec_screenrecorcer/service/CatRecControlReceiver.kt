package com.ibbie.catrec_screenrecorcer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatRecControlReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val app = context.applicationContext
        when (intent?.action) {
            ACTION_RECORD_TOGGLE -> handleRecordToggle(app)
            ACTION_SCREENSHOT_OR_PAUSE -> handleScreenshotOrPause(app)
            ACTION_OVERLAY_TOGGLE -> {
                val pending = goAsync()
                receiverScope.launch {
                    try {
                        val floatingOn =
                            if (FloatingControlsNotificationCache.initialized) {
                                FloatingControlsNotificationCache.peek()
                            } else {
                                Log.d(TAG, "overlay_toggle_floating: DataStore cold fallback")
                                val v = SettingsRepository(app).floatingControls.first()
                                FloatingControlsNotificationCache.update(v)
                                v
                            }
                        withContext(Dispatchers.Main) {
                            try {
                                handleOverlayToggleOnMain(app, floatingOn)
                            } finally {
                                pending.finish()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "overlay_toggle read failed", e)
                        pending.finish()
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        AppControlNotification.refresh(app)
                    }, 350L)
                }
                return
            }
            ACTION_EXIT_APP -> {
                app.stopService(Intent(app, OverlayService::class.java))
                app.startService(
                    Intent(app, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_EXIT_SERVICE
                    },
                )
                AppControlNotification.cancel(app)
                app.sendBroadcast(Intent(MainActivity.ACTION_FINISH_UI).setPackage(app.packageName))
                return
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            AppControlNotification.refresh(app)
        }, 350L)
    }

    private fun handleRecordToggle(app: Context) {
        when {
            RecordingState.isBuffering.value -> {
                app.startService(
                    Intent(app, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP_BUFFER
                    },
                )
            }
            RecordingState.isRecording.value -> {
                app.startService(
                    Intent(app, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_TOGGLE_PAUSE
                    },
                )
            }
            RecordingState.isPrepared.value -> {
                val mode = RecordingState.currentMode.value
                val action =
                    if (mode == CaptureMode.CLIPPER) {
                        ScreenRecordService.ACTION_START_BUFFER_FROM_OVERLAY
                    } else {
                        ScreenRecordService.ACTION_START_FROM_OVERLAY
                    }
                app.startService(Intent(app, ScreenRecordService::class.java).apply { this.action = action })
            }
            else -> {
                val asBuffer = RecordingState.currentMode.value == CaptureMode.CLIPPER
                app.startActivity(
                    Intent(app, OverlayRecordProjectionActivity::class.java).apply {
                        putExtra(OverlayRecordProjectionActivity.EXTRA_START_AS_BUFFER, asBuffer)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    private fun handleScreenshotOrPause(app: Context) {
        when {
            RecordingState.isRecording.value -> {
                app.startService(
                    Intent(app, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    },
                )
            }
            else -> {
                app.startService(
                    Intent(app, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
                    },
                )
            }
        }
    }

    private fun handleOverlayToggleOnMain(
        app: Context,
        floatingOn: Boolean,
    ) {
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
            app.startService(
                Intent(app, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_CLOSE_OVERLAY
                },
            )
        } else {
            app.startService(
                Intent(app, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SHOW_IDLE_CONTROLS
                },
            )
        }
    }

    companion object {
        private const val TAG = "CatRecControlReceiver"

        private val receiverJob = SupervisorJob()
        private val receiverScope = CoroutineScope(receiverJob + Dispatchers.IO)

        const val ACTION_RECORD_TOGGLE =
            "com.ibbie.catrec_screenrecorcer.CONTROL_RECORD_TOGGLE"
        const val ACTION_SCREENSHOT_OR_PAUSE =
            "com.ibbie.catrec_screenrecorcer.CONTROL_SCREENSHOT_OR_PAUSE"
        const val ACTION_OVERLAY_TOGGLE =
            "com.ibbie.catrec_screenrecorcer.CONTROL_OVERLAY_TOGGLE"
        const val ACTION_EXIT_APP =
            "com.ibbie.catrec_screenrecorcer.CONTROL_EXIT_APP"
    }
}
