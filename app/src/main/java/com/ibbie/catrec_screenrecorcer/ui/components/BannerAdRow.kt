package com.ibbie.catrec_screenrecorcer.ui.components

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ads.AdMobAdRequestFactory
import com.ibbie.catrec_screenrecorcer.ads.MobileAdsInitializer

/**
 * While [loaded] is false, measures the child using a fixed adaptive-banner height in px (so AdMob
 * gets valid constraints for [AdSize.getLargeAnchoredAdaptiveBannerAdSize]) but reports **0** height
 * to the parent and clips overflow — no visible strip. After load, uses normal measurement.
 */
private fun Modifier.bannerAdaptiveSlot(
    loaded: Boolean,
    preloadHeightPx: Int,
): Modifier =
    this.layout { measurable, constraints ->
        val maxW = constraints.maxWidth
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else if (loaded) {
            val placeable =
                measurable.measure(
                    Constraints(
                        minWidth = maxW,
                        maxWidth = maxW,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )
            layout(maxW, placeable.height) { placeable.place(0, 0) }
        } else {
            val h = preloadHeightPx.coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(maxW, h))
            layout(maxW, 0) { placeable.place(0, 0) }
        }
    }

@Composable
fun BannerAdRow(
    adsDisabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val unitId = stringResource(R.string.admob_banner_unit_id)
    val configuration = LocalConfiguration.current
    // Use screen width — not LocalWindowInfo.containerSize — so we do not dispose/recreate the
    // AdView when window insets or compose bounds settle a few frames after cold start (API 35+).
    val bannerWidthDp = configuration.screenWidthDp.coerceAtLeast(320)
    val localeFingerprint = configuration.locales.toLanguageTags()
    val localeTag =
        remember(localeFingerprint) {
            configuration.locales
                .get(0)
                ?.toLanguageTag()
                .orEmpty()
        }

    if (adsDisabled) return

    val preloadHeightPx =
        remember(
            bannerWidthDp,
            configuration.orientation,
            configuration.densityDpi,
            configuration.fontScale,
        ) {
            val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, bannerWidthDp)
            adSize.getHeightInPixels(context).coerceAtLeast(1)
        }

    // Include locale so language / layout-direction changes get a fresh AdView; avoid stacking
    // MobileAds.initialize callbacks on a view that Compose may already have released.
    key(unitId, bannerWidthDp, localeTag) {
        var hasRenderableAd by remember { mutableStateOf(false) }
        val bannerAdListener =
            remember {
                object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.d("BannerAdRow", "onAdFailedToLoad: ${error.message} code=${error.code}")
                        hasRenderableAd = false
                    }

                    override fun onAdLoaded() {
                        Log.d("BannerAdRow", "onAdLoaded")
                        hasRenderableAd = true
                    }
                }
            }
        AndroidView(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .bannerAdaptiveSlot(hasRenderableAd, preloadHeightPx),
            factory = { ctx ->
                AdView(ctx).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    adUnitId = unitId
                    setAdListener(bannerAdListener)
                    setAdSize(
                        AdSize.getLargeAnchoredAdaptiveBannerAdSize(ctx, bannerWidthDp),
                    )
                    // Match AppOpenAdManager: only load after SDK init. Loading in factory() often runs
                    // before Application.onCreate’s initialize callback finishes → failed banner loads.
                    // Use applicationContext so init/load does not run against an activity ConfigurationContext
                    // (avoids odd WebView / resource resolution paths on some OEM builds).
                    // [MobileAdsInitializer] owns SDK init; this callback runs only after it is ready.
                    MobileAdsInitializer.runAfterInitialized(ctx) {
                        loadAd(AdMobAdRequestFactory.build())
                    }
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
