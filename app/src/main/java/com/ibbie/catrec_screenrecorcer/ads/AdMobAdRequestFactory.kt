package com.ibbie.catrec_screenrecorcer.ads

import android.os.Bundle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest

/**
 * Builds [AdRequest] instances that respect the in-app "personalized ads" toggle.
 *
 * Non-personalized ads must use the `npa=1` network extra. Do not use RequestConfiguration
 * tag-for-under-age-of-consent for NPA; that flag is for minors or COPPA-style signaling and crushes fill if misused.
 */
object AdMobAdRequestFactory {
    @Volatile
    var personalizedAdsEnabled: Boolean = true

    fun build(): AdRequest {
        val builder = AdRequest.Builder()
        if (!personalizedAdsEnabled) {
            val extras = Bundle().apply { putString("npa", "1") }
            builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
        }
        return builder.build()
    }
}
