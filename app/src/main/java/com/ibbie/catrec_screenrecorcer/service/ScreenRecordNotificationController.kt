package com.ibbie.catrec_screenrecorcer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R

internal class ScreenRecordNotificationController(
    private val context: Context,
) {
    fun createNotificationChannels() {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_screen_recording),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE_ID,
                context.getString(R.string.notif_channel_recording_complete),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BUFFER_ID,
                context.getString(R.string.notif_channel_rolling_buffer),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun buildReadyNotification(
        floatingOn: Boolean,
        overlayVisible: Boolean,
    ): Notification {
        AppControlNotification.cancel(context)
        val tapPI =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val homePi =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val exitPi =
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_EXIT_APP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val recordPi =
            PendingIntent.getBroadcast(
                context,
                10,
                Intent(context, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_RECORD_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val overlayPi =
            PendingIntent.getBroadcast(
                context,
                12,
                Intent(context, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_OVERLAY_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val collapsed = RemoteViews(context.packageName, R.layout.notification_app_controls_collapsed)
        val expanded = RemoteViews(context.packageName, R.layout.notification_app_controls_expanded)

        val startLabel = context.getString(R.string.notif_cc_record)
        collapsed.setImageViewResource(R.id.btn_record_icon, R.drawable.ic_record)
        collapsed.setTextViewText(R.id.btn_record_label, startLabel)
        collapsed.setContentDescription(R.id.btn_record, context.getString(R.string.recording_start))
        collapsed.setOnClickPendingIntent(R.id.btn_record, recordPi)
        expanded.setImageViewResource(R.id.btn_record_icon, R.drawable.ic_record)
        expanded.setTextViewText(R.id.btn_record_label, startLabel)
        expanded.setContentDescription(R.id.btn_record, context.getString(R.string.recording_start))
        expanded.setOnClickPendingIntent(R.id.btn_record, recordPi)

        updateNotificationState(isRecording = false, collapsed, expanded)
        bindShadeOverlayExit(collapsed, expanded, floatingOn, overlayVisible, overlayPi, exitPi)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_home, homePi)
        expanded.setOnClickPendingIntent(R.id.notif_ac_home, homePi)

        expanded.setViewVisibility(R.id.notif_ac_revoke, View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_mute, View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_show_controls, View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_row_secondary, View.GONE)

        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_ready_title))
            .setContentText(context.getString(R.string.notif_ready_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPI)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .build()
    }

    fun buildRecordingNotification(
        isPaused: Boolean,
        isRecordingMuted: Boolean,
        showFloatingControls: Boolean,
        controlsDismissedByUser: Boolean,
        floatingOn: Boolean,
        overlayVisible: Boolean,
        contentText: String? = null,
    ): Notification {
        AppControlNotification.cancel(context)
        val tapPending =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val homePi =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val exitPi =
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_EXIT_APP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val overlayPi =
            PendingIntent.getBroadcast(
                context,
                12,
                Intent(context, CatRecControlReceiver::class.java).apply {
                    action = CatRecControlReceiver.ACTION_OVERLAY_TOGGLE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val muteAction =
            if (isRecordingMuted) {
                ScreenRecordService.ACTION_UNMUTE
            } else {
                ScreenRecordService.ACTION_MUTE
            }
        val mutePI =
            PendingIntent.getService(
                context,
                3,
                Intent(context, ScreenRecordService::class.java).apply { action = muteAction },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val resumePI =
            PendingIntent.getService(
                context,
                1,
                Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_RESUME },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pausePI =
            PendingIntent.getService(
                context,
                2,
                Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pauseOrResumePI = if (isPaused) resumePI else pausePI

        val statusLine =
            contentText ?: when {
                isPaused -> context.getString(R.string.notif_recording_paused)
                isRecordingMuted -> context.getString(R.string.notif_recording_muted)
                else -> context.getString(R.string.notif_recording_in_progress)
            }

        val collapsed = RemoteViews(context.packageName, R.layout.notification_app_controls_collapsed)
        val expanded = RemoteViews(context.packageName, R.layout.notification_app_controls_expanded)
        val pauseIcon =
            if (isPaused) {
                R.drawable.ic_play
            } else {
                R.drawable.ic_notification_pause
            }
        val pauseDesc =
            context.getString(
                if (isPaused) {
                    R.string.notif_action_resume
                } else {
                    R.string.notif_action_pause
                },
            )

        collapsed.setImageViewResource(R.id.btn_record_icon, pauseIcon)
        collapsed.setTextViewText(R.id.btn_record_label, pauseDesc)
        collapsed.setContentDescription(R.id.btn_record, pauseDesc)
        collapsed.setOnClickPendingIntent(R.id.btn_record, pauseOrResumePI)
        expanded.setImageViewResource(R.id.btn_record_icon, pauseIcon)
        expanded.setTextViewText(R.id.btn_record_label, pauseDesc)
        expanded.setContentDescription(R.id.btn_record, pauseDesc)
        expanded.setOnClickPendingIntent(R.id.btn_record, pauseOrResumePI)

        updateNotificationState(isRecording = true, collapsed, expanded)
        bindShadeOverlayExit(collapsed, expanded, floatingOn, overlayVisible, overlayPi, exitPi)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_home, homePi)
        expanded.setOnClickPendingIntent(R.id.notif_ac_home, homePi)

        expanded.setViewVisibility(R.id.notif_ac_revoke, View.GONE)
        expanded.setViewVisibility(R.id.notif_ac_row_secondary, View.VISIBLE)
        expanded.setViewVisibility(R.id.notif_ac_mute, View.GONE)
        expanded.setImageViewResource(R.id.notif_ac_mute, android.R.drawable.ic_lock_silent_mode)
        expanded.setContentDescription(
            R.id.notif_ac_mute,
            context.getString(if (isRecordingMuted) R.string.notif_action_unmute else R.string.notif_action_mute),
        )
        expanded.setOnClickPendingIntent(R.id.notif_ac_mute, mutePI)

        if (showFloatingControls && controlsDismissedByUser) {
            val showControlsPI =
                PendingIntent.getService(
                    context,
                    5,
                    Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_SHOW_CONTROLS },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            expanded.setViewVisibility(R.id.notif_ac_show_controls, View.VISIBLE)
            expanded.setOnClickPendingIntent(R.id.notif_ac_show_controls, showControlsPI)
        } else {
            expanded.setViewVisibility(R.id.notif_ac_show_controls, View.GONE)
        }

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notif_title_short))
                .setContentText(statusLine)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(tapPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return notification
    }

    fun buildBufferNotification(
        clipperDurationMinutes: Int,
        statusText: String? = null,
    ): Notification {
        val tapPI =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stopPI =
            PendingIntent.getService(
                context,
                20,
                Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP_BUFFER },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val clipPI =
            PendingIntent.getService(
                context,
                21,
                Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_SAVE_CLIP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val bufferSecs = clipperDurationMinutes.coerceIn(1, 5) * 60
        return NotificationCompat
            .Builder(context, CHANNEL_BUFFER_ID)
            .setContentTitle(context.getString(R.string.notif_buffer_title))
            .setContentText(
                statusText ?: context.getString(R.string.notif_buffer_buffering, bufferSecs),
            ).setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPI)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_save, context.getString(R.string.notif_buffer_save_clip), clipPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_stop), stopPI)
            .build()
    }

    fun buildPreparedNotificationWithSavedRecording(uri: Uri): Notification {
        val thumbnail: Bitmap? =
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, Size(320, 180), null)
                } else {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context.applicationContext, uri)
                    val bmp = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    bmp
                }
            } catch (_: Exception) {
                null
            }

        val tapPending =
            PendingIntent.getActivity(
                context,
                10,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val openPending =
            PendingIntent.getActivity(
                context,
                11,
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        val sharePending =
            PendingIntent.getActivity(
                context,
                12,
                Intent
                    .createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        context.getString(R.string.share_recording_title),
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_IMMUTABLE,
            )
        val deletePending =
            PendingIntent.getService(
                context,
                13,
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ACTION_DELETE_SAVED_RECORDING
                    putExtra(EXTRA_LAST_SAVED_RECORDING_URI, uri.toString())
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val revokePI =
            PendingIntent.getService(
                context,
                50,
                Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_REVOKE_PREPARE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val style =
            NotificationCompat
                .BigPictureStyle()
                .setBigContentTitle(context.getString(R.string.notif_post_title))
                .setSummaryText(context.getString(R.string.notif_ready_text))
        if (thumbnail != null) {
            style.bigPicture(thumbnail)
        }

        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_post_title))
            .setContentText(context.getString(R.string.notif_ready_text))
            .setSubText(context.getString(R.string.notif_post_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(style)
            .apply {
                if (thumbnail != null) setLargeIcon(thumbnail)
            }.addAction(android.R.drawable.ic_menu_view, context.getString(R.string.notif_post_open), openPending)
            .addAction(android.R.drawable.ic_menu_share, context.getString(R.string.notif_post_share), sharePending)
            .addAction(android.R.drawable.ic_menu_delete, context.getString(R.string.notif_post_delete), deletePending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notif_action_revoke),
                revokePI,
            ).build()
            .also { n ->
                n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    private fun bindShadeOverlayExit(
        collapsed: RemoteViews,
        expanded: RemoteViews,
        floatingOn: Boolean,
        overlayVisible: Boolean,
        overlayPi: PendingIntent,
        exitPi: PendingIntent,
    ) {
        val overlayDesc =
            when {
                !floatingOn -> context.getString(R.string.notif_action_overlay_enable_in_settings)
                overlayVisible -> context.getString(R.string.notif_action_overlay_hide)
                else -> context.getString(R.string.notif_action_overlay_show)
            }
        val alpha = if (floatingOn) 255 else 100
        collapsed.setContentDescription(R.id.notif_ac_overlay, overlayDesc)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_overlay, overlayPi)
        collapsed.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", alpha)
        collapsed.setOnClickPendingIntent(R.id.notif_ac_exit, exitPi)
        expanded.setContentDescription(R.id.notif_ac_overlay, overlayDesc)
        expanded.setOnClickPendingIntent(R.id.notif_ac_overlay, overlayPi)
        expanded.setInt(R.id.notif_ac_overlay_icon, "setImageAlpha", alpha)
        expanded.setOnClickPendingIntent(R.id.notif_ac_exit, exitPi)
    }

    private fun updateNotificationState(
        isRecording: Boolean,
        collapsed: RemoteViews,
        expanded: RemoteViews,
    ) {
        val stopPi =
            PendingIntent.getService(
                context,
                100,
                Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val screenshotPi =
            PendingIntent.getActivity(
                context,
                11,
                Intent(context, ScreenshotAfterShadeActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    )
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val views = arrayOf(collapsed, expanded)
        if (isRecording) {
            val stopLabel = context.getString(R.string.notif_action_stop)
            for (rv in views) {
                rv.setImageViewResource(R.id.btn_screenshot_icon, R.drawable.ic_stop)
                rv.setTextViewText(R.id.btn_screenshot_label, context.getString(R.string.notif_cc_stop))
                rv.setContentDescription(R.id.btn_screenshot, stopLabel)
                rv.setOnClickPendingIntent(R.id.btn_screenshot, stopPi)
            }
        } else {
            val shotDesc = context.getString(R.string.notif_action_screenshot)
            for (rv in views) {
                rv.setImageViewResource(R.id.btn_screenshot_icon, R.drawable.ic_screenshot)
                rv.setTextViewText(R.id.btn_screenshot_label, context.getString(R.string.notif_cc_shot))
                rv.setContentDescription(R.id.btn_screenshot, shotDesc)
                rv.setOnClickPendingIntent(R.id.btn_screenshot, screenshotPi)
            }
        }
    }

    private companion object {
        private const val CHANNEL_ID = "CatRec_Recording_Channel"
        private const val CHANNEL_DONE_ID = "CatRec_Done_Channel"
        private const val CHANNEL_BUFFER_ID = "CatRec_Buffer_Channel"
        private const val ACTION_DELETE_SAVED_RECORDING = "com.ibbie.catrec_screenrecorcer.DELETE_SAVED_RECORDING"
        private const val EXTRA_LAST_SAVED_RECORDING_URI = "EXTRA_LAST_SAVED_RECORDING_URI"
    }
}
