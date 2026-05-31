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

enum class StartupPermission {
    NOTIFICATIONS,
    MEDIA_LIBRARY,
    MEDIA_AUDIO,
    NEARBY_DEVICES,
}

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
        private const val KEY_STARTUP_PERMISSION_ATTEMPTED_PREFIX = "startup_permission_attempted_"
        private const val KEY_STARTUP_PERMISSION_REQUEST_BLOCKED_PREFIX = "startup_permission_request_blocked_"
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
     * Granular READ_MEDIA_AUDIO (API 33+) is prompted in the initial setup chain separately.
     * Microphone, camera, overlay, and legacy Bluetooth remain feature-gated where applicable.
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

    /** Startup essentials only: notifications plus visual media access (recordings tab). */
    fun areAllGranted(): Boolean =
        isNotificationGranted() &&
            isMediaLibraryReadGranted()

    fun getMissingStartupPermissions(): List<StartupPermission> =
        buildList {
            if (!isNotificationGranted()) add(StartupPermission.NOTIFICATIONS)
            if (!isMediaLibraryReadGranted()) add(StartupPermission.MEDIA_LIBRARY)
            if (Build.VERSION.SDK_INT >= 33 && !isMediaAudioReadGranted()) add(StartupPermission.MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= 31 && !isBluetoothConnectGranted()) add(StartupPermission.NEARBY_DEVICES)
        }

    fun isStartupPermissionGranted(permission: StartupPermission): Boolean =
        when (permission) {
            StartupPermission.NOTIFICATIONS -> isNotificationGranted()
            StartupPermission.MEDIA_LIBRARY -> isMediaLibraryReadGranted()
            StartupPermission.MEDIA_AUDIO -> isMediaAudioReadGranted()
            StartupPermission.NEARBY_DEVICES -> isBluetoothConnectGranted()
        }

    fun runtimePermissionsForStartupPermission(permission: StartupPermission): Array<String> =
        when (permission) {
            StartupPermission.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyArray()
                }
            StartupPermission.MEDIA_LIBRARY -> mediaLibraryReadPermissions()
            StartupPermission.MEDIA_AUDIO -> mediaAudioReadPermissions()
            StartupPermission.NEARBY_DEVICES -> bluetoothPermissions()
        }

    fun isStartupPermissionFlowComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun markStartupPermissionFlowComplete() = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, true) }

    fun isStartupPermissionRequestBlocked(permission: StartupPermission): Boolean =
        prefs.getBoolean(startupPermissionRequestBlockedKey(permission), false)

    fun recordStartupPermissionDialogResult(
        permission: StartupPermission,
        granted: Boolean,
        canAskAgain: Boolean,
    ) {
        val attemptedKey = startupPermissionAttemptedKey(permission)
        val blockedKey = startupPermissionRequestBlockedKey(permission)
        val wasAttempted = prefs.getBoolean(attemptedKey, false)
        prefs.edit {
            putBoolean(attemptedKey, true)
            when {
                granted || canAskAgain -> remove(blockedKey)
                wasAttempted -> putBoolean(blockedKey, true)
                else -> remove(blockedKey)
            }
        }
    }

    fun clearRequestBlocksForGrantedStartupPermissions() {
        prefs.edit {
            StartupPermission.entries
                .filter { isStartupPermissionGranted(it) }
                .forEach { remove(startupPermissionRequestBlockedKey(it)) }
        }
    }

    /**
     * Human-readable list for existing permission status surfaces.
     * Essentials determine [areAllGranted]; music/audio (API 33+) and Nearby Devices (API 31+)
     * are optional and never block recording or setup completion.
     */
    fun getMissingPermissions(): List<PermissionInfo> =
        getMissingStartupPermissions().map { permission ->
            when (permission) {
                StartupPermission.NOTIFICATIONS ->
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_notifications),
                        rationale = context.getString(R.string.perm_rationale_notifications),
                    )
                StartupPermission.MEDIA_LIBRARY ->
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_media_library),
                        rationale = context.getString(R.string.perm_rationale_media_library),
                    )
                StartupPermission.MEDIA_AUDIO ->
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_music_audio),
                        rationale = context.getString(R.string.perm_rationale_music_audio),
                    )
                StartupPermission.NEARBY_DEVICES ->
                    PermissionInfo(
                        name = context.getString(R.string.perm_name_nearby_devices),
                        rationale = context.getString(R.string.perm_rationale_nearby_devices),
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

    fun isSetupComplete(): Boolean = isStartupPermissionFlowComplete()

    fun markSetupComplete() = markStartupPermissionFlowComplete()

    fun isFirstAppLaunch(): Boolean = !prefs.getBoolean(KEY_APP_LAUNCHED_ONCE, false)

    fun markAppLaunchedOnce() = prefs.edit { putBoolean(KEY_APP_LAUNCHED_ONCE, true) }

    private fun startupPermissionAttemptedKey(permission: StartupPermission): String =
        KEY_STARTUP_PERMISSION_ATTEMPTED_PREFIX + permission.name

    private fun startupPermissionRequestBlockedKey(permission: StartupPermission): String =
        KEY_STARTUP_PERMISSION_REQUEST_BLOCKED_PREFIX + permission.name
}
