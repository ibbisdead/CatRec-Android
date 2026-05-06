package com.ibbie.catrec_screenrecorcer.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ibbie.catrec_screenrecorcer.R

data class PermissionInfo(
    val name: String,
    val rationale: String,
)

class PermissionManager(
    private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "catrec_permissions"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_NOTIFICATIONS_GRANTED = "notifications_granted"
        private const val KEY_AUDIO_GRANTED = "audio_granted"
        private const val KEY_CAMERA_GRANTED = "camera_granted"
        private const val KEY_OVERLAY_GRANTED = "overlay_granted"
        private const val KEY_APP_LAUNCHED_ONCE = "app_launched_once"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isNotificationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun isAudioGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    fun isCameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Visual read access for recordings/screenshots in the startup Recordings tab.
     * Audio media, microphone, camera, overlay, and nearby-device permissions are feature-gated.
     */
    fun mediaLibraryReadPermissions(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= 34 ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            Build.VERSION.SDK_INT >= 33 ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun isMediaLibraryReadGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            val hasFullVisual =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val hasPartialVisual =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            hasFullVisual || hasPartialVisual
        } else {
            mediaLibraryReadPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    fun mediaAudioReadPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO) else emptyArray()

    fun isMediaAudioReadGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    fun isBluetoothConnectGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Startup essentials only: notifications plus visual media access. */
    fun areAllGranted(): Boolean =
        isNotificationGranted() &&
            isMediaLibraryReadGranted()

    /** Startup-essential permissions that are still missing. */
    fun getMissingPermissions(): List<PermissionInfo> =
        buildList {
            if (!isNotificationGranted()) {
                add(
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_notifications),
                        rationale = context.getString(R.string.perm_rationale_notifications),
                    ),
                )
            }
            if (!isMediaLibraryReadGranted()) {
                add(
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_media_library),
                        rationale = context.getString(R.string.perm_rationale_media_library),
                    ),
                )
            }
        }

    fun saveNotificationGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_GRANTED, granted) }

    fun saveAudioGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_AUDIO_GRANTED, granted) }

    fun saveCameraGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_CAMERA_GRANTED, granted) }

    fun saveOverlayGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_OVERLAY_GRANTED, granted) }

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun markSetupComplete() = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, true) }

    fun isFirstAppLaunch(): Boolean = !prefs.getBoolean(KEY_APP_LAUNCHED_ONCE, false)

    fun markAppLaunchedOnce() = prefs.edit { putBoolean(KEY_APP_LAUNCHED_ONCE, true) }
}
