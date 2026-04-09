package com.ibbie.catrec_screenrecorcer.ui.components

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.ibbie.catrec_screenrecorcer.R

@Composable
fun BannerAdRow(
    adsDisabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val unitId = stringResource(R.string.admob_banner_unit_id)
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    if (adsDisabled) return

    key(unitId, screenWidthDp) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            factory = { ctx ->
                AdView(ctx).apply {
                    adUnitId = unitId
                    adListener = object : AdListener() {
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            Log.w("BannerAdRow", "onAdFailedToLoad: ${error.message} code=${error.code}")
                        }

                        override fun onAdLoaded() {
                            Log.d("BannerAdRow", "onAdLoaded")
                        }
                    }
                    MobileAds.initialize(ctx) {
                        val density = ctx.resources.displayMetrics.density
                        val adWidth = (ctx.resources.displayMetrics.widthPixels / density).toInt()
                            .coerceAtLeast(320)
                        val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidth)
                        setAdSize(adSize)
                        loadAd(AdRequest.Builder().build())
                    }
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
