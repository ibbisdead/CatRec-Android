package com.ibbie.catrec_screenrecorcer.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.RecordingState

class RecordTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRecording = RecordingState.isRecording.value
        val isPrepared = RecordingState.isPrepared.value

        when {
            isRecording -> {
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    },
                )
            }
            isPrepared -> {
                startForegroundService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_START_FROM_OVERLAY
                    },
                )
            }
            else -> {
                // Route through MainActivity so Pro, permission, and MediaProjection gates can run.
                MainActivity.markRoutedRecordingAppOpenSuppressed("quick_tile_record_open_main")
                val intent =
                    MainActivity.addRoutedRecordingSuppressionExtras(Intent(this, MainActivity::class.java)).apply {
                        action = MainActivity.ACTION_START_RECORDING_FROM_OVERLAY
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                    }
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(
                            this,
                            10,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                } else {
                    // PendingIntent overload is API 34+. [collapse] is not a public SDK method on [TileService].
                    startActivity(intent)
                }
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRecording = RecordingState.isRecording.value
        tile.label = getString(R.string.tile_record_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_record)
        tile.state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle =
                when {
                    isRecording -> getString(R.string.tile_subtitle_recording)
                    RecordingState.isPrepared.value -> getString(R.string.tile_subtitle_ready)
                    else -> getString(R.string.tile_subtitle_tap_open)
                }
        }
        tile.updateTile()
    }
}
