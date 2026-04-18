package com.ibbie.catrec_screenrecorcer.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ads.AdMobAdRequestFactory
import com.ibbie.catrec_screenrecorcer.ads.resetWindowFocusAfterFullscreenOverlay
import com.ibbie.catrec_screenrecorcer.data.AdGate
import com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.service.OverlayService
import com.ibbie.catrec_screenrecorcer.ui.components.*
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel
import com.ibbie.catrec_screenrecorcer.ui.theme.SwitchOffGray

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: RecordingViewModel,
    navController: NavController,
) {
    val context = LocalContext.current
    val accent = LocalAccentColor.current

    // Video
    val fps by viewModel.fps.collectAsState()
    val bitrate by viewModel.bitrate.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val videoEncoder by viewModel.videoEncoder.collectAsState()
    val recordingOrientation by viewModel.recordingOrientation.collectAsState()
    val isGifCaptureMode by viewModel.isGifCaptureMode.collectAsState()
    val adaptivePerformanceEnabled by viewModel.adaptivePerformanceEnabled.collectAsState()
    val gifRecorderPresetId by viewModel.gifRecorderPresetId.collectAsState()

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
    val brushOverlayEnabled by viewModel.brushOverlayEnabled.collectAsState()
    val hideFloatingIconWhileRecording by viewModel.hideFloatingIconWhileRecording.collectAsState()
    val postScreenshotOptions by viewModel.postScreenshotOptions.collectAsState()
    val recordSingleAppEnabled by viewModel.recordSingleAppEnabled.collectAsState()
    val touchOverlay by viewModel.touchOverlay.collectAsState()
    val countdown by viewModel.countdown.collectAsState()
    val clipperDurationMinutes by viewModel.clipperDurationMinutes.collectAsState()
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
    val watermarkLocation by viewModel.watermarkLocation.collectAsState()

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
    val accentHex by viewModel.accentColor.collectAsState()
    val accentHex2 by viewModel.accentColor2.collectAsState()
    val accentGradient by viewModel.accentUseGradient.collectAsState()

    // Storage
    val saveLocationUri by viewModel.saveLocationUri.collectAsState()
    val filenamePattern by viewModel.filenamePattern.collectAsState()
    val autoDelete by viewModel.autoDelete.collectAsState()

    // General
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()

    // Privacy
    val analyticsEnabled by viewModel.analyticsEnabled.collectAsState()
    val personalizedAdsEnabled by viewModel.personalizedAdsEnabled.collectAsState()

    /** Remove-ads purchase — bypasses all rewarded-ad gates ([AdGate]). */
    val adsDisabled by viewModel.adsDisabled.collectAsState()

    val isRecording by RecordingState.isRecording.collectAsState()
    val scrollState = rememberScrollState()
    val canDrawOverlays = Settings.canDrawOverlays(context)

    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    val clipperDurationLabels =
        listOf(
            stringResource(R.string.clipper_duration_1m),
            stringResource(R.string.clipper_duration_2m),
            stringResource(R.string.clipper_duration_3m),
            stringResource(R.string.clipper_duration_4m),
            stringResource(R.string.clipper_duration_5m),
        )

    // Watermark live preview
    if (showWatermark && canDrawOverlays && !isRecording) {
        DisposableEffect(Unit) {
            context.startService(
                Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SHOW_WATERMARK_PREVIEW
                    putExtra(OverlayService.EXTRA_WATERMARK_SIZE, watermarkSize)
                    putExtra(OverlayService.EXTRA_WATERMARK_OPACITY, watermarkOpacity)
                    putExtra(OverlayService.EXTRA_WATERMARK_SHAPE, watermarkShape)
                    putExtra(OverlayService.EXTRA_WATERMARK_IMAGE_URI, watermarkImageUri)
                    putExtra(OverlayService.EXTRA_WATERMARK_X_FRACTION, watermarkXFraction)
                    putExtra(OverlayService.EXTRA_WATERMARK_Y_FRACTION, watermarkYFraction)
                },
            )
            OverlayService.onPreviewPositionChanged = { x, y ->
                viewModel.setWatermarkXFraction(x)
                viewModel.setWatermarkYFraction(y)
            }
            onDispose {
                context.startService(
                    Intent(context, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_HIDE_WATERMARK_PREVIEW
                    },
                )
                OverlayService.onPreviewPositionChanged = null
            }
        }
        LaunchedEffect(watermarkSize, watermarkOpacity, watermarkShape, watermarkImageUri, watermarkXFraction, watermarkYFraction) {
            OverlayService.updatePreviewIfActive(
                watermarkSize,
                watermarkOpacity,
                watermarkShape,
                watermarkImageUri,
                watermarkXFraction,
                watermarkYFraction,
            )
        }
    }

    val folderPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
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
    var showClipperDurationDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
    var showPatternDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showScreenshotFormatDialog by remember { mutableStateOf(false) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }
    var showLagWarningDialog by remember { mutableStateOf(false) }
    var showAccentPickerDialog by remember { mutableStateOf(false) }
    var accentPickingSecond by remember { mutableStateOf(false) }
    var accentHexInput by remember(accentHex) { mutableStateOf(accentHex) }
    var accentHex2Input by remember(accentHex2) { mutableStateOf(accentHex2) }

    var showAudioMenuSheet by remember { mutableStateOf(false) }
    var showVideoMenuSheet by remember { mutableStateOf(false) }
    val audioMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val videoMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Ad Gate ───────────────────────────────────────────────────────────────
    // Tracks which feature is pending unlock and what to do once unlocked.
    var adGateFeature by remember { mutableStateOf("") }
    var adGateFeatureName by remember { mutableStateOf("") }
    var adGatePending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showAdGateDialog by remember { mutableStateOf(false) }
    var adGateAd by remember { mutableStateOf<RewardedAd?>(null) }
    var adGateLoading by remember { mutableStateOf(false) }

    fun loadAdGateAd() {
        if (adGateLoading || adGateAd != null) return
        adGateLoading = true
        RewardedAd.load(
            context.applicationContext,
            // Replace with a dedicated ad unit ID for feature unlocks once live on Play Store.
            "ca-app-pub-7741372232895726/8137302121",
            AdMobAdRequestFactory.build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    adGateAd = ad
                    adGateLoading = false
                }

                override fun onAdFailedToLoad(e: LoadAdError) {
                    adGateAd = null
                    adGateLoading = false
                }
            },
        )
    }

    LaunchedEffect(Unit) { loadAdGateAd() }
    DisposableEffect(Unit) { onDispose { adGateAd = null } }

    /** Show the ad-gate dialog for [feature]. On unlock, [action] is invoked. */
    fun gateFeature(
        feature: String,
        name: String,
        action: () -> Unit,
    ) {
        if (AdGate.isUnlocked(feature, adsDisabled)) {
            action()
            return
        }
        adGateFeature = feature
        adGateFeatureName = name
        adGatePending = action
        showAdGateDialog = true
    }

    // Canonical English values (stored in DataStore / sent to encoder). Separator lines use "—" for grouping.
    val resolutionOptions =
        listOf(
            "Native",
            "— 16:9 —",
            "3840x2160 (4K)",
            "2560x1440 (2K)",
            "1920x1080 (FHD)",
            "1280x720 (HD)",
            "854x480 (SD)",
            "640x360",
            "— 20:9 (Tall Phone) —",
            "3200x1440",
            "2400x1080",
            "2520x1080",
            "— 18:9 (2:1) —",
            "2880x1440",
            "2160x1080",
            "— 21:9 (Ultrawide) —",
            "2560x1080",
            "3440x1440",
            "— 4:3 —",
            "1920x1440",
            "1440x1080",
            "1280x960",
            "960x720",
            "— 9:16 (Portrait) —",
            "1080x1920",
            "720x1280",
            "1440x2560",
            "Custom…",
        )

    val languageLabelIds =
        listOf(
            R.string.language_system,
            R.string.language_english,
            R.string.language_arabic,
            R.string.language_chinese_simplified,
            R.string.language_chinese_traditional,
            R.string.language_french,
            R.string.language_german,
            R.string.language_hindi,
            R.string.language_indonesian,
            R.string.language_italian,
            R.string.language_japanese,
            R.string.language_korean,
            R.string.language_portuguese,
            R.string.language_russian,
            R.string.language_spanish,
            R.string.language_turkish,
            R.string.language_vietnamese,
        )
    val languageCodes =
        listOf(
            "system",
            "en",
            "ar",
            "zh-CN",
            "zh-TW",
            "fr",
            "de",
            "hi",
            "in",
            "it",
            "ja",
            "ko",
            "pt",
            "ru",
            "es",
            "tr",
            "vi",
        )

    val stopBehaviorSummary =
        stopBehavior.joinToString(", ") { key ->
            when (key) {
                StopBehaviorKeys.NOTIFICATION -> context.getString(R.string.stop_behavior_notification)
                StopBehaviorKeys.SHAKE -> context.getString(R.string.stop_behavior_shake)
                StopBehaviorKeys.SCREEN_OFF -> context.getString(R.string.stop_behavior_screen_off)
                StopBehaviorKeys.PAUSE_ON_SCREEN_OFF -> context.getString(R.string.stop_behavior_pause_on_screen_off)
                else -> key
            }
        }
    val langIdx = languageCodes.indexOf(appLanguage).takeIf { it >= 0 } ?: 0
    val languageDisplay = context.getString(languageLabelIds.getOrElse(langIdx) { R.string.language_system })

    // ── Dialogs (all logic unchanged) ──────────────────────────────────────────
    if (showFpsDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_fps_title),
            options = listOf("24", "30", "45", "60", "90", "120"),
            selectedOption = "${fps.toInt()}",
            onOptionSelected = { selected ->
                showFpsDialog = false
                if ((selected == "90" || selected == "120") && !AdGate.isUnlocked(AdGate.HIGH_FPS, adsDisabled)) {
                    gateFeature(
                        AdGate.HIGH_FPS,
                        context.getString(R.string.gate_high_fps, selected),
                    ) { viewModel.setFps(selected.toFloat()) }
                } else {
                    viewModel.setFps(selected.toFloat())
                }
            },
            onDismiss = { showFpsDialog = false },
        )
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
            },
        )
    }
    if (showBitrateDialog) {
        val bitrateKeys = listOf(1, 2, 4, 6, 8, 10, 12, 16, 20, 25, 30, 40, 50, 60, 80, 100, 120, 150, 200)
        val mbpsLabel = stringResource(R.string.label_mbps)
        val bitrateLabels = bitrateKeys.map { "$it $mbpsLabel" }
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_bitrate_title),
            options = bitrateLabels,
            selectedOption = "${bitrate.toInt()} $mbpsLabel",
            onOptionSelected = { label ->
                val idx = bitrateLabels.indexOf(label)
                if (idx >= 0) viewModel.setBitrate(bitrateKeys[idx].toFloat())
                showBitrateDialog = false
            },
            onDismiss = { showBitrateDialog = false },
        )
    }
    if (showResolutionDialog) {
        ResolutionDialog(
            options = resolutionOptions,
            selectedOption = resolution,
            onOptionSelected = { sel ->
                when {
                    sel == "Custom…" -> {
                        showResolutionDialog = false
                        showCustomResolutionDialog = true
                    }
                    sel.startsWith("—") -> {}
                    else -> {
                        viewModel.setResolution(sel)
                        showResolutionDialog = false
                    }
                }
            },
            onDismiss = { showResolutionDialog = false },
        )
    }
    if (showCustomResolutionDialog) {
        CustomResolutionDialog(
            current = if (resolution.contains("x") && !resolutionOptions.contains(resolution)) resolution else "",
            onConfirm = {
                viewModel.setResolution(it)
                showCustomResolutionDialog = false
            },
            onDismiss = { showCustomResolutionDialog = false },
        )
    }
    if (showVideoEncoderDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_video_encoder),
            options = listOf("H.264", "H.265 (HEVC)"),
            selectedOption = videoEncoder,
            onOptionSelected = {
                viewModel.setVideoEncoder(it)
                showVideoEncoderDialog = false
            },
            onDismiss = { showVideoEncoderDialog = false },
        )
    }
    if (showOrientationDialog) {
        val orientKeys = listOf("Auto", "Portrait", "Landscape")
        val orientLabels =
            listOf(
                stringResource(R.string.setting_orientation_auto),
                stringResource(R.string.setting_orientation_portrait),
                stringResource(R.string.setting_orientation_landscape),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.setting_orientation),
            options = orientLabels,
            selectedOption = orientLabels[orientKeys.indexOf(recordingOrientation).coerceIn(0, orientLabels.lastIndex)],
            onOptionSelected = { label ->
                val idx = orientLabels.indexOf(label)
                if (idx >= 0) viewModel.setRecordingOrientation(orientKeys[idx])
                showOrientationDialog = false
            },
            onDismiss = { showOrientationDialog = false },
        )
    }
    if (showAudioBitrateDialog) {
        val audioBitrateKeys = listOf(32, 64, 96, 128, 192, 256, 320)
        val kbpsLabel = stringResource(R.string.label_kbps)
        val audioBitrateLabels = audioBitrateKeys.map { "$it $kbpsLabel" }
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_audio_bitrate),
            options = audioBitrateLabels,
            selectedOption = "$audioBitrate $kbpsLabel",
            onOptionSelected = { label ->
                val idx = audioBitrateLabels.indexOf(label)
                if (idx >= 0) viewModel.setAudioBitrate(audioBitrateKeys[idx])
                showAudioBitrateDialog = false
            },
            onDismiss = { showAudioBitrateDialog = false },
        )
    }
    if (showAudioSampleRateDialog) {
        val sampleRateKeys = listOf(8000, 16000, 22050, 44100, 48000)
        val hzLabel = stringResource(R.string.label_hz)
        val sampleRateLabels = sampleRateKeys.map { "$it $hzLabel" }
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_sample_rate),
            options = sampleRateLabels,
            selectedOption = "$audioSampleRate $hzLabel",
            onOptionSelected = { label ->
                val idx = sampleRateLabels.indexOf(label)
                if (idx >= 0) viewModel.setAudioSampleRate(sampleRateKeys[idx])
                showAudioSampleRateDialog = false
            },
            onDismiss = { showAudioSampleRateDialog = false },
        )
    }
    if (showAudioEncoderDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_audio_encoder),
            options = listOf("AAC-LC", "AAC-HE", "AAC-HE v2", "AAC-ELD"),
            selectedOption = audioEncoder,
            onOptionSelected = {
                viewModel.setAudioEncoder(it)
                showAudioEncoderDialog = false
            },
            onDismiss = { showAudioEncoderDialog = false },
        )
    }
    if (showCountdownDialog) {
        val countdownKeys = listOf(0, 3, 5, 10)
        val countdownLabels =
            listOf(
                stringResource(R.string.countdown_none),
                stringResource(R.string.countdown_3s),
                stringResource(R.string.countdown_5s),
                stringResource(R.string.countdown_10s),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_countdown_timer),
            options = countdownLabels,
            selectedOption = countdownLabels[countdownKeys.indexOf(countdown).coerceIn(0, countdownLabels.lastIndex)],
            onOptionSelected = { label ->
                val idx = countdownLabels.indexOf(label)
                if (idx >= 0) viewModel.setCountdown(countdownKeys[idx])
                showCountdownDialog = false
            },
            onDismiss = { showCountdownDialog = false },
        )
    }
    if (showClipperDurationDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_clipper_duration_title),
            options = clipperDurationLabels,
            selectedOption = clipperDurationLabels.getOrElse(clipperDurationMinutes - 1) { clipperDurationLabels.first() },
            onOptionSelected = { sel ->
                val idx = clipperDurationLabels.indexOf(sel)
                if (idx >= 0) viewModel.setClipperDurationMinutes(idx + 1)
                showClipperDurationDialog = false
            },
            onDismiss = { showClipperDurationDialog = false },
        )
    }
    if (showStopDialog) {
        val stopOpts =
            listOf(
                StopBehaviorKeys.NOTIFICATION to R.string.stop_behavior_notification,
                StopBehaviorKeys.SHAKE to R.string.stop_behavior_shake,
                StopBehaviorKeys.SCREEN_OFF to R.string.stop_behavior_screen_off,
                StopBehaviorKeys.PAUSE_ON_SCREEN_OFF to R.string.stop_behavior_pause_on_screen_off,
            )
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(stringResource(R.string.setting_stop_behavior)) },
            text = {
                Column {
                    stopOpts.forEach { (key, labelRes) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setStopBehavior(key) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = stopBehavior.contains(key),
                                onCheckedChange = { viewModel.setStopBehavior(key) },
                            )
                            Text(stringResource(labelRes), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStopDialog = false }) { Text(stringResource(R.string.action_done)) } },
        )
    }
    if (showPatternDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_filename_pattern),
            options = listOf("yyyyMMdd_HHmmss", "CatRec_Timestamp", "Date_Time"),
            selectedOption = filenamePattern,
            onOptionSelected = {
                viewModel.setFilenamePattern(it)
                showPatternDialog = false
            },
            onDismiss = { showPatternDialog = false },
        )
    }
    if (showThemeDialog) {
        val themeChoices =
            listOf(
                "System" to R.string.theme_system,
                "Light" to R.string.theme_light,
                "Dark" to R.string.theme_dark,
            )
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.dialog_theme_title)) },
            text = {
                Column {
                    themeChoices.forEach { (theme, labelRes) ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setAppTheme(theme)
                                        showThemeDialog = false
                                    }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = appTheme == theme, onClick = {
                                viewModel.setAppTheme(theme)
                                showThemeDialog = false
                            })
                            Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.dialog_language_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    languageLabelIds.forEachIndexed { idx, labelRes ->
                        val code = languageCodes[idx]
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = appLanguage == code, onClick = {
                                        showLanguageDialog = false
                                        viewModel.setAppLanguageWithUiApply(context, code)
                                    })
                                    .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = appLanguage == code, onClick = {
                                showLanguageDialog = false
                                viewModel.setAppLanguageWithUiApply(context, code)
                            })
                            Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showScreenshotFormatDialog) {
        val formatKeys = listOf("JPEG", "PNG", "WebP")
        val formatLabels =
            listOf(
                stringResource(R.string.format_jpeg),
                stringResource(R.string.format_png),
                stringResource(R.string.format_webp),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_screenshot_format),
            options = formatLabels,
            selectedOption = formatLabels[formatKeys.indexOf(screenshotFormat).takeIf { it >= 0 } ?: 0],
            onOptionSelected = { label ->
                val idx = formatLabels.indexOf(label)
                if (idx >= 0) viewModel.setScreenshotFormat(formatKeys[idx])
                showScreenshotFormatDialog = false
            },
            onDismiss = { showScreenshotFormatDialog = false },
        )
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
            onDismiss = { showCameraSettingsDialog = false },
        )
    }

    // ── Main layout (no nested Scaffold: outer NavGraph already has the tab header) ──
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
        )

        // ── CONTROLS ──────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_controls))
            SwitchSettingItem(
                Icons.Default.ControlCamera,
                stringResource(R.string.setting_floating_controls),
                stringResource(R.string.settings_floating_controls_desc),
                floatingControls,
            ) {
                if (it && !Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, context.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                } else {
                    viewModel.setFloatingControls(it)
                    if (it && Settings.canDrawOverlays(context)) {
                        context.startService(
                            Intent(context, com.ibbie.catrec_screenrecorcer.service.OverlayService::class.java).apply {
                                action = com.ibbie.catrec_screenrecorcer.service.OverlayService.ACTION_SHOW_IDLE_CONTROLS
                            },
                        )
                    } else if (!it) {
                        context.startService(
                            Intent(context, com.ibbie.catrec_screenrecorcer.service.OverlayService::class.java).apply {
                                action = com.ibbie.catrec_screenrecorcer.service.OverlayService.ACTION_HIDE_IDLE_CONTROLS
                            },
                        )
                    }
                }
            }
            SwitchSettingItem(
                Icons.Default.Brush,
                stringResource(R.string.setting_brush_overlay),
                stringResource(R.string.settings_brush_overlay_desc),
                brushOverlayEnabled,
            ) {
                viewModel.setBrushOverlayEnabled(it)
                if (floatingControls && canDrawOverlays) {
                    context.startService(
                        Intent(context, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_REBUILD_CONTROLS_CARD
                        },
                    )
                }
            }
            SwitchSettingItem(
                Icons.Default.VisibilityOff,
                stringResource(R.string.setting_hide_floating_while_recording),
                stringResource(R.string.settings_hide_floating_while_recording_desc),
                hideFloatingIconWhileRecording,
            ) { viewModel.setHideFloatingIconWhileRecording(it) }
            SwitchSettingItem(
                Icons.Default.Share,
                stringResource(R.string.setting_post_screenshot_options),
                stringResource(R.string.settings_post_screenshot_options_desc),
                postScreenshotOptions,
            ) { viewModel.setPostScreenshotOptions(it) }
            SwitchSettingItem(
                Icons.Default.Apps,
                stringResource(R.string.setting_record_single_app),
                stringResource(R.string.settings_record_single_app_desc),
                recordSingleAppEnabled,
            ) { viewModel.setRecordSingleAppEnabled(it) }
            SwitchSettingItem(
                Icons.Default.TouchApp,
                stringResource(R.string.settings_show_touches),
                stringResource(R.string.settings_show_touches_desc),
                touchOverlay,
            ) {
                viewModel.setTouchOverlay(it)
                if (it) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (_: Exception) {
                    }
                    Toast.makeText(context, context.getString(R.string.toast_enable_show_taps), Toast.LENGTH_LONG).show()
                }
            }
            ClickableSettingItem(
                Icons.Default.Timer,
                stringResource(R.string.setting_countdown),
                if (countdown == 0) {
                    stringResource(R.string.setting_countdown_off)
                } else {
                    stringResource(R.string.setting_countdown_seconds, countdown)
                },
            ) { showCountdownDialog = true }
            ClickableSettingItem(Icons.Default.StopCircle, stringResource(R.string.setting_stop_behavior), stopBehaviorSummary) {
                showStopDialog = true
            }
            ClickableSettingItem(
                Icons.Default.ContentCut,
                stringResource(R.string.setting_clipper_duration),
                clipperDurationLabels.getOrElse(clipperDurationMinutes - 1) { clipperDurationLabels.first() },
            ) { showClipperDurationDialog = true }
            if (!batteryOptimizationIgnored) {
                ClickableSettingItem(
                    Icons.Filled.PowerSettingsNew,
                    stringResource(R.string.setting_allow_background_title),
                    stringResource(R.string.setting_allow_background_desc),
                ) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.toast_battery_settings_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                }
            }
        }

        // ── Recording quality (open audio / video+GIF menus) ───────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_recording_quality))
            ListItem(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAudioMenuSheet = true },
                headlineContent = { Text(stringResource(R.string.settings_open_audio_menu)) },
                supportingContent = { Text(stringResource(R.string.settings_open_audio_menu_sub)) },
                leadingContent = { Icon(Icons.Default.GraphicEq, null, tint = accent.copy(alpha = 0.7f)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showVideoMenuSheet = true },
                headlineContent = { Text(stringResource(R.string.settings_open_video_menu)) },
                supportingContent = { Text(stringResource(R.string.settings_open_video_menu_sub)) },
                leadingContent = { Icon(Icons.Default.VideoSettings, null, tint = accent.copy(alpha = 0.7f)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        // ── OVERLAY ───────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_overlay))
            ClickableSettingItem(
                Icons.Default.CameraAlt,
                stringResource(R.string.setting_camera_settings),
                if (cameraOverlay) {
                    stringResource(R.string.camera_status_enabled, cameraAspectRatio, cameraFacing)
                } else {
                    stringResource(R.string.state_disabled)
                },
            ) {
                if (!AdGate.isUnlocked(AdGate.CAMERA_SETTINGS, adsDisabled)) {
                    gateFeature(AdGate.CAMERA_SETTINGS, context.getString(R.string.gate_feature_camera_overlay)) {
                        showCameraSettingsDialog = true
                    }
                } else {
                    showCameraSettingsDialog = true
                }
            }

            SwitchSettingItem(
                Icons.AutoMirrored.Filled.BrandingWatermark,
                stringResource(R.string.setting_watermark),
                null,
                showWatermark,
            ) {
                if (it && !Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, context.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                } else {
                    viewModel.setShowWatermark(it)
                }
            }

            if (showWatermark) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    color = Color(0x33FF0033),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Visibility, null, tint = accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (canDrawOverlays && !isRecording) {
                                stringResource(R.string.watermark_preview_active)
                            } else if (isRecording) {
                                stringResource(R.string.watermark_preview_recording)
                            } else {
                                stringResource(R.string.watermark_preview_need_overlay)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFCCAAAA),
                        )
                    }
                }

                var showWatermarkLocDialog2 by remember { mutableStateOf(false) }
                if (showWatermarkLocDialog2) {
                    val snapKeys = listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right", "Center")
                    val snapLabels =
                        listOf(
                            stringResource(R.string.watermark_position_top_left),
                            stringResource(R.string.watermark_position_top_right),
                            stringResource(R.string.watermark_position_bottom_left),
                            stringResource(R.string.watermark_position_bottom_right),
                            stringResource(R.string.watermark_position_center),
                        )
                    val selectedSnapLabel =
                        if (watermarkLocation in snapKeys) {
                            snapLabels[snapKeys.indexOf(watermarkLocation)]
                        } else {
                            stringResource(R.string.watermark_snap_custom)
                        }
                    SingleChoiceDialog(
                        title = stringResource(R.string.watermark_snap_dialog_title),
                        options = snapLabels,
                        selectedOption = selectedSnapLabel,
                        onOptionSelected = { label ->
                            val idx = snapLabels.indexOf(label)
                            if (idx < 0) return@SingleChoiceDialog
                            val pos = snapKeys[idx]
                            // Fractions are offset / (screen − watermark), so 0/1 are true corners for any size & DPI.
                            val (x, y) =
                                when (pos) {
                                    "Top Left" -> Pair(0f, 0f)
                                    "Top Right" -> Pair(1f, 0f)
                                    "Bottom Left" -> Pair(0f, 1f)
                                    "Bottom Right" -> Pair(1f, 1f)
                                    else -> Pair(0.5f, 0.5f)
                                }
                            viewModel.setWatermarkXFraction(x)
                            viewModel.setWatermarkYFraction(y)
                            viewModel.setWatermarkLocation(pos)
                            showWatermarkLocDialog2 = false
                        },
                        onDismiss = { showWatermarkLocDialog2 = false },
                    )
                }
                ClickableSettingItem(
                    Icons.Default.Place,
                    stringResource(R.string.watermark_snap_corner_title),
                    stringResource(R.string.watermark_snap_corner_desc),
                ) {
                    showWatermarkLocDialog2 = true
                }

                SettingsListRow(
                    leadingContent = { Icon(Icons.Default.Crop, null, tint = accent.copy(alpha = 0.7f)) },
                    headlineContent = {
                        Text(
                            stringResource(R.string.watermark_shape),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            SingleChoiceSegmentedButtonRow {
                                val shapeKeys = listOf("Square", "Circle")
                                val shapeLabels =
                                    listOf(
                                        stringResource(R.string.shape_square),
                                        stringResource(R.string.shape_circle),
                                    )
                                shapeKeys.forEachIndexed { idx, shape ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(idx, shapeKeys.size),
                                        onClick = { viewModel.setWatermarkShape(shape) },
                                        selected = watermarkShape == shape,
                                    ) { Text(shapeLabels[idx]) }
                                }
                            }
                        }
                    },
                )

                GlassSlider(
                    stringResource(R.string.setting_watermark_size),
                    stringResource(R.string.label_dp, watermarkSize),
                    watermarkSize.toFloat(),
                    50f..300f,
                    49,
                ) {
                    viewModel.setWatermarkSize(it.toInt())
                }
                GlassSlider(
                    stringResource(R.string.setting_watermark_opacity),
                    stringResource(R.string.label_percent, watermarkOpacity),
                    watermarkOpacity.toFloat(),
                    10f..100f,
                    17,
                ) {
                    viewModel.setWatermarkOpacity(it.toInt())
                }

                val imagePickerLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        if (uri != null) {
                            try {
                                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (
                                _: Exception,
                            ) {
                            }
                            navController.navigate("crop/${Uri.encode(uri.toString())}")
                        }
                    }
                ClickableSettingItem(
                    Icons.Default.Image,
                    stringResource(R.string.watermark_image),
                    if (watermarkImageUri != null) {
                        stringResource(
                            R.string.watermark_image_custom,
                        )
                    } else {
                        stringResource(R.string.watermark_image_default)
                    },
                ) { imagePickerLauncher.launch("image/*") }
                if (watermarkImageUri != null) {
                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setWatermarkImageUri(null) },
                        headlineContent = { Text(stringResource(R.string.watermark_reset_icon)) },
                        leadingContent = { Icon(Icons.Default.RestartAlt, null, tint = accent.copy(alpha = 0.7f)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }

        // ── SCREENSHOTS ───────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_screenshots))
            val screenshotFormatDisplay =
                when (screenshotFormat) {
                    "JPEG" -> stringResource(R.string.format_jpeg)
                    "PNG" -> stringResource(R.string.format_png)
                    "WebP" -> stringResource(R.string.format_webp)
                    else -> screenshotFormat
                }
            ClickableSettingItem(
                Icons.Default.PhotoSizeSelectLarge,
                stringResource(R.string.setting_screenshot_format),
                screenshotFormatDisplay,
            ) {
                showScreenshotFormatDialog = true
            }
            GlassSlider(
                stringResource(R.string.setting_screenshot_quality),
                "$screenshotQuality%",
                screenshotQuality.toFloat(),
                10f..100f,
                17,
            ) {
                viewModel.setScreenshotQuality(it.toInt())
            }
        }

        // ── THEME ─────────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_theme))
            val themeDisplay =
                when (appTheme) {
                    "Light" -> stringResource(R.string.theme_light)
                    "Dark" -> stringResource(R.string.theme_dark)
                    else -> stringResource(R.string.theme_system)
                }
            ClickableSettingItem(
                Icons.Default.SettingsSystemDaydream,
                stringResource(R.string.setting_theme),
                themeDisplay,
            ) { showThemeDialog = true }

            // ── Accent Color row ─────────────────────────────────────────
            val parsedAccent =
                remember(accentHex) {
                    runCatching { Color(android.graphics.Color.parseColor("#${accentHex.removePrefix("#").take(6)}")) }
                        .getOrDefault(accent)
                }
            val parsedAccent2 =
                remember(accentHex2) {
                    runCatching { Color(android.graphics.Color.parseColor("#${accentHex2.removePrefix("#").take(6)}")) }
                        .getOrDefault(Color(0xFFFF6600))
                }
            SettingsListRow(
                leadingContent = {
                    Icon(Icons.Default.Palette, null, tint = accent.copy(alpha = 0.7f))
                },
                headlineContent = {
                    Text(stringResource(R.string.accent_color_title), style = MaterialTheme.typography.bodyMedium)
                },
                supportingContent = {
                    Text(
                        if (accentGradient) {
                            "#${accentHex.uppercase()}  →  #${accentHex2.uppercase()}"
                        } else {
                            "#${accentHex.uppercase()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(parsedAccent)
                                    .border(1.dp, Color(0x44FFFFFF), CircleShape),
                        )
                        if (accentGradient) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(parsedAccent2)
                                        .border(1.dp, Color(0x44FFFFFF), CircleShape),
                            )
                        }
                        TextButton(onClick = { showAccentPickerDialog = true }) {
                            Text(stringResource(R.string.action_change), color = accent, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
            )

            // Performance Mode toggle
            val perfSubtitle =
                when {
                    isLowEndDevice && performanceMode ->
                        stringResource(R.string.perf_auto_low_end)
                    isLowEndDevice && !performanceMode ->
                        stringResource(R.string.perf_quality_may_lag)
                    performanceMode -> stringResource(R.string.perf_static_glass)
                    else -> stringResource(R.string.perf_dynamic_glass)
                }
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.7f),
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.setting_performance_mode), style = MaterialTheme.typography.bodyMedium)
                },
                supportingContent = {
                    Text(
                        perfSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (isLowEndDevice && !performanceMode) {
                                accent.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
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
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = accent,
                                checkedTrackColor = accent.copy(alpha = 0.3f),
                                uncheckedThumbColor = Color(0xFF555555),
                                uncheckedTrackColor = Color(0xFF222222),
                            ),
                    )
                },
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
                        modifier = Modifier.size(28.dp),
                    )
                },
                title = { Text(stringResource(R.string.performance_warning_title)) },
                text = {
                    Text(stringResource(R.string.performance_warning_body))
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setPerformanceMode(false)
                        showLagWarningDialog = false
                    }) {
                        Text(stringResource(R.string.action_enable_anyway), color = accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLagWarningDialog = false }) {
                        Text(stringResource(R.string.action_keep_performance_mode))
                    }
                },
            )
        }

        // ── ACCENT COLOR PICKER DIALOG ────────────────────────────────────
        if (showAccentPickerDialog) {
            val accentPresets =
                listOf(
                    "FF0033" to "Crimson",
                    "FF4500" to "Sunset",
                    "FFD700" to "Gold",
                    "00C853" to "Neon",
                    "00E5FF" to "Cyan",
                    "2979FF" to "Electric",
                    "D500F9" to "Plasma",
                    "FF4081" to "Rose",
                )
            AlertDialog(
                onDismissRequest = { showAccentPickerDialog = false },
                title = {
                    Text(
                        text =
                            if (accentGradient && accentPickingSecond) {
                                stringResource(R.string.accent_gradient_color_2)
                            } else {
                                stringResource(R.string.accent_color_title)
                            },
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Preset swatches
                        Text(
                            stringResource(R.string.accent_presets),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            accentPresets.take(8).forEach { (hex, _) ->
                                val c =
                                    remember(hex) {
                                        runCatching { Color(android.graphics.Color.parseColor("#$hex")) }
                                            .getOrDefault(accent)
                                    }
                                val isSelected =
                                    if (accentGradient && accentPickingSecond) {
                                        accentHex2.equals(hex, true)
                                    } else {
                                        accentHex.equals(hex, true)
                                    }
                                Box(
                                    modifier =
                                        Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .border(
                                                if (isSelected) 2.5.dp else 1.dp,
                                                if (isSelected) Color.White else Color(0x44FFFFFF),
                                                CircleShape,
                                            ).clickable {
                                                if (accentGradient && accentPickingSecond) {
                                                    accentHex2Input = hex
                                                    viewModel.setAccentColor2(hex)
                                                } else {
                                                    accentHexInput = hex
                                                    viewModel.setAccentColor(hex)
                                                }
                                            },
                                )
                            }
                        }

                        // Hex input
                        val hexTarget = if (accentGradient && accentPickingSecond) accentHex2Input else accentHexInput
                        val hexSetter: (String) -> Unit = { v ->
                            if (accentGradient && accentPickingSecond) accentHex2Input = v else accentHexInput = v
                        }
                        Text(
                            stringResource(R.string.label_hex_code),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = hexTarget,
                            onValueChange = {
                                val clean =
                                    it
                                        .removePrefix("#")
                                        .uppercase()
                                        .filter { c -> c in "0123456789ABCDEF" }
                                        .take(6)
                                hexSetter(clean)
                                if (clean.length == 6) {
                                    if (accentGradient && accentPickingSecond) {
                                        viewModel.setAccentColor2(clean)
                                    } else {
                                        viewModel.setAccentColor(clean)
                                    }
                                }
                            },
                            prefix = { Text("#", color = accent) },
                            placeholder = { Text(stringResource(R.string.accent_hex_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Gradient toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(stringResource(R.string.accent_gradient_toggle), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.accent_gradient_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = accentGradient,
                                onCheckedChange = {
                                    viewModel.setAccentUseGradient(it)
                                    if (it) accentPickingSecond = false
                                },
                                colors =
                                    SwitchDefaults.colors(
                                        checkedThumbColor = accent,
                                        checkedTrackColor = accent.copy(alpha = 0.3f),
                                        uncheckedThumbColor = Color(0xFF555555),
                                        uncheckedTrackColor = Color(0xFF222222),
                                    ),
                            )
                        }

                        if (accentGradient) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val c1 =
                                    remember(accentHexInput) {
                                        runCatching { Color(android.graphics.Color.parseColor("#$accentHexInput")) }
                                            .getOrDefault(accent)
                                    }
                                val c2 =
                                    remember(accentHex2Input) {
                                        runCatching { Color(android.graphics.Color.parseColor("#$accentHex2Input")) }
                                            .getOrDefault(Color(0xFFFF6600))
                                    }
                                TextButton(
                                    onClick = { accentPickingSecond = false },
                                    border = if (!accentPickingSecond) BorderStroke(1.dp, accent) else null,
                                ) {
                                    Box(Modifier.size(14.dp).clip(CircleShape).background(c1))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.accent_color_1))
                                }
                                TextButton(
                                    onClick = { accentPickingSecond = true },
                                    border = if (accentPickingSecond) BorderStroke(1.dp, accent) else null,
                                ) {
                                    Box(Modifier.size(14.dp).clip(CircleShape).background(c2))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.accent_color_2))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAccentPickerDialog = false }) {
                        Text(stringResource(R.string.action_done), color = accent, fontWeight = FontWeight.Bold)
                    }
                },
            )
        }

        // ── LANGUAGE ──────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_language))
            ClickableSettingItem(
                Icons.Default.Language,
                stringResource(R.string.setting_language),
                languageDisplay,
            ) { showLanguageDialog = true }
        }

        // ── STORAGE ───────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_storage))
            ClickableSettingItem(
                Icons.Default.Folder,
                stringResource(R.string.setting_save_location),
                if (saveLocationUri != null) {
                    stringResource(
                        R.string.setting_save_location_custom,
                    )
                } else {
                    stringResource(R.string.setting_save_location_default)
                },
            ) { folderPickerLauncher.launch(null) }
            ClickableSettingItem(Icons.Default.TextFields, stringResource(R.string.setting_filename_pattern), filenamePattern) {
                showPatternDialog = true
            }
            SwitchSettingItem(
                Icons.Default.DeleteSweep,
                stringResource(R.string.setting_auto_delete_title),
                stringResource(R.string.setting_auto_delete_desc),
                autoDelete,
            ) {
                viewModel.setAutoDelete(it)
            }
        }

        // ── GENERAL ───────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_general))
            SwitchSettingItem(
                Icons.Default.Smartphone,
                stringResource(R.string.setting_keep_screen_on_title),
                stringResource(R.string.setting_keep_screen_on_desc),
                keepScreenOn,
            ) {
                viewModel.setKeepScreenOn(it)
            }
        }

        // ── PRIVACY ───────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            GlassSectionHeader(stringResource(R.string.settings_section_privacy))
            SwitchSettingItem(
                Icons.Default.Analytics,
                stringResource(R.string.setting_usage_analytics),
                stringResource(R.string.setting_usage_analytics_desc),
                analyticsEnabled,
            ) { viewModel.setAnalyticsEnabled(it) }
            SwitchSettingItem(
                Icons.Default.PrivacyTip,
                stringResource(R.string.setting_personalized_ads),
                if (personalizedAdsEnabled) {
                    stringResource(R.string.setting_personalized_ads_on_desc)
                } else {
                    stringResource(R.string.setting_personalized_ads_off_desc)
                },
                personalizedAdsEnabled,
            ) { viewModel.setPersonalizedAdsEnabled(it) }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showAudioMenuSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAudioMenuSheet = false },
            sheetState = audioMenuSheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    stringResource(R.string.settings_section_audio),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                SwitchSettingItem(
                    Icons.Default.Mic,
                    stringResource(R.string.setting_microphone),
                    null,
                    recordAudio,
                ) { viewModel.setRecordAudio(it) }
                SwitchSettingItem(
                    Icons.Default.MusicNote,
                    stringResource(R.string.setting_internal_audio),
                    stringResource(R.string.setting_internal_audio_note),
                    internalAudio,
                ) {
                    viewModel.setInternalAudio(it)
                }
                ClickableSettingItem(
                    Icons.Default.GraphicEq,
                    stringResource(R.string.setting_audio_bitrate),
                    "$audioBitrate ${stringResource(R.string.label_kbps)}",
                ) {
                    showAudioBitrateDialog = true
                }
                ClickableSettingItem(
                    Icons.Default.Audiotrack,
                    stringResource(R.string.setting_audio_sample_rate),
                    "$audioSampleRate ${stringResource(R.string.label_hz)}",
                ) {
                    showAudioSampleRateDialog = true
                }
                SettingsListRow(
                    leadingContent = { Icon(Icons.Default.SettingsVoice, contentDescription = null, tint = accent.copy(alpha = 0.7f)) },
                    headlineContent = {
                        Text(
                            stringResource(R.string.setting_audio_channels),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            SingleChoiceSegmentedButtonRow {
                                listOf(
                                    stringResource(R.string.setting_audio_channels_mono) to "Mono",
                                    stringResource(R.string.setting_audio_channels_stereo) to "Stereo",
                                ).forEachIndexed { idx, pair ->
                                    val label = pair.first
                                    val value = pair.second
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                        onClick = { viewModel.setAudioChannels(value) },
                                        selected = audioChannels == value,
                                    ) { Text(label) }
                                }
                            }
                        }
                    },
                )
                ClickableSettingItem(
                    Icons.Default.Tune,
                    stringResource(R.string.setting_audio_encoder),
                    audioEncoder,
                ) { showAudioEncoderDialog = true }
                SwitchSettingItem(
                    Icons.AutoMirrored.Filled.CallSplit,
                    stringResource(R.string.setting_separate_mic),
                    stringResource(R.string.setting_separate_mic_desc),
                    separateMicRecording,
                ) { newValue ->
                    if (newValue && !AdGate.isUnlocked(AdGate.SEPARATE_MIC, adsDisabled)) {
                        gateFeature(AdGate.SEPARATE_MIC, context.getString(R.string.gate_feature_separate_mic)) {
                            viewModel.setSeparateMicRecording(true)
                        }
                    } else {
                        viewModel.setSeparateMicRecording(newValue)
                    }
                }
            }
        }
    }

    if (showVideoMenuSheet) {
        val videoLocked = isGifCaptureMode
        ModalBottomSheet(
            onDismissRequest = { showVideoMenuSheet = false },
            sheetState = videoMenuSheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    stringResource(R.string.settings_section_gif_recorder),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_gif_recorder_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.setting_gif_preset),
                    style = MaterialTheme.typography.titleSmall,
                )
                GifRecordingPresets.all.forEach { preset ->
                    val selected = gifRecorderPresetId == preset.id
                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setGifRecorderPresetId(preset.id) },
                        headlineContent = { Text(stringResource(preset.titleRes)) },
                        trailingContent = {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.setGifRecorderPresetId(preset.id) },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_section_video),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                if (videoLocked) {
                    Text(
                        text = stringResource(R.string.video_locked_by_gif_preset),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                val resolutionDisplay =
                    when (resolution) {
                        "Native" -> stringResource(R.string.setting_resolution_native)
                        "Custom…" -> stringResource(R.string.setting_resolution_custom)
                        else -> resolution
                    }
                val orientationDisplay =
                    when (recordingOrientation) {
                        "Auto" -> stringResource(R.string.setting_orientation_auto)
                        "Portrait" -> stringResource(R.string.setting_orientation_portrait)
                        "Landscape" -> stringResource(R.string.setting_orientation_landscape)
                        else -> recordingOrientation
                    }
                ClickableSettingItem(
                    Icons.Default.Speed,
                    stringResource(R.string.setting_fps),
                    "${fps.toInt()} ${stringResource(R.string.label_fps)}",
                    enabled = !videoLocked,
                ) { if (!videoLocked) showFpsDialog = true }
                ClickableSettingItem(
                    Icons.Default.DataUsage,
                    stringResource(R.string.setting_bitrate),
                    "${bitrate.toInt()} ${stringResource(R.string.label_mbps)}",
                    enabled = !videoLocked,
                ) { if (!videoLocked) showBitrateDialog = true }
                SwitchSettingItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.setting_adaptive_recording_performance),
                    subtitle = stringResource(R.string.setting_adaptive_recording_performance_summary),
                    checked = adaptivePerformanceEnabled,
                    enabled = !videoLocked,
                    onCheckedChange = { viewModel.setAdaptivePerformanceEnabled(it) },
                )
                ClickableSettingItem(
                    Icons.Default.AspectRatio,
                    stringResource(R.string.setting_resolution),
                    resolutionDisplay,
                    enabled = !videoLocked,
                ) { if (!videoLocked) showResolutionDialog = true }
                ClickableSettingItem(
                    Icons.Default.VideoSettings,
                    stringResource(R.string.setting_video_encoder),
                    videoEncoder,
                    enabled = !videoLocked,
                ) { if (!videoLocked) showVideoEncoderDialog = true }
                ClickableSettingItem(
                    Icons.Default.ScreenRotation,
                    stringResource(R.string.setting_orientation),
                    orientationDisplay,
                    enabled = !videoLocked,
                ) { if (!videoLocked) showOrientationDialog = true }
            }
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
    onDismiss: () -> Unit,
) {
    val accent = LocalAccentColor.current
    var showCameraFacingDialog by remember { mutableStateOf(false) }
    var showCameraAspectDialog by remember { mutableStateOf(false) }
    var showCameraOrientationDialog by remember { mutableStateOf(false) }
    val cameraOrientation by viewModel.cameraOrientation.collectAsState()

    if (showCameraFacingDialog) {
        val facingKeys = listOf("Front", "Rear")
        val facingLabels =
            listOf(
                stringResource(R.string.camera_facing_front),
                stringResource(R.string.camera_facing_rear),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.camera_facing),
            options = facingLabels,
            selectedOption = facingLabels[facingKeys.indexOf(cameraFacing).coerceIn(0, facingLabels.lastIndex)],
            onOptionSelected = { label ->
                val idx = facingLabels.indexOf(label)
                if (idx >= 0) viewModel.setCameraFacing(facingKeys[idx])
                showCameraFacingDialog = false
            },
            onDismiss = { showCameraFacingDialog = false },
        )
    }
    if (showCameraAspectDialog) {
        val aspectKeys = listOf("Circle", "Square", "16:9", "4:3")
        val aspectLabels =
            listOf(
                stringResource(R.string.shape_circle),
                stringResource(R.string.shape_square),
                stringResource(R.string.ratio_16_9),
                stringResource(R.string.ratio_4_3),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.camera_aspect_ratio),
            options = aspectLabels,
            selectedOption = aspectLabels[aspectKeys.indexOf(cameraAspectRatio).coerceIn(0, aspectLabels.lastIndex)],
            onOptionSelected = { label ->
                val idx = aspectLabels.indexOf(label)
                if (idx >= 0) viewModel.setCameraAspectRatio(aspectKeys[idx])
                showCameraAspectDialog = false
            },
            onDismiss = { showCameraAspectDialog = false },
        )
    }
    if (showCameraOrientationDialog) {
        val orientKeys = listOf("Auto", "Portrait", "Landscape")
        val orientLabels =
            listOf(
                stringResource(R.string.setting_orientation_auto),
                stringResource(R.string.setting_orientation_portrait),
                stringResource(R.string.setting_orientation_landscape),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_camera_orientation),
            options = orientLabels,
            selectedOption = orientLabels[orientKeys.indexOf(cameraOrientation).coerceIn(0, orientLabels.lastIndex)],
            onOptionSelected = { label ->
                val idx = orientLabels.indexOf(label)
                if (idx >= 0) viewModel.setCameraOrientation(orientKeys[idx])
                showCameraOrientationDialog = false
            },
            onDismiss = { showCameraOrientationDialog = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.camera_settings_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ListItem(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setCameraOverlay(!cameraOverlay) },
                    headlineContent = { Text(stringResource(R.string.camera_enable)) },
                    supportingContent = { Text(stringResource(R.string.camera_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Switch(checked = cameraOverlay, onCheckedChange = {
                            if (it && !canDrawOverlays) {
                                Toast.makeText(context, context.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                                )
                            } else {
                                viewModel.setCameraOverlay(it)
                            }
                        })
                    },
                )

                if (cameraOverlay) {
                    if (canDrawOverlays && !isRecording) {
                        DisposableEffect(Unit) {
                            context.startService(
                                Intent(context, OverlayService::class.java).apply {
                                    action = OverlayService.ACTION_SHOW_CAMERA_PREVIEW
                                    putExtra(OverlayService.EXTRA_CAMERA_SIZE, cameraOverlaySize)
                                    putExtra(OverlayService.EXTRA_CAMERA_X_FRACTION, cameraXFraction)
                                    putExtra(OverlayService.EXTRA_CAMERA_Y_FRACTION, cameraYFraction)
                                },
                            )
                            OverlayService.onCameraPreviewPositionChanged = { x, y ->
                                viewModel.setCameraXFraction(x)
                                viewModel.setCameraYFraction(y)
                            }
                            onDispose {
                                context.startService(
                                    Intent(context, OverlayService::class.java).apply {
                                        action = OverlayService.ACTION_HIDE_CAMERA_PREVIEW
                                    },
                                )
                                OverlayService.onCameraPreviewPositionChanged = null
                            }
                        }
                        LaunchedEffect(cameraOverlaySize, cameraXFraction, cameraYFraction) {
                            OverlayService.updateCameraPreviewIfActive(cameraOverlaySize, cameraXFraction, cameraYFraction)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Visibility,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.camera_drag_hint),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setCameraLockPosition(!cameraLockPosition) },
                        headlineContent = { Text(stringResource(R.string.camera_lock_position)) },
                        supportingContent = { Text(stringResource(R.string.camera_lock_desc_short)) },
                        leadingContent = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = cameraLockPosition, onCheckedChange = { viewModel.setCameraLockPosition(it) })
                        },
                    )
                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { showCameraFacingDialog = true },
                        headlineContent = { Text(stringResource(R.string.camera_facing)) },
                        supportingContent = {
                            Text(
                                when (cameraFacing) {
                                    "Front" -> stringResource(R.string.camera_facing_front)
                                    "Rear" -> stringResource(R.string.camera_facing_rear)
                                    else -> cameraFacing
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Cameraswitch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { showCameraAspectDialog = true },
                        headlineContent = { Text(stringResource(R.string.camera_aspect_ratio)) },
                        supportingContent = {
                            Text(
                                when (cameraAspectRatio) {
                                    "Circle" -> stringResource(R.string.shape_circle)
                                    "Square" -> stringResource(R.string.shape_square)
                                    "16:9" -> stringResource(R.string.ratio_16_9)
                                    "4:3" -> stringResource(R.string.ratio_4_3)
                                    else -> cameraAspectRatio
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Default.AspectRatio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { showCameraOrientationDialog = true },
                        headlineContent = { Text(stringResource(R.string.camera_orientation)) },
                        supportingContent = {
                            Text(
                                when (cameraOrientation) {
                                    "Auto" -> stringResource(R.string.setting_orientation_auto)
                                    "Portrait" -> stringResource(R.string.setting_orientation_portrait)
                                    "Landscape" -> stringResource(R.string.setting_orientation_landscape)
                                    else -> cameraOrientation
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Default.ScreenRotation, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.camera_size), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.label_dp, cameraOverlaySize),
                                style = MaterialTheme.typography.bodyMedium,
                                color = accent,
                            )
                        }
                        Slider(
                            value = cameraOverlaySize.toFloat(),
                            onValueChange = { viewModel.setCameraOverlaySize(it.toInt()) },
                            valueRange = 60f..240f,
                            steps = 35,
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = accent,
                                    activeTrackColor = accent,
                                    inactiveTrackColor = accent.copy(alpha = 0.4f),
                                ),
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.camera_opacity), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.label_percent, cameraOpacity),
                                style = MaterialTheme.typography.bodyMedium,
                                color = accent,
                            )
                        }
                        Slider(
                            value = cameraOpacity.toFloat(),
                            onValueChange = { viewModel.setCameraOpacity(it.toInt()) },
                            valueRange = 10f..100f,
                            steps = 17,
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = accent,
                                    activeTrackColor = accent,
                                    inactiveTrackColor = accent.copy(alpha = 0.4f),
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

/**
 * Row-based settings line (avoids Material3 [ListItem] intrinsic measure bug where
 * maxWidth can become negative when parent width is 0 or trailing is very wide).
 */
@Composable
private fun SettingsListRow(
    modifier: Modifier = Modifier,
    leadingContent: @Composable () -> Unit,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 16.dp)) { leadingContent() }
        Column(Modifier.weight(1f)) {
            headlineContent()
            supportingContent?.invoke()
        }
        trailingContent?.invoke()
    }
}

