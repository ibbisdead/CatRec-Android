package com.ibbie.catrec_screenrecorcer.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.ColorMode
import com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets
import com.ibbie.catrec_screenrecorcer.data.Rec709CompatBrightnessCorrection
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.service.OverlayService
import com.ibbie.catrec_screenrecorcer.service.RecordingResolutionInvalidReason
import com.ibbie.catrec_screenrecorcer.service.RecordingResolutionPreset
import com.ibbie.catrec_screenrecorcer.service.RecordingResolutionPresetKind
import com.ibbie.catrec_screenrecorcer.service.RecordingResolutionSupport
import com.ibbie.catrec_screenrecorcer.service.RecordingResolutionValidation
import com.ibbie.catrec_screenrecorcer.ui.components.*
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel
import com.ibbie.catrec_screenrecorcer.ui.safeStringResource
import com.ibbie.catrec_screenrecorcer.ui.theme.SwitchOffGray
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt
import androidx.core.content.ContextCompat
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressionReason
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressor
import com.ibbie.catrec_screenrecorcer.utils.PermissionManager

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
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val accent = LocalAccentColor.current
    val uiState by viewModel.settingsUiState.collectAsState()
    val isLowEndDevice = rememberIsLowEndDevice()
    val canDrawOverlays = Settings.canDrawOverlays(context)

    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var showBatteryRationaleDialog by remember { mutableStateOf(false) }
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
    if (uiState.showWatermark && canDrawOverlays && !uiState.isRecording) {
        DisposableEffect(Unit) {
            context.startService(
                Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SHOW_WATERMARK_PREVIEW
                    putExtra(OverlayService.EXTRA_WATERMARK_SIZE, uiState.watermarkSize)
                    putExtra(OverlayService.EXTRA_WATERMARK_OPACITY, uiState.watermarkOpacity)
                    putExtra(OverlayService.EXTRA_WATERMARK_SHAPE, uiState.watermarkShape)
                    putExtra(OverlayService.EXTRA_WATERMARK_IMAGE_URI, uiState.watermarkImageUri)
                    putExtra(OverlayService.EXTRA_WATERMARK_X_FRACTION, uiState.watermarkXFraction)
                    putExtra(OverlayService.EXTRA_WATERMARK_Y_FRACTION, uiState.watermarkYFraction)
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
        LaunchedEffect(uiState.watermarkSize, uiState.watermarkOpacity, uiState.watermarkShape, uiState.watermarkImageUri, uiState.watermarkXFraction, uiState.watermarkYFraction) {
            OverlayService.updatePreviewIfActive(
                uiState.watermarkSize,
                uiState.watermarkOpacity,
                uiState.watermarkShape,
                uiState.watermarkImageUri,
                uiState.watermarkXFraction,
                uiState.watermarkYFraction,
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
    val imagePickerLauncher =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
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
    val permissionManager = remember { PermissionManager(context) }
    var pendingEnableMicrophone by remember { mutableStateOf(false) }
    var pendingEnableInternalAudio by remember { mutableStateOf(false) }
    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            permissionManager.saveAudioGranted(granted)
            if (granted && pendingEnableMicrophone) viewModel.setRecordAudio(true)
            if (granted && pendingEnableInternalAudio) viewModel.setInternalAudio(true)
            pendingEnableMicrophone = false
            pendingEnableInternalAudio = false
        }

    // Dialog states
    var showFpsDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }
    var showResolutionDialog by remember { mutableStateOf(false) }
    var showCustomResolutionDialog by remember { mutableStateOf(false) }
    var showVideoEncoderDialog by remember { mutableStateOf(false) }
    var showColorModeDialog by remember { mutableStateOf(false) }
    var showRec709BrightnessCorrectionDialog by remember { mutableStateOf(false) }
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
    var showWatermarkLocDialog2 by remember { mutableStateOf(false) }
    var showLagWarningDialog by remember { mutableStateOf(false) }
    var showAccentPickerDialog by remember { mutableStateOf(false) }
    var accentPickingSecond by remember { mutableStateOf(false) }
    var accentHexInput by remember(uiState.accentHex) { mutableStateOf(uiState.accentHex) }
    var accentHex2Input by remember(uiState.accentHex2) { mutableStateOf(uiState.accentHex2) }

    var showAudioMenuSheet by remember { mutableStateOf(false) }
    var showVideoMenuSheet by remember { mutableStateOf(false) }
    val audioMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val videoMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val resolutionPresets =
        remember(
            configuration.orientation,
            configuration.screenWidthDp,
            configuration.screenHeightDp,
            uiState.recordingOrientation,
            uiState.videoEncoder,
            uiState.fps,
            isLowEndDevice,
        ) {
            RecordingResolutionSupport.generatePresets(
                context = context,
                recordingOrientation = uiState.recordingOrientation,
                videoEncoder = uiState.videoEncoder,
                fps = uiState.fps.toInt(),
                lowEndDeviceProfile = isLowEndDevice,
            )
        }
    val selectedResolutionSetting = RecordingResolutionSupport.normalizeSavedSetting(uiState.resolution)

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
        uiState.stopBehavior.joinToString(", ") { key ->
            when (key) {
                StopBehaviorKeys.NOTIFICATION -> resources.getString(R.string.stop_behavior_notification)
                StopBehaviorKeys.SHAKE -> resources.getString(R.string.stop_behavior_shake)
                StopBehaviorKeys.SCREEN_OFF -> resources.getString(R.string.stop_behavior_screen_off)
                StopBehaviorKeys.PAUSE_ON_SCREEN_OFF -> resources.getString(R.string.stop_behavior_pause_on_screen_off)
                else -> key
            }
        }
    val langIdx = languageCodes.indexOf(uiState.appLanguage).takeIf { it >= 0 } ?: 0
    val languageDisplay = stringResource(languageLabelIds.getOrElse(langIdx) { R.string.language_system })

    // ── Dialogs (all logic unchanged) ──────────────────────────────────────────
    if (showFpsDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_fps_title),
            options = listOf("24", "30", "45", "60", "90", "120"),
            selectedOption = "${uiState.fps.toInt()}",
            onOptionSelected = { selected ->
                viewModel.setFps(selected.toFloat())
            },
            onDismiss = { showFpsDialog = false },
        )
    }

    if (showBitrateDialog) {
        val bitrateKeys = listOf(1, 2, 4, 6, 8, 10, 12, 16, 20, 25, 30, 40, 50, 60, 80, 100, 120, 150, 200)
        val mbpsLabel = stringResource(R.string.label_mbps)
        val bitrateLabels = bitrateKeys.map { "$it $mbpsLabel" }
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_bitrate_title),
            options = bitrateLabels,
            selectedOption = "${uiState.bitrate.toInt()} $mbpsLabel",
            onOptionSelected = { label ->
                val idx = bitrateLabels.indexOf(label)
                if (idx >= 0) viewModel.setBitrate(bitrateKeys[idx].toFloat())
            },
            onDismiss = { showBitrateDialog = false },
        )
    }
    if (showResolutionDialog) {
        ResolutionDialog(
            presets = resolutionPresets,
            selectedOption = selectedResolutionSetting,
            onOptionSelected = { sel ->
                when {
                    sel == RecordingResolutionSupport.CUSTOM_OPTION -> {
                        showCustomResolutionDialog = true
                    }
                    else -> {
                        viewModel.setResolution(sel)
                    }
                }
            },
            onDismiss = { showResolutionDialog = false },
        )
    }
    if (showCustomResolutionDialog) {
        CustomResolutionDialog(
            current = RecordingResolutionSupport.parseSize(selectedResolutionSetting)?.setting ?: "",
            videoEncoder = uiState.videoEncoder,
            fps = uiState.fps.toInt(),
            lowEndDeviceProfile = isLowEndDevice,
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
            selectedOption = uiState.videoEncoder,
            onOptionSelected = {
                viewModel.setVideoEncoder(it)
            },
            onDismiss = { showVideoEncoderDialog = false },
        )
    }
    if (showColorModeDialog) {
        val colorModeOptions = listOf(
            ColorMode.STANDARD,
            ColorMode.FULL,
        )
        val colorModeLabels = listOf(
            stringResource(R.string.setting_color_mode_standard),
            stringResource(R.string.setting_color_mode_full),
        )
        SingleChoiceDialog(
            title = stringResource(R.string.setting_color_mode),
            options = colorModeOptions,
            optionLabels = colorModeLabels,
            selectedOption = uiState.colorMode,
            onOptionSelected = { viewModel.setColorMode(it) },
            onDismiss = { showColorModeDialog = false },
        )
    }
    if (showRec709BrightnessCorrectionDialog) {
        val correctionOptions =
            listOf(
                Rec709CompatBrightnessCorrection.OFF,
                Rec709CompatBrightnessCorrection.LOW,
                Rec709CompatBrightnessCorrection.MEDIUM,
            )
        val correctionLabels =
            listOf(
                stringResource(R.string.rec709_brightness_correction_off),
                stringResource(R.string.rec709_brightness_correction_low),
                stringResource(R.string.rec709_brightness_correction_medium),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.setting_rec709_brightness_correction),
            options = correctionOptions,
            optionLabels = correctionLabels,
            selectedOption = uiState.rec709CompatBrightnessCorrection,
            onOptionSelected = { viewModel.setRec709CompatBrightnessCorrection(it) },
            onDismiss = { showRec709BrightnessCorrectionDialog = false },
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
            selectedOption = orientLabels[orientKeys.indexOf(uiState.recordingOrientation).coerceIn(0, orientLabels.lastIndex)],
            onOptionSelected = { label ->
                val idx = orientLabels.indexOf(label)
                if (idx >= 0) viewModel.setRecordingOrientation(orientKeys[idx])
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
            selectedOption = "${uiState.audioBitrate} $kbpsLabel",
            onOptionSelected = { label ->
                val idx = audioBitrateLabels.indexOf(label)
                if (idx >= 0) viewModel.setAudioBitrate(audioBitrateKeys[idx])
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
            selectedOption = "${uiState.audioSampleRate} $hzLabel",
            onOptionSelected = { label ->
                val idx = sampleRateLabels.indexOf(label)
                if (idx >= 0) viewModel.setAudioSampleRate(sampleRateKeys[idx])
            },
            onDismiss = { showAudioSampleRateDialog = false },
        )
    }
    if (showAudioEncoderDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_audio_encoder),
            options = listOf("AAC-LC", "AAC-HE", "AAC-HE v2", "AAC-ELD"),
            selectedOption = uiState.audioEncoder,
            onOptionSelected = {
                viewModel.setAudioEncoder(it)
            },
            onDismiss = { showAudioEncoderDialog = false },
        )
    }
    if (showCountdownDialog) {
        val countdownKeys = listOf(0, 3, 5, 10, 20, 30)
        val countdownLabels =
            listOf(
                stringResource(R.string.countdown_none),
                stringResource(R.string.countdown_3s),
                stringResource(R.string.countdown_5s),
                stringResource(R.string.countdown_10s),
                stringResource(R.string.countdown_20s),
                stringResource(R.string.countdown_30s),
            )
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_countdown_timer),
            options = countdownLabels,
            selectedOption = countdownLabels[countdownKeys.indexOf(uiState.countdown).coerceIn(0, countdownLabels.lastIndex)],
            onOptionSelected = { label ->
                val idx = countdownLabels.indexOf(label)
                if (idx >= 0) viewModel.setCountdown(countdownKeys[idx])
            },
            onDismiss = { showCountdownDialog = false },
        )
    }
    if (showClipperDurationDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.dialog_clipper_duration_title),
            options = clipperDurationLabels,
            selectedOption = clipperDurationLabels.getOrElse(uiState.clipperDurationMinutes - 1) { clipperDurationLabels.first() },
            onOptionSelected = { sel ->
                val idx = clipperDurationLabels.indexOf(sel)
                if (idx >= 0) viewModel.setClipperDurationMinutes(idx + 1)
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
                                checked = uiState.stopBehavior.contains(key),
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
            selectedOption = uiState.filenamePattern,
            onOptionSelected = {
                viewModel.setFilenamePattern(it)
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
                            RadioButton(selected = uiState.appTheme == theme, onClick = {
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
                                    .selectable(selected = uiState.appLanguage == code, onClick = {
                                        viewModel.setAppLanguageWithUiApply(context, code)
                                        showLanguageDialog = false
                                    })
                                    .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = uiState.appLanguage == code, onClick = {
                                viewModel.setAppLanguageWithUiApply(context, code)
                                showLanguageDialog = false
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
            selectedOption = formatLabels[formatKeys.indexOf(uiState.screenshotFormat).takeIf { it >= 0 } ?: 0],
            onOptionSelected = { label ->
                val idx = formatLabels.indexOf(label)
                if (idx >= 0) viewModel.setScreenshotFormat(formatKeys[idx])
            },
            onDismiss = { showScreenshotFormatDialog = false },
        )
    }
    if (showCameraSettingsDialog) {
        CameraSettingsDialog(
            viewModel = viewModel,
            cameraOverlay = uiState.cameraOverlay,
            cameraLockPosition = uiState.cameraLockPosition,
            cameraFacing = uiState.cameraFacing,
            cameraAspectRatio = uiState.cameraAspectRatio,
            cameraOpacity = uiState.cameraOpacity,
            cameraOverlaySize = uiState.cameraOverlaySize,
            cameraXFraction = uiState.cameraXFraction,
            cameraYFraction = uiState.cameraYFraction,
            cameraOrientation = uiState.cameraOrientation,
            isRecording = uiState.isRecording,
            canDrawOverlays = canDrawOverlays,
            context = context,
            onDismiss = { showCameraSettingsDialog = false },
        )
    }

    // ── Main layout (no nested Scaffold: outer NavGraph already has the tab header) ──
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        item(key = "title/header", contentType = "header") {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
            )
        }

        item(key = "settings_content", contentType = "settings_card") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                // ── CONTROLS ──────────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_controls))
            SwitchSettingItem(
                Icons.Default.ControlCamera,
                stringResource(R.string.setting_floating_controls),
                stringResource(R.string.settings_floating_controls_desc),
                uiState.floatingControls,
            ) {
                if (it && !Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, resources.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.ANDROID_SETTINGS)
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()),
                    )
                } else {
                    viewModel.setFloatingControls(it)
                    if (it && Settings.canDrawOverlays(context)) {
                        context.startService(
                            Intent(context, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_SHOW_IDLE_CONTROLS
                            },
                        )
                    } else if (!it) {
                        context.startService(
                            Intent(context, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_HIDE_IDLE_CONTROLS
                            },
                        )
                    }
                }
            }
            SwitchSettingItem(
                Icons.Default.VisibilityOff,
                stringResource(R.string.setting_hide_floating_while_recording),
                stringResource(R.string.settings_hide_floating_while_recording_desc),
                uiState.hideFloatingIconWhileRecording,
            ) { viewModel.setHideFloatingIconWhileRecording(it) }
                SwitchSettingItem(
                    Icons.Default.Share,
                    stringResource(R.string.setting_post_screenshot_options),
                    stringResource(R.string.settings_post_screenshot_options_desc),
                    uiState.postScreenshotOptions,
                ) { viewModel.setPostScreenshotOptions(it) }
                SwitchSettingItem(
                    Icons.Default.Apps,
                    stringResource(R.string.setting_record_single_app),
                    stringResource(R.string.settings_record_single_app_desc),
                    uiState.recordSingleAppEnabled,
                ) { viewModel.setRecordSingleAppEnabled(it) }
                SwitchSettingItem(
                    Icons.Default.TouchApp,
                    stringResource(R.string.settings_show_touches),
                    stringResource(R.string.settings_show_touches_desc),
                    uiState.touchOverlay,
                ) {
                    viewModel.setTouchOverlay(it)
                    if (it) {
                        try {
                            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.ANDROID_SETTINGS)
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: Exception) {
                        }
                        Toast.makeText(context, resources.getString(R.string.toast_enable_show_taps), Toast.LENGTH_LONG).show()
                    }
                }
                ClickableSettingItem(
                    Icons.Default.Timer,
                    stringResource(R.string.setting_countdown),
                    if (uiState.countdown == 0) {
                        stringResource(R.string.setting_countdown_off)
                    } else {
                        pluralStringResource(R.plurals.setting_countdown_seconds, uiState.countdown, uiState.countdown)
                    },
                ) { showCountdownDialog = true }
                ClickableSettingItem(Icons.Default.StopCircle, stringResource(R.string.setting_stop_behavior), stopBehaviorSummary) {
                    showStopDialog = true
                }
                ClickableSettingItem(
                    Icons.Default.ContentCut,
                    stringResource(R.string.setting_clipper_duration),
                    clipperDurationLabels.getOrElse(uiState.clipperDurationMinutes - 1) { clipperDurationLabels.first() },
                ) { showClipperDurationDialog = true }
            if (!batteryOptimizationIgnored) {
                ClickableSettingItem(
                    Icons.Filled.PowerSettingsNew,
                    stringResource(R.string.setting_allow_background_title),
                    stringResource(R.string.setting_allow_background_desc),
                ) { showBatteryRationaleDialog = true }
            }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── Recording quality (open audio / video+GIF menus) ───────────────
            GlassSectionHeader(stringResource(R.string.settings_section_recording_quality))
            ListItem(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAudioMenuSheet = true },
                headlineContent = {
                    Text(
                        stringResource(R.string.settings_open_audio_menu),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.settings_open_audio_menu_sub),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Default.GraphicEq, null, tint = accent.copy(alpha = 0.7f)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showVideoMenuSheet = true },
                headlineContent = {
                    Text(
                        stringResource(R.string.settings_open_video_menu),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.settings_open_video_menu_sub),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Default.VideoSettings, null, tint = accent.copy(alpha = 0.7f)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── OVERLAY ───────────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_overlay))
            ClickableSettingItem(
                Icons.Default.CameraAlt,
                stringResource(R.string.setting_camera_settings),
                if (uiState.cameraOverlay) {
                    stringResource(R.string.camera_status_enabled, uiState.cameraAspectRatio, uiState.cameraFacing)
                } else {
                    stringResource(R.string.state_disabled)
                },
                isPro = true,
            ) {
                showCameraSettingsDialog = true
            }

            SwitchSettingItem(
                Icons.AutoMirrored.Filled.BrandingWatermark,
                stringResource(R.string.setting_watermark),
                null,
                uiState.showWatermark,
                isPro = true,
            ) {
                if (it && !Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, resources.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.ANDROID_SETTINGS)
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                    )
                } else {
                    viewModel.setShowWatermark(it)
                }
            }

        if (uiState.showWatermark) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
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
                            if (canDrawOverlays && !uiState.isRecording) {
                                stringResource(R.string.watermark_preview_active)
                            } else if (uiState.isRecording) {
                                stringResource(R.string.watermark_preview_recording)
                            } else {
                                stringResource(R.string.watermark_preview_need_overlay)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFCCAAAA),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                                    selected = uiState.watermarkShape == shape,
                                ) {
                                    Text(
                                        shapeLabels[idx],
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                )

                GlassSlider(
                    stringResource(R.string.setting_watermark_size),
                    safeStringResource(R.string.label_dp, uiState.watermarkSize),
                    uiState.watermarkSize.toFloat(),
                    50f..300f,
                    49,
                ) {
                    viewModel.setWatermarkSize(it.toInt())
                }
                GlassSlider(
                    stringResource(R.string.setting_watermark_opacity),
                    safeStringResource(R.string.label_percent, uiState.watermarkOpacity),
                    uiState.watermarkOpacity.toFloat(),
                    10f..100f,
                    17,
                ) {
                    viewModel.setWatermarkOpacity(it.toInt())
                }

                ClickableSettingItem(
                    Icons.Default.Image,
                    stringResource(R.string.watermark_image),
                    if (uiState.watermarkImageUri != null) {
                        stringResource(
                            R.string.watermark_image_custom,
                        )
                    } else {
                        stringResource(R.string.watermark_image_default)
                    },
                ) { imagePickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                if (uiState.watermarkImageUri != null) {
                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setWatermarkImageUri(null) },
                        headlineContent = {
                            Text(
                                stringResource(R.string.watermark_reset_icon),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { Icon(Icons.Default.RestartAlt, null, tint = accent.copy(alpha = 0.7f)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
        }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── SCREENSHOTS ───────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_screenshots))
            val screenshotFormatDisplay =
                when (uiState.screenshotFormat) {
                    "JPEG" -> stringResource(R.string.format_jpeg)
                    "PNG" -> stringResource(R.string.format_png)
                    "WebP" -> stringResource(R.string.format_webp)
                    else -> uiState.screenshotFormat
                }
            ClickableSettingItem(
                Icons.Default.PhotoSizeSelectLarge,
                stringResource(R.string.setting_screenshot_format),
                screenshotFormatDisplay,
            ) { showScreenshotFormatDialog = true }
            GlassSlider(
                stringResource(R.string.setting_screenshot_quality),
                "${uiState.screenshotQuality}%",
                uiState.screenshotQuality.toFloat(),
                10f..100f,
                17,
            ) {
                viewModel.setScreenshotQuality(it.toInt())
            }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── THEME ─────────────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_theme))
            val themeDisplay =
                when (uiState.appTheme) {
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
                remember(uiState.accentHex) {
                    runCatching { Color("#${uiState.accentHex.removePrefix("#").take(6)}".toColorInt()) }
                        .getOrDefault(accent)
                }
            val parsedAccent2 =
                remember(uiState.accentHex2) {
                    runCatching { Color("#${uiState.accentHex2.removePrefix("#").take(6)}".toColorInt()) }
                        .getOrDefault(Color(0xFFFF8C00))
                }
            SettingsListRow(
                leadingContent = {
                    Icon(Icons.Default.Palette, null, tint = accent.copy(alpha = 0.7f))
                },
                headlineContent = {
                    Text(
                        stringResource(R.string.accent_color_title),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        if (uiState.accentGradient) {
                            "#${uiState.accentHex.uppercase()}  →  #${uiState.accentHex2.uppercase()}"
                        } else {
                            "#${uiState.accentHex.uppercase()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        if (uiState.accentGradient) {
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
                            Text(
                                stringResource(R.string.action_change),
                                color = accent,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )

            // Performance Mode toggle
            val perfSubtitle =
                when {
                    isLowEndDevice && uiState.performanceMode ->
                        stringResource(R.string.perf_auto_low_end)
                    isLowEndDevice && !uiState.performanceMode ->
                        stringResource(R.string.perf_quality_may_lag)
                    uiState.performanceMode -> stringResource(R.string.perf_static_glass)
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
                    Text(
                        stringResource(R.string.setting_performance_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        perfSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (isLowEndDevice && !uiState.performanceMode) {
                                accent.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = uiState.performanceMode,
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
                                checkedThumbColor = Color.White,
                                checkedTrackColor = accent,
                                uncheckedThumbColor = SwitchOffGray,
                                uncheckedTrackColor = SwitchOffGray.copy(alpha = 0.5f),
                            ),
                    )
                },
            )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── LANGUAGE ──────────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_language))
            ClickableSettingItem(
                Icons.Default.Language,
                stringResource(R.string.setting_language),
                languageDisplay,
            ) { showLanguageDialog = true }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── STORAGE ───────────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_storage))
            ClickableSettingItem(
                Icons.Default.Folder,
                stringResource(R.string.setting_save_location),
                if (uiState.saveLocationUri != null) {
                    stringResource(
                        R.string.setting_save_location_custom,
                    )
                } else {
                    stringResource(R.string.setting_save_location_default)
                },
            ) { folderPickerLauncher.launch(null) }
            ClickableSettingItem(Icons.Default.TextFields, stringResource(R.string.setting_filename_pattern), uiState.filenamePattern) {
                showPatternDialog = true
            }
            SwitchSettingItem(
                Icons.Default.DeleteSweep,
                stringResource(R.string.setting_auto_delete_title),
                stringResource(R.string.setting_auto_delete_desc),
                uiState.autoDelete,
            ) {
                viewModel.setAutoDelete(it)
            }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── GENERAL ───────────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_general))
            SwitchSettingItem(
                Icons.Default.Smartphone,
                stringResource(R.string.setting_keep_screen_on_title),
                stringResource(R.string.setting_keep_screen_on_desc),
                uiState.keepScreenOn,
            ) {
                viewModel.setKeepScreenOn(it)
            }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
                // ── PRIVACY ───────────────────────────────────────────────────────
            GlassSectionHeader(stringResource(R.string.settings_section_privacy))
            SwitchSettingItem(
                Icons.Default.Analytics,
                stringResource(R.string.setting_usage_analytics),
                stringResource(R.string.setting_usage_analytics_desc),
                uiState.analyticsEnabled,
            ) { viewModel.setAnalyticsEnabled(it) }
            SwitchSettingItem(
                Icons.Default.PrivacyTip,
                stringResource(R.string.setting_personalized_ads),
                if (uiState.personalizedAdsEnabled) {
                    stringResource(R.string.setting_personalized_ads_on_desc)
                } else {
                    stringResource(R.string.setting_personalized_ads_off_desc)
                },
                uiState.personalizedAdsEnabled,
            ) { viewModel.setPersonalizedAdsEnabled(it) }
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showBatteryRationaleDialog) {
        BatteryOptimizationRationaleDialog(
            onDismiss = {
                showBatteryRationaleDialog = false
                batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
            },
        )
    }
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
            if (uiState.watermarkLocation in snapKeys) {
                snapLabels[snapKeys.indexOf(uiState.watermarkLocation)]
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
                            if (uiState.accentGradient && accentPickingSecond) {
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
                                    runCatching { Color("#$hex".toColorInt()) }
                                            .getOrDefault(accent)
                                    }
                                val isSelected =
                                    if (uiState.accentGradient && accentPickingSecond) {
                                        uiState.accentHex2.equals(hex, true)
                                    } else {
                                        uiState.accentHex.equals(hex, true)
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
                                                if (uiState.accentGradient && accentPickingSecond) {
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
                        val hexTarget = if (uiState.accentGradient && accentPickingSecond) accentHex2Input else accentHexInput
                        val hexSetter: (String) -> Unit = { v ->
                            if (uiState.accentGradient && accentPickingSecond) accentHex2Input = v else accentHexInput = v
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
                                    if (uiState.accentGradient && accentPickingSecond) {
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
                                checked = uiState.accentGradient,
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

                        if (uiState.accentGradient) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val c1 =
                                    remember(accentHexInput) {
                                        runCatching { Color("#$accentHexInput".toColorInt()) }
                                            .getOrDefault(accent)
                                    }
                                val c2 =
                                    remember(accentHex2Input) {
                                        runCatching { Color("#$accentHex2Input".toColorInt()) }
                                            .getOrDefault(Color(0xFFFF8C00))
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
                    uiState.recordAudio,
                ) {
                    if (it && !permissionManager.isAudioGranted()) {
                        pendingEnableMicrophone = true
                        pendingEnableInternalAudio = false
                        AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.setRecordAudio(it)
                    }
                }
                SwitchSettingItem(
                    Icons.Default.MusicNote,
                    stringResource(R.string.setting_internal_audio),
                    stringResource(R.string.setting_internal_audio_note),
                    uiState.internalAudio,
                ) {
                    if (it && !permissionManager.isAudioGranted()) {
                        pendingEnableInternalAudio = true
                        pendingEnableMicrophone = false
                        AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.setInternalAudio(it)
                    }
                }
                ClickableSettingItem(
                    Icons.Default.GraphicEq,
                    stringResource(R.string.setting_audio_bitrate),
                    "${uiState.audioBitrate} ${stringResource(R.string.label_kbps)}",
                ) { showAudioBitrateDialog = true }
                ClickableSettingItem(
                    Icons.Default.Audiotrack,
                    stringResource(R.string.setting_audio_sample_rate),
                    "${uiState.audioSampleRate} ${stringResource(R.string.label_hz)}",
                ) { showAudioSampleRateDialog = true }
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
                                    selected = uiState.audioChannels == value,
                                ) {
                                    Text(
                                        label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                )
                ClickableSettingItem(
                    Icons.Default.Tune,
                    stringResource(R.string.setting_audio_encoder),
                    uiState.audioEncoder,
                ) { showAudioEncoderDialog = true }
                SwitchSettingItem(
                    Icons.AutoMirrored.Filled.CallSplit,
                    stringResource(R.string.setting_separate_mic),
                    stringResource(R.string.setting_separate_mic_desc),
                    uiState.separateMicRecording,
                    isPro = true,
                ) { newValue -> viewModel.setSeparateMicRecording(newValue) }
            }
        }
    }

    if (showVideoMenuSheet) {
        val videoLocked = uiState.isGifCaptureMode
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
                    val selected = uiState.gifRecorderPresetId == preset.id
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
                    resolutionPresets
                        .firstOrNull { it.setting == selectedResolutionSetting }
                        ?.let { resolutionPresetLabel(it) }
                        ?: RecordingResolutionSupport.parseSize(selectedResolutionSetting)?.setting
                        ?: stringResource(R.string.setting_resolution_native)
                val orientationDisplay =
                    when (uiState.recordingOrientation) {
                        "Auto" -> stringResource(R.string.setting_orientation_auto)
                        "Portrait" -> stringResource(R.string.setting_orientation_portrait)
                        "Landscape" -> stringResource(R.string.setting_orientation_landscape)
                        else -> uiState.recordingOrientation
                    }
                ClickableSettingItem(
                    Icons.Default.Speed,
                    stringResource(R.string.setting_fps),
                    "${uiState.fps.toInt()} ${stringResource(R.string.label_fps)}",
                    enabled = !videoLocked,
                    isPro = true,
                ) {
                    if (!videoLocked) showFpsDialog = true
                }
                ClickableSettingItem(
                    Icons.Default.DataUsage,
                    stringResource(R.string.setting_bitrate),
                    "${uiState.bitrate.toInt()} ${stringResource(R.string.label_mbps)}",
                    enabled = !videoLocked,
                ) {
                    if (!videoLocked) showBitrateDialog = true
                }
                SwitchSettingItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.setting_adaptive_recording_performance),
                    subtitle = stringResource(R.string.setting_adaptive_recording_performance_summary),
                    checked = uiState.adaptivePerformanceEnabled,
                    enabled = !videoLocked,
                    onCheckedChange = { viewModel.setAdaptivePerformanceEnabled(it) },
                )
                ClickableSettingItem(
                    Icons.Default.AspectRatio,
                    stringResource(R.string.setting_resolution),
                    resolutionDisplay,
                    enabled = !videoLocked,
                ) {
                    if (!videoLocked) showResolutionDialog = true
                }
                ClickableSettingItem(
                    Icons.Default.VideoSettings,
                    stringResource(R.string.setting_video_encoder),
                    uiState.videoEncoder,
                    enabled = !videoLocked,
                ) {
                    if (!videoLocked) showVideoEncoderDialog = true
                }
                val colorModeDisplay = when (uiState.colorMode) {
                    ColorMode.FULL -> stringResource(R.string.setting_color_mode_full)
                    else -> stringResource(R.string.setting_color_mode_standard)
                }
                ClickableSettingItem(
                    Icons.Default.Palette,
                    stringResource(R.string.setting_color_mode),
                    colorModeDisplay,
                    enabled = !videoLocked,
                ) {
                    if (!videoLocked) showColorModeDialog = true
                }
                if (uiState.colorMode == ColorMode.STANDARD) {
                    SwitchSettingItem(
                        icon = Icons.Default.Tune,
                        title = stringResource(R.string.setting_force_rec709_compat),
                        subtitle = stringResource(R.string.setting_force_rec709_compat_summary),
                        checked = uiState.forceRec709Compatibility,
                        enabled = !videoLocked,
                        onCheckedChange = { viewModel.setForceRec709Compatibility(it) },
                    )
                }
                if (uiState.colorMode == ColorMode.STANDARD && uiState.forceRec709Compatibility) {
                    val correctionDisplay =
                        when (uiState.rec709CompatBrightnessCorrection) {
                            Rec709CompatBrightnessCorrection.LOW ->
                                stringResource(R.string.rec709_brightness_correction_low)
                            Rec709CompatBrightnessCorrection.MEDIUM ->
                                stringResource(R.string.rec709_brightness_correction_medium)
                            else -> stringResource(R.string.rec709_brightness_correction_off)
                        }
                    ClickableSettingItem(
                        Icons.Default.BrightnessMedium,
                        stringResource(R.string.setting_rec709_brightness_correction),
                        "$correctionDisplay\n${stringResource(R.string.setting_rec709_brightness_correction_summary)}",
                        enabled = !videoLocked,
                    ) {
                        if (!videoLocked) showRec709BrightnessCorrectionDialog = true
                    }
                }
                ClickableSettingItem(
                    Icons.Default.ScreenRotation,
                    stringResource(R.string.setting_orientation),
                    orientationDisplay,
                    enabled = !videoLocked,
                ) {
                    if (!videoLocked) showOrientationDialog = true
                }
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
    cameraOrientation: String,
    isRecording: Boolean,
    canDrawOverlays: Boolean,
    context: Context,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val accent = LocalAccentColor.current
    var showCameraFacingDialog by remember { mutableStateOf(false) }
    var showCameraAspectDialog by remember { mutableStateOf(false) }
    var showCameraOrientationDialog by remember { mutableStateOf(false) }
    var pendingEnableCamera by remember { mutableStateOf(false) }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            if (granted && pendingEnableCamera) {
                if (!canDrawOverlays) {
                    Toast.makeText(context, resources.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.ANDROID_SETTINGS)
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                    )
                } else {
                    viewModel.setCameraOverlay(true)
                }
            }
            pendingEnableCamera = false
        }

    fun setCameraOverlayChecked(checked: Boolean) {
        if (checked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingEnableCamera = true
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (checked && !canDrawOverlays) {
            Toast.makeText(context, resources.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.ANDROID_SETTINGS)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
            )
        } else {
            viewModel.setCameraOverlay(checked)
        }
    }

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
            },
            onDismiss = { showCameraOrientationDialog = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.camera_settings_title), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                ProBadge()
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ListItem(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { setCameraOverlayChecked(!cameraOverlay) },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.camera_enable))
                            Spacer(Modifier.width(8.dp))
                            ProBadge()
                        }
                    },
                    supportingContent = {
                        Text(stringResource(R.string.camera_enable_desc))
                    },
                    leadingContent = { Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Switch(checked = cameraOverlay, onCheckedChange = {
                            setCameraOverlayChecked(it)
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
                                safeStringResource(R.string.label_dp, cameraOverlaySize),
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
                                safeStringResource(R.string.label_percent, cameraOpacity),
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

@Composable
private fun resolutionPresetLabel(preset: RecordingResolutionPreset): String {
    val title =
        when (preset.kind) {
            RecordingResolutionPresetKind.NATIVE -> stringResource(R.string.setting_resolution_native)
            RecordingResolutionPresetKind.UHD_4K -> stringResource(R.string.resolution_preset_4k)
            RecordingResolutionPresetKind.QHD_2K -> stringResource(R.string.resolution_preset_2k)
            RecordingResolutionPresetKind.TIER_1080 -> stringResource(R.string.resolution_preset_1080)
            RecordingResolutionPresetKind.TIER_720 -> stringResource(R.string.resolution_preset_720)
            RecordingResolutionPresetKind.TIER_480 -> stringResource(R.string.resolution_preset_480)
        }
    return "$title (${preset.size.setting})"
}

// ── Custom Resolution Dialog ───────────────────────────────────────────────────

@Composable
private fun CustomResolutionDialog(
    current: String,
    videoEncoder: String,
    fps: Int,
    lowEndDeviceProfile: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
                        val filtered = input.filter { it.isDigit() || it == '-' || it == 'x' || it == 'X' || it == '\u00D7' }.lowercase()
                        val xCount = filtered.count { it == 'x' || it == '\u00D7' }
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
                when (
                    val validation =
                        RecordingResolutionSupport.validateCustomResolution(
                            input = text,
                            context = context,
                            videoEncoder = videoEncoder,
                            fps = fps,
                            lowEndDeviceProfile = lowEndDeviceProfile,
                        )
                ) {
                    is RecordingResolutionValidation.Valid -> onConfirm(validation.size.setting)
                    is RecordingResolutionValidation.Invalid -> {
                        error =
                            when (validation.reason) {
                                RecordingResolutionInvalidReason.REQUIRED ->
                                    resources.getString(R.string.error_resolution_required)
                                RecordingResolutionInvalidReason.POSITIVE_NUMBERS ->
                                    resources.getString(R.string.error_resolution_positive)
                                RecordingResolutionInvalidReason.EVEN_DIMENSIONS ->
                                    resources.getString(R.string.error_resolution_even)
                                RecordingResolutionInvalidReason.BELOW_MINIMUM ->
                                    resources.getString(R.string.error_resolution_min)
                                RecordingResolutionInvalidReason.EXCEEDS_SAFE_MAXIMUM ->
                                    resources.getString(R.string.error_resolution_max)
                                RecordingResolutionInvalidReason.UNSUPPORTED_BY_ENCODER ->
                                    resources.getString(R.string.error_resolution_encoder_unsupported)
                                RecordingResolutionInvalidReason.TOO_LARGE_FOR_PROFILE ->
                                    resources.getString(R.string.error_resolution_too_large_for_profile)
                            }
                    }
                }
            }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ── Resolution Dialog (handles separators) ────────────────────────────────────

@Composable
private fun ResolutionDialog(
    presets: List<RecordingResolutionPreset>,
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
                presets.forEach { preset ->
                    val isSelected = selectedOption == preset.setting
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = isSelected, onClick = { onOptionSelected(preset.setting); onDismiss() })
                                .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = { onOptionSelected(preset.setting); onDismiss() })
                        Text(
                            resolutionPresetLabel(preset),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }

                val customSelected =
                    RecordingResolutionSupport.parseSize(selectedOption) != null &&
                        presets.none { it.setting == selectedOption }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(RecordingResolutionSupport.CUSTOM_OPTION); onDismiss() }
                            .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (customSelected) {
                        RadioButton(
                            selected = true,
                            onClick = { onOptionSelected(RecordingResolutionSupport.CUSTOM_OPTION); onDismiss() },
                        )
                    } else {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.padding(start = 8.dp).size(20.dp), tint = accent)
                    }
                    val customText =
                        RecordingResolutionSupport.parseSize(selectedOption)
                            ?.takeIf { customSelected }
                            ?.let { "${stringResource(R.string.setting_resolution_custom)} (${it.setting})" }
                            ?: stringResource(R.string.setting_resolution_custom)
                    Text(
                        customText,
                        modifier = Modifier.padding(start = 16.dp),
                        color = accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
    /** Optional display labels; when provided, index-matched to [options] for localised display. */
    optionLabels: List<String>? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, option ->
                    val label = optionLabels?.getOrNull(index) ?: option
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = option == selectedOption, onClick = { onOptionSelected(option); onDismiss() })
                                .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selectedOption, onClick = { onOptionSelected(option); onDismiss() })
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SwitchSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    isPro: Boolean = false,
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
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPro) {
                    Spacer(Modifier.width(8.dp))
                    ProBadge()
                }
            }
        },
        supportingContent =
            if (subtitle != null) {
                {
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    isPro: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccentColor.current
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled, onClick = onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPro) {
                    Spacer(Modifier.width(8.dp))
                    ProBadge()
                }
            }
        },
        supportingContent =
            if (subtitle != null) {
                {
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                null
            },
        leadingContent = { Icon(icon, null, tint = accent.copy(alpha = 0.7f)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
