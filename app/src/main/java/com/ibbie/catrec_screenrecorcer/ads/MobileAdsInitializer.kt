package com.ibbie.catrec_screenrecorcer.ads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.MobileAds
import com.ibbie.catrec_screenrecorcer.R

/**
 * AdMob touches the Bluetooth stack (e.g. audio routing) during [MobileAds.initialize].
 * On API 31+ that requires [Manifest.permission.BLUETOOTH_CONNECT] at runtime; initializing
 * before it is granted produces logcat warnings. We defer init until the permission is granted.
 *
 * Remove-ads purchasers: [adsDisabled] is true — [MobileAds.initialize] is never called so the SDK
 * does not start threads/WebView or contact ad servers.
 */
object MobileAdsInitializer {
    /** Mirrors remove-ads / DataStore — when true, [MobileAds.initialize] is never called. */
    @Volatile
    var adsDisabled: Boolean = false

    private val lock = Any()
    private var initRequested = false
    private var initComplete = false
    private val pending = mutableListOf<() -> Unit>()

    /**
     * Returns true when it is safe to call into APIs that use [android.bluetooth.BluetoothAdapter]
     * (including Mobile Ads init). API 30 and below do not use BLUETOOTH_CONNECT.
     */
    fun canUseBluetoothStack(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Idempotent: starts [MobileAds.initialize] once [canUseBluetoothStack] is satisfied,
     * then preloads the app-open ad. No-op if API 31+ and BLUETOOTH_CONNECT is not granted.
     */
    fun initializeIfReady(context: Context) {
        val app = context.applicationContext
        if (adsDisabled) return
        if (!canUseBluetoothStack(app)) return
        synchronized(lock) {
            if (initRequested) return
            initRequested = true
        }
        MobileAds.initialize(app) {
            val toRun =
                synchronized(lock) {
                    initComplete = true
                    pending.toList().also { pending.clear() }
                }
            toRun.forEach { runCatching(it::invoke) }
            AppOpenAdManager.load(app, app.getString(R.string.admob_app_open_unit_id))
        }
    }

    /**
     * Runs [action] after the SDK has finished initializing, or immediately if already done.
     * If init is deferred (missing BLUETOOTH_CONNECT), [action] is queued until [initializeIfReady] succeeds.
     */
    fun runAfterInitialized(
        context: Context,
        action: () -> Unit,
    ) {
        if (adsDisabled) return
        val runNow =
            synchronized(lock) {
                if (initComplete) {
                    return@synchronized action
                }
                pending.add(action)
                null
            }
        runNow?.invoke() ?: initializeIfReady(context)
    }
}
