package com.ibbie.catrec_screenrecorcer.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R

/**
 * User-visible follow-up when a capture session was active and the process/service was restarted
 * with a null [Intent] (typical after the system reclaimed CatRec). Primary path posts immediately;
 * [RecordingInterruptedRetryReceiver] is scheduled as a lightweight fallback if the first post fails.
 */
object RecordingInterruptionNotifier {
    const val CHANNEL_ID = "CatRec_Recording_Interrupted"
    const val NOTIFICATION_ID = 1007
    const val ACTION_RETRY = "com.ibbie.catrec_screenrecorcer.action.RECORDING_INTERRUPT_RETRY"
    private const val ALARM_REQUEST_CODE = 19022

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_recording_interrupted),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_channel_recording_interrupted_desc)
                    setShowBadge(true)
                },
            )
        }
    }

    /** @return true if notify succeeded */
    fun notifyInterrupted(context: Context): Boolean {
        ensureChannel(context.applicationContext)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val appCtx = context.applicationContext
        val openApp =
            Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentPi =
            PendingIntent.getActivity(
                appCtx,
                NOTIFICATION_ID,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(appCtx.getString(R.string.notif_recording_interrupted_title))
                .setContentText(appCtx.getString(R.string.notif_recording_interrupted_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(appCtx.getString(R.string.notif_recording_interrupted_text)))
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        return try {
            nm.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            Log.e("RecordingInterruption", "notifyInterrupted failed", e)
            false
        }
    }

    fun scheduleRetryAlarm(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi =
            PendingIntent.getBroadcast(
                app,
                ALARM_REQUEST_CODE,
                Intent(app, RecordingInterruptedRetryReceiver::class.java).setAction(ACTION_RETRY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val triggerAt = SystemClock.elapsedRealtime() + 15_000L
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: Exception) {
            Log.e("RecordingInterruption", "scheduleRetryAlarm failed", e)
        }
    }
}

class RecordingInterruptedRetryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != RecordingInterruptionNotifier.ACTION_RETRY) return
        RecordingInterruptionNotifier.notifyInterrupted(context.applicationContext)
    }
}
