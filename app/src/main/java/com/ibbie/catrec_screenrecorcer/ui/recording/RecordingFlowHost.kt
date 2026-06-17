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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.CatRecApplication
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdManager
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressionReason
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressor
import com.ibbie.catrec_screenrecorcer.ads.MobileAdsInitializer
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.RecordingUiSnapshot
import com.ibbie.catrec_screenrecorcer.data.recording.ProRecordingFeature
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingStartProGateResult
import com.ibbie.catrec_screenrecorcer.service.AppControlNotification
import com.ibbie.catrec_screenrecorcer.service.ScreenRecordService
import com.ibbie.catrec_screenrecorcer.ui.components.ProFeature
import com.ibbie.catrec_screenrecorcer.ui.components.ProUnlockDialog
import com.ibbie.catrec_screenrecorcer.ui.settings.BatteryOptimizationRationaleDialog
import com.ibbie.catrec_screenrecorcer.util.MediaProjectionIntents
import com.ibbie.catrec_screenrecorcer.utils.BatteryOptimizationHelper
import com.ibbie.catrec_screenrecorcer.utils.PermissionManager
import com.ibbie.catrec_screenrecorcer.utils.StartupPermission
import kotlinx.coroutines.launch

private const val RECORDING_FLOW_HOST_LOG = "RecordingFlowHost"

private enum class RecordingFlowPermissionStep {
    IDLE,
    NOTIFICATIONS,
    MEDIA_LIBRARY,

    /** READ_MEDIA_AUDIO when API 33+; skipped otherwise (legacy storage step already covers audio on older APIs). */
    MEDIA_AUDIO,

    /** BLUETOOTH_CONNECT when API 31+; skipped on older APIs. */
    NEARBY_DEVICES,
    COMPLETE,
}

private fun StartupPermission.toRecordingFlowStep(): RecordingFlowPermissionStep =
    when (this) {
        StartupPermission.NOTIFICATIONS -> RecordingFlowPermissionStep.NOTIFICATIONS
        StartupPermission.MEDIA_LIBRARY -> RecordingFlowPermissionStep.MEDIA_LIBRARY
        StartupPermission.MEDIA_AUDIO -> RecordingFlowPermissionStep.MEDIA_AUDIO
        StartupPermission.NEARBY_DEVICES -> RecordingFlowPermissionStep.NEARBY_DEVICES
    }

internal fun safeLaunchStartupRuntimePermission(
    permissionName: String,
    flowStepName: String,
    optional: Boolean,
    launch: () -> Unit,
    onOptionalLaunchFailed: () -> Unit,
    onMandatoryLaunchFailed: () -> Unit,
    reportNonFatal: (Throwable) -> Unit = { throwable ->
        val crash = FirebaseCrashlytics.getInstance()
        crash.setCustomKey("startup_perm_launch_perm", permissionName)
        crash.setCustomKey("startup_perm_launch_step", flowStepName)
        crash.setCustomKey("startup_perm_launch_optional", optional.toString())
        crash.setCustomKey("startup_perm_launch_sdk", Build.VERSION.SDK_INT.toString())
        crash.setCustomKey("startup_perm_launch_mfg", Build.MANUFACTURER.orEmpty())
        crash.setCustomKey("startup_perm_launch_model", Build.MODEL.orEmpty())
        crash.recordException(
            IllegalStateException(
                "Runtime permission launcher failed permission=$permissionName step=$flowStepName optional=$optional",
                throwable,
            ),
        )
    },
): Boolean =
    try {
        launch()
        true
    } catch (t: Exception) {
        reportNonFatal(t)
        if (optional) {
            onOptionalLaunchFailed()
        } else {
            onMandatoryLaunchFailed()
        }
        false
    }

private enum class PendingProRecordingStart {
    RECORDING,
    BUFFER,
    ROUTED_RECORDING,
    ROUTED_BUFFER,
}

/** Next step after the runtime RECORD_AUDIO dialog finishes (grant or deny) for host-driven starts. */
private sealed class PendingAfterRecordAudio {
    data object FullRecording : PendingAfterRecordAudio()

    data object RollingBuffer : PendingAfterRecordAudio()
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
    val mainActivity = context as? MainActivity

