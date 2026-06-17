package com.ibbie.catrec_screenrecorcer.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.ibbie.catrec_screenrecorcer.R
import java.util.concurrent.Executors

/**
 * Initializes AdMob only after the UI is foregrounded.
 *
 * Remove-ads purchasers: [adsDisabled] is true — [MobileAds.initialize] is never called so the SDK
 * does not start threads/WebView or contact ad servers.
 *
 * **Pending callbacks:** only [runAfterInitialized] enqueues lambdas (today: banner [loadAd] from
 * [com.ibbie.catrec_screenrecorcer.ui.components.BannerAdRow]). Callbacks are not queued when ads are
 * disabled.
 */
object MobileAdsInitializer {
    private const val TAG = "MobileAdsInit"
    private const val INIT_CALLBACK_TIMEOUT_MS = 30_000L

    /**
     * Mirrors remove-ads / DataStore — when set true, clears [pending] and cancels the init timeout
     * runnable (in-flight SDK init may still finish but [finishInitDrain] will not run ad APIs).
     */
    @Volatile
    private var adsDisabledBacking = false

    /**
     * Guards against process-start initialization from background services/receivers. The Ads SDK can
     * load WebView/Chromium during initialization; doing that while CatRec is backgrounded has produced
     * Crashlytics ANRs blamed to the MobileAds initialization thread.
     */
    @Volatile
    private var foregroundEligible = false

    var adsDisabled: Boolean
        get() = adsDisabledBacking
        set(value) {
            adsDisabledBacking = value
            if (value) {
                synchronized(lock) {
                    pending.clear()
                }
                mainHandler.removeCallbacks(timeoutRunnable)
            }
        }

    fun setForegroundEligible(eligible: Boolean) {
        foregroundEligible = eligible
        if (!eligible) return
        val app = timeoutApp ?: return
        initializeIfReady(app)
    }

    /**
     * [MobileAds.initialize] loads the WebView/Chromium stack on some devices; doing that on the
     * main thread during [android.app.Application.onCreate] has triggered ANRs (see Crashlytics).
     */
    private val initExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "catrec-mobileads-init").apply { isDaemon = true }
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var initRequested = false

    /** True once pending callbacks are drained and post-init work has run (SDK callback or timeout). */
    private var initComplete = false
    private var timedOut = false
    private val pending = mutableListOf<() -> Unit>()

    private var timeoutApp: Context? = null
    private val timeoutRunnable =
        Runnable {
            val app = timeoutApp ?: return@Runnable
            synchronized(lock) {
                if (initComplete) return@Runnable
                timedOut = true
            }
            Log.w(
                TAG,
                "MobileAds.initialize() callback did not arrive within ${INIT_CALLBACK_TIMEOUT_MS}ms; " +
                    "draining pending on main without SDK confirmation.",
            )
            finishInitDrain(app, fromSdkCallback = false)
        }

    fun canUseBluetoothStack(context: Context): Boolean = true

    /**
     * Idempotent: starts [MobileAds.initialize], then preloads the app-open ad.
     */
    fun initializeIfReady(context: Context) {
        val app = context.applicationContext
        if (adsDisabled) return
        if (!foregroundEligible) {
            timeoutApp = app
            Log.d(TAG, "Skipping MobileAds.initialize until app UI is foregrounded.")
            return
        }
        synchronized(lock) {
            if (initRequested) return
            initRequested = true
        }
        timeoutApp = app
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, INIT_CALLBACK_TIMEOUT_MS)
        initExecutor.execute {
            MobileAds.initialize(app) {
                mainHandler.post {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    finishInitDrain(app, fromSdkCallback = true)
                }
            }
        }
    }

    /**
     * Runs [action] on the main looper after the SDK has finished initializing, or posts it there
     * immediately if the init gate has already cleared.
     *
     * Does not queue or start initialization when [adsDisabled].
     */
    fun runAfterInitialized(
        context: Context,
        action: () -> Unit,
    ) {
        val app = context.applicationContext
        if (adsDisabled) return
        synchronized(lock) {
            if (initComplete) {
                mainHandler.post { runCatching(action::invoke) }
                return
            }
            pending.add(action)
        }
        initializeIfReady(context)
    }

    /** Must run on the main thread; safe to call from [timeoutRunnable] and the SDK completion post. */
    private fun finishInitDrain(
        app: Context,
        fromSdkCallback: Boolean,
    ) {
        val callbacks: List<() -> Unit>
        synchronized(lock) {
            if (initComplete) {
                if (fromSdkCallback && timedOut) {
                    Log.d(
                        TAG,
                        "MobileAds.initialize() completed after timeout; pending already drained.",
                    )
                }
                return
            }
            initComplete = true
            callbacks = pending.toList()
            pending.clear()
        }
        if (adsDisabled) return
        callbacks.forEach { runCatching(it::invoke) }
        AppOpenAdManager.load(app, app.getString(R.string.admob_app_open_unit_id))
    }
}
