package com.ibbie.catrec_screenrecorcer.ui.recording

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ibbie.catrec_screenrecorcer.CatRecApplication
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.RecordingUiSnapshot
import com.ibbie.catrec_screenrecorcer.data.recording.ProRecordingFeature
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingStartProGateResult
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdManager
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressionReason
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressor
import com.ibbie.catrec_screenrecorcer.ads.MobileAdsInitializer
import com.ibbie.catrec_screenrecorcer.service.AppControlNotification
import com.ibbie.catrec_screenrecorcer.service.ScreenRecordService
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.components.ProFeature
import com.ibbie.catrec_screenrecorcer.ui.components.ProUnlockDialog
import com.ibbie.catrec_screenrecorcer.util.MediaProjectionIntents
import com.ibbie.catrec_screenrecorcer.utils.PermissionInfo
import com.ibbie.catrec_screenrecorcer.utils.PermissionManager
import com.ibbie.catrec_screenrecorcer.utils.BatteryOptimizationHelper
import com.ibbie.catrec_screenrecorcer.ui.settings.BatteryOptimizationRationaleDialog
import androidx.core.net.toUri
import kotlinx.coroutines.launch

private const val RECORDING_FLOW_HOST_LOG = "RecordingFlowHost"

private enum class RecordingFlowPermissionStep {
    IDLE,
    NOTIFICATIONS,
    MEDIA_LIBRARY,
    COMPLETE,
}

private enum class PendingProRecordingStart {
    RECORDING,
    BUFFER,
    ROUTED_RECORDING,
    ROUTED_BUFFER,
}

/**
 * Invoked from the global FAB (and similar): stop if active, otherwise start record / clipper / GIF flow.
 */
val LocalFabRecordingControl =
    compositionLocalOf<() -> Unit> {
        { }
    }

