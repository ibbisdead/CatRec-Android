package com.ibbie.catrec_screenrecorcer.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.ibbie.catrec_screenrecorcer.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.ibbie.catrec_screenrecorcer.data.AdGate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.service.OverlayService
import com.ibbie.catrec_screenrecorcer.ui.components.*
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.theme.SwitchOffGray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: RecordingViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val accent = LocalAccentColor.current

    // Video
    val fps by viewModel.fps.collectAsState()
    val bitrate by viewModel.bitrate.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val videoEncoder by viewModel.videoEncoder.collectAsState()
    val recordingOrientation by viewModel.recordingOrientation.collectAsState()

    // Audio
    val recordAudio by viewModel.recordAudio.collectAsState()
    val internalAudio by viewModel.internalAudio.collectAsState()
    val audioBitrate by viewModel.audioBitrate.collectAsState()
    val audioSampleRate by viewModel.audioSampleRate.collectAsState()
    val audioChannels by viewModel.audioChannels.collectAsState()
    val audioEncoder by viewModel.audioEncoder.collectAsState()
    val separateMicRecording by viewModel.separateMicRecording.collectAsState()

    // Controls
    val floatingControls by viewModel.floatingControls.collectAsState()
    val touchOverlay by viewModel.touchOverlay.collectAsState()
    val countdown by viewModel.countdown.collectAsState()
    val stopBehavior by viewModel.stopBehavior.collectAsState()

    // Camera Overlay
    val cameraOverlay by viewModel.cameraOverlay.collectAsState()
    val cameraOverlaySize by viewModel.cameraOverlaySize.collectAsState()
    val cameraXFraction by viewModel.cameraXFraction.collectAsState()
    val cameraYFraction by viewModel.cameraYFraction.collectAsState()
    val cameraLockPosition by viewModel.cameraLockPosition.collectAsState()
    val cameraFacing by viewModel.cameraFacing.collectAsState()
    val cameraAspectRatio by viewModel.cameraAspectRatio.collectAsState()
    val cameraOpacity by viewModel.cameraOpacity.collectAsState()

    // Watermark
    val showWatermark by viewModel.showWatermark.collectAsState()
    val watermarkImageUri by viewModel.watermarkImageUri.collectAsState()
    val watermarkShape by viewModel.watermarkShape.collectAsState()
    val watermarkOpacity by viewModel.watermarkOpacity.collectAsState()
    val watermarkSize by viewModel.watermarkSize.collectAsState()
    val watermarkXFraction by viewModel.watermarkXFraction.collectAsState()
    val watermarkYFraction by viewModel.watermarkYFraction.collectAsState()

    // Screenshots
    val screenshotFormat by viewModel.screenshotFormat.collectAsState()
    val screenshotQuality by viewModel.screenshotQuality.collectAsState()

    // Theme & Language
    val appTheme by viewModel.appTheme.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()

    // UI Mode
    val performanceMode by viewModel.performanceMode.collectAsState()
    val isLowEndDevice = rememberIsLowEndDevice()

    // Accent Color
    val accentHex       by viewModel.accentColor.collectAsState()
    val accentHex2      by viewModel.accentColor2.collectAsState()
    val accentGradient  by viewModel.accentUseGradient.collectAsState()

    // Storage
    val saveLocationUri by viewModel.saveLocationUri.collectAsState()
    val filenamePattern by viewModel.filenamePattern.collectAsState()
    val autoDelete by viewModel.autoDelete.collectAsState()

    // General
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()

    // Privacy
    val analyticsEnabled by viewModel.analyticsEnabled.collectAsState()

    val isRecording by RecordingState.isRecording.collectAsState()
    val scrollState = rememberScrollState()
    val canDrawOverlays = Settings.canDrawOverlays(context)

    // Watermark live preview
    if (showWatermark && canDrawOverlays && !isRecording) {
        DisposableEffect(Unit) {
            context.startService(Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_WATERMARK_PREVIEW
                putExtra(OverlayService.EXTRA_WATERMARK_SIZE, watermarkSize)
                putExtra(OverlayService.EXTRA_WATERMARK_OPACITY, watermarkOpacity)
                putExtra(OverlayService.EXTRA_WATERMARK_SHAPE, watermarkShape)
                putExtra(OverlayService.EXTRA_WATERMARK_IMAGE_URI, watermarkImageUri)
                putExtra(OverlayService.EXTRA_WATERMARK_X_FRACTION, watermarkXFraction)
                putExtra(OverlayService.EXTRA_WATERMARK_Y_FRACTION, watermarkYFraction)
            })
            OverlayService.onPreviewPositionChanged = { x, y ->
                viewModel.setWatermarkXFraction(x); viewModel.setWatermarkYFraction(y)
            }
            onDispose {
                context.startService(Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_HIDE_WATERMARK_PREVIEW
                })
                OverlayService.onPreviewPositionChanged = null
            }
        }
        LaunchedEffect(watermarkSize, watermarkOpacity, watermarkShape, watermarkImageUri, watermarkXFraction, watermarkYFraction) {
            OverlayService.updatePreviewIfActive(watermarkSize, watermarkOpacity, watermarkShape, watermarkImageUri, watermarkXFraction, watermarkYFraction)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.setSaveLocationUri(uri.toString())
        }
    }

    // Dialog states
    var showFpsDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }
    var showResolutionDialog by remember { mutableStateOf(false) }
    var showCustomResolutionDialog by remember { mutableStateOf(false) }
    var showVideoEncoderDialog by remember { mutableStateOf(false) }
    var showOrientationDialog by remember { mutableStateOf(false) }
    var showAudioBitrateDialog by remember { mutableStateOf(false) }
    var showAudioSampleRateDialog by remember { mutableStateOf(false) }
    var showAudioEncoderDialog by remember { mutableStateOf(false) }
    var showCountdownDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
    var showPatternDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showScreenshotFormatDialog by remember { mutableStateOf(false) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }
    var showCameraAspectRatioDialog by remember { mutableStateOf(false) }
    var showCameraFacingDialog by remember { mutableStateOf(false) }
    var showCameraOrientationDialog by remember { mutableStateOf(false) }
    var showWatermarkLocDialog by remember { mutableStateOf(false) }
    var showLagWarningDialog  by remember { mutableStateOf(false) }
    var showAccentPickerDialog by remember { mutableStateOf(false) }
    var accentPickingSecond   by remember { mutableStateOf(false) }
    var accentHexInput        by remember(accentHex)  { mutableStateOf(accentHex)  }
    var accentHex2Input       by remember(accentHex2) { mutableStateOf(accentHex2) }

    // ── Ad Gate ───────────────────────────────────────────────────────────────
    // Tracks which feature is pending unlock and what to do once unlocked.
    var adGateFeature     by remember { mutableStateOf("") }
    var adGateFeatureName by remember { mutableStateOf("") }
    var adGatePending     by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showAdGateDialog  by remember { mutableStateOf(false) }
    var adGateAd          by remember { mutableStateOf<RewardedAd?>(null) }
    var adGateLoading     by remember { mutableStateOf(false) }

    fun loadAdGateAd() {
        if (adGateLoading || adGateAd != null) return
        adGateLoading = true
        RewardedAd.load(
            context,
            // Replace with a dedicated ad unit ID for feature unlocks once live on Play Store.
            "ca-app-pub-7741372232895726/8137302121",
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { adGateAd = ad; adGateLoading = false }
                override fun onAdFailedToLoad(e: LoadAdError) { adGateAd = null; adGateLoading = false }
            }
        )
    }

    LaunchedEffect(Unit) { loadAdGateAd() }
    DisposableEffect(Unit) { onDispose { adGateAd = null } }

    /** Show the ad-gate dialog for [feature]. On unlock, [action] is invoked. */
    fun gateFeature(feature: String, name: String, action: () -> Unit) {
        if (AdGate.isUnlocked(feature)) { action(); return }
        adGateFeature = feature; adGateFeatureName = name; adGatePending = action
        showAdGateDialog = true
    }

    // Resolution options
    val resolutionOptions = listOf(
        "Native",
        "— 16:9 —",
        "3840x2160 (4K)", "2560x1440 (2K)", "1920x1080 (FHD)", "1280x720 (HD)", "854x480 (SD)", "640x360",
        "— 20:9 (Tall Phone) —",
        "3200x1440", "2400x1080", "2520x1080",
        "— 18:9 (2:1) —",
        "2880x1440", "2160x1080",
        "— 21:9 (Ultrawide) —",
        "2560x1080", "3440x1440",
        "— 4:3 —",
        "1920x1440", "1440x1080", "1280x960", "960x720",
        "— 9:16 (Portrait) —",
        "1080x1920", "720x1280", "1440x2560",
        "Custom…"
    )

    val languageOptions = listOf(
        "System Default", "English", "العربية (Arabic)", "中文简体 (Chinese Simplified)",
        "中文繁體 (Chinese Traditional)", "Français (French)", "Deutsch (German)",
        "हिन्दी (Hindi)", "Bahasa Indonesia", "Italiano (Italian)", "日本語 (Japanese)",
        "한국어 (Korean)", "Português (Portuguese)", "Русский (Russian)",
        "Español (Spanish)", "Türkçe (Turkish)", "Tiếng Việt (Vietnamese)"
    )
    val languageCodes = listOf(
        "system", "en", "ar", "zh-CN", "zh-TW", "fr", "de", "hi", "in", "it", "ja", "ko", "pt", "ru", "es", "tr", "vi"
    )

    // ── Dialogs (all logic unchanged) ──────────────────────────────────────────
    if (showFpsDialog) {
        SingleChoiceDialog("Frame Rate (FPS)", listOf("24", "30", "45", "60", "90", "120"),
            "${fps.toInt()}",
            onOptionSelected = { selected ->
                showFpsDialog = false
                if ((selected == "90" || selected == "120") && !AdGate.isUnlocked(AdGate.HIGH_FPS)) {
                    gateFeature(AdGate.HIGH_FPS, "$selected FPS") { viewModel.setFps(selected.toFloat()) }
                } else {
                    viewModel.setFps(selected.toFloat())
                }
            },
            onDismiss = { showFpsDialog = false })
    }

    if (showAdGateDialog) {
        AdGateDialog(
            featureName = adGateFeatureName,
            ad = adGateAd,
            isAdLoading = adGateLoading,
            onUnlocked = {
                AdGate.unlock(adGateFeature)
                adGatePending?.invoke()
                adGatePending = null
                showAdGateDialog = false
                adGateAd = null
                loadAdGateAd()
            },
            onDismiss = {
                adGatePending = null
                showAdGateDialog = false
            }
        )
    }
    if (showBitrateDialog) {
        SingleChoiceDialog("Bitrate",
            listOf("1 Mbps","2 Mbps","4 Mbps","6 Mbps","8 Mbps","10 Mbps","12 Mbps","16 Mbps",
                "20 Mbps","25 Mbps","30 Mbps","40 Mbps","50 Mbps","60 Mbps","80 Mbps","100 Mbps","120 Mbps","150 Mbps","200 Mbps"),
            "${bitrate.toInt()} Mbps",
            onOptionSelected = { viewModel.setBitrate(it.replace(" Mbps","").toFloatOrNull() ?: 10f); showBitrateDialog = false },
            onDismiss = { showBitrateDialog = false })
    }
    if (showResolutionDialog) {
        ResolutionDialog(
            options = resolutionOptions,
            selectedOption = resolution,
            onOptionSelected = { sel ->
                when {
                    sel == "Custom…" -> { showResolutionDialog = false; showCustomResolutionDialog = true }
                    sel.startsWith("—") -> {}
                    else -> { viewModel.setResolution(sel); showResolutionDialog = false }
                }
            },
            onDismiss = { showResolutionDialog = false }
        )
    }
    if (showCustomResolutionDialog) {
        CustomResolutionDialog(
            current = if (resolution.contains("x") && !resolutionOptions.contains(resolution)) resolution else "",
            onConfirm = { viewModel.setResolution(it); showCustomResolutionDialog = false },
            onDismiss = { showCustomResolutionDialog = false }
        )
    }
    if (showVideoEncoderDialog) {
        SingleChoiceDialog("Video Encoder", listOf("H.264", "H.265 (HEVC)"), videoEncoder,
            onOptionSelected = { viewModel.setVideoEncoder(it); showVideoEncoderDialog = false },
            onDismiss = { showVideoEncoderDialog = false })
    }
    if (showOrientationDialog) {
        SingleChoiceDialog("Orientation", listOf("Auto", "Portrait", "Landscape"), recordingOrientation,
            onOptionSelected = { viewModel.setRecordingOrientation(it); showOrientationDialog = false },
            onDismiss = { showOrientationDialog = false })
    }
    if (showAudioBitrateDialog) {
        SingleChoiceDialog("Audio Bitrate", listOf("32 kbps","64 kbps","96 kbps","128 kbps","192 kbps","256 kbps","320 kbps"),
            "$audioBitrate kbps",
            onOptionSelected = { viewModel.setAudioBitrate(it.replace(" kbps","").toIntOrNull() ?: 128); showAudioBitrateDialog = false },
            onDismiss = { showAudioBitrateDialog = false })
    }
    if (showAudioSampleRateDialog) {
        SingleChoiceDialog("Sample Rate", listOf("8000 Hz","16000 Hz","22050 Hz","44100 Hz","48000 Hz"),
            "$audioSampleRate Hz",
            onOptionSelected = { viewModel.setAudioSampleRate(it.replace(" Hz","").toIntOrNull() ?: 48000); showAudioSampleRateDialog = false },
            onDismiss = { showAudioSampleRateDialog = false })
    }
    if (showAudioEncoderDialog) {
        SingleChoiceDialog("Audio Encoder", listOf("AAC-LC", "AAC-HE", "AAC-HE v2", "AAC-ELD"), audioEncoder,
            onOptionSelected = { viewModel.setAudioEncoder(it); showAudioEncoderDialog = false },
            onDismiss = { showAudioEncoderDialog = false })
    }
    if (showCountdownDialog) {
        SingleChoiceDialog("Countdown Timer", listOf("None","3s","5s","10s"),
            if (countdown == 0) "None" else "${countdown}s",
            onOptionSelected = {
                viewModel.setCountdown(when(it) { "3s"->3; "5s"->5; "10s"->10; else->0 })
                showCountdownDialog = false
            },
            onDismiss = { showCountdownDialog = false })
    }
    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop Behavior") },
            text = {
                Column {
                    listOf("Notification","Shake Device","Screen Off","Pause on Screen Off").forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setStopBehavior(option) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = stopBehavior.contains(option), onCheckedChange = { viewModel.setStopBehavior(option) })
                            Text(option, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStopDialog = false }) { Text("Done") } }
        )
    }
    if (showPatternDialog) {
        SingleChoiceDialog("Filename Pattern", listOf("yyyyMMdd_HHmmss","CatRec_Timestamp","Date_Time"), filenamePattern,
            onOptionSelected = { viewModel.setFilenamePattern(it); showPatternDialog = false },
            onDismiss = { showPatternDialog = false })
    }
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("App Theme") },
            text = {
                Column {
                    listOf("System","Light","Dark").forEach { theme ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setAppTheme(theme); showThemeDialog = false }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = appTheme == theme, onClick = { viewModel.setAppTheme(theme); showThemeDialog = false })
                            Text(theme, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") } }
        )
    }
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("App Language") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    languageOptions.forEachIndexed { idx, lang ->
                        val code = languageCodes[idx]
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = appLanguage == code, onClick = {
                                    viewModel.setAppLanguage(code)
                                    showLanguageDialog = false
                                    applyLanguage(context, code)
                                })
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = appLanguage == code, onClick = {
                                viewModel.setAppLanguage(code)
                                showLanguageDialog = false
                                applyLanguage(context, code)
                            })
                            Text(lang, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text("Cancel") } }
        )
    }
    if (showScreenshotFormatDialog) {
        SingleChoiceDialog("Screenshot Format", listOf("JPEG","PNG","WebP"), screenshotFormat,
            onOptionSelected = { viewModel.setScreenshotFormat(it); showScreenshotFormatDialog = false },
            onDismiss = { showScreenshotFormatDialog = false })
    }
    if (showCameraSettingsDialog) {
        CameraSettingsDialog(
            viewModel = viewModel,
            cameraOverlay = cameraOverlay,
            cameraLockPosition = cameraLockPosition,
            cameraFacing = cameraFacing,
            cameraAspectRatio = cameraAspectRatio,
            cameraOpacity = cameraOpacity,
            cameraOverlaySize = cameraOverlaySize,
            cameraXFraction = cameraXFraction,
            cameraYFraction = cameraYFraction,
            isRecording = isRecording,
            canDrawOverlays = canDrawOverlays,
            context = context,
            onDismiss = { showCameraSettingsDialog = false }
        )
    }

    // ── Main Layout — Glass Card sections ──────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 2.sp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xCC0A0A0A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {

            // ── CONTROLS ──────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_controls))
                SwitchSettingItem(Icons.Default.ControlCamera, stringResource(R.string.setting_floating_controls),
                    "Always-on bubble — record, screenshot & controls from any app",
                    floatingControls) {
                    if (it && !Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    } else {
                        viewModel.setFloatingControls(it)
                        if (it && Settings.canDrawOverlays(context)) {
                            context.startService(Intent(context, com.ibbie.catrec_screenrecorcer.service.OverlayService::class.java).apply {
                                action = com.ibbie.catrec_screenrecorcer.service.OverlayService.ACTION_SHOW_IDLE_CONTROLS
                            })
                        } else if (!it) {
                            context.startService(Intent(context, com.ibbie.catrec_screenrecorcer.service.OverlayService::class.java).apply {
                                action = com.ibbie.catrec_screenrecorcer.service.OverlayService.ACTION_HIDE_IDLE_CONTROLS
                            })
                        }
                    }
                }
                SwitchSettingItem(Icons.Default.TouchApp, "Show Touches", "Enable in Developer Options",
                    touchOverlay) {
                    viewModel.setTouchOverlay(it)
                    if (it) {
                        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {}
                        Toast.makeText(context, "Enable 'Show Taps' manually", Toast.LENGTH_LONG).show()
                    }
                }
                ClickableSettingItem(Icons.Default.Timer, stringResource(R.string.setting_countdown),
                    if (countdown == 0) "Off" else "$countdown seconds") { showCountdownDialog = true }
                ClickableSettingItem(Icons.Default.StopCircle, stringResource(R.string.setting_stop_behavior), stopBehavior.joinToString(", ")) {
                    showStopDialog = true
                }
            }

            // ── VIDEO ─────────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_video))
                ClickableSettingItem(Icons.Default.Speed, stringResource(R.string.setting_fps), "${fps.toInt()} FPS") { showFpsDialog = true }
                ClickableSettingItem(Icons.Default.DataUsage, stringResource(R.string.setting_bitrate), "${bitrate.toInt()} Mbps") { showBitrateDialog = true }
                ClickableSettingItem(Icons.Default.AspectRatio, stringResource(R.string.setting_resolution), resolution) { showResolutionDialog = true }
                ClickableSettingItem(Icons.Default.VideoSettings, stringResource(R.string.setting_video_encoder), videoEncoder) { showVideoEncoderDialog = true }
                ClickableSettingItem(Icons.Default.ScreenRotation, stringResource(R.string.setting_orientation), recordingOrientation) { showOrientationDialog = true }
            }

            // ── AUDIO ─────────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_audio))
                SwitchSettingItem(Icons.Default.Mic, stringResource(R.string.setting_microphone), null, recordAudio) { viewModel.setRecordAudio(it) }
                SwitchSettingItem(Icons.Default.MusicNote, stringResource(R.string.setting_internal_audio), "Android 10+ only", internalAudio) { viewModel.setInternalAudio(it) }
                ClickableSettingItem(Icons.Default.GraphicEq, stringResource(R.string.setting_audio_bitrate), "$audioBitrate kbps") { showAudioBitrateDialog = true }
                ClickableSettingItem(Icons.Default.Audiotrack, stringResource(R.string.setting_audio_sample_rate), "$audioSampleRate Hz") { showAudioSampleRateDialog = true }

                // Audio Channels — segmented
                ListItem(
                    headlineContent = { Text(stringResource(R.string.setting_audio_channels)) },
                    leadingContent = { Icon(Icons.Default.SettingsVoice, contentDescription = null, tint = accent.copy(alpha = 0.7f)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    trailingContent = {
                        SingleChoiceSegmentedButtonRow {
                            listOf("Mono","Stereo").forEachIndexed { idx, ch ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                    onClick = { viewModel.setAudioChannels(ch) },
                                    selected = audioChannels == ch
                                ) { Text(ch) }
                            }
                        }
                    }
                )

                ClickableSettingItem(Icons.Default.Tune, stringResource(R.string.setting_audio_encoder), audioEncoder) { showAudioEncoderDialog = true }
                SwitchSettingItem(Icons.AutoMirrored.Filled.CallSplit, stringResource(R.string.setting_separate_mic),
                    stringResource(R.string.setting_separate_mic_desc), separateMicRecording) { newValue ->
                    if (newValue && !AdGate.isUnlocked(AdGate.SEPARATE_MIC)) {
                        gateFeature(AdGate.SEPARATE_MIC, "Separate Mic Track") {
                            viewModel.setSeparateMicRecording(true)
                        }
                    } else {
                        viewModel.setSeparateMicRecording(newValue)
                    }
                }
            }

            // ── OVERLAY ───────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_overlay))
                ClickableSettingItem(Icons.Default.CameraAlt, stringResource(R.string.setting_camera_settings),
                    if (cameraOverlay) "Enabled — ${cameraAspectRatio} • ${cameraFacing}" else "Disabled") {
                    if (!AdGate.isUnlocked(AdGate.CAMERA_SETTINGS)) {
                        gateFeature(AdGate.CAMERA_SETTINGS, "Camera Overlay Settings") {
                            showCameraSettingsDialog = true
                        }
                    } else {
                        showCameraSettingsDialog = true
                    }
                }

                SwitchSettingItem(Icons.AutoMirrored.Filled.BrandingWatermark, stringResource(R.string.setting_watermark), null, showWatermark) {
                    if (it && !Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    } else viewModel.setShowWatermark(it)
                }

                if (showWatermark) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        color = Color(0x33FF0033),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Visibility, null, tint = accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (canDrawOverlays && !isRecording) "Live preview active — drag watermark to reposition"
                                else if (isRecording) "Preview unavailable while recording"
                                else "Grant overlay permission to see live preview",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFCCAAAA)
                            )
                        }
                    }

                    var showWatermarkLocDialog2 by remember { mutableStateOf(false) }
                    if (showWatermarkLocDialog2) {
                        SingleChoiceDialog("Snap to Position", listOf("Top Left","Top Right","Bottom Left","Bottom Right","Center"), "Custom",
                            onOptionSelected = { pos ->
                                val (x, y) = when(pos) {
                                    "Top Left"     -> Pair(0.03f, 0.04f)
                                    "Top Right"    -> Pair(0.75f, 0.04f)
                                    "Bottom Left"  -> Pair(0.03f, 0.88f)
                                    "Bottom Right" -> Pair(0.75f, 0.88f)
                                    else           -> Pair(0.43f, 0.46f)
                                }
                                viewModel.setWatermarkXFraction(x); viewModel.setWatermarkYFraction(y)
                                viewModel.setWatermarkLocation(pos); showWatermarkLocDialog2 = false
                            },
                            onDismiss = { showWatermarkLocDialog2 = false })
                    }
                    ClickableSettingItem(Icons.Default.Place, "Snap to Corner", "Or drag on screen") { showWatermarkLocDialog2 = true }

                    ListItem(
                        headlineContent = { Text("Watermark Shape") },
                        leadingContent = { Icon(Icons.Default.Crop, null, tint = accent.copy(alpha = 0.7f)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = {
                            SingleChoiceSegmentedButtonRow {
                                listOf("Square","Circle").forEachIndexed { idx, shape ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                        onClick = { viewModel.setWatermarkShape(shape) },
                                        selected = watermarkShape == shape
                                    ) { Text(shape) }
                                }
                            }
                        }
                    )

                    GlassSlider("Size", "${watermarkSize}dp", watermarkSize.toFloat(), 50f..300f, 49) { viewModel.setWatermarkSize(it.toInt()) }
                    GlassSlider("Opacity", "${watermarkOpacity}%", watermarkOpacity.toFloat(), 10f..100f, 17) { viewModel.setWatermarkOpacity(it.toInt()) }

                    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        if (uri != null) {
                            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                            navController.navigate("crop/${Uri.encode(uri.toString())}")
                        }
                    }
                    ClickableSettingItem(Icons.Default.Image, "Watermark Image",
                        if (watermarkImageUri != null) "Custom image set" else "Default (app icon)") { imagePickerLauncher.launch("image/*") }
                    if (watermarkImageUri != null) {
                        ListItem(
                            headlineContent = { Text("Reset to Default Icon") },
                            leadingContent = { Icon(Icons.Default.RestartAlt, null, tint = accent.copy(alpha = 0.7f)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { viewModel.setWatermarkImageUri(null) }
                        )
                    }
                }
            }

            // ── SCREENSHOTS ───────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_screenshots))
                ClickableSettingItem(Icons.Default.PhotoSizeSelectLarge, stringResource(R.string.setting_screenshot_format), screenshotFormat) { showScreenshotFormatDialog = true }
                GlassSlider(stringResource(R.string.setting_screenshot_quality), "${screenshotQuality}%", screenshotQuality.toFloat(), 10f..100f, 17) {
                    viewModel.setScreenshotQuality(it.toInt())
                }
            }

            // ── THEME ─────────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_theme))
                ClickableSettingItem(Icons.Default.SettingsSystemDaydream, stringResource(R.string.setting_theme), appTheme) { showThemeDialog = true }

                // ── Accent Color row ─────────────────────────────────────────
                val parsedAccent = remember(accentHex) {
                    runCatching { Color(android.graphics.Color.parseColor("#${accentHex.removePrefix("#").take(6)}")) }
                        .getOrDefault(accent)
                }
                val parsedAccent2 = remember(accentHex2) {
                    runCatching { Color(android.graphics.Color.parseColor("#${accentHex2.removePrefix("#").take(6)}")) }
                        .getOrDefault(Color(0xFFFF6600))
                }
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(Icons.Default.Palette, null, tint = accent.copy(alpha = 0.7f))
                    },
                    headlineContent = {
                        Text("Accent Color", style = MaterialTheme.typography.bodyMedium)
                    },
                    supportingContent = {
                        Text(
                            if (accentGradient) "#${accentHex.uppercase()}  →  #${accentHex2.uppercase()}"
                            else "#${accentHex.uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF888888)
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(parsedAccent)
                                    .border(1.dp, Color(0x44FFFFFF), CircleShape)
                            )
                            if (accentGradient) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(parsedAccent2)
                                        .border(1.dp, Color(0x44FFFFFF), CircleShape)
                                )
                            }
                            TextButton(onClick = { showAccentPickerDialog = true }) {
                                Text("Change", color = accent, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                )

                // Performance Mode toggle
                val perfSubtitle = when {
                    isLowEndDevice && performanceMode ->
                        "Auto-enabled — low-end device detected"
                    isLowEndDevice && !performanceMode ->
                        "Quality mode (may cause lag on this device)"
                    performanceMode -> "Static glass — blur disabled"
                    else            -> "Dynamic glass blur enabled"
                }
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            tint = accent.copy(alpha = 0.7f)
                        )
                    },
                    headlineContent = {
                        Text("Performance Mode", style = MaterialTheme.typography.bodyMedium)
                    },
                    supportingContent = {
                        Text(
                            perfSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLowEndDevice && !performanceMode)
                                accent.copy(alpha = 0.8f)
                            else
                                Color(0xFF888888)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = performanceMode,
                            onCheckedChange = { enabled ->
                                if (!enabled && isLowEndDevice) {
                                    // User is trying to enable blur on a low-end device → warn
                                    showLagWarningDialog = true
                                } else {
                                    viewModel.setPerformanceMode(enabled)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor       = accent,
                                checkedTrackColor       = accent.copy(alpha = 0.3f),
                                uncheckedThumbColor     = Color(0xFF555555),
                                uncheckedTrackColor     = Color(0xFF222222)
                            )
                        )
                    }
                )
            }

            // Lag-warning dialog (shown when low-end user disables Performance Mode)
            if (showLagWarningDialog) {
                AlertDialog(
                    onDismissRequest = { showLagWarningDialog = false },
                    icon = {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    title = { Text("Performance Warning") },
                    text = {
                        Text(
                            "Your device has less than 6 GB of RAM or is running Android 11 or below. " +
                            "Enabling real-time glass blur effects may cause UI lag, especially during recording.\n\n" +
                            "Do you want to enable Quality Mode anyway?"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.setPerformanceMode(false)
                            showLagWarningDialog = false
                        }) {
                            Text("Enable Anyway", color = accent, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLagWarningDialog = false }) {
                            Text("Keep Performance Mode")
                        }
                    }
                )
            }

            // ── ACCENT COLOR PICKER DIALOG ────────────────────────────────────
            if (showAccentPickerDialog) {
                val accentPresets = listOf(
                    "FF0033" to "Crimson",
                    "FF4500" to "Sunset",
                    "FFD700" to "Gold",
                    "00C853" to "Neon",
                    "00E5FF" to "Cyan",
                    "2979FF" to "Electric",
                    "D500F9" to "Plasma",
                    "FF4081" to "Rose"
                )
                AlertDialog(
                    onDismissRequest = { showAccentPickerDialog = false },
                    title = {
                        Text(
                            if (accentGradient && accentPickingSecond) "Gradient Color 2" else "Accent Color",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Preset swatches
                            Text("Presets", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                accentPresets.take(8).forEach { (hex, _) ->
                                    val c = remember(hex) {
                                        runCatching { Color(android.graphics.Color.parseColor("#$hex")) }
                                            .getOrDefault(accent)
                                    }
                                    val isSelected = if (accentGradient && accentPickingSecond) accentHex2.equals(hex, true)
                                                     else accentHex.equals(hex, true)
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .border(
                                                if (isSelected) 2.5.dp else 1.dp,
                                                if (isSelected) Color.White else Color(0x44FFFFFF),
                                                CircleShape
                                            )
                                            .clickable {
                                                if (accentGradient && accentPickingSecond) {
                                                    accentHex2Input = hex
                                                    viewModel.setAccentColor2(hex)
                                                } else {
                                                    accentHexInput = hex
                                                    viewModel.setAccentColor(hex)
                                                }
                                            }
                                    )
                                }
                            }

                            // Hex input
                            val hexTarget = if (accentGradient && accentPickingSecond) accentHex2Input else accentHexInput
                            val hexSetter: (String) -> Unit = { v ->
                                if (accentGradient && accentPickingSecond) accentHex2Input = v else accentHexInput = v
                            }
                            Text("Hex code", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
                            OutlinedTextField(
                                value = hexTarget,
                                onValueChange = {
                                    val clean = it.removePrefix("#").uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6)
                                    hexSetter(clean)
                                    if (clean.length == 6) {
                                        if (accentGradient && accentPickingSecond) viewModel.setAccentColor2(clean)
                                        else viewModel.setAccentColor(clean)
                                    }
                                },
                                prefix = { Text("#", color = accent) },
                                placeholder = { Text("FF0033") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Gradient toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Gradient accent", style = MaterialTheme.typography.bodyMedium)
                                    Text("Two-color rim glow", style = MaterialTheme.typography.bodySmall, color = Color(0xFF888888))
                                }
                                Switch(
                                    checked = accentGradient,
                                    onCheckedChange = {
                                        viewModel.setAccentUseGradient(it)
                                        if (it) accentPickingSecond = false
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor   = accent,
                                        checkedTrackColor   = accent.copy(alpha = 0.3f),
                                        uncheckedThumbColor = Color(0xFF555555),
                                        uncheckedTrackColor = Color(0xFF222222)
                                    )
                                )
                            }

                            if (accentGradient) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val c1 = remember(accentHexInput) {
                                        runCatching { Color(android.graphics.Color.parseColor("#$accentHexInput")) }
                                            .getOrDefault(accent)
                                    }
                                    val c2 = remember(accentHex2Input) {
                                        runCatching { Color(android.graphics.Color.parseColor("#$accentHex2Input")) }
                                            .getOrDefault(Color(0xFFFF6600))
                                    }
                                    TextButton(
                                        onClick = { accentPickingSecond = false },
                                        border = if (!accentPickingSecond) BorderStroke(1.dp, accent) else null
                                    ) {
                                        Box(Modifier.size(14.dp).clip(CircleShape).background(c1))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Color 1")
                                    }
                                    TextButton(
                                        onClick = { accentPickingSecond = true },
                                        border = if (accentPickingSecond) BorderStroke(1.dp, accent) else null
                                    ) {
                                        Box(Modifier.size(14.dp).clip(CircleShape).background(c2))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Color 2")
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAccentPickerDialog = false }) {
                            Text("Done", color = accent, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // ── LANGUAGE ──────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_language))
                val languageDisplay = when (appLanguage) {
                    "system" -> "System Default"
                    "en"     -> "English"
                    "ar"     -> "العربية (Arabic)"
                    "zh-CN"  -> "中文简体 (Chinese Simplified)"
                    "zh-TW"  -> "中文繁體 (Chinese Traditional)"
                    "fr"     -> "Français (French)"
                    "de"     -> "Deutsch (German)"
                    "hi"     -> "हिन्दी (Hindi)"
                    "in"     -> "Bahasa Indonesia"
                    "it"     -> "Italiano (Italian)"
                    "ja"     -> "日本語 (Japanese)"
                    "ko"     -> "한국어 (Korean)"
                    "pt"     -> "Português (Portuguese)"
                    "ru"     -> "Русский (Russian)"
                    "es"     -> "Español (Spanish)"
                    "tr"     -> "Türkçe (Turkish)"
                    "vi"     -> "Tiếng Việt (Vietnamese)"
                    else     -> "System Default"
                }
                ClickableSettingItem(Icons.Default.Language, stringResource(R.string.setting_language), languageDisplay) { showLanguageDialog = true }
            }

            // ── STORAGE ───────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_storage))
                ClickableSettingItem(Icons.Default.Folder, stringResource(R.string.setting_save_location),
                    if (saveLocationUri != null) "Custom Folder Set" else stringResource(R.string.setting_save_location_default)) { folderPickerLauncher.launch(null) }
                ClickableSettingItem(Icons.Default.TextFields, stringResource(R.string.setting_filename_pattern), filenamePattern) { showPatternDialog = true }
                SwitchSettingItem(Icons.Default.DeleteSweep, "Auto-delete Old Recordings", "Keep last 50", autoDelete) { viewModel.setAutoDelete(it) }
            }

            // ── GENERAL ───────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader(stringResource(R.string.settings_section_general))
                SwitchSettingItem(Icons.Default.Smartphone, "Keep Screen On", "During recording", keepScreenOn) { viewModel.setKeepScreenOn(it) }
            }

            // ── PRIVACY ───────────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                GlassSectionHeader("Privacy")
                SwitchSettingItem(
                    Icons.Default.PrivacyTip,
                    "Personalized Ads",
                    if (analyticsEnabled) "AdMob may use your advertising ID" else "Non-personalized ads only",
                    analyticsEnabled
                ) { viewModel.setAnalyticsEnabled(it) }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Camera Settings Dialog (logic fully preserved) ────────────────────────────

@Composable
private fun CameraSettingsDialog(
    viewModel: RecordingViewModel,
    cameraOverlay: Boolean,
    cameraLockPosition: Boolean,
    cameraFacing: String,
    cameraAspectRatio: String,
    cameraOpacity: Int,
    cameraOverlaySize: Int,
    cameraXFraction: Float,
    cameraYFraction: Float,
    isRecording: Boolean,
    canDrawOverlays: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit
) {
    val accent = LocalAccentColor.current
    var showCameraFacingDialog by remember { mutableStateOf(false) }
    var showCameraAspectDialog by remember { mutableStateOf(false) }
    var showCameraOrientationDialog by remember { mutableStateOf(false) }
    val cameraOrientation by viewModel.cameraOrientation.collectAsState()

    if (showCameraFacingDialog) {
        SingleChoiceDialog("Camera", listOf("Front","Rear"), cameraFacing,
            onOptionSelected = { viewModel.setCameraFacing(it); showCameraFacingDialog = false },
            onDismiss = { showCameraFacingDialog = false })
    }
    if (showCameraAspectDialog) {
        SingleChoiceDialog("Aspect Ratio", listOf("Circle","Square","16:9","4:3"), cameraAspectRatio,
            onOptionSelected = { viewModel.setCameraAspectRatio(it); showCameraAspectDialog = false },
            onDismiss = { showCameraAspectDialog = false })
    }
    if (showCameraOrientationDialog) {
        SingleChoiceDialog("Camera Orientation", listOf("Auto","Portrait","Landscape"), cameraOrientation,
            onOptionSelected = { viewModel.setCameraOrientation(it); showCameraOrientationDialog = false },
            onDismiss = { showCameraOrientationDialog = false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = { Text("Enable Camera") },
                    supportingContent = { Text("Show camera overlay during recording") },
                    leadingContent = { Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Switch(checked = cameraOverlay, onCheckedChange = {
                            if (it && !canDrawOverlays) {
                                Toast.makeText(context, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                            } else viewModel.setCameraOverlay(it)
                        })
                    },
                    modifier = Modifier.clickable { viewModel.setCameraOverlay(!cameraOverlay) }
                )

                if (cameraOverlay) {
                    if (canDrawOverlays && !isRecording) {
                        DisposableEffect(Unit) {
                            context.startService(Intent(context, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_SHOW_CAMERA_PREVIEW
                                putExtra(OverlayService.EXTRA_CAMERA_SIZE, cameraOverlaySize)
                                putExtra(OverlayService.EXTRA_CAMERA_X_FRACTION, cameraXFraction)
                                putExtra(OverlayService.EXTRA_CAMERA_Y_FRACTION, cameraYFraction)
                            })
                            OverlayService.onCameraPreviewPositionChanged = { x, y ->
                                viewModel.setCameraXFraction(x); viewModel.setCameraYFraction(y)
                            }
                            onDispose {
                                context.startService(Intent(context, OverlayService::class.java).apply {
                                    action = OverlayService.ACTION_HIDE_CAMERA_PREVIEW
                                })
                                OverlayService.onCameraPreviewPositionChanged = null
                            }
                        }
                        LaunchedEffect(cameraOverlaySize, cameraXFraction, cameraYFraction) {
                            OverlayService.updateCameraPreviewIfActive(cameraOverlaySize, cameraXFraction, cameraYFraction)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Drag the camera circle to reposition", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }

                    ListItem(
                        headlineContent = { Text("Lock Position") },
                        supportingContent = { Text("Prevent accidental movement") },
                        leadingContent = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = cameraLockPosition, onCheckedChange = { viewModel.setCameraLockPosition(it) })
                        },
                        modifier = Modifier.clickable { viewModel.setCameraLockPosition(!cameraLockPosition) }
                    )
                    ListItem(
                        headlineContent = { Text("Camera") },
                        supportingContent = { Text(cameraFacing) },
                        leadingContent = { Icon(Icons.Default.Cameraswitch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showCameraFacingDialog = true }
                    )
                    ListItem(
                        headlineContent = { Text("Aspect Ratio") },
                        supportingContent = { Text(cameraAspectRatio) },
                        leadingContent = { Icon(Icons.Default.AspectRatio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showCameraAspectDialog = true }
                    )
                    ListItem(
                        headlineContent = { Text("Orientation") },
                        supportingContent = { Text(cameraOrientation) },
                        leadingContent = { Icon(Icons.Default.ScreenRotation, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showCameraOrientationDialog = true }
                    )

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Size", style = MaterialTheme.typography.bodyMedium)
                            Text("${cameraOverlaySize}dp", style = MaterialTheme.typography.bodyMedium, color = accent)
                        }
                        Slider(
                            value = cameraOverlaySize.toFloat(), onValueChange = { viewModel.setCameraOverlaySize(it.toInt()) },
                            valueRange = 60f..240f, steps = 35, modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = 0.4f))
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Opacity", style = MaterialTheme.typography.bodyMedium)
                            Text("${cameraOpacity}%", style = MaterialTheme.typography.bodyMedium, color = accent)
                        }
                        Slider(
                            value = cameraOpacity.toFloat(), onValueChange = { viewModel.setCameraOpacity(it.toInt()) },
                            valueRange = 10f..100f, steps = 17, modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

// ── Custom Resolution Dialog ───────────────────────────────────────────────────

@Composable
private fun CustomResolutionDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Resolution") },
        text = {
            Column {
                Text("Enter resolution as WIDTHxHEIGHT (e.g. 1920x1080)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() || it == 'x' }.lowercase()
                        val xCount = filtered.count { it == 'x' }
                        text = if (xCount <= 1) filtered else text
                        error = null
                    },
                    label = { Text("e.g. 1920x1080") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parts = text.split("x")
                val w = parts.getOrNull(0)?.toIntOrNull()
                val h = parts.getOrNull(1)?.toIntOrNull()
                when {
                    parts.size != 2 || w == null || h == null -> error = "Format must be WxH (e.g. 1920x1080)"
                    w < 100 || h < 100 -> error = "Minimum resolution is 100x100"
                    w > 7680 || h > 7680 -> error = "Maximum resolution is 7680 on either side"
                    else -> onConfirm("${w}x${h}")
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Resolution Dialog (handles separators) ────────────────────────────────────

@Composable
private fun ResolutionDialog(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = LocalAccentColor.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolution") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    when {
                        option.startsWith("—") -> {
                            Text(
                                option.trim('—', ' '),
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                        option == "Custom…" -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onOptionSelected(option) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.padding(start = 8.dp).size(20.dp), tint = accent)
                                Text("Custom…", modifier = Modifier.padding(start = 16.dp), color = accent, fontWeight = FontWeight.Medium)
                            }
                        }
                        else -> {
                            val isSelected = selectedOption == option
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(selected = isSelected, onClick = { onOptionSelected(option) })
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSelected, onClick = { onOptionSelected(option) })
                                Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Ad Gate Dialog ─────────────────────────────────────────────────────────────

@Composable
private fun AdGateDialog(
    featureName: String,
    ad: RewardedAd?,
    isAdLoading: Boolean,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val accent = LocalAccentColor.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.PlayCircle, null,
                tint = accent,
                modifier = Modifier.size(40.dp)
            )
        },
        title = { Text("Premium Feature", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$featureName is a premium feature.")
                Text(
                    if (ad != null) "Watch a short ad to unlock it for this session."
                    else "Tap Unlock to enable it for this session.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val loadedAd = ad
                    if (loadedAd != null && activity != null) {
                        loadedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                                // Ad couldn't show — unlock anyway as fallback
                                onUnlocked()
                            }
                        }
                        loadedAd.show(activity) { onUnlocked() }
                    } else {
                        // No ad loaded yet (not on Play Store / test build) — unlock directly
                        onUnlocked()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text(
                    when {
                        isAdLoading -> "Loading…"
                        ad != null  -> "Watch Ad"
                        else        -> "Unlock"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Language Helper ────────────────────────────────────────────────────────────

private fun applyLanguage(context: android.content.Context, languageCode: String) {
    // Persist to SharedPreferences first so attachBaseContext picks it up on recreate.
    com.ibbie.catrec_screenrecorcer.utils.LocaleHelper.persist(context, languageCode)

    val localeList = if (languageCode.equals("system", ignoreCase = true)) {
        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
    } else {
        androidx.core.os.LocaleListCompat.forLanguageTags(languageCode)
    }
    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)

    // Always recreate so the wrapped context (and all stringResource calls) refresh.
    (context as? android.app.Activity)?.recreate()
}

// ── Shared Composables ─────────────────────────────────────────────────────────

@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = option == selectedOption, onClick = { onOptionSelected(option) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selectedOption, onClick = { onOptionSelected(option) })
                        Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Legacy alias kept for backward-compat — now styled as GlassSectionHeader inside GlassCards. */
@Composable
fun SettingsSectionHeader(title: String) {
    GlassSectionHeader(title)
}

@Composable
fun SwitchSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val accent = LocalAccentColor.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle, color = Color(0xFF888888)) } } else null,
        leadingContent = { Icon(icon, null, tint = accent.copy(alpha = 0.7f)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accent,
                    uncheckedThumbColor = SwitchOffGray,
                    uncheckedTrackColor = SwitchOffGray.copy(alpha = 0.5f)
                )
            )
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
fun ClickableSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val accent = LocalAccentColor.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle, color = Color(0xFF888888)) } } else null,
        leadingContent = { Icon(icon, null, tint = accent.copy(alpha = 0.7f)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
