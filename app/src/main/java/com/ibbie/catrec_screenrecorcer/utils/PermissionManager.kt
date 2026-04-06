package com.ibbie.catrec_screenrecorcer.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ibbie.catrec_screenrecorcer.R

data class PermissionInfo(
    val name: String,
    val rationale: String,
)

class PermissionManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "catrec_permissions"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_NOTIFICATIONS_GRANTED = "notifications_granted"
        private const val KEY_AUDIO_GRANTED = "audio_granted"
        private const val KEY_CAMERA_GRANTED = "camera_granted"
        private const val KEY_OVERLAY_GRANTED = "overlay_granted"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Live permission checks (always query the system) ---

    fun isNotificationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun isAudioGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun isCameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(context)

    fun areAllGranted(): Boolean =
        isNotificationGranted() && isAudioGranted() && isCameraGranted() && isOverlayGranted()

    /** Returns a list of [PermissionInfo] for every permission that is NOT currently granted. */
    fun getMissingPermissions(): List<PermissionInfo> = buildList {
        if (!isNotificationGranted()) {
            add(
                PermissionInfo(
                    name = context.getString(R.string.perm_name_notifications),
                    rationale = context.getString(R.string.perm_rationale_notifications),
                )
            )
        }
        if (!isAudioGranted()) {
            add(
                PermissionInfo(
                    name = context.getString(R.string.perm_name_microphone),
                    rationale = context.getString(R.string.perm_rationale_microphone),
                )
            )
        }
        if (!isCameraGranted()) {
            add(
                PermissionInfo(
                    name = context.getString(R.string.perm_name_camera),
                    rationale = context.getString(R.string.perm_rationale_camera),
                )
            )
        }
        if (!isOverlayGranted()) {
            add(
                PermissionInfo(
                    name = context.getString(R.string.perm_name_overlay),
                    rationale = context.getString(R.string.perm_rationale_overlay),
                )
            )
        }
    }

    // --- SharedPreferences: persist granted state across sessions ---

    fun saveNotificationGranted(granted: Boolean) =
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_GRANTED, granted).apply()

    fun saveAudioGranted(granted: Boolean) =
        prefs.edit().putBoolean(KEY_AUDIO_GRANTED, granted).apply()

    fun saveCameraGranted(granted: Boolean) =
        prefs.edit().putBoolean(KEY_CAMERA_GRANTED, granted).apply()

    fun saveOverlayGranted(granted: Boolean) =
        prefs.edit().putBoolean(KEY_OVERLAY_GRANTED, granted).apply()

    // --- First-launch setup tracking ---

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun markSetupComplete() =
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
}
