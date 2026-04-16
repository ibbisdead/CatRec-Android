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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
     * Read access for Recordings / Screenshots tabs (MediaStore). API 33+: video + images + audio;
     * API 34+ also [READ_MEDIA_VISUAL_USER_SELECTED] (Selected Photos / partial library; request with other READ_MEDIA_* in one call).
     * Older: [READ_EXTERNAL_STORAGE].
     */
    fun mediaLibraryReadPermissions(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.READ_MEDIA_AUDIO,
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> emptyArray()
        }

    fun isMediaLibraryReadGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val video =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED
            val images =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
            val audio =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            if (video && images && audio) return true
            val partial =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ) == PackageManager.PERMISSION_GRANTED
            return partial
        }
        val perms = mediaLibraryReadPermissions()
        if (perms.isEmpty()) return true
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
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

    fun saveNotificationGranted(granted: Boolean) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_GRANTED, granted).apply()

    fun saveAudioGranted(granted: Boolean) = prefs.edit().putBoolean(KEY_AUDIO_GRANTED, granted).apply()

    fun saveCameraGranted(granted: Boolean) = prefs.edit().putBoolean(KEY_CAMERA_GRANTED, granted).apply()

    fun saveOverlayGranted(granted: Boolean) = prefs.edit().putBoolean(KEY_OVERLAY_GRANTED, granted).apply()

    // --- First-launch setup tracking ---

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun markSetupComplete() = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
}
