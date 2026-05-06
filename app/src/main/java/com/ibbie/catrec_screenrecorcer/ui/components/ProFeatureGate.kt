package com.ibbie.catrec_screenrecorcer.ui.components

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ads.AdMobAdRequestFactory
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressionReason
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressor
import com.ibbie.catrec_screenrecorcer.ads.resetWindowFocusAfterFullscreenOverlay
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import kotlinx.coroutines.launch

private const val PRO_GATE_TAG = "ProGate"
private const val PRO_REWARDED_AD_UNIT_ID = "ca-app-pub-7741372232895726/8137302121"

enum class ProFeature(val logName: String) {
    RECORDING_120_FPS("pro_120fps"),
    SEPARATE_AUDIO_TRACKS("pro_separate_audio_tracks"),
    CAMERA_OVERLAY("pro_camera_overlay"),
    WATERMARK("pro_watermark"),
    MERGE_CLIPS("pro_merge_clips"),
    VIDEO_TO_GIF("pro_video_to_gif"),
}

@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.pro_feature)
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Text(
            text = "PRO",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ProUnlockDialog(
    title: String,
    body: String,
    watchButtonText: String,
    showRemoveAds: Boolean,
    triggeredFeatures: List<ProFeature>,
    onUnlocked: () -> Unit,
    onRemoveAds: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val toastAdUnavailable = stringResource(R.string.pro_unlock_ad_unavailable)

    var rewardedAd by remember { mutableStateOf<RewardedAd?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf(false) }
    var rewardEarned by remember { mutableStateOf(false) }
    val featureLog = triggeredFeatures.joinToString(",") { it.logName }

    fun loadRewardedAd() {
        if (loading || rewardedAd != null) return
        loading = true
        RewardedAd.load(
            context.applicationContext,
            PRO_REWARDED_AD_UNIT_ID,
            AdMobAdRequestFactory.build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    Log.d(PRO_GATE_TAG, "rewarded ad loaded purpose=pro_unlock features=$featureLog")
                    rewardedAd = ad
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(PRO_GATE_TAG, "rewarded ad failed to load purpose=pro_unlock code=${error.code} msg=${error.message}")
                    rewardedAd = null
                    loading = false
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        loadRewardedAd()
    }

    AlertDialog(
        onDismissRequest = {
            if (!showing) onDismiss()
        },
        icon = { ProBadge() },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                enabled = !showing && !loading,
                onClick = {
                    val ad = rewardedAd
                    if (activity == null || ad == null) {
                        Log.w(PRO_GATE_TAG, "pro unlock blocked: rewarded ad unavailable features=$featureLog")
                        Toast.makeText(context, toastAdUnavailable, Toast.LENGTH_SHORT).show()
                        loadRewardedAd()
                        return@TextButton
                    }
                    showing = true
                    rewardEarned = false
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.REWARDED_AD)
                    ad.fullScreenContentCallback =
                        object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                activity.resetWindowFocusAfterFullscreenOverlay()
                                AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.REWARDED_AD)
                                showing = false
                                rewardedAd = null
                                if (rewardEarned) {
                                    scope.launch {
                                        val until = repository.grantTimedProAccess()
                                        Log.d(PRO_GATE_TAG, "Pro access granted until=$until features=$featureLog")
                                        Log.d(PRO_GATE_TAG, "blocked action resumed after reward features=$featureLog")
                                        onUnlocked()
                                        onDismiss()
                                    }
                                } else {
                                    Log.d(PRO_GATE_TAG, "rewarded ad not earned purpose=pro_unlock features=$featureLog")
                                }
                            }

                            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                                Log.w(PRO_GATE_TAG, "rewarded ad failed to show purpose=pro_unlock msg=${error.message}")
                                activity.resetWindowFocusAfterFullscreenOverlay()
                                AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.REWARDED_AD)
                                showing = false
                                rewardEarned = false
                                rewardedAd = null
                                Toast.makeText(context, toastAdUnavailable, Toast.LENGTH_SHORT).show()
                                loadRewardedAd()
                            }
                        }
                    ad.show(activity) {
                        rewardEarned = true
                        Log.d(PRO_GATE_TAG, "rewarded ad earned purpose=pro_unlock features=$featureLog")
                    }
                },
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(watchButtonText)
                }
            }
        },
        dismissButton = {
            if (showRemoveAds && onRemoveAds != null) {
                TextButton(
                    enabled = !showing,
                    onClick = {
                        onRemoveAds()
                    },
                ) {
                    Text(stringResource(R.string.pro_unlock_remove_ads))
                }
            }
        },
    )
}

fun logProGateCheck(
    features: List<ProFeature>,
    adsDisabled: Boolean,
    proUnlockedUntilMillis: Long,
) {
    val now = System.currentTimeMillis()
    val names = features.joinToString(",") { it.logName }
    Log.d(PRO_GATE_TAG, "Pro gate checked features=$names adsDisabled=$adsDisabled unlockedUntil=$proUnlockedUntilMillis now=$now")
    when {
        adsDisabled -> Log.d(PRO_GATE_TAG, "Pro gate bypassed due to Remove Ads features=$names")
        proUnlockedUntilMillis > now -> Log.d(PRO_GATE_TAG, "Pro gate bypassed due to active 4-hour unlock features=$names")
    }
}
