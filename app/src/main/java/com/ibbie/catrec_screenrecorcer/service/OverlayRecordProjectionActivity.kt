package com.ibbie.catrec_screenrecorcer.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressionReason
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressor
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.util.MediaProjectionIntents
import com.ibbie.catrec_screenrecorcer.utils.PermissionManager
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

    private val recordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            PermissionManager(this).saveAudioGranted(granted)
            if (!granted) {
                Toast
                    .makeText(
                        this,
                        getString(R.string.toast_audio_permission_required_recording_not_started),
                        Toast.LENGTH_LONG,
                    ).show()
                finish()
                return@registerForActivityResult
            }
            launchProjectionCapture()
        }

    private val projectionCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
            when {
                result.resultCode == RESULT_OK && result.data != null -> {
                    val asBuffer = intent.getBooleanExtra(EXTRA_START_AS_BUFFER, false)
                    val svc =
                        Intent(this, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_START_AFTER_OVERLAY_PROJECTION
                            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                            putExtra(ScreenRecordService.EXTRA_OVERLAY_SESSION_AS_BUFFER, asBuffer)
                        }
                    startForegroundService(svc)
                    // Tear down this host immediately so a transparent activity cannot sit above
                    // games/apps and steal touch focus (especially rolling-buffer / Clipper).
                    if (asBuffer && isTaskRoot) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
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
        val (recordAudio, internalAudio) =
            runBlocking {
                val repo = SettingsRepository(applicationContext)
                Pair(repo.recordAudio.first(), repo.internalAudio.first())
            }
        val needsAudio = recordAudio || internalAudio
        if (needsAudio &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionCapture()
        }
    }

    private fun launchProjectionCapture() {
        AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
        projectionCapture.launch(MediaProjectionIntents.createScreenCaptureIntent(this))
    }
}
