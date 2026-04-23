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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.RecordingUiSnapshot
import com.ibbie.catrec_screenrecorcer.ads.MobileAdsInitializer
import com.ibbie.catrec_screenrecorcer.service.AppControlNotification
import com.ibbie.catrec_screenrecorcer.service.ScreenRecordService
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.util.MediaProjectionIntents
import com.ibbie.catrec_screenrecorcer.utils.PermissionInfo
import com.ibbie.catrec_screenrecorcer.utils.PermissionManager
import androidx.core.net.toUri

private const val RECORDING_FLOW_HOST_LOG = "RecordingFlowHost"

private enum class RecordingFlowPermissionStep {
    IDLE,
    NOTIFICATIONS,
    AUDIO,
    CAMERA,
    OVERLAY,
    MEDIA_LIBRARY,
    COMPLETE,
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
    val betaFeedbackEmailSubject = stringResource(R.string.beta_feedback_email_subject)
    val toastNoEmailApp = stringResource(R.string.toast_no_email_app)
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionManager = remember { PermissionManager(context) }
    val accent = LocalAccentColor.current

    var allPermissionsGranted by remember { mutableStateOf(permissionManager.areAllGranted()) }
    var missingPermissions by remember { mutableStateOf(permissionManager.getMissingPermissions()) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var setupStep by remember { mutableStateOf(RecordingFlowPermissionStep.IDLE) }

    var pendingAutoStart by remember { mutableStateOf(false) }
    var pendingScreenshotProjection by remember { mutableStateOf(false) }

    fun consumeScreenshotExtra(activity: Activity?) {
        val a = activity ?: return
        if (a.intent.getBooleanExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT_PROJECTION, false)) {
            a.intent.removeExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT_PROJECTION)
            pendingScreenshotProjection = true
        }
    }

    LaunchedEffect(Unit) {
        val activity = context as? Activity
        if (activity?.intent?.action == "ACTION_START_RECORDING_FROM_OVERLAY") {
            activity.intent.action = null
            pendingAutoStart = true
        }
        consumeScreenshotExtra(activity)
    }

    var showBetaNotice by remember { mutableStateOf(false) }
    LaunchedEffect(allPermissionsGranted) {
        if (!allPermissionsGranted) {
            showBetaNotice = false
            return@LaunchedEffect
        }
        // Idle controls notification: post after POST_NOTIFICATIONS (or Settings) allows it.
        AppControlNotification.refresh(context.applicationContext)
        val alreadyShown = viewModel.betaNoticePersistedValue()
        showBetaNotice = !alreadyShown
    }

    fun refreshPermissions() {
        allPermissionsGranted = permissionManager.areAllGranted()
        missingPermissions = permissionManager.getMissingPermissions()
        viewModel.updatePermissionsState(allPermissionsGranted)
        if (allPermissionsGranted) permissionManager.markSetupComplete()
    }

    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            permissionManager.saveOverlayGranted(Settings.canDrawOverlays(context))
            setupStep = RecordingFlowPermissionStep.MEDIA_LIBRARY
        }

    val mediaLibraryPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            MobileAdsInitializer.initializeIfReady(context)
            setupStep = RecordingFlowPermissionStep.COMPLETE
        }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            permissionManager.saveCameraGranted(granted)
            setupStep = RecordingFlowPermissionStep.OVERLAY
        }

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            permissionManager.saveAudioGranted(granted)
            setupStep = RecordingFlowPermissionStep.CAMERA
        }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            permissionManager.saveNotificationGranted(granted)
            setupStep = RecordingFlowPermissionStep.AUDIO
        }

    LaunchedEffect(setupStep) {
        when (setupStep) {
            RecordingFlowPermissionStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    !permissionManager.isNotificationGranted()
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    setupStep = RecordingFlowPermissionStep.AUDIO
                }
            }

            RecordingFlowPermissionStep.AUDIO -> {
                if (!permissionManager.isAudioGranted()) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    setupStep = RecordingFlowPermissionStep.CAMERA
                }
            }

            RecordingFlowPermissionStep.CAMERA -> {
                if (!permissionManager.isCameraGranted()) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    setupStep = RecordingFlowPermissionStep.OVERLAY
                }
            }

            RecordingFlowPermissionStep.OVERLAY -> {
                if (!permissionManager.isOverlayGranted()) {
                    val intent =
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri(),
                        )
                    overlayPermissionLauncher.launch(intent)
                } else {
                    setupStep = RecordingFlowPermissionStep.MEDIA_LIBRARY
                }
            }

            RecordingFlowPermissionStep.MEDIA_LIBRARY -> {
                val mediaPerms = permissionManager.mediaLibraryReadPermissions()
                // Also bundle BLUETOOTH_CONNECT (API 31+) so it is granted in the same pass;
                // it is optional (denial is silently accepted — no core feature depends on it).
                val btPerms = permissionManager.bluetoothPermissions()
                val needsMedia = !permissionManager.isMediaLibraryReadGranted()
                val needsBt =
                    Build.VERSION.SDK_INT >= 31 && !permissionManager.isBluetoothConnectGranted()
                if (!needsMedia && !needsBt) {
                    MobileAdsInitializer.initializeIfReady(context)
                    setupStep = RecordingFlowPermissionStep.COMPLETE
                } else {
                    val toRequest =
                        buildList {
                            if (needsMedia) addAll(mediaPerms)
                            if (needsBt) addAll(btPerms)
                        }.distinct()
                    if (toRequest.isEmpty()) {
                        MobileAdsInitializer.initializeIfReady(context)
                        setupStep = RecordingFlowPermissionStep.COMPLETE
                    } else {
                        mediaLibraryPermissionLauncher.launch(toRequest.toTypedArray())
                    }
                }
            }

            RecordingFlowPermissionStep.COMPLETE -> {
                MobileAdsInitializer.initializeIfReady(context)
                refreshPermissions()
                setupStep = RecordingFlowPermissionStep.IDLE
            }

            RecordingFlowPermissionStep.IDLE -> { }
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionManager.isSetupComplete() || !permissionManager.areAllGranted()) {
            setupStep = RecordingFlowPermissionStep.NOTIFICATIONS
        } else {
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
                    if (allPermissionsGranted) permissionManager.markSetupComplete()
                    // Retry after returning from Settings (e.g. user enabled app notifications).
                    AppControlNotification.refresh(context.applicationContext)

                    val activity = context as? Activity
                    if (activity?.intent?.action == "ACTION_START_RECORDING_FROM_OVERLAY") {
                        activity.intent.action = null
                        pendingAutoStart = true
                    }
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
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                viewModel.startRecordingService(context, result.resultCode, result.data!!)
                (context as? Activity)?.moveTaskToBack(true)
            } else {
                Toast.makeText(context, toastScreenCaptureDenied, Toast.LENGTH_SHORT).show()
            }
        }

    val bufferProjectionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                viewModel.startBufferService(context, result.resultCode, result.data!!)
                (context as? Activity)?.moveTaskToBack(true)
            } else {
                Toast.makeText(context, toastScreenCaptureDenied, Toast.LENGTH_SHORT).show()
            }
        }

    val screenshotProjectionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
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
        if (Build.VERSION.SDK_INT < 31 || permissionManager.isBluetoothConnectGranted()) {
            action()
        } else {
            pendingBluetoothContinuation = action
            bluetoothConnectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun startBufferFlow() {
        gateBluetoothAndRun {
            bufferProjectionLauncher.launch(
                MediaProjectionIntents.createScreenCaptureIntent(
                    context,
                    viewModel.recordingUiSnapshot.value.recordSingleAppEnabled,
                ),
            )
        }
    }

    val storagePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
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
            showPermissionDialog = true
        } else {
            checkStorageAndProceed()
        }
    }

    val startRecordingFlow: () -> Unit = {
        if (!allPermissionsGranted) {
            showPermissionDialog = true
        } else {
            gateBluetoothAndRun { checkAudioAndProceed() }
        }
    }

    LaunchedEffect(pendingAutoStart, allPermissionsGranted, setupStep) {
        if (pendingAutoStart && allPermissionsGranted && setupStep == RecordingFlowPermissionStep.IDLE) {
            pendingAutoStart = false
            startRecordingFlow()
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
            onDismiss = { },
        )
    }

    if (showBetaNotice) {
        BetaNoticeDialog(
            accent = accent,
            onFeedback = {
                viewModel.setBetaNoticeShown(true)
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("ibbiedead@gmail.com"))
                            putExtra(Intent.EXTRA_SUBJECT, betaFeedbackEmailSubject)
                        },
                    )
                } catch (_: Exception) {
                    Toast.makeText(context, toastNoEmailApp, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = {
                viewModel.setBetaNoticeShown(true)
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
    launcher.launch(MediaProjectionIntents.createScreenCaptureIntent(context, recordSingleAppEnabled))
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

@Composable
private fun BetaNoticeDialog(
    accent: Color,
    onFeedback: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E0E0E),
        shape = RoundedCornerShape(20.dp),
        icon = {
            Icon(
                Icons.Default.BugReport,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(36.dp),
            )
        },
        title = {
            Text(
                stringResource(R.string.beta_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.beta_line_1),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                )
                Text(
                    stringResource(R.string.beta_line_2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCCCCCC),
                )
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Text(
                    stringResource(R.string.beta_line_3),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCCCCCC),
                )
                Text(
                    stringResource(R.string.beta_line_4),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onFeedback,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.action_leave_feedback), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_got_it), color = Color(0xFF888888))
            }
        },
    )
}
