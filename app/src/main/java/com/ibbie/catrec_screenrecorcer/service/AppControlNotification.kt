package com.ibbie.catrec_screenrecorcer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ongoing notification with icon [ImageButton]s (custom [RemoteViews]), shown while the main UI is available.
 * Refreshed when capture state changes.
 */
object AppControlNotification {
    const val CHANNEL_ID = "CatRec_App_Controls"
    private const val NOTIFICATION_ID = 9102
    private const val TAG = "AppControlNotification"

    private val refreshJob = SupervisorJob()
    private val refreshScope = CoroutineScope(refreshJob + Dispatchers.Main.immediate)

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_app_controls),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_app_controls_desc)
                setShowBadge(false)
            },
        )
    }

    fun cancel(context: Context) {
        context
            .getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID)
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)

        val isRecording = RecordingState.isRecording.value
        val isBuffering = RecordingState.isBuffering.value
        val isPrepared = RecordingState.isPrepared.value
        val isSaving = RecordingState.isSaving.value

        // Single shade entry: ScreenRecordService uses [ScreenRecordService.MAIN_FOREGROUND_NOTIFICATION_ID].
        // If we are currently saving a video/GIF, suppress the idle notification so it doesn't double up.
        if (isBuffering || isSaving) {
            cancel(app)
            return
        }
        if (isPrepared || isRecording) {
            cancel(app)
            app.startService(
                Intent(app, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_REFRESH_MAIN_NOTIFICATION
                },
            )
            return
        }

        refreshScope.launch(Dispatchers.IO) {
            try {
                val floatingOn =
                    if (FloatingControlsNotificationCache.initialized) {
                        FloatingControlsNotificationCache.peek()
                    } else {
                        Log.d(TAG, "app_control_notif floating: DataStore cold fallback")
                        val v = SettingsRepository(app).floatingControls.first()
                        FloatingControlsNotificationCache.update(v)
                        v
                    }
                withContext(Dispatchers.Main) {
                    val overlayVisible = OverlayService.idleControlsBubbleVisible
                    postIdleAppControlNotification(app, floatingOn, overlayVisible)
                }
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
            }
        }
    }

    private fun postIdleAppControlNotification(
        app: Context,
        floatingOn: Boolean,
        overlayVisible: Boolean,
    ) {
        val homePi =
            PendingIntent.getActivity(
                app,
                1,
                Intent(app, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val exitPi =
            PendingIntent.getBroadcast(
                app,
                2,
                Intent(app, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_EXIT_APP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val recordPi =
            PendingIntent.getBroadcast(
                app,
                10,
                Intent(app, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_RECORD_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val screenshotPi =
            PendingIntent.getBroadcast(
                app,
                11,
                Intent(app, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_SCREENSHOT_OR_PAUSE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val overlayPi =
            PendingIntent.getBroadcast(
                app,
                12,
                Intent(app, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_OVERLAY_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        // Idle-only: Record · Screenshot · Overlay · Exit (same row IDs as the service notification).
        val primaryIcon = R.drawable.ic_record
        val primaryDesc = app.getString(R.string.recording_start)
        val secondaryIcon = R.drawable.ic_screenshot
        val secondaryDesc = app.getString(R.string.notif_action_screenshot)
        val primaryLabel = app.getString(R.string.notif_cc_record)
        val secondaryLabel = app.getString(R.string.notif_cc_shot)

        val overlayDesc =
            when {
                !floatingOn -> app.getString(R.string.notif_action_overlay_enable_in_settings)
                overlayVisible -> app.getString(R.string.notif_action_overlay_hide)
                else -> app.getString(R.string.notif_action_overlay_show)
            }

        val summary = app.getString(R.string.notif_app_controls_text_idle)

        val collapsed = RemoteViews(app.packageName, R.layout.notification_app_controls_collapsed)
        collapsed.setImageViewResource(R.id.btn_record_icon, primaryIcon)
        collapsed.setTextViewText(R.id.btn_record_label, primaryLabel)
        collapsed.setContentDescription(R.id.btn_record, primaryDesc)
        collapsed.setOnClickPendingIntent(R.id.btn_record, recordPi)

        collapsed.setImageViewResource(R.id.btn_screenshot_icon, secondaryIcon)
        collapsed.setTextViewText(R.id.btn_screenshot_label, secondaryLabel)
        collapsed.setContentDescription(R.id.btn_screenshot, secondaryDesc)
        collapsed.setOnClickPendingIntent(R.id.btn_screenshot, screenshotPi)

        collapsed.setContentDescription(R.id.notif_ac_overlay, overlayDesc)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_overlay, overlayPi)
        if (floatingOn) {
            collapsed.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", 255)
        } else {
            collapsed.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", 100)
        }

        collapsed.setOnClickPendingIntent(R.id.notif_ac_home, homePi)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_exit, exitPi)

        val expanded = RemoteViews(app.packageName, R.layout.notification_app_controls_expanded)
        expanded.setImageViewResource(R.id.btn_record_icon, primaryIcon)
        expanded.setTextViewText(R.id.btn_record_label, primaryLabel)
        expanded.setContentDescription(R.id.btn_record, primaryDesc)
        expanded.setOnClickPendingIntent(R.id.btn_record, recordPi)

        expanded.setImageViewResource(R.id.btn_screenshot_icon, secondaryIcon)
        expanded.setTextViewText(R.id.btn_screenshot_label, secondaryLabel)
        expanded.setContentDescription(R.id.btn_screenshot, secondaryDesc)
        expanded.setOnClickPendingIntent(R.id.btn_screenshot, screenshotPi)

        expanded.setContentDescription(R.id.notif_ac_overlay, overlayDesc)
        expanded.setOnClickPendingIntent(R.id.notif_ac_overlay, overlayPi)
        if (floatingOn) {
            expanded.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", 255)
        } else {
            expanded.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", 100)
        }

        expanded.setOnClickPendingIntent(R.id.notif_ac_home, homePi)
        expanded.setOnClickPendingIntent(R.id.notif_ac_exit, exitPi)

        expanded.setViewVisibility(R.id.notif_ac_revoke, android.view.View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_mute, android.view.View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_show_controls, android.view.View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_row_secondary, android.view.View.GONE)

        val notification =
            NotificationCompat
                .Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(app.getString(R.string.notif_app_controls_title))
                .setContentText(summary)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(homePi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .build()

        app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
