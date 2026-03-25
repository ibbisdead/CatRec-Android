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
class ClipperTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isBuffering = RecordingState.isBuffering.value
        val isPrepared  = RecordingState.isPrepared.value

        when {
            isBuffering -> {
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP_BUFFER
                    }
                )
            }
            isPrepared -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_START_BUFFER_FROM_OVERLAY
                        }
                    )
                } else {
                    startService(
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_START_BUFFER_FROM_OVERLAY
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
        val isBuffering = RecordingState.isBuffering.value
        tile.label = "CatRec Clipper"
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.state = if (isBuffering) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isBuffering) "Buffering…" else if (RecordingState.isPrepared.value) "Ready" else "Tap to open app"
        }
        tile.updateTile()
    }
}
