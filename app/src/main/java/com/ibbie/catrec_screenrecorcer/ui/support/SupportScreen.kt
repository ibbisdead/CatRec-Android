package com.ibbie.catrec_screenrecorcer.ui.support

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.ibbie.catrec_screenrecorcer.BuildConfig
import com.ibbie.catrec_screenrecorcer.CatRecApplication
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.billing.BillingUiEvent
import com.ibbie.catrec_screenrecorcer.navigation.Screen
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel
import com.ibbie.catrec_screenrecorcer.ui.theme.isLightTheme

private const val PRIVACY_POLICY_URL = "https://github.com/ibbisdead/CatRec-Android/blob/main/privacy-policy.md"
private const val TERMS_OF_SERVICE_URL = "https://github.com/ibbisdead/CatRec-Android/blob/main/terms-of-service.md"
private const val PERMISSIONS_DISCLOSURE_URL = "https://github.com/ibbisdead/CatRec-Android/blob/main/permissions-disclosure.md"

@Composable
fun SupportScreen(
    viewModel: RecordingViewModel,
    navController: NavController,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val activity = context as? Activity
    val adsDisabled by viewModel.adsDisabled.collectAsState()
    val billing = remember(context) {
        (context.applicationContext as CatRecApplication).billingManager
    }

    var showChangelogDialog by rememberSaveable { mutableStateOf(false) }

    var rewardedAd by remember { mutableStateOf<RewardedAd?>(null) }
    var isAdLoading by remember { mutableStateOf(false) }

    fun loadRewardedAd() {
        if (adsDisabled || isAdLoading) return
        isAdLoading = true
        RewardedAd.load(
            context,
            "ca-app-pub-7741372232895726/8137302121",
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isAdLoading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isAdLoading = false
                }
            }
        )
    }

    LaunchedEffect(adsDisabled) {
        if (adsDisabled) {
            rewardedAd = null
            isAdLoading = false
        } else {
            loadRewardedAd()
        }
    }

    DisposableEffect(adsDisabled) {
        onDispose {
            rewardedAd = null
        }
    }

    LaunchedEffect(Unit) {
        billing.uiEvents.collect { ev ->
            when (ev) {
                BillingUiEvent.SupportMeConsumed ->
                    Toast.makeText(context, context.getString(R.string.billing_thanks_support_me), Toast.LENGTH_SHORT).show()
                BillingUiEvent.RemoveAdsPending ->
                    Toast.makeText(context, context.getString(R.string.billing_pending), Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        setImageResource(R.mipmap.ic_launcher)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                modifier = Modifier.size(120.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.support_app_headline),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.support_version_label, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Pets, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                stringResource(R.string.support_section_help),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = TextAlign.Start
            )

            SupportActionCard(
                icon = Icons.Default.Email,
                title = stringResource(R.string.support_send_feedback),
                subtitle = stringResource(R.string.support_send_feedback_desc),
                onClick = {
                    navController.navigate(Screen.Feedback.route) { launchSingleTop = true }
                }
            )

            Spacer(Modifier.height(32.dp))

            Text(
                stringResource(R.string.support_section_support_dev),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = TextAlign.Start
            )

            SupportActionCard(
                icon = Icons.Default.OndemandVideo,
                title = stringResource(R.string.support_subscribe_youtube),
                subtitle = stringResource(R.string.support_subscribe_youtube_desc),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://youtube.com/@ibbie")))
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.support_toast_youtube), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.RemoveCircleOutline,
                title = if (adsDisabled) stringResource(R.string.support_ads_removed) else stringResource(R.string.support_remove_ads),
                subtitle = if (adsDisabled) stringResource(R.string.support_ads_removed_desc)
                else stringResource(R.string.support_remove_ads_desc),
                highlight = !adsDisabled,
                onClick = {
                    if (adsDisabled) return@SupportActionCard
                    if (activity == null) return@SupportActionCard
                    if (!billing.launchRemoveAdsPurchase(activity)) {
                        Toast.makeText(context, context.getString(R.string.billing_store_not_ready), Toast.LENGTH_SHORT).show()
                        billing.refreshPurchasesIfConnected()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            if (!adsDisabled) {
                SupportActionCard(
                    icon = Icons.Default.PlayCircle,
                    title = stringResource(R.string.support_watch_ad),
                    subtitle = if (isAdLoading) stringResource(R.string.support_watch_ad_sub_loading)
                    else stringResource(R.string.support_watch_ad_sub),
                    onClick = {
                        val ad = rewardedAd
                        when {
                            ad != null && activity != null -> {
                                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                    override fun onAdDismissedFullScreenContent() {
                                        rewardedAd = null
                                        loadRewardedAd()
                                    }
                                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                                        rewardedAd = null
                                        loadRewardedAd()
                                        Toast.makeText(context, context.getString(R.string.support_toast_ad_failed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                ad.show(activity) {
                                    Toast.makeText(context, context.getString(R.string.support_toast_thanks_ad), Toast.LENGTH_SHORT).show()
                                }
                            }
                            isAdLoading -> Toast.makeText(context, context.getString(R.string.support_toast_ad_loading), Toast.LENGTH_SHORT).show()
                            else -> {
                                Toast.makeText(context, context.getString(R.string.support_toast_no_ad), Toast.LENGTH_SHORT).show()
                                loadRewardedAd()
                            }
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            SupportActionCard(
                icon = Icons.Default.MonetizationOn,
                title = stringResource(R.string.support_donate),
                subtitle = stringResource(R.string.support_donate_desc),
                highlight = true,
                onClick = {
                    if (activity == null) return@SupportActionCard
                    if (!billing.launchSupportMePurchase(activity)) {
                        Toast.makeText(context, context.getString(R.string.billing_store_not_ready), Toast.LENGTH_SHORT).show()
                        billing.refreshPurchasesIfConnected()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Filled.Restore,
                title = stringResource(R.string.support_restore_purchases),
                subtitle = stringResource(R.string.support_restore_purchases_desc),
                onClick = {
                    if (billing.refreshPurchasesIfConnected()) {
                        Toast.makeText(context, context.getString(R.string.support_restore_purchases_toast), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.billing_store_not_ready), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.CardGiftcard,
                title = stringResource(R.string.support_play_promo_title),
                subtitle = stringResource(R.string.support_play_promo_desc),
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/redeem"))
                        )
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.support_toast_promo_browser), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.Share,
                title = stringResource(R.string.support_share),
                subtitle = stringResource(R.string.support_share_desc),
                onClick = {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.toast_share_app))
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(shareIntent, null))
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.History,
                title = stringResource(R.string.support_changelog),
                subtitle = stringResource(R.string.support_changelog_desc),
                onClick = { showChangelogDialog = true }
            )

            Spacer(Modifier.height(32.dp))

            Text(
                stringResource(R.string.support_section_legal),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = TextAlign.Start
            )

            SupportActionCard(
                icon = Icons.Default.PrivacyTip,
                title = stringResource(R.string.support_privacy_policy),
                subtitle = stringResource(R.string.support_privacy_policy_desc),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.support_toast_browser), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.Gavel,
                title = stringResource(R.string.support_terms_of_service),
                subtitle = stringResource(R.string.support_terms_of_service_desc),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_OF_SERVICE_URL)))
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.support_toast_browser), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.Security,
                title = stringResource(R.string.support_permissions_disclosure),
                subtitle = stringResource(R.string.support_permissions_disclosure_desc),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PERMISSIONS_DISCLOSURE_URL)))
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.support_toast_browser), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showChangelogDialog) {
        val changelog098Items = stringArrayResource(R.array.changelog_v098_items).toList()
        val changelog096Items = stringArrayResource(R.array.changelog_v096_items).toList()
        val changelog095Items = stringArrayResource(R.array.changelog_v095_items).toList()
        val changelog090Items = stringArrayResource(R.array.changelog_v090_items).toList()
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            icon = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.dialog_changelog_title), fontWeight = FontWeight.Bold) },
            text = {
                // Newest release at top.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ChangelogEntry(
                        version = stringResource(R.string.changelog_version_template, BuildConfig.VERSION_NAME),
                        label = stringResource(R.string.label_beta),
                        changes = changelog098Items,
                    )
                    Spacer(Modifier.height(20.dp))
                    ChangelogEntry(
                        version = stringResource(R.string.changelog_version_v096),
                        label = stringResource(R.string.label_beta),
                        changes = changelog096Items,
                    )
                    Spacer(Modifier.height(20.dp))
                    ChangelogEntry(
                        version = stringResource(R.string.changelog_version_v095),
                        label = stringResource(R.string.label_beta),
                        changes = changelog095Items,
                    )
                    Spacer(Modifier.height(20.dp))
                    ChangelogEntry(
                        version = stringResource(R.string.changelog_version_v090),
                        label = stringResource(R.string.label_beta),
                        changes = changelog090Items,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showChangelogDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun ChangelogEntry(version: String, label: String, changes: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        changes.forEach { change ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(stringResource(R.string.list_bullet), modifier = Modifier.padding(end = 8.dp, top = 1.dp), color = MaterialTheme.colorScheme.primary)
                Text(change, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SupportActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlight: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val light = scheme.isLightTheme()
    val containerColor = when {
        highlight && !light -> scheme.primaryContainer
        highlight && light -> scheme.surfaceVariant
        else -> scheme.surfaceVariant
    }
    val titleColor = when {
        highlight && !light -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    val subtitleColor = when {
        highlight && !light -> scheme.onPrimaryContainer.copy(alpha = 0.72f)
        else -> scheme.onSurfaceVariant
    }
    val iconTint = scheme.primary
    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlight && light) {
                    Modifier.border(1.5.dp, scheme.primary.copy(alpha = 0.42f), shape)
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (light) 0.dp else 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(32.dp), tint = iconTint)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = titleColor)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
        }
    }
}
