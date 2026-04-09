package com.ibbie.catrec_screenrecorcer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Ongoing notification with quick actions, shown while the main UI is available.
 * Refreshed when capture state changes.
 */
object AppControlNotification {

    const val CHANNEL_ID = "CatRec_App_Controls"
    private const val NOTIFICATION_ID = 9102

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID)
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)

        val isRecording = RecordingState.isRecording.value
        val isBuffering = RecordingState.isBuffering.value
        val isPrepared = RecordingState.isPrepared.value
        val isPaused = RecordingState.isRecordingPaused.value
        val overlayVisible = OverlayService.idleControlsBubbleVisible
        val floatingOn = runBlocking {
            SettingsRepository(app).floatingControls.first()
        }

        val homePi = PendingIntent.getActivity(
            app,
            1,
            Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val exitPi = PendingIntent.getActivity(
            app,
            2,
            Intent(app, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_EXIT_APP, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val recordPi = PendingIntent.getBroadcast(
            app,
            10,
            Intent(app, CatRecControlReceiver::class.java).apply {
                action = CatRecControlReceiver.ACTION_RECORD_TOGGLE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val screenshotPi = PendingIntent.getBroadcast(
            app,
            11,
            Intent(app, CatRecControlReceiver::class.java).apply {
                action = CatRecControlReceiver.ACTION_SCREENSHOT_OR_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val overlayPi = PendingIntent.getBroadcast(
            app,
            12,
            Intent(app, CatRecControlReceiver::class.java).apply {
                action = CatRecControlReceiver.ACTION_OVERLAY_TOGGLE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val recordTitle = when {
            isBuffering || isRecording -> app.getString(R.string.notif_action_stop)
            else -> app.getString(R.string.recording_start)
        }

        val screenshotTitle = when {
            isRecording -> {
                if (isPaused) app.getString(R.string.notif_action_resume)
                else app.getString(R.string.notif_action_pause)
            }
            else -> app.getString(R.string.notif_action_screenshot)
        }

        val overlayTitle = when {
            !floatingOn -> app.getString(R.string.notif_action_overlay_enable_in_settings)
            overlayVisible -> app.getString(R.string.notif_action_overlay_hide)
            else -> app.getString(R.string.notif_action_overlay_show)
        }

        val summary = buildString {
            if (isBuffering) append(app.getString(R.string.capture_mode_clipper))
            else if (isRecording) append(app.getString(R.string.capture_mode_recording))
            else if (isPrepared) append(app.getString(R.string.notif_ready_title))
            else append(app.getString(R.string.notif_app_controls_text_idle))
        }

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(app.getString(R.string.notif_app_controls_title))
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(homePi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_play, recordTitle, recordPi)
            .addAction(android.R.drawable.ic_menu_camera, screenshotTitle, screenshotPi)
            .addAction(android.R.drawable.ic_menu_view, overlayTitle, overlayPi)
            .addAction(android.R.drawable.ic_menu_compass, app.getString(R.string.notif_action_home), homePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, app.getString(R.string.notif_action_exit), exitPi)
            .build()

        app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
