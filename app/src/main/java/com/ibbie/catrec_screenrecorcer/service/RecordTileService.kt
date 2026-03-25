package com.ibbie.catrec_screenrecorcer.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.RecordingState

@RequiresApi(Build.VERSION_CODES.N)
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRecording = RecordingState.isRecording.value
        val isPrepared  = RecordingState.isPrepared.value

        when {
            isRecording -> {
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    }
                )
            }
            isPrepared -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_START_FROM_OVERLAY
                        }
                    )
                } else {
                    startService(
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_START_FROM_OVERLAY
                        }
                    )
                }
            }
            else -> {
                // Not authorized — open the app so the user can grant overlay permission.
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRecording = RecordingState.isRecording.value
        tile.label = "CatRec Record"
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRecording) "Recording…" else if (RecordingState.isPrepared.value) "Ready" else "Tap to open app"
        }
        tile.updateTile()
    }
}
