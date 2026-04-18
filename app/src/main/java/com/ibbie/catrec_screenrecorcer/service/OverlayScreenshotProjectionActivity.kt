package com.ibbie.catrec_screenrecorcer.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Transparent host for the system screen-capture consent when the user takes a screenshot
 * from the floating overlay without an active MediaProjection session — avoids bringing
 * [com.ibbie.catrec_screenrecorcer.MainActivity] to the foreground.
 */
class OverlayScreenshotProjectionActivity : ComponentActivity() {
    private val projectionCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when {
                result.resultCode == Activity.RESULT_OK && result.data != null -> {
                    val (ssFmt, ssQ) =
                        runBlocking {
                            val repo = SettingsRepository(applicationContext)
                            Pair(repo.screenshotFormat.first(), repo.screenshotQuality.first())
                        }
                    val prepare =
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_PREPARE
                            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_FORMAT, ssFmt)
                            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_QUALITY, ssQ)
                        }
                    startForegroundService(prepare)
                    val appCtx = applicationContext
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            appCtx.startService(
                                Intent(appCtx, ScreenRecordService::class.java).apply {
                                    action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
                                },
                            )
                        },
                        450L,
                    )
                    finish()
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
