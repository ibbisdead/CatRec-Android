package com.ibbie.catrec_screenrecorcer.ui.components

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ads.AdMobAdRequestFactory

@Composable
fun BannerAdRow(
    adsDisabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val unitId = stringResource(R.string.admob_banner_unit_id)
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val localeFingerprint = configuration.locales.toLanguageTags()
    val localeTag =
        remember(localeFingerprint) {
            configuration.locales.get(0)?.toLanguageTag().orEmpty()
        }

    if (adsDisabled) return

    // Include locale so language / layout-direction changes get a fresh AdView; avoid stacking
    // MobileAds.initialize callbacks on a view that Compose may already have released.
    key(unitId, screenWidthDp, localeTag) {
        AndroidView(
            modifier =
                modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            factory = { ctx ->
                AdView(ctx).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    adUnitId = unitId
                    adListener =
                        object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                Log.w("BannerAdRow", "onAdFailedToLoad: ${error.message} code=${error.code}")
                            }

                            override fun onAdLoaded() {
                                Log.d("BannerAdRow", "onAdLoaded")
                            }
                        }
                    val density = ctx.resources.displayMetrics.density
                    val adWidth =
                        (ctx.resources.displayMetrics.widthPixels / density)
                            .toInt()
                            .coerceAtLeast(320)
                    setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidth))
                    // Match AppOpenAdManager: only load after SDK init. Loading in factory() often runs
                    // before Application.onCreate’s initialize callback finishes → failed banner loads.
                    // Use applicationContext so init/load does not run against an activity ConfigurationContext
                    // (avoids odd WebView / resource resolution paths on some OEM builds).
                    MobileAds.initialize(ctx.applicationContext) {
                        loadAd(AdMobAdRequestFactory.build())
                    }
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
