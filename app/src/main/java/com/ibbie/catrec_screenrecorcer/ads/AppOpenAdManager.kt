package com.ibbie.catrec_screenrecorcer.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen app-open ads. Respects remove-ads entitlement and central suppression state.
 */
object AppOpenAdManager {
    private const val TAG = "AppOpenAd"
    private const val MAX_AD_AGE_MS = 4 * 60 * 60 * 1000L

    @Volatile
    var adsDisabled: Boolean = false
        set(value) {
            field = value
            if (value) {
                appOpenAd = null
                isLoading.set(false)
                pendingShowActivity = null
                pendingShowUnitId = null
            }
        }

    @Volatile
    var firstLaunchSession: Boolean = false

    private var firstLaunchBlockedForegroundEventId: Long = 0

    @Volatile
    var firstRunPermissionsComplete: Boolean = true

    private var appOpenAd: AppOpenAd? = null
    private val isLoading = AtomicBoolean(false)
    private var loadTime: Long = 0
    private var currentForegroundEventId: Long = 0
    private var shownForegroundEventId: Long = 0

    /**
     * Used only when every gating check already passed but the cold-start ad has not loaded yet.
     */
    private var pendingShowActivity: WeakReference<Activity>? = null
    private var pendingShowUnitId: String? = null
    private var pendingShowForegroundEventId: Long = 0

    @Volatile
    var isShowingAd: Boolean = false
        private set

    fun load(
        context: Context,
        adUnitId: String,
    ) {
        if (adsDisabled) return
        if (isLoading.get() || isAdAvailable()) return
        if (!isLoading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        AppOpenAd.load(
            appContext,
            adUnitId,
            AdMobAdRequestFactory.build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    isLoading.set(false)
                    appOpenAd = ad
                    loadTime = Date().time
                    Log.d(TAG, "onAdLoaded")
                    tryShowPendingAfterLoad(adUnitId)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading.set(false)
                    pendingShowActivity = null
                    pendingShowUnitId = null
                    Log.w(TAG, "onAdFailedToLoad: ${error.message} code=${error.code}")
                }
            },
        )
    }

    private fun tryShowPendingAfterLoad(loadedUnitId: String) {
        val id = pendingShowUnitId ?: return
        if (id != loadedUnitId) return
        val eventId = pendingShowForegroundEventId
        val act = pendingShowActivity?.get()
        pendingShowActivity = null
        pendingShowUnitId = null
        pendingShowForegroundEventId = 0
        if (act != null) {
            showIfAvailable(act, loadedUnitId, eventId)
        }
    }

    private fun isAdAvailable(): Boolean {
        if (appOpenAd == null) return false
        val age = Date().time - loadTime
        if (age > MAX_AD_AGE_MS) {
            appOpenAd = null
            return false
        }
        return true
    }

    fun showIfAvailable(
        activity: Activity,
        adUnitId: String,
        foregroundEventId: Long = ensureForegroundEvent(),
    ) {
        if (adsDisabled) {
            Log.d(TAG, "blocked: ads_disabled")
            clearPendingShow()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            Log.d(TAG, "blocked: invalid_activity")
            clearPendingShow()
            return
        }
        if (isShowingAd) {
            Log.d(TAG, "blocked: already_showing_app_open")
            clearPendingShow()
            return
        }
        if (shownForegroundEventId == foregroundEventId) {
            Log.d(TAG, "blocked: already_shown_for_foreground_event id=$foregroundEventId")
            clearPendingShow()
            load(activity.applicationContext, adUnitId)
            return
        }

        val now = System.currentTimeMillis()
        val blockReason = showBlockReason(now, foregroundEventId)
        if (blockReason != null) {
            Log.d(
                TAG,
                "blocked: $blockReason loaded=${appOpenAd != null} loading=${isLoading.get()} " +
                    "firstLaunch=$firstLaunchSession firstRunComplete=$firstRunPermissionsComplete",
            )
            clearPendingShow()
            load(activity.applicationContext, adUnitId)
            return
        }

        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            Log.d(TAG, "blocked: no_loaded_ad; requesting load and pending foreground show event=$foregroundEventId")
            pendingShowActivity = WeakReference(activity)
            pendingShowUnitId = adUnitId
            pendingShowForegroundEventId = foregroundEventId
            load(activity.applicationContext, adUnitId)
            return
        }

        Log.d(TAG, "showing app-open ad foregroundEvent=$foregroundEventId")
        isShowingAd = true
        shownForegroundEventId = foregroundEventId
        ad.fullScreenContentCallback =
            object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    activity.resetWindowFocusAfterFullscreenOverlay()
                    appOpenAd = null
                    isShowingAd = false
                    load(activity.applicationContext, adUnitId)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    Log.w(TAG, "onAdFailedToShow: ${error.message}")
                    activity.resetWindowFocusAfterFullscreenOverlay()
                    appOpenAd = null
                    isShowingAd = false
                    load(activity.applicationContext, adUnitId)
                }

                override fun onAdShowedFullScreenContent() = Unit
            }
        ad.show(activity)
    }

    fun beginForegroundEvent(): Long {
        currentForegroundEventId += 1
        if (firstLaunchSession && firstLaunchBlockedForegroundEventId == 0L) {
            firstLaunchBlockedForegroundEventId = currentForegroundEventId
        } else if (currentForegroundEventId > firstLaunchBlockedForegroundEventId) {
            firstLaunchSession = false
        }
        clearPendingShow()
        Log.d(TAG, "foreground_event id=$currentForegroundEventId")
        return currentForegroundEventId
    }

    fun ensureForegroundEvent(): Long =
        if (currentForegroundEventId > 0) {
            currentForegroundEventId
        } else {
            beginForegroundEvent()
        }

    private fun showBlockReason(
        now: Long,
        foregroundEventId: Long,
    ): String? {
        if (firstLaunchSession && foregroundEventId == firstLaunchBlockedForegroundEventId) {
            return AppOpenAdSuppressionReason.FIRST_LAUNCH.logName
        }
        if (!firstRunPermissionsComplete) return AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS.logName
        return AppOpenAdSuppressor.activeBlockReason(now)
    }

    private fun clearPendingShow() {
        pendingShowActivity = null
        pendingShowUnitId = null
        pendingShowForegroundEventId = 0
    }
}
