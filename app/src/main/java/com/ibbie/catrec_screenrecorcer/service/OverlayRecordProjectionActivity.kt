package com.ibbie.catrec_screenrecorcer.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.util.MediaProjectionIntents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Invisible host for the system MediaProjection consent dialog when the user starts
 * recording from the floating overlay without prepare mode — avoids bringing [MainActivity] forward.
 */
class OverlayRecordProjectionActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START_AS_BUFFER = "EXTRA_START_AS_BUFFER"
    }

    private val projectionCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when {
                result.resultCode == Activity.RESULT_OK && result.data != null -> {
                    val asBuffer = intent.getBooleanExtra(EXTRA_START_AS_BUFFER, false)
                    val svc =
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_START_AFTER_OVERLAY_PROJECTION
                            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                            putExtra(ScreenRecordService.EXTRA_OVERLAY_SESSION_AS_BUFFER, asBuffer)
                        }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc)
                    } else {
                        startService(svc)
                    }
                    // Tear down this host immediately so a transparent activity cannot sit above
                    // games/apps and steal touch focus (especially rolling-buffer / Clipper).
                    if (asBuffer && isTaskRoot) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                }
                result.resultCode != Activity.RESULT_CANCELED -> {
                    Toast.makeText(this, getString(R.string.toast_screen_capture_denied), Toast.LENGTH_SHORT).show()
                    finish()
                }
                else -> finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val singleApp =
            runBlocking {
                SettingsRepository(applicationContext).recordSingleAppEnabled.first()
            }
        projectionCapture.launch(MediaProjectionIntents.createScreenCaptureIntent(this, singleApp))
    }
}
