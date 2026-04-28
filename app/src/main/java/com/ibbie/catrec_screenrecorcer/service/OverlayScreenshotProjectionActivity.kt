package com.ibbie.catrec_screenrecorcer.service

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.ibbie.catrec_screenrecorcer.CatRecApplication
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.util.MediaProjectionIntents

/**
 * Transparent host for the system screen-capture consent when the user takes a screenshot
 * from the floating overlay without an active MediaProjection session — avoids bringing
 * [com.ibbie.catrec_screenrecorcer.MainActivity] to the foreground.
 *
 * The projection token obtained here is forwarded inside a single
 * [ScreenRecordService.ACTION_TAKE_SCREENSHOT_ONE_SHOT] start so the service uses it once and
 * releases it immediately — the next screenshot prompts the user again.
 */
class OverlayScreenshotProjectionActivity : ComponentActivity() {
    private val projectionCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when {
                result.resultCode == RESULT_OK && result.data != null -> {
                    val snap =
                        (application as? CatRecApplication)
                            ?.settingsConfigCache
                            ?.current()
                    val ssFmt = snap?.screenshotFormat ?: "JPEG"
                    val ssQ = snap?.screenshotQuality ?: 90
                    val oneShot =
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_TAKE_SCREENSHOT_ONE_SHOT
                            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_FORMAT, ssFmt)
                            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_QUALITY, ssQ)
                        }
                    startForegroundService(oneShot)
                    finish()
                }
                result.resultCode != RESULT_CANCELED -> {
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
            (application as? CatRecApplication)
                ?.settingsConfigCache
                ?.current()
                ?.recordSingleAppEnabled
                ?: false
        projectionCapture.launch(MediaProjectionIntents.createScreenCaptureIntent(this, singleApp))
    }
}
