package com.ibbie.catrec_screenrecorcer.ui.support

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.ibbie.catrec_screenrecorcer.R

private const val PRIVACY_POLICY_URL = "https://github.com/ibbisdead/CatRec-Android/blob/main/privacy-policy.md"
private const val TERMS_OF_SERVICE_URL = "https://github.com/ibbisdead/CatRec-Android/blob/main/terms-of-service.md"
private const val PERMISSIONS_DISCLOSURE_URL = "https://github.com/ibbisdead/CatRec-Android/blob/main/permissions-disclosure.md"

@Composable
fun SupportScreen() {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val activity = context as? Activity

    var showChangelogDialog by rememberSaveable { mutableStateOf(false) }
    var showComingSoonDialog by rememberSaveable { mutableStateOf(false) }

    var rewardedAd by remember { mutableStateOf<RewardedAd?>(null) }
    var isAdLoading by remember { mutableStateOf(false) }

    fun loadRewardedAd() {
        if (isAdLoading) return
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

    LaunchedEffect(Unit) { loadRewardedAd() }

    DisposableEffect(Unit) {
        onDispose { rewardedAd = null }
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
                "CatRec Screen Recorder",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Version 0.9.1 Beta",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Pets, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "Support the Developer",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = TextAlign.Start
            )

            SupportActionCard(
                icon = Icons.Default.OndemandVideo,
                title = "Subscribe to YouTube",
                subtitle = "Follow @ibbie for updates",
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://youtube.com/@ibbie")))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not open YouTube", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.RemoveCircleOutline,
                title = "Remove Ads — Coming Soon",
                subtitle = "One-time purchase — available when CatRec launches on the Play Store",
                highlight = false,
                onClick = { showComingSoonDialog = true }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.PlayCircle,
                title = "Watch an Ad",
                subtitle = if (isAdLoading) "Loading ad…" else "Quick way to support for free",
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
                                    Toast.makeText(context, "Couldn't show ad. Try again later.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            ad.show(activity) {
                                Toast.makeText(context, "Thank you for watching! 🐱", Toast.LENGTH_SHORT).show()
                            }
                        }
                        isAdLoading -> Toast.makeText(context, "Ad is loading, please wait…", Toast.LENGTH_SHORT).show()
                        else -> {
                            Toast.makeText(context, "No ad available right now. Try again later.", Toast.LENGTH_SHORT).show()
                            loadRewardedAd()
                        }
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.MonetizationOn,
                title = "Buy me a coffee — Coming Soon",
                subtitle = "Repeatable donations available when CatRec launches on the Play Store",
                highlight = false,
                onClick = { showComingSoonDialog = true }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.Share,
                title = "Share CatRec",
                subtitle = "Tell your friends about us",
                onClick = {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, "Check out CatRec - the coolest screen recorder!")
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(shareIntent, null))
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.History,
                title = "Changelog",
                subtitle = "See what's new in this version",
                onClick = { showChangelogDialog = true }
            )

            Spacer(Modifier.height(32.dp))

            Text(
                "Legal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = TextAlign.Start
            )

            SupportActionCard(
                icon = Icons.Default.PrivacyTip,
                title = "Privacy Policy",
                subtitle = "How CatRec handles your data",
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.Gavel,
                title = "Terms of Service",
                subtitle = "Usage rules and disclaimers",
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_OF_SERVICE_URL)))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            SupportActionCard(
                icon = Icons.Default.Security,
                title = "Permissions Disclosure",
                subtitle = "Why each permission is needed",
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PERMISSIONS_DISCLOSURE_URL)))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showComingSoonDialog) {
        AlertDialog(
            onDismissRequest = { showComingSoonDialog = false },
            icon = { Icon(Icons.Default.MonetizationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) },
            title = { Text("Coming Soon") },
            text = { Text("In-app purchases will be available once CatRec launches on the Google Play Store. Thank you for your patience and support!") },
            confirmButton = { TextButton(onClick = { showComingSoonDialog = false }) { Text("Got it") } }
        )
    }

    if (showChangelogDialog) {
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            icon = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
            title = { Text("Changelog", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ChangelogEntry(
                        version = "v0.9.0",
                        label = "Beta",
                        changes = listOf(
                            // Recording
                            "Screen recording with H.264 and H.265 (HEVC) codec support",
                            "60-second rolling Clipper mode with seamless segment stitching",
                            "Save Clip button appears instantly while Clipper is running",
                            "Wide resolution selection for all aspect ratios, plus custom input",
                            "Auto orientation tracking during recording",
                            "Countdown timer before recording starts",
                            "Keep-screen-on option during recording",
                            // Audio
                            "Audio recording: microphone, internal audio, or mixed",
                            "Separate microphone track recording",
                            "Advanced audio settings: bitrate, sample rate, channels, encoder",
                            "Fixed stereo audio recording for stereo channel mode",
                            "Fixed audio bitrate accuracy with CBR encoding",
                            // Overlay
                            "Floating controls overlay: pause, stop, mute, screenshot",
                            "Overlay available at all times, not just during recording",
                            "Start recording or clipping directly from the overlay button",
                            "One-tap authorize overlay recording — no repeated permission prompts",
                            "Overlay X dismiss appears immediately on drag",
                            "Camera overlay with live face-cam preview",
                            "Watermark overlay with custom images, shape, opacity, and position",
                            // Screenshots
                            "Screenshots tab with format and quality settings",
                            "Screenshots captured from the overlay camera button",
                            // Customization & UI
                            "Accent color customization: presets, gradients, and hex input",
                            "Dynamic theme — all UI elements follow the chosen accent color",
                            "Performance mode toggle disables blur for lower-end devices",
                            "Full settings reorganization (Controls, Video, Audio, Overlay…)",
                            "Language support for 16 languages",
                            "Fixed language preference persisting after app restart",
                            // Library & Notifications
                            "Recordings library with thumbnail previews",
                            "In-app video trimmer",
                            "Post-recording notification with thumbnail and quick actions",
                            // Quick Settings
                            "Quick Settings tiles: CatRec Record and CatRec Clipper"
                        )
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showChangelogDialog = false }) { Text("Close") } }
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
                Text("•", modifier = Modifier.padding(end = 8.dp, top = 1.dp), color = MaterialTheme.colorScheme.primary)
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(32.dp),
                tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
