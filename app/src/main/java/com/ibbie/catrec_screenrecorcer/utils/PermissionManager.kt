package com.ibbie.catrec_screenrecorcer.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ibbie.catrec_screenrecorcer.R
import androidx.core.content.edit

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
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Live permission checks (always query the system) ---

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
     * Read access for the app's recordings and screenshots in MediaStore.
     * API 33+: [READ_MEDIA_VIDEO], [READ_MEDIA_IMAGES], [READ_MEDIA_AUDIO].
     *   - VIDEO + IMAGES are required so MediaStore queries return own recordings/screenshots
     *     after a fresh install or reinstall (some OEMs clear MediaStore entries on uninstall).
     *   - AUDIO covers mic-sidecar files indexed under Music/Recordings/CatRec.
     * API 32 and below: [READ_EXTERNAL_STORAGE].
     */
    fun mediaLibraryReadPermissions(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= 33 ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_AUDIO,
                )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun isMediaLibraryReadGranted(): Boolean {
        val perms = mediaLibraryReadPermissions()
        if (perms.isEmpty()) return true
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * BLUETOOTH_CONNECT (API 31+, dangerous) — requested alongside media permissions during
     * setup so AdMob's audio-routing detection works without logcat warnings. Optional:
     * denial does not break any core feature.
     */
    fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    /** API 31+: runtime BLUETOOTH_CONNECT; older APIs have no equivalent dangerous permission. */
    fun isBluetoothConnectGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun areAllGranted(): Boolean =
        isNotificationGranted() &&
            isAudioGranted() &&
            isCameraGranted() &&
            isOverlayGranted() &&
            isMediaLibraryReadGranted()

    /** Returns a list of [PermissionInfo] for every permission that is NOT currently granted. */
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
            if (!isAudioGranted()) {
                add(
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_microphone),
                        rationale = context.getString(R.string.perm_rationale_microphone),
                    ),
                )
            }
            if (!isCameraGranted()) {
                add(
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_camera),
                        rationale = context.getString(R.string.perm_rationale_camera),
                    ),
                )
            }
            if (!isOverlayGranted()) {
                add(
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_overlay),
                        rationale = context.getString(R.string.perm_rationale_overlay),
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

    // --- SharedPreferences: persist granted state across sessions ---

    fun saveNotificationGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_GRANTED, granted) }

    fun saveAudioGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_AUDIO_GRANTED, granted) }

    fun saveCameraGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_CAMERA_GRANTED, granted) }

    fun saveOverlayGranted(granted: Boolean) =
        prefs.edit { putBoolean(KEY_OVERLAY_GRANTED, granted) }

    // --- First-launch setup tracking ---

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun markSetupComplete() = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, true) }
}