// ── Custom Resolution Dialog ───────────────────────────────────────────────────

@Composable
private fun CustomResolutionDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_custom_resolution_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.dialog_custom_resolution_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() || it == 'x' }.lowercase()
                        val xCount = filtered.count { it == 'x' }
                        text = if (xCount <= 1) filtered else text
                        error = null
                    },
                    label = { Text(stringResource(R.string.setting_resolution_custom_hint)) },
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parts = text.split("x")
                val w = parts.getOrNull(0)?.toIntOrNull()
                val h = parts.getOrNull(1)?.toIntOrNull()
                when {
                    parts.size != 2 || w == null || h == null -> error = context.getString(R.string.error_resolution_format)
                    w < 100 || h < 100 -> error = context.getString(R.string.error_resolution_min)
                    w > 7680 || h > 7680 -> error = context.getString(R.string.error_resolution_max)
                    else -> onConfirm("${w}x$h")
                }
            }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ── Resolution Dialog (handles separators) ────────────────────────────────────

@Composable
private fun ResolutionDialog(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalAccentColor.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_resolution_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    when {
                        option.startsWith("—") -> {
                            val label =
                                when (option) {
                                    "— 16:9 —" -> stringResource(R.string.resolution_group_16_9)
                                    "— 20:9 (Tall Phone) —" -> stringResource(R.string.resolution_group_20_9)
                                    "— 18:9 (2:1) —" -> stringResource(R.string.resolution_group_18_9)
                                    "— 21:9 (Ultrawide) —" -> stringResource(R.string.resolution_group_21_9)
                                    "— 4:3 —" -> stringResource(R.string.resolution_group_4_3)
                                    "— 9:16 (Portrait) —" -> stringResource(R.string.resolution_group_9_16)
                                    else -> option.trim('—', ' ')
                                }
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        option == "Custom…" -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onOptionSelected(option) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.padding(start = 8.dp).size(20.dp), tint = accent)
                                Text(
                                    stringResource(R.string.setting_resolution_custom),
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = accent,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        else -> {
                            val isSelected = selectedOption == option
                            val displayText = if (option == "Native") stringResource(R.string.setting_resolution_native) else option
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(selected = isSelected, onClick = { onOptionSelected(option) })
                                        .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = isSelected, onClick = { onOptionSelected(option) })
                                Text(displayText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ── Ad Gate Dialog ─────────────────────────────────────────────────────────────

@Composable
private fun AdGateDialog(
    featureName: String,
    ad: RewardedAd?,
    isAdLoading: Boolean,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val accent = LocalAccentColor.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.PlayCircle,
                null,
                tint = accent,
                modifier = Modifier.size(40.dp),
            )
        },
        title = { Text(stringResource(R.string.premium_feature_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.premium_feature_body, featureName))
                Text(
                    if (ad != null) {
                        stringResource(R.string.premium_feature_watch_ad)
                    } else {
                        stringResource(R.string.premium_feature_tap_unlock)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val loadedAd = ad
                    if (loadedAd != null && activity != null) {
                        loadedAd.fullScreenContentCallback =
                            object : FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() {
                                    activity.resetWindowFocusAfterFullscreenOverlay()
                                }

                                override fun onAdFailedToShowFullScreenContent(e: AdError) {
                                    activity.resetWindowFocusAfterFullscreenOverlay()
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
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) {
                Text(
                    when {
                        isAdLoading -> stringResource(R.string.action_loading)
                        ad != null -> stringResource(R.string.action_watch_ad)
                        else -> stringResource(R.string.action_unlock)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ── Shared Composables ─────────────────────────────────────────────────────────

@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = option == selectedOption, onClick = { onOptionSelected(option) })
                                .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selectedOption, onClick = { onOptionSelected(option) })
                        Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val accent = LocalAccentColor.current
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    if (enabled) onCheckedChange(!checked)
                },
        headlineContent = { Text(title) },
        supportingContent =
            if (subtitle != null) {
                { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                null
            },
        leadingContent = { Icon(icon, null, tint = accent.copy(alpha = 0.7f)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
            Switch(
                enabled = enabled,
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent,
                        uncheckedThumbColor = SwitchOffGray,
                        uncheckedTrackColor = SwitchOffGray.copy(alpha = 0.5f),
                    ),
            )
        },
    )
}

@Composable
fun ClickableSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = LocalAccentColor.current
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent =
            if (subtitle != null) {
                { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                null
            },
        leadingContent = { Icon(icon, null, tint = accent.copy(alpha = 0.7f)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
