package com.ibbie.catrec_screenrecorcer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.service.ScreenRecordService
import kotlinx.coroutines.launch

/**
 * Explains microphone fallback when internal playback capture is silent.
 *
 * **During recording**, users enable automatic fallback in Settings — the service hot-swaps to the
 * mic with non-blocking toasts (no modal). This activity remains for user-initiated fallback /
 * stop flows if a full prompt is explicitly opened.
 *
 * Does not use [AlertDialog.setOnDismissListener] { [finish] } on the **recording** dialog —
 * dismissing after tapping “Use microphone” would destroy this activity before the runtime
 * permission result returns.
 */
class InternalSilenceFallbackActivity : AppCompatActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    private val recordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.i(TAG, "fallback_accepted after permission grant autoFallbackPending=$pendingEnableAutoFallback")
                deliverMicFallbackAndFinish(pendingEnableAutoFallback)
            } else {
                Log.w(TAG, "fallback_mic_declined reason=permission_denied")
                Toast
                    .makeText(
                        this,
                        getString(R.string.toast_mic_fallback_needs_permission_recording_silent),
                        Toast.LENGTH_LONG,
                    ).show()
                finish()
            }
        }

    private var pendingEnableAutoFallback: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }

        val micSupported =
            intent.getBooleanExtra(EXTRA_MIC_FALLBACK_SUPPORTED, true)
        val silenceMs = intent.getLongExtra(EXTRA_SILENCE_DURATION_MS, 0L)

        val checkbox =
            CheckBox(this).apply {
                text = getString(R.string.internal_silent_auto_fallback_checkbox)
            }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val frame =
            FrameLayout(this).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(checkbox)
            }

        val message =
            if (micSupported) {
                getString(R.string.internal_silent_dialog_message)
            } else {
                getString(R.string.internal_silent_dialog_message) +
                    "\n\n" +
                    getString(R.string.internal_silent_dialog_no_mic_fallback_hint)
            }

        Log.i(
            TAG,
            "dialog_shown silenceMs=$silenceMs micSupported=$micSupported",
        )

        val builder =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.internal_silent_dialog_title)
                .setMessage(message)
                .setView(frame)
                .setCancelable(false)

        if (micSupported) {
            builder.setPositiveButton(R.string.internal_silent_use_mic_fallback) { _, _ ->
                pendingEnableAutoFallback = checkbox.isChecked
                when {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> {
                        Log.i(TAG, "fallback_accepted autoFallback=${checkbox.isChecked}")
                        deliverMicFallbackAndFinish(checkbox.isChecked)
                    }
                    else -> {
                        recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }

        builder
            .setNeutralButton(R.string.internal_silent_keep_recording_silent) { _, _ ->
                if (checkbox.isChecked) {
                    lifecycleScope.launch {
                        settingsRepository.setAutoMicFallbackWhenInternalSilent(true)
                    }
                }
                Log.i(TAG, "fallback_declined_keep_silent autoFallbackCheckbox=${checkbox.isChecked}")
                finish()
            }.setNegativeButton(R.string.internal_silent_stop_recording) { _, _ ->
                Log.i(TAG, "fallback_chose_stop_recording")
                startService(
                    Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    },
                )
                finish()
            }
            .show()
    }

    private fun deliverMicFallbackAndFinish(enableAutoSetting: Boolean) {
        if (enableAutoSetting) {
            lifecycleScope.launch {
                settingsRepository.setAutoMicFallbackWhenInternalSilent(true)
            }
        }
        startService(
            Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_INTERNAL_SILENCE_USE_MIC_FALLBACK
            },
        )
        finish()
    }

    companion object {
        private const val TAG = "InternalSilenceFallback"

        const val EXTRA_MIC_FALLBACK_SUPPORTED = "extra_mic_fallback_supported"
        const val EXTRA_SILENCE_DURATION_MS = "extra_silence_duration_ms"

        fun createIntent(
            context: Context,
            micFallbackSupported: Boolean,
            silenceDurationMs: Long,
        ): Intent =
            Intent(context, InternalSilenceFallbackActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_MIC_FALLBACK_SUPPORTED, micFallbackSupported)
                putExtra(EXTRA_SILENCE_DURATION_MS, silenceDurationMs)
            }
    }
}
