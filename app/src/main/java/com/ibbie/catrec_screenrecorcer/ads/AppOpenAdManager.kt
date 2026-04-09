package com.ibbie.catrec_screenrecorcer.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen app open ads. Respects [adsDisabled] (remove-ads / promo entitlement).
 * Loads the next ad after dismiss or failed show.
 */
object AppOpenAdManager {

    private const val TAG = "AppOpenAd"
    private const val MIN_INTERVAL_MS = 5_000L
    private const val MAX_AD_AGE_MS = 4 * 60 * 60 * 1000L

    @Volatile
    var adsDisabled: Boolean = false
        set(value) {
            field = value
            if (value) {
                appOpenAd = null
                isLoading.set(false)
            }
        }

    private var appOpenAd: AppOpenAd? = null
    private val isLoading = AtomicBoolean(false)
    private var loadTime: Long = 0
    private var lastShownAt: Long = 0

    @Volatile
    var isShowingAd: Boolean = false
        private set

    fun load(context: Context, adUnitId: String) {
        if (adsDisabled) return
        if (isLoading.get() || isAdAvailable()) return
        if (!isLoading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        AppOpenAd.load(
            appContext,
            adUnitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    isLoading.set(false)
                    appOpenAd = ad
                    loadTime = Date().time
                    Log.d(TAG, "onAdLoaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading.set(false)
                    Log.w(TAG, "onAdFailedToLoad: ${error.message} code=${error.code}")
                }
            },
        )
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

    /**
     * Shows a loaded ad if allowed; otherwise requests a load for next time.
     */
    fun showIfAvailable(activity: Activity, adUnitId: String) {
        if (adsDisabled) return
        if (isShowingAd) return
        val now = System.currentTimeMillis()
        if (now - lastShownAt < MIN_INTERVAL_MS && lastShownAt > 0) {
            load(activity.applicationContext, adUnitId)
            return
        }
        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            load(activity.applicationContext, adUnitId)
            return
        }
        isShowingAd = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                load(activity.applicationContext, adUnitId)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "onAdFailedToShow: ${error.message}")
                appOpenAd = null
                isShowingAd = false
                load(activity.applicationContext, adUnitId)
            }

            override fun onAdShowedFullScreenContent() {
                lastShownAt = System.currentTimeMillis()
            }
        }
        ad.show(activity)
    }
}
