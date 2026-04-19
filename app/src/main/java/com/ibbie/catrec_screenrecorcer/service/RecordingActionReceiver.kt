package com.ibbie.catrec_screenrecorcer.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ibbie.catrec_screenrecorcer.utils.trySilentDeleteMedia
import androidx.core.net.toUri

class RecordingActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DELETE_RECORDING = "com.ibbie.catrec_screenrecorcer.DELETE_RECORDING"
        const val EXTRA_RECORDING_URI = "recording_uri"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_DELETE_RECORDING) return

        val uriString = intent.getStringExtra(EXTRA_RECORDING_URI) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        try {
            val uri = uriString.toUri()
            val ok = trySilentDeleteMedia(context, uri)
            Log.d("RecordingActionReceiver", "Delete ${if (ok) "ok" else "failed"} for $uriString")
        } catch (e: Exception) {
            Log.e("RecordingActionReceiver", "Delete failed for $uriString", e)
        }

        if (notifId >= 0) {
            context.getSystemService(NotificationManager::class.java).cancel(notifId)
        }
    }
}
