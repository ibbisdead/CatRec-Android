package com.ibbie.catrec_screenrecorcer.ads

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class ProRewardedAdAvailability(
    val available: Boolean = false,
    val loading: Boolean = false,
)

object ProRewardedAdManager {
    private const val TAG = "ProRewardedAd"
    private const val PRO_REWARDED_AD_UNIT_ID = "ca-app-pub-7741372232895726/8137302121"
    private const val MAX_AD_AGE_MS = 60 * 60 * 1000L

    private val isLoading = AtomicBoolean(false)
    private val _availability = MutableStateFlow(ProRewardedAdAvailability())

    val availability: StateFlow<ProRewardedAdAvailability> = _availability.asStateFlow()

    @Volatile
    private var backgroundPreloadAllowed = true

    @Volatile
    private var rewardedAd: RewardedAd? = null

    @Volatile
    private var loadTimeMillis: Long = 0L

    fun setBackgroundPreloadAllowed(allowed: Boolean) {
        backgroundPreloadAllowed = allowed
        if (!allowed) {
            Log.d(TAG, "background preload disabled for this launch")
        }
    }

    fun onAdsDisabledChanged(disabled: Boolean) {
        if (disabled) clear()
    }

    fun preloadInBackground(
        context: Context,
        trigger: String,
    ) {
        if (!backgroundPreloadAllowed) return
        preload(context, "background:$trigger")
    }

    fun preloadOnDemand(
        context: Context,
        trigger: String,
    ) {
        preload(context, "on_demand:$trigger")
    }

    fun claimAd(
        context: Context,
        trigger: String,
    ): RewardedAd? {
        val ad = rewardedAd
        if (ad != null && hasFreshAd()) {
            rewardedAd = null
            loadTimeMillis = 0L
            publishAvailability()
            Log.d(TAG, "rewarded ad claimed trigger=$trigger")
            return ad
        }

        if (ad != null) {
            Log.d(TAG, "discarding expired rewarded ad trigger=$trigger")
            rewardedAd = null
            loadTimeMillis = 0L
            publishAvailability()
        }
        preloadOnDemand(context, "claim_miss:$trigger")
        return null
    }

    private fun preload(
        context: Context,
        trigger: String,
    ) {
        if (MobileAdsInitializer.adsDisabled) {
            clear()
            return
        }
        if (hasFreshAd()) {
            publishAvailability()
            return
        }
        rewardedAd = null
        loadTimeMillis = 0L
        if (!isLoading.compareAndSet(false, true)) {
            publishAvailability()
            return
        }
        publishAvailability()

        val appContext = context.applicationContext
        MobileAdsInitializer.runAfterInitialized(appContext) {
            if (MobileAdsInitializer.adsDisabled) {
                clear()
                return@runAfterInitialized
            }
            if (hasFreshAd()) {
                isLoading.set(false)
                publishAvailability()
                return@runAfterInitialized
            }

            Log.d(TAG, "rewarded ad load requested trigger=$trigger")
            RewardedAd.load(
                appContext,
                PRO_REWARDED_AD_UNIT_ID,
                AdMobAdRequestFactory.build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        loadTimeMillis = System.currentTimeMillis()
                        isLoading.set(false)
                        publishAvailability()
                        Log.d(TAG, "rewarded ad loaded trigger=$trigger")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        rewardedAd = null
                        loadTimeMillis = 0L
                        isLoading.set(false)
                        publishAvailability()
                        Log.w(TAG, "rewarded ad failed to load trigger=$trigger code=${error.code} msg=${error.message}")
                    }
                },
            )
        }
    }

    private fun hasFreshAd(): Boolean {
        rewardedAd ?: return false
        val age = System.currentTimeMillis() - loadTimeMillis
        if (age > MAX_AD_AGE_MS) {
            rewardedAd = null
            loadTimeMillis = 0L
            return false
        }
        return true
    }

    private fun clear() {
        rewardedAd = null
        loadTimeMillis = 0L
        isLoading.set(false)
        publishAvailability()
    }

    private fun publishAvailability() {
        _availability.value =
            ProRewardedAdAvailability(
                available = rewardedAd != null && hasFreshAd(),
                loading = isLoading.get(),
            )
    }
}