@Composable
fun FabRecordingBridge(
    viewModel: RecordingViewModel,
    recordingUiSnapshot: RecordingUiSnapshot,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val toastScreenCaptureDenied = stringResource(R.string.toast_screen_capture_denied)
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val billing = remember(context) { (context.applicationContext as CatRecApplication).billingManager }
    val permissionManager = remember { PermissionManager(context) }

    var allPermissionsGranted by remember { mutableStateOf(permissionManager.areAllGranted()) }
    var missingPermissions by remember { mutableStateOf(permissionManager.getMissingPermissions()) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var setupStep by remember { mutableStateOf(RecordingFlowPermissionStep.IDLE) }

    var pendingAutoStart by remember { mutableStateOf(false) }
    var pendingAutoBufferStart by remember { mutableStateOf(false) }
    var pendingScreenshotProjection by remember { mutableStateOf(false) }
    var showBatteryRationaleDialog by remember { mutableStateOf(false) }
    var showProRecordingDialog by remember { mutableStateOf(false) }
    var pendingProRecordingFeatures by remember { mutableStateOf<List<ProFeature>>(emptyList()) }
    var pendingProStart by remember { mutableStateOf<PendingProRecordingStart?>(null) }
    var routedRecordingActionActive by remember { mutableStateOf(false) }
    var routedMediaProjectionPending by remember { mutableStateOf(false) }
    val adsDisabledForResume by viewModel.adsDisabled.collectAsState(initial = viewModel.adsDisabled.value)

    fun clearRoutedRecordingSuppression(trigger: String) {
        if (!routedRecordingActionActive) return
        routedRecordingActionActive = false
        routedMediaProjectionPending = false
        Log.d(RECORDING_FLOW_HOST_LOG, "routed action suppression cleared trigger=$trigger")
        AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.ROUTED_RECORDING_ACTION)
    }

    fun consumeScreenshotExtra(activity: Activity?) {
        val a = activity ?: return
        if (a.intent.getBooleanExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT_PROJECTION, false)) {
            a.intent.removeExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT_PROJECTION)
            pendingScreenshotProjection = true
        }
    }

    fun consumeRoutedRecordingStart(activity: Activity?) {
        val a = activity ?: return
        when (a.intent.action) {
            MainActivity.ACTION_START_RECORDING_FROM_OVERLAY -> {
                MainActivity.markRoutedRecordingAppOpenSuppressed("recording_flow_consume_recording")
                routedRecordingActionActive = true
                Log.d(RECORDING_FLOW_HOST_LOG, "recording start requested source=routed_overlay_recording")
                a.intent.action = null
                a.intent.removeExtra(MainActivity.EXTRA_SUPPRESS_APP_OPEN_AD)
                a.intent.removeExtra(MainActivity.EXTRA_ROUTE_REASON)
                pendingAutoStart = true
            }
            MainActivity.ACTION_START_BUFFER_FROM_OVERLAY -> {
                MainActivity.markRoutedRecordingAppOpenSuppressed("recording_flow_consume_buffer")
                routedRecordingActionActive = true
                Log.d(RECORDING_FLOW_HOST_LOG, "recording start requested source=routed_overlay_buffer")
                a.intent.action = null
                a.intent.removeExtra(MainActivity.EXTRA_SUPPRESS_APP_OPEN_AD)
                a.intent.removeExtra(MainActivity.EXTRA_ROUTE_REASON)
                pendingAutoBufferStart = true
            }
            else -> {
                if (MainActivity.isRoutedRecordingActionIntent(a.intent)) {
                    MainActivity.markRoutedRecordingAppOpenSuppressed("recording_flow_consume_open_only")
                    routedRecordingActionActive = true
                    a.intent.removeExtra(MainActivity.EXTRA_SUPPRESS_APP_OPEN_AD)
                    a.intent.removeExtra(MainActivity.EXTRA_ROUTE_REASON)
                    clearRoutedRecordingSuppression("open_only_no_pending_start")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val activity = context as? Activity
        consumeRoutedRecordingStart(activity)
        consumeScreenshotExtra(activity)
    }

    LaunchedEffect(allPermissionsGranted) {
        if (!allPermissionsGranted) {
            return@LaunchedEffect
        }
        // Idle controls notification: post after POST_NOTIFICATIONS (or Settings) allows it.
        AppControlNotification.refresh(context.applicationContext)
        // On aggressive-killer OEMs, nudge once if battery is not yet exempted.
        if (BatteryOptimizationHelper.isAggressiveKiller() &&
            !BatteryOptimizationHelper.isExempted(context)
        ) {
            showBatteryRationaleDialog = true
        }
    }

    fun refreshPermissions() {
        allPermissionsGranted = permissionManager.areAllGranted()
        missingPermissions = permissionManager.getMissingPermissions()
        viewModel.updatePermissionsState(allPermissionsGranted)
    }

    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.ANDROID_SETTINGS)
            permissionManager.saveOverlayGranted(Settings.canDrawOverlays(context))
        }

    val mediaLibraryPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            MobileAdsInitializer.initializeIfReady(context)
            setupStep = RecordingFlowPermissionStep.COMPLETE
        }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            permissionManager.saveCameraGranted(granted)
        }

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            permissionManager.saveAudioGranted(granted)
        }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            permissionManager.saveNotificationGranted(granted)
            setupStep = RecordingFlowPermissionStep.MEDIA_LIBRARY
        }

    LaunchedEffect(setupStep) {
        when (setupStep) {
            RecordingFlowPermissionStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    !permissionManager.isNotificationGranted()
                ) {
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    setupStep = RecordingFlowPermissionStep.MEDIA_LIBRARY
                }
            }

            RecordingFlowPermissionStep.MEDIA_LIBRARY -> {
                val mediaPerms = permissionManager.mediaLibraryReadPermissions()
                // it is optional (denial is silently accepted — no core feature depends on it).
                val needsMedia = !permissionManager.isMediaLibraryReadGranted()
                if (!needsMedia) {
                    MobileAdsInitializer.initializeIfReady(context)
                    setupStep = RecordingFlowPermissionStep.COMPLETE
                } else {
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    mediaLibraryPermissionLauncher.launch(mediaPerms)
                }
            }

            RecordingFlowPermissionStep.COMPLETE -> {
                MobileAdsInitializer.initializeIfReady(context)
                permissionManager.markSetupComplete()
                AppOpenAdManager.firstRunPermissionsComplete = true
                AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
                refreshPermissions()
                if (routedRecordingActionActive && !pendingAutoStart && !pendingAutoBufferStart && pendingProStart == null) {
                    clearRoutedRecordingSuppression("permission_setup_complete_no_pending_start")
                }
                setupStep = RecordingFlowPermissionStep.IDLE
            }

            RecordingFlowPermissionStep.IDLE -> { }
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionManager.isSetupComplete() || !permissionManager.areAllGranted()) {
            AppOpenAdManager.firstRunPermissionsComplete = false
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
            setupStep = RecordingFlowPermissionStep.NOTIFICATIONS
        } else {
            AppOpenAdManager.firstRunPermissionsComplete = true
            AppOpenAdSuppressor.clear(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
            MobileAdsInitializer.initializeIfReady(context)
            refreshPermissions()
        }
    }

    // Run after permission bootstrap so we do not call notify() before POST_NOTIFICATIONS can be granted (API 33+).
    LaunchedEffect(
        recordingUiSnapshot.isRecording,
        recordingUiSnapshot.isBuffering,
        recordingUiSnapshot.isPrepared,
        recordingUiSnapshot.isRecordingPaused,
        recordingUiSnapshot.isSaving,
    ) {
        if (Log.isLoggable(RECORDING_FLOW_HOST_LOG, Log.DEBUG)) {
            Log.d(
                RECORDING_FLOW_HOST_LOG,
                "AppControlNotification.refresh: rec=${recordingUiSnapshot.isRecording} buf=${recordingUiSnapshot.isBuffering} " +
                    "prep=${recordingUiSnapshot.isPrepared} paused=${recordingUiSnapshot.isRecordingPaused} saving=${recordingUiSnapshot.isSaving}",
            )
        }
        AppControlNotification.refresh(context.applicationContext)
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    allPermissionsGranted = permissionManager.areAllGranted()
                    missingPermissions = permissionManager.getMissingPermissions()
                    viewModel.updatePermissionsState(allPermissionsGranted)
                    AppOpenAdManager.firstRunPermissionsComplete = permissionManager.isSetupComplete()
                    // Retry after returning from Settings (e.g. user enabled app notifications).
                    AppControlNotification.refresh(context.applicationContext)

                    val activity = context as? Activity
                    consumeRoutedRecordingStart(activity)
                    consumeScreenshotExtra(activity)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaProjectionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                viewModel.startRecordingService(context, result.resultCode, result.data!!)
                if (routedMediaProjectionPending) {
                    clearRoutedRecordingSuppression("media_projection_recording_started")
                }
                (context as? Activity)?.moveTaskToBack(true)
            } else {
                if (routedMediaProjectionPending) {
                    clearRoutedRecordingSuppression("media_projection_recording_denied")
                }
                Toast.makeText(context, toastScreenCaptureDenied, Toast.LENGTH_SHORT).show()
            }
        }

    val bufferProjectionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                viewModel.startBufferService(context, result.resultCode, result.data!!)
                if (routedMediaProjectionPending) {
                    clearRoutedRecordingSuppression("media_projection_buffer_started")
                }
                (context as? Activity)?.moveTaskToBack(true)
            } else {
                if (routedMediaProjectionPending) {
                    clearRoutedRecordingSuppression("media_projection_buffer_denied")
                }
                Toast.makeText(context, toastScreenCaptureDenied, Toast.LENGTH_SHORT).show()
            }
        }

    val screenshotProjectionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // One-shot standalone screenshot: the service creates the MediaProjection from
                // this fresh token, captures a single frame, releases the projection and stops.
                // No prepared session is kept alive — the next screenshot prompts consent again.
                val oneShot =
                    Intent(context, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_TAKE_SCREENSHOT_ONE_SHOT
                        putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                        putExtra(
                            ScreenRecordService.EXTRA_SCREENSHOT_FORMAT,
                            viewModel.screenshotFormat.value,
                        )
                        putExtra(
                            ScreenRecordService.EXTRA_SCREENSHOT_QUALITY,
                            viewModel.screenshotQuality.value,
                        )
                    }
                ContextCompat.startForegroundService(context, oneShot)
            } else {
                Toast.makeText(context, toastScreenCaptureDenied, Toast.LENGTH_SHORT).show()
            }
        }

    /** Runs after the user dismisses the API 31+ BLUETOOTH_CONNECT sheet (grant or deny). */
    var pendingBluetoothContinuation by remember { mutableStateOf<(() -> Unit)?>(null) }

    val bluetoothConnectLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            MobileAdsInitializer.initializeIfReady(context)
            pendingBluetoothContinuation?.invoke()
            pendingBluetoothContinuation = null
        }

    /**
     * API 31+: [BLUETOOTH_CONNECT] is runtime; GMS/AdMob may touch the stack before capture.
     * If already granted (or API 30 or below), runs [action] immediately; otherwise shows the system
     * picker once, then continues (recording is not blocked if the user taps Deny).
     */
    fun gateBluetoothAndRun(action: () -> Unit) {
        action()
    }

    val storagePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            startMediaProjection(
                context,
                mediaProjectionLauncher,
                viewModel.recordingUiSnapshot.value.recordSingleAppEnabled,
            )
        }

    fun checkStorageAndProceed() {
        if (Build.VERSION.SDK_INT <= 28) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startMediaProjection(
                    context,
                    mediaProjectionLauncher,
                    viewModel.recordingUiSnapshot.value.recordSingleAppEnabled,
                )
            }
        } else {
            startMediaProjection(
                context,
                mediaProjectionLauncher,
                viewModel.recordingUiSnapshot.value.recordSingleAppEnabled,
            )
        }
    }

    fun checkAudioAndProceed() {
        val audioSnap = viewModel.recordingUiSnapshot.value
        if ((audioSnap.recordAudio || audioSnap.internalAudio) && !permissionManager.isAudioGranted()) {
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            checkStorageAndProceed()
        }
    }

    fun continueRecordingAfterProGate() {
        gateBluetoothAndRun { checkAudioAndProceed() }
    }

    fun continueBufferAfterProGate() {
        gateBluetoothAndRun {
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
            bufferProjectionLauncher.launch(
                MediaProjectionIntents.createScreenCaptureIntent(
                    context,
                    viewModel.recordingUiSnapshot.value.recordSingleAppEnabled,
                ),
            )
        }
    }

    fun startPreparedServiceFromRoutedGate(asBuffer: Boolean) {
        val action =
            if (asBuffer) {
                ScreenRecordService.ACTION_START_BUFFER_FROM_OVERLAY
            } else {
                ScreenRecordService.ACTION_START_FROM_OVERLAY
            }
        Log.d(
            RECORDING_FLOW_HOST_LOG,
            "pending recording start resumed through prepared service action=$action",
        )
        ContextCompat.startForegroundService(
            context,
            Intent(context, ScreenRecordService::class.java).apply { this.action = action },
        )
        clearRoutedRecordingSuppression("prepared_service_start_requested")
    }

    fun continueStartAfterProGate(pendingStart: PendingProRecordingStart) {
        when (pendingStart) {
            PendingProRecordingStart.RECORDING -> continueRecordingAfterProGate()
            PendingProRecordingStart.BUFFER -> continueBufferAfterProGate()
            PendingProRecordingStart.ROUTED_RECORDING -> {
                if (viewModel.recordingUiSnapshot.value.isPrepared) {
                    startPreparedServiceFromRoutedGate(asBuffer = false)
                } else {
                    routedMediaProjectionPending = true
                    continueRecordingAfterProGate()
                }
            }
            PendingProRecordingStart.ROUTED_BUFFER -> {
                if (viewModel.recordingUiSnapshot.value.isPrepared) {
                    startPreparedServiceFromRoutedGate(asBuffer = true)
                } else {
                    routedMediaProjectionPending = true
                    continueBufferAfterProGate()
                }
            }
        }
    }

    fun requestProUnlockForStart(
        features: List<ProFeature>,
        pendingStart: PendingProRecordingStart,
    ) {
        if (showProRecordingDialog) {
            Log.d(
                RECORDING_FLOW_HOST_LOG,
                "Pro dialog already showing; ignoring duplicate start request pending=$pendingStart features=${features.joinToString(",") { it.logName }}",
            )
            return
        }
        pendingProStart = pendingStart
        pendingProRecordingFeatures = features
        showProRecordingDialog = true
    }

    fun startRecordingFlow(pendingStart: PendingProRecordingStart = PendingProRecordingStart.RECORDING) {
        val source =
            if (pendingStart == PendingProRecordingStart.ROUTED_RECORDING) {
                "routed_recording"
            } else {
                "main_recording"
            }
        Log.d(RECORDING_FLOW_HOST_LOG, "recording start requested source=$source")
        if (!allPermissionsGranted) {
            showPermissionDialog = true
        } else {
            coroutineScope.launch {
                when (val gate = viewModel.checkRecordingProGate(source)) {
                    RecordingStartProGateResult.Allowed -> continueStartAfterProGate(pendingStart)
                    is RecordingStartProGateResult.BlockedNeedsPro -> {
                        requestProUnlockForStart(
                            gate.features.map { it.toUiProFeature() },
                            pendingStart,
                        )
                    }
                }
            }
        }
    }

    fun startBufferFlow(pendingStart: PendingProRecordingStart = PendingProRecordingStart.BUFFER) {
        val source =
            if (pendingStart == PendingProRecordingStart.ROUTED_BUFFER) {
                "routed_buffer"
            } else {
                "main_buffer"
            }
        Log.d(RECORDING_FLOW_HOST_LOG, "recording start requested source=$source")
        if (!allPermissionsGranted) {
            showPermissionDialog = true
        } else {
            coroutineScope.launch {
                when (val gate = viewModel.checkBufferProGate(source)) {
                    RecordingStartProGateResult.Allowed -> continueStartAfterProGate(pendingStart)
                    is RecordingStartProGateResult.BlockedNeedsPro -> {
                        requestProUnlockForStart(
                            gate.features.map { it.toUiProFeature() },
                            pendingStart,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(pendingAutoStart, allPermissionsGranted, setupStep) {
        if (pendingAutoStart && allPermissionsGranted && setupStep == RecordingFlowPermissionStep.IDLE) {
            pendingAutoStart = false
            startRecordingFlow(PendingProRecordingStart.ROUTED_RECORDING)
        }
    }

    LaunchedEffect(pendingAutoBufferStart, allPermissionsGranted, setupStep) {
        if (pendingAutoBufferStart && allPermissionsGranted && setupStep == RecordingFlowPermissionStep.IDLE) {
            pendingAutoBufferStart = false
            startBufferFlow(PendingProRecordingStart.ROUTED_BUFFER)
        }
    }

    LaunchedEffect(adsDisabledForResume) {
        val pendingStart = pendingProStart
        if (adsDisabledForResume && showProRecordingDialog && pendingStart != null) {
            showProRecordingDialog = false
            pendingProStart = null
            Log.d(
                RECORDING_FLOW_HOST_LOG,
                "pending recording start resumed after Remove Ads entitlement pending=$pendingStart features=${pendingProRecordingFeatures.joinToString(",") { it.logName }}",
            )
            continueStartAfterProGate(pendingStart)
        }
    }

    LaunchedEffect(pendingScreenshotProjection, allPermissionsGranted, setupStep) {
        if (pendingScreenshotProjection && allPermissionsGranted && setupStep == RecordingFlowPermissionStep.IDLE) {
            pendingScreenshotProjection = false
            gateBluetoothAndRun {
                startMediaProjection(
                    context,
                    screenshotProjectionLauncher,
                    viewModel.recordingUiSnapshot.value.recordSingleAppEnabled,
                )
            }
        }
    }

    if (showPermissionDialog && missingPermissions.isNotEmpty()) {
        PermissionRationaleDialog(
            missingPermissions = missingPermissions,
            onGrantNow = {
                setupStep = RecordingFlowPermissionStep.NOTIFICATIONS
            },
            onDismiss = {
                showPermissionDialog = false
                clearRoutedRecordingSuppression("permission_dialog_dismissed")
            },
        )
    }

    if (showBatteryRationaleDialog) {
        BatteryOptimizationRationaleDialog(
            onDismiss = { showBatteryRationaleDialog = false },
        )
    }

    if (showProRecordingDialog) {
        ProUnlockDialog(
            title = stringResource(R.string.pro_recording_title),
            body = stringResource(R.string.pro_recording_body),
            watchButtonText = stringResource(R.string.pro_unlock_watch_ad),
            showRemoveAds = true,
            triggeredFeatures = pendingProRecordingFeatures,
            onUnlocked = {
                val pendingStart = pendingProStart
                showProRecordingDialog = false
                pendingProStart = null
                Log.d(
                    RECORDING_FLOW_HOST_LOG,
                    "pending recording start resumed after reward/purchase pending=$pendingStart features=${pendingProRecordingFeatures.joinToString(",") { it.logName }}",
                )
                continueStartAfterProGate(pendingStart ?: PendingProRecordingStart.RECORDING)
            },
            onRemoveAds = {
                val activity = context as? Activity
                if (activity != null) {
                    Log.d(RECORDING_FLOW_HOST_LOG, "Remove Ads launched from recording Pro gate")
                    billing.launchRemoveAdsPurchase(activity)
                }
            },
            onDismiss = {
                Log.d(
                    RECORDING_FLOW_HOST_LOG,
                    "recording start canceled/dismissed pending=$pendingProStart features=${pendingProRecordingFeatures.joinToString(",") { it.logName }}",
                )
                showProRecordingDialog = false
                pendingProStart = null
                clearRoutedRecordingSuppression("pro_dialog_dismissed")
            },
        )
    }

    val fabRecordingControl: () -> Unit = fab@{
        val s: RecordingUiSnapshot = recordingUiSnapshot
        if (s.isSaving) return@fab
        when {
            s.isRecording -> viewModel.stopRecordingService(context)
            s.isBuffering -> viewModel.stopBufferService(context)
            s.captureMode == CaptureMode.CLIPPER -> startBufferFlow()
            else -> startRecordingFlow()
        }
    }

    CompositionLocalProvider(LocalFabRecordingControl provides fabRecordingControl) {
        content()
    }
}

private fun startMediaProjection(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    recordSingleAppEnabled: Boolean,
) {
    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
    launcher.launch(MediaProjectionIntents.createScreenCaptureIntent(context, recordSingleAppEnabled))
}

private fun ProRecordingFeature.toUiProFeature(): ProFeature =
    when (this) {
        ProRecordingFeature.RECORDING_120_FPS -> ProFeature.RECORDING_120_FPS
        ProRecordingFeature.SEPARATE_AUDIO_TRACKS -> ProFeature.SEPARATE_AUDIO_TRACKS
        ProRecordingFeature.CAMERA_OVERLAY -> ProFeature.CAMERA_OVERLAY
        ProRecordingFeature.WATERMARK -> ProFeature.WATERMARK
    }

@Composable
private fun PermissionRationaleDialog(
    missingPermissions: List<PermissionInfo>,
    onGrantNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalAccentColor.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null, tint = accent) },
        title = { Text(stringResource(R.string.perm_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.perm_rationale_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                missingPermissions.forEach { perm ->
                    Row(
                        modifier = Modifier.padding(bottom = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = accent,
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .padding(top = 1.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = perm.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = perm.rationale,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onGrantNow) { Text(stringResource(R.string.action_grant_now)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
        },
    )
}
