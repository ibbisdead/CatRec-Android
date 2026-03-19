package com.ibbie.catrec_screenrecorcer.ui.recording

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ibbie.catrec_screenrecorcer.ui.components.*
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.theme.CyberBlack
import com.ibbie.catrec_screenrecorcer.utils.PermissionInfo
import com.ibbie.catrec_screenrecorcer.utils.PermissionManager
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Permission setup is driven by a simple state machine so that each step
// (notifications → audio → overlay) is triggered declaratively via
// LaunchedEffect, avoiding circular launcher-call references.
// ---------------------------------------------------------------------------
private enum class PermissionSetupStep {
    IDLE, NOTIFICATIONS, AUDIO, CAMERA, OVERLAY, COMPLETE
}

@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val permissionManager = remember { PermissionManager(context) }

    val accent = LocalAccentColor.current

    // ---- Recording / Buffer state ----
    val isRecording by viewModel.isRecording.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val bitrate by viewModel.bitrate.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val recordAudio by viewModel.recordAudio.collectAsState()
    val internalAudio by viewModel.internalAudio.collectAsState()

    // "RECORD" or "CLIPPER" — mutually exclusive.
    // Lock the tab while either mode is actively running.
    var selectedMode by remember { mutableStateOf("RECORD") }
    val canSwitchMode = !isRecording && !isBuffering

    // ---- Permission state ----
    var allPermissionsGranted by remember { mutableStateOf(permissionManager.areAllGranted()) }
    var missingPermissions by remember { mutableStateOf(permissionManager.getMissingPermissions()) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var setupStep by remember { mutableStateOf(PermissionSetupStep.IDLE) }

    // ---- Animations ----
    val infiniteTransition = rememberInfiniteTransition(label = "RecordingBlink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BlinkAlpha"
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (isRecording) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ButtonScale"
    )

    // -----------------------------------------------------------------------
    // Permission refresh: re-query the system and push state into the ViewModel
    // so any observer (e.g. the record button) immediately reflects reality.
    // -----------------------------------------------------------------------
    fun refreshPermissions() {
        allPermissionsGranted = permissionManager.areAllGranted()
        missingPermissions = permissionManager.getMissingPermissions()
        viewModel.updatePermissionsState(allPermissionsGranted)
        if (allPermissionsGranted) permissionManager.markSetupComplete()
    }

    // -----------------------------------------------------------------------
    // ActivityResultLaunchers — declared before the LaunchedEffect(setupStep)
    // that references them, so the compiler sees them first.
    // -----------------------------------------------------------------------

    // Step 4 — SYSTEM_ALERT_WINDOW (special Settings intent, not a normal permission)
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        permissionManager.saveOverlayGranted(Settings.canDrawOverlays(context))
        setupStep = PermissionSetupStep.COMPLETE
    }

    // Step 3 — CAMERA
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionManager.saveCameraGranted(granted)
        setupStep = PermissionSetupStep.OVERLAY
    }

    // Step 2 — RECORD_AUDIO
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionManager.saveAudioGranted(granted)
        setupStep = PermissionSetupStep.CAMERA
    }

    // Step 1 — POST_NOTIFICATIONS (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionManager.saveNotificationGranted(granted)
        setupStep = PermissionSetupStep.AUDIO
    }

    // -----------------------------------------------------------------------
    // Permission setup state machine
    // Each step either launches its permission or skips straight to the next.
    // -----------------------------------------------------------------------
    LaunchedEffect(setupStep) {
        when (setupStep) {
            PermissionSetupStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !permissionManager.isNotificationGranted()
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    setupStep = PermissionSetupStep.AUDIO
                }
            }

            PermissionSetupStep.AUDIO -> {
                if (!permissionManager.isAudioGranted()) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    setupStep = PermissionSetupStep.CAMERA
                }
            }

            PermissionSetupStep.CAMERA -> {
                if (!permissionManager.isCameraGranted()) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    setupStep = PermissionSetupStep.OVERLAY
                }
            }

            PermissionSetupStep.OVERLAY -> {
                if (!permissionManager.isOverlayGranted()) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    overlayPermissionLauncher.launch(intent)
                } else {
                    setupStep = PermissionSetupStep.COMPLETE
                }
            }

            PermissionSetupStep.COMPLETE -> {
                refreshPermissions()
                setupStep = PermissionSetupStep.IDLE
            }

            PermissionSetupStep.IDLE -> { /* no-op */ }
        }
    }

    // -----------------------------------------------------------------------
    // First launch: start permission sequence if setup has never been completed
    // or if a permission was revoked since last launch.
    // -----------------------------------------------------------------------
    LaunchedEffect(Unit) {
        if (!permissionManager.isSetupComplete() || !permissionManager.areAllGranted()) {
            setupStep = PermissionSetupStep.NOTIFICATIONS
        } else {
            refreshPermissions()
        }
    }

    // -----------------------------------------------------------------------
    // onResume / onStart: re-check permissions every time the screen comes
    // back into view (e.g. after returning from Settings).
    // -----------------------------------------------------------------------
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allPermissionsGranted = permissionManager.areAllGranted()
                missingPermissions = permissionManager.getMissingPermissions()
                viewModel.updatePermissionsState(allPermissionsGranted)
                if (allPermissionsGranted) permissionManager.markSetupComplete()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // -----------------------------------------------------------------------
    // Media projection + legacy storage launchers (recording flow)
    // -----------------------------------------------------------------------
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start service immediately; service handles countdown within FGS grace period.
            viewModel.startRecordingService(context, result.resultCode, result.data!!)
            (context as? Activity)?.moveTaskToBack(true)
        } else {
            Toast.makeText(context, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Separate launcher for buffer mode (needs the same MediaProjection grant).
    val bufferProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startBufferService(context, result.resultCode, result.data!!)
            (context as? Activity)?.moveTaskToBack(true)
        } else {
            Toast.makeText(context, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    fun startBufferFlow() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        bufferProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        startMediaProjection(context, mediaProjectionLauncher)
    }

    fun checkStorageAndProceed() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startMediaProjection(context, mediaProjectionLauncher)
            }
        } else {
            startMediaProjection(context, mediaProjectionLauncher)
        }
    }

    // Re-verify audio at record time as a safeguard (it may have been revoked
    // between the setup sequence and when the user presses record).
    fun checkAudioAndProceed() {
        if ((recordAudio || internalAudio) && !permissionManager.isAudioGranted()) {
            showPermissionDialog = true
        } else {
            checkStorageAndProceed()
        }
    }

    // Entry point for the record button.
    val startRecordingFlow: () -> Unit = {
        if (!allPermissionsGranted) {
            showPermissionDialog = true
        } else {
            checkAudioAndProceed()
        }
    }

    // -----------------------------------------------------------------------
    // Permission dialog — shown when a permission is missing at record-start
    // OR when the user taps "Fix" on the persistent banner.
    // -----------------------------------------------------------------------
    if (showPermissionDialog && missingPermissions.isNotEmpty()) {
        PermissionRationaleDialog(
            missingPermissions = missingPermissions,
            onGrantNow = {
                showPermissionDialog = false
                setupStep = PermissionSetupStep.NOTIFICATIONS
            },
            onDismiss = { showPermissionDialog = false }
        )
    }

    // -----------------------------------------------------------------------
    // Cyber-Cat HUD Layout
    // -----------------------------------------------------------------------
    val audioStatus = when {
        recordAudio && internalAudio -> "M+I"
        recordAudio                  -> "Mic"
        internalAudio                -> "Int"
        else                         -> "Mute"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1A0008), CyberBlack),
                    radius = 900f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // ── Permissions banner ───────────────────────────────────────────
            if (!allPermissionsGranted) {
                MissingPermissionsBanner(
                    missingPermissions = missingPermissions,
                    onFixClick = { showPermissionDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )
            }

            // ── Mode selector: RECORD | CLIPPER ─────────────────────────────
            ModeSelector(
                selectedMode = selectedMode,
                onModeSelected = { if (canSwitchMode) selectedMode = it },
                enabled = canSwitchMode,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // ── Status HUD glass card ────────────────────────────────────────
            GlassCard(
                modifier = Modifier.padding(bottom = 32.dp),
                cornerRadius = 16.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    when {
                        isRecording -> {
                            Box(modifier = Modifier.size(10.dp).scale(blinkAlpha)
                                .clip(RoundedCornerShape(50)).background(accent))
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        isBuffering -> {
                            Box(modifier = Modifier.size(10.dp).scale(blinkAlpha)
                                .clip(RoundedCornerShape(50)).background(Color(0xFFFF8C00)))
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                    }
                    Text(
                        text = when { isRecording -> "● REC"; isBuffering -> "◉ CLIPPING"; else -> "STANDBY" },
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp),
                        fontWeight = FontWeight.Bold,
                        color = when { isRecording -> accent; isBuffering -> Color(0xFFFF8C00); else -> Color(0xFF888888) }
                    )
                    if (isRecording || isBuffering) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "Notification → Stop",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF666666),
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // ── High-settings warning ────────────────────────────────────────
            if (fps > 60 || bitrate > 30) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x442A0008))
                        .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "High settings — performance impact possible",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFBB8888),
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // ── HUD ring: CatRecordButton + 4 floating GlassPills ────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .scale(buttonScale)
                    .size(280.dp)
            ) {
                // FPS — top-left
                GlassPill(
                    label = "FPS",
                    value = "${fps.toInt()}",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 18.dp, start = 8.dp)
                )
                // Resolution — top-right
                GlassPill(
                    label = "RES",
                    value = resolution.take(9),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 18.dp, end = 8.dp)
                )

                // Cat Record Button — centered
                // In CLIPPER mode the button starts/stops the rolling buffer engine.
                CatRecordButton(
                    isRecording = isRecording || isBuffering,
                    isEnabled   = allPermissionsGranted,
                    onClick = {
                        when {
                            selectedMode == "RECORD" -> {
                                if (isRecording) viewModel.stopRecordingService(context)
                                else startRecordingFlow()
                            }
                            selectedMode == "CLIPPER" -> {
                                if (isBuffering) viewModel.stopBufferService(context)
                                else startBufferFlow()
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )

                // Bitrate — bottom-left
                GlassPill(
                    label = "Mbps",
                    value = "${bitrate.toInt()}",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 18.dp, start = 8.dp)
                )
                // Audio mode — bottom-right
                GlassPill(
                    label = "Audio",
                    value = audioStatus,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 18.dp, end = 8.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Tap label ────────────────────────────────────────────────────
            Text(
                text = when {
                    isRecording            -> "TAP TO STOP"
                    isBuffering            -> "TAP TO STOP CLIPPER"
                    !allPermissionsGranted -> "PERMISSIONS REQUIRED"
                    selectedMode == "CLIPPER" -> "TAP TO START CLIPPER"
                    else                   -> "TAP TO START"
                },
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
                color = when { isRecording -> accent; isBuffering -> Color(0xFFFF8C00); else -> Color(0xFF666666) },
                fontWeight = FontWeight.Medium
            )

            // ── Save Clip button — visible only when clipper is active ───────
            if (isBuffering) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.saveClip(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF8C00),
                        contentColor   = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.65f)
                ) {
                    Text(
                        text = "✂  SAVE CLIP",
                        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Saves the last 60 seconds",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888),
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Persistent banner — shown whenever at least one required permission is missing.
// ---------------------------------------------------------------------------
@Composable
private fun MissingPermissionsBanner(
    missingPermissions: List<PermissionInfo>,
    onFixClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccentColor.current
    GlassCard(modifier = modifier, cornerRadius = 12.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Permissions needed",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = missingPermissions.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAFAFAF)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onFixClick,
                colors = ButtonDefaults.textButtonColors(contentColor = accent)
            ) {
                Text("Grant Now", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// AlertDialog listing every missing permission with plain-English rationale.
// ---------------------------------------------------------------------------
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
        title = { Text("Permissions Required") },
        text = {
            Column {
                Text(
                    text = "CatRec needs the following permissions to record your screen:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                missingPermissions.forEach { perm ->
                    Row(
                        modifier = Modifier.padding(bottom = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = perm.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = perm.rationale,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onGrantNow) { Text("Grant Now") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not Now") }
        }
    )
}

// ---------------------------------------------------------------------------
// Segmented mode selector: RECORD | CLIPPER
// ---------------------------------------------------------------------------
@Composable
private fun ModeSelector(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccentColor.current
    val modes = listOf("RECORD", "CLIPPER")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .background(Color(0x22000000)),
        horizontalArrangement = Arrangement.Center,
    ) {
        modes.forEachIndexed { idx, mode ->
            val selected = selectedMode == mode
            val bg = if (selected) accent.copy(alpha = 0.85f) else Color.Transparent
            val contentColor = if (selected) Color.Black else Color(0xFF888888)

            TextButton(
                onClick = { if (enabled) onModeSelected(mode) },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = bg,
                    contentColor   = contentColor,
                    disabledContentColor = contentColor.copy(alpha = 0.5f)
                ),
                enabled = enabled,
                shape = when (idx) {
                    0    -> RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                    modes.lastIndex -> RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                    else -> RoundedCornerShape(0.dp)
                },
                modifier = Modifier.defaultMinSize(minWidth = 110.dp)
            ) {
                val icon = if (mode == "RECORD") "●" else "◉"
                Text(
                    text = "$icon  $mode",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private fun startMediaProjection(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    launcher.launch(mpm.createScreenCaptureIntent())
}