    var allPermissionsGranted by remember { mutableStateOf(permissionManager.areAllGranted()) }
    var setupStep by remember { mutableStateOf(RecordingFlowPermissionStep.IDLE) }
    var startupPermissionFlowActive by remember { mutableStateOf(false) }
    var pendingStartupPermissionHealthCheck by remember { mutableStateOf(false) }
    var appOpenAdDecisionGeneration by remember(mainActivity) {
        mutableStateOf(mainActivity?.currentAppOpenDecisionGeneration() ?: 0)
    }

    var pendingAutoStart by remember { mutableStateOf(false) }
    var pendingAutoBufferStart by remember { mutableStateOf(false) }
    var pendingScreenshotProjection by remember { mutableStateOf(false) }
    var showBatteryRationaleDialog by remember { mutableStateOf(false) }
    var showProRecordingDialog by remember { mutableStateOf(false) }
    var pendingProRecordingFeatures by remember { mutableStateOf<List<ProFeature>>(emptyList()) }
    var pendingProStart by remember { mutableStateOf<PendingProRecordingStart?>(null) }
    var routedRecordingActionActive by remember { mutableStateOf(false) }
    var routedMediaProjectionPending by remember { mutableStateOf(false) }
    var pendingAfterRecordAudio by remember { mutableStateOf<PendingAfterRecordAudio?>(null) }
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
        viewModel.updatePermissionsState(allPermissionsGranted)
    }

    fun canAskAgain(permission: StartupPermission): Boolean {
        val activity = context as? Activity ?: return true
        val runtimePermissions = permissionManager.runtimePermissionsForStartupPermission(permission)
        if (runtimePermissions.isEmpty()) return false
        return runtimePermissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }

    fun recordStartupPermissionResult(permission: StartupPermission) {
        permissionManager.recordStartupPermissionDialogResult(
            permission = permission,
            granted = permissionManager.isStartupPermissionGranted(permission),
            canAskAgain = canAskAgain(permission),
        )
    }

    fun requestableMissingStartupPermissions(): List<StartupPermission> {
        permissionManager.clearRequestBlocksForGrantedStartupPermissions()
        return permissionManager
            .getMissingStartupPermissions()
            .filterNot { permissionManager.isStartupPermissionRequestBlocked(it) }
    }

    fun settleStartupPermissionFlowWithoutDialog() {
        MobileAdsInitializer.initializeIfReady(context)
        permissionManager.markStartupPermissionFlowComplete()
        AppOpenAdManager.firstRunPermissionsComplete = true
        AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
        startupPermissionFlowActive = false
        refreshPermissions()
    }

    fun runPendingStartupPermissionHealthCheck(trigger: String) {
        if (!pendingStartupPermissionHealthCheck) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (startupPermissionFlowActive || setupStep != RecordingFlowPermissionStep.IDLE) return

        val flowComplete = permissionManager.isStartupPermissionFlowComplete()
        val requestableMissing = requestableMissingStartupPermissions()
        if (flowComplete && requestableMissing.isEmpty()) {
            pendingStartupPermissionHealthCheck = false
            AppOpenAdManager.firstRunPermissionsComplete = true
            AppOpenAdSuppressor.clear(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
            MobileAdsInitializer.initializeIfReady(context)
            refreshPermissions()
            return
        }

        if (requestableMissing.isEmpty()) {
            pendingStartupPermissionHealthCheck = false
            settleStartupPermissionFlowWithoutDialog()
            return
        }

        pendingStartupPermissionHealthCheck = false
        startupPermissionFlowActive = true
        Log.d(
            RECORDING_FLOW_HOST_LOG,
            "startup permission health check launching trigger=$trigger missing=${requestableMissing.joinToString(",")}",
        )
        AppOpenAdManager.firstRunPermissionsComplete = false
        AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
        refreshPermissions()
        setupStep = requestableMissing.first().toRecordingFlowStep()
    }

    fun queueStartupPermissionHealthCheck(trigger: String) {
        pendingStartupPermissionHealthCheck = true
        runPendingStartupPermissionHealthCheck(trigger)
    }

    DisposableEffect(mainActivity) {
        val removeListener =
            mainActivity?.addAppOpenDecisionCompleteListener {
                appOpenAdDecisionGeneration = mainActivity.currentAppOpenDecisionGeneration()
            }
        onDispose { removeListener?.invoke() }
    }

    LaunchedEffect(appOpenAdDecisionGeneration) {
        if (appOpenAdDecisionGeneration > 0) {
            queueStartupPermissionHealthCheck("app_open_decision")
        }
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
            recordStartupPermissionResult(StartupPermission.MEDIA_LIBRARY)
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            MobileAdsInitializer.initializeIfReady(context)
            setupStep = RecordingFlowPermissionStep.MEDIA_AUDIO
        }

    val mediaAudioReadPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            recordStartupPermissionResult(StartupPermission.MEDIA_AUDIO)
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            MobileAdsInitializer.initializeIfReady(context)
            setupStep = RecordingFlowPermissionStep.NEARBY_DEVICES
        }

    val nearbyDevicesPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            recordStartupPermissionResult(StartupPermission.NEARBY_DEVICES)
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

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            permissionManager.saveNotificationGranted(granted)
            recordStartupPermissionResult(StartupPermission.NOTIFICATIONS)
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            setupStep = RecordingFlowPermissionStep.MEDIA_LIBRARY
        }

    fun <I> launchStartupPermissionSafely(
        launcher: ActivityResultLauncher<I>,
        input: I,
        startupPermission: StartupPermission,
        permissionName: String,
        optional: Boolean,
        nextStep: RecordingFlowPermissionStep,
    ) {
        val launched =
            safeLaunchStartupRuntimePermission(
                permissionName = permissionName,
                flowStepName = startupPermission.toRecordingFlowStep().name,
                optional = optional,
                launch = { launcher.launch(input) },
                onOptionalLaunchFailed = {
                    permissionManager.markStartupPermissionRequestBlocked(startupPermission)
                    AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    setupStep = nextStep
                },
                onMandatoryLaunchFailed = {
                    AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    pendingStartupPermissionHealthCheck = false
                    startupPermissionFlowActive = false
                    setupStep = RecordingFlowPermissionStep.IDLE
                },
            )
        if (!launched) {
            Log.w(
                RECORDING_FLOW_HOST_LOG,
                "Runtime permission launcher failed permission=$permissionName step=${startupPermission.toRecordingFlowStep()} optional=$optional",
            )
        }
    }

    LaunchedEffect(setupStep) {
        when (setupStep) {
            RecordingFlowPermissionStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    !permissionManager.isNotificationGranted() &&
                    !permissionManager.isStartupPermissionRequestBlocked(StartupPermission.NOTIFICATIONS)
                ) {
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    launchStartupPermissionSafely(
                        launcher = notificationPermissionLauncher,
                        input = Manifest.permission.POST_NOTIFICATIONS,
                        startupPermission = StartupPermission.NOTIFICATIONS,
                        permissionName = Manifest.permission.POST_NOTIFICATIONS,
                        optional = false,
                        nextStep = RecordingFlowPermissionStep.MEDIA_LIBRARY,
                    )
                } else {
                    setupStep = RecordingFlowPermissionStep.MEDIA_LIBRARY
                }
            }

            RecordingFlowPermissionStep.MEDIA_LIBRARY -> {
                val mediaPerms = permissionManager.mediaLibraryReadPermissions()
                // Optional (denial is accepted — recordings tab may be limited).
                val needsMedia = !permissionManager.isMediaLibraryReadGranted()
                if (!needsMedia ||
                    permissionManager.isStartupPermissionRequestBlocked(StartupPermission.MEDIA_LIBRARY)
                ) {
                    setupStep = RecordingFlowPermissionStep.MEDIA_AUDIO
                } else {
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    launchStartupPermissionSafely(
                        launcher = mediaLibraryPermissionLauncher,
                        input = mediaPerms,
                        startupPermission = StartupPermission.MEDIA_LIBRARY,
                        permissionName = mediaPerms.joinToString("|"),
                        optional = true,
                        nextStep = RecordingFlowPermissionStep.MEDIA_AUDIO,
                    )
                }
            }

            RecordingFlowPermissionStep.MEDIA_AUDIO -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    !permissionManager.isMediaAudioReadGranted() &&
                    !permissionManager.isStartupPermissionRequestBlocked(StartupPermission.MEDIA_AUDIO)
                ) {
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    launchStartupPermissionSafely(
                        launcher = mediaAudioReadPermissionLauncher,
                        input = Manifest.permission.READ_MEDIA_AUDIO,
                        startupPermission = StartupPermission.MEDIA_AUDIO,
                        permissionName = Manifest.permission.READ_MEDIA_AUDIO,
                        optional = true,
                        nextStep = RecordingFlowPermissionStep.NEARBY_DEVICES,
                    )
                } else {
                    setupStep = RecordingFlowPermissionStep.NEARBY_DEVICES
                }
            }

            RecordingFlowPermissionStep.NEARBY_DEVICES -> {
                if (Build.VERSION.SDK_INT >= 31 &&
                    !permissionManager.isBluetoothConnectGranted() &&
                    !permissionManager.isStartupPermissionRequestBlocked(StartupPermission.NEARBY_DEVICES)
                ) {
                    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                    launchStartupPermissionSafely(
                        launcher = nearbyDevicesPermissionLauncher,
                        input = Manifest.permission.BLUETOOTH_CONNECT,
                        startupPermission = StartupPermission.NEARBY_DEVICES,
                        permissionName = Manifest.permission.BLUETOOTH_CONNECT,
                        optional = true,
                        nextStep = RecordingFlowPermissionStep.COMPLETE,
                    )
                } else {
                    setupStep = RecordingFlowPermissionStep.COMPLETE
                }
            }

            RecordingFlowPermissionStep.COMPLETE -> {
                MobileAdsInitializer.initializeIfReady(context)
                permissionManager.markStartupPermissionFlowComplete()
                AppOpenAdManager.firstRunPermissionsComplete = true
                AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
                refreshPermissions()
                if (routedRecordingActionActive && !pendingAutoStart && !pendingAutoBufferStart && pendingProStart == null) {
                    clearRoutedRecordingSuppression("permission_setup_complete_no_pending_start")
                }
                pendingStartupPermissionHealthCheck = false
                startupPermissionFlowActive = false
                setupStep = RecordingFlowPermissionStep.IDLE
            }

            RecordingFlowPermissionStep.IDLE -> { }
        }
    }

    LaunchedEffect(Unit) {
        refreshPermissions()
        AppOpenAdManager.firstRunPermissionsComplete = permissionManager.isStartupPermissionFlowComplete()
        if (AppOpenAdManager.firstRunPermissionsComplete) {
            AppOpenAdSuppressor.clear(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
            MobileAdsInitializer.initializeIfReady(context)
        } else {
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
        }
    }

    // Run after permission bootstrap so we do not call notify() before POST_NOTIFICATIONS can be granted (API 33+).
    LaunchedEffect(
        recordingUiSnapshot.isRecording,
        recordingUiSnapshot.isBuffering,
        recordingUiSnapshot.isRecordingPaused,
        recordingUiSnapshot.isSaving,
    ) {
        if (Log.isLoggable(RECORDING_FLOW_HOST_LOG, Log.DEBUG)) {
            Log.d(
                RECORDING_FLOW_HOST_LOG,
                "AppControlNotification.refresh: rec=${recordingUiSnapshot.isRecording} buf=${recordingUiSnapshot.isBuffering} " +
                    "paused=${recordingUiSnapshot.isRecordingPaused} saving=${recordingUiSnapshot.isSaving}",
            )
        }
        AppControlNotification.refresh(context.applicationContext)
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    allPermissionsGranted = permissionManager.areAllGranted()
                    viewModel.updatePermissionsState(allPermissionsGranted)
                    AppOpenAdManager.firstRunPermissionsComplete =
                        permissionManager.isStartupPermissionFlowComplete() &&
                        !startupPermissionFlowActive &&
                        setupStep == RecordingFlowPermissionStep.IDLE
                    // Retry after returning from Settings (e.g. user enabled app notifications).
                    AppControlNotification.refresh(context.applicationContext)

                    val activity = context as? Activity
                    consumeRoutedRecordingStart(activity)
                    consumeScreenshotExtra(activity)
                    runPendingStartupPermissionHealthCheck("on_resume")
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
                val started = viewModel.startRecordingService(context, result.resultCode, result.data!!)
                if (routedMediaProjectionPending) {
                    clearRoutedRecordingSuppression(
                        if (started) {
                            "media_projection_recording_started"
                        } else {
                            "media_projection_recording_start_blocked"
                        },
                    )
                }
                if (started) {
                    (context as? Activity)?.moveTaskToBack(true)
                }
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
                val started = viewModel.startBufferService(context, result.resultCode, result.data!!)
                if (routedMediaProjectionPending) {
                    clearRoutedRecordingSuppression(
                        if (started) {
                            "media_projection_buffer_started"
                        } else {
                            "media_projection_buffer_start_blocked"
                        },
                    )
                }
                if (started) {
                    (context as? Activity)?.moveTaskToBack(true)
                }
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

    /**
     * Bluetooth is requested once in [RecordingFlowPermissionStep.NEARBY_DEVICES] during initial setup.
     * Prompting again here would duplicate dialogs; recording does not depend on Bluetooth.
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
                )
            }
        } else {
            startMediaProjection(
                context,
                mediaProjectionLauncher,
            )
        }
    }

    fun launchBufferProjectionForFlow() {
        AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
        bufferProjectionLauncher.launch(
            MediaProjectionIntents.createScreenCaptureIntent(context),
        )
    }

    fun resumeAfterRecordAudioPermission(granted: Boolean) {
        val pending = pendingAfterRecordAudio ?: return
        pendingAfterRecordAudio = null
        if (!granted) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.toast_audio_permission_required_recording_not_started),
                    Toast.LENGTH_LONG,
                ).show()
            clearRoutedRecordingSuppression("record_audio_denied")
            return
        }
        when (pending) {
            PendingAfterRecordAudio.FullRecording -> checkStorageAndProceed()
            PendingAfterRecordAudio.RollingBuffer -> launchBufferProjectionForFlow()
        }
    }

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            permissionManager.saveAudioGranted(granted)
            resumeAfterRecordAudioPermission(granted)
        }

    fun checkAudioAndProceed() {
        val audioSnap = viewModel.recordingUiSnapshot.value
        if ((audioSnap.recordAudio || audioSnap.internalAudio) && !permissionManager.isAudioGranted()) {
            pendingAfterRecordAudio = PendingAfterRecordAudio.FullRecording
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            pendingAfterRecordAudio = null
            checkStorageAndProceed()
        }
    }

    fun continueRecordingAfterProGate() {
        gateBluetoothAndRun { checkAudioAndProceed() }
    }

    fun continueBufferAfterProGate() {
        gateBluetoothAndRun {
            val audioSnap = viewModel.recordingUiSnapshot.value
            if ((audioSnap.recordAudio || audioSnap.internalAudio) && !permissionManager.isAudioGranted()) {
                pendingAfterRecordAudio = PendingAfterRecordAudio.RollingBuffer
                AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.RUNTIME_PERMISSION_REQUEST)
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                pendingAfterRecordAudio = null
                launchBufferProjectionForFlow()
            }
        }
    }

    fun continueStartAfterProGate(pendingStart: PendingProRecordingStart) {
        when (pendingStart) {
            PendingProRecordingStart.RECORDING -> continueRecordingAfterProGate()
            PendingProRecordingStart.BUFFER -> continueBufferAfterProGate()
            PendingProRecordingStart.ROUTED_RECORDING -> {
                routedMediaProjectionPending = true
                continueRecordingAfterProGate()
            }
            PendingProRecordingStart.ROUTED_BUFFER -> {
                routedMediaProjectionPending = true
                continueBufferAfterProGate()
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
                "Pro dialog already showing; ignoring duplicate start request pending=$pendingStart features=${features.joinToString(
                    ",",
                ) { it.logName }}",
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
            queueStartupPermissionHealthCheck("recording_start_missing_startup_permission")
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
            queueStartupPermissionHealthCheck("buffer_start_missing_startup_permission")
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
                "pending recording start resumed after Remove Ads entitlement pending=$pendingStart features=${pendingProRecordingFeatures.joinToString(
                    ",",
                ) { it.logName }}",
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
                )
            }
        }
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
                    "pending recording start resumed after reward/purchase pending=$pendingStart features=${pendingProRecordingFeatures.joinToString(
                        ",",
                    ) { it.logName }}",
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
                    "recording start canceled/dismissed pending=$pendingProStart features=${pendingProRecordingFeatures.joinToString(
                        ",",
                    ) { it.logName }}",
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
) {
    AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.MEDIA_PROJECTION)
    launcher.launch(MediaProjectionIntents.createScreenCaptureIntent(context))
}

private fun ProRecordingFeature.toUiProFeature(): ProFeature =
    when (this) {
        ProRecordingFeature.RECORDING_HIGH_FPS -> ProFeature.RECORDING_HIGH_FPS
        ProRecordingFeature.HIGH_BITRATE -> ProFeature.HIGH_BITRATE
        ProRecordingFeature.SEPARATE_AUDIO_TRACKS -> ProFeature.SEPARATE_AUDIO_TRACKS
        ProRecordingFeature.CAMERA_OVERLAY -> ProFeature.CAMERA_OVERLAY
        ProRecordingFeature.WATERMARK -> ProFeature.WATERMARK
    }
