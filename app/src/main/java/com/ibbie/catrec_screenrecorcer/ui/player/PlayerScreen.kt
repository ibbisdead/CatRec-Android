package com.ibbie.catrec_screenrecorcer.ui.player

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.utils.contentUriReadableForPlayback
import com.ibbie.catrec_screenrecorcer.utils.createDeleteRequestPendingIntent
import com.ibbie.catrec_screenrecorcer.utils.formatDurationMs
import com.ibbie.catrec_screenrecorcer.utils.trySilentDeleteMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    encodedUri: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val videoUri = remember(encodedUri) { Uri.decode(encodedUri).toUri() }
    val scope = rememberCoroutineScope()

    val mediaReadable =
        produceState<Boolean?>(initialValue = null, key1 = videoUri) {
            value =
                withContext(Dispatchers.IO) {
                    contentUriReadableForPlayback(context, videoUri)
                }
        }

    when (mediaReadable.value) {
        null -> {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        false -> {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            )
                            .padding(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back), tint = Color.White)
                }
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.player_video_unavailable),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        true -> {
            val playerVideoUnavailableMsg = stringResource(R.string.player_video_unavailable)
            val playerNoAppVideoMsg = stringResource(R.string.player_no_app_video)
            val shareVideoChooserTitle = stringResource(R.string.action_share)

            // ── ExoPlayer setup ───────────────────────────────────────────────────────
            val exoPlayer =
                remember(videoUri) {
                    ExoPlayer.Builder(context).build().apply {
                        setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                            // handleAudioFocus =
                            true,
                        )
                        setMediaItem(MediaItem.fromUri(videoUri))
                        prepare()
                        playWhenReady = true
                    }
                }
            DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

            // ── UI state ──────────────────────────────────────────────────────────────
            var showControls by remember { mutableStateOf(true) }
            var isPlaying by remember { mutableStateOf(true) }
            var currentPositionMs by remember { mutableLongStateOf(0L) }
            var durationMs by remember { mutableLongStateOf(0L) }
            var isSeeking by remember { mutableStateOf(false) }
            var showMenu by remember { mutableStateOf(false) }
            var showRenameDialog by remember { mutableStateOf(false) }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var seekFeedback by remember { mutableStateOf<String?>(null) }

            val deleteMediaLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
                    if (res.resultCode == Activity.RESULT_OK) {
                        exoPlayer.stop()
                        navController.popBackStack()
                    }
                }

            // Auto-hide controls
            LaunchedEffect(showControls) {
                if (showControls) {
                    delay(3000)
                    if (!isSeeking) showControls = false
                }
            }

            // Periodically update seek bar position
            LaunchedEffect(exoPlayer) {
                while (true) {
                    if (!isSeeking) {
                        currentPositionMs = exoPlayer.currentPosition
                        durationMs = exoPlayer.duration.coerceAtLeast(1L)
                        isPlaying = exoPlayer.isPlaying
                    }
                    delay(500)
                }
            }

            // Listen for playback state changes
            DisposableEffect(exoPlayer) {
                val listener =
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                isPlaying = false
                                showControls = true
                            }
                        }

                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Toast
                                .makeText(
                                    context,
                                    playerVideoUnavailableMsg,
                                    Toast.LENGTH_LONG,
                                ).show()
                            navController.popBackStack()
                        }
                    }
                exoPlayer.addListener(listener)
                onDispose { exoPlayer.removeListener(listener) }
            }

            // ── Rename dialog ─────────────────────────────────────────────────────────
            var renameText by remember {
                mutableStateOf(
                    videoUri.lastPathSegment
                        ?.substringAfterLast("/")
                        ?.substringBeforeLast(".") ?: "recording",
                )
            }

            if (showRenameDialog) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(stringResource(R.string.player_rename_title)) },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            label = { Text(stringResource(R.string.player_file_name_label)) },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            try {
                                val newName =
                                    renameText.trimEnd().let {
                                        if (it.endsWith(".mp4")) it else "$it.mp4"
                                    }
                                val values =
                                    ContentValues().apply {
                                        put(MediaStore.Video.Media.DISPLAY_NAME, newName)
                                    }
                                context.contentResolver.update(videoUri, values, null, null)
                                Toast.makeText(context, resources.getString(R.string.player_renamed_to, newName), Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast
                                    .makeText(
                                        context,
                                        resources.getString(R.string.player_rename_failed, e.message ?: ""),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }) { Text(stringResource(R.string.action_rename)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { }) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(stringResource(R.string.delete_recording_title)) },
                    text = { Text(stringResource(R.string.delete_generic_recording)) },
                    confirmButton = {
                        TextButton(onClick = {
                            if (trySilentDeleteMedia(context, videoUri)) {
                                exoPlayer.stop()
                                navController.popBackStack()
                            } else if (Build.VERSION.SDK_INT >= 30) {
                                val pi = createDeleteRequestPendingIntent(context, listOf(videoUri))
                                if (pi != null) {
                                    deleteMediaLauncher.launch(
                                        IntentSenderRequest.Builder(pi.intentSender).build(),
                                    )
                                } else {
                                    Toast
                                        .makeText(
                                            context,
                                            resources.getString(R.string.player_delete_failed, ""),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            } else {
                                Toast
                                    .makeText(
                                        context,
                                        resources.getString(R.string.player_delete_failed, ""),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { }) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }

            // ── Layout ─────────────────────────────────────────────────────────────────
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
            ) {
                // Video surface
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { showControls = !showControls },
                                    onDoubleTap = { offset ->
                                        val seekDelta = 10_000L
                                        if (offset.x < size.width / 2) {
                                            // Left: rewind
                                            exoPlayer.seekTo((exoPlayer.currentPosition - seekDelta).coerceAtLeast(0))
                                            seekFeedback = "−10s"
                                        } else {
                                            // Right: fast-forward
                                            exoPlayer.seekTo((exoPlayer.currentPosition + seekDelta).coerceAtMost(exoPlayer.duration))
                                            seekFeedback = "+10s"
                                        }
                                        scope.launch {
                                            delay(800)
                                            seekFeedback = null
                                        }
                                    },
                                )
                            },
                )

                // Double-tap feedback
                AnimatedVisibility(
                    visible = seekFeedback != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(seekFeedback ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }

                // ── Controls overlay ───────────────────────────────────────────────
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                    ) {
                        // Top bar
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                        ),
                                    )
                                    .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back), tint = Color.White)
                            }

                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.content_desc_more_options), tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_open_external)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                        onClick = {
                                            showMenu = false
                                            val intent =
                                                Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(videoUri, "video/mp4")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                Toast.makeText(context, playerNoAppVideoMsg, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_rename)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = {
                                            showMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_trim)) },
                                        leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                                        onClick = {
                                            showMenu = false
                                            navController.navigate("trim?videoUri=${Uri.encode(videoUri.toString())}")
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_share)) },
                                        leadingIcon = { Icon(Icons.Default.Share, null) },
                                        onClick = {
                                            showMenu = false
                                            val intent =
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "video/mp4"
                                                    putExtra(Intent.EXTRA_STREAM, videoUri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                            context.startActivity(Intent.createChooser(intent, shareVideoChooserTitle))
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = {
                                            showMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        // Center play/pause
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Rewind 10s
                            Box(
                                modifier =
                                    Modifier
                                        .size(52.dp)
                                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(onClick = {
                                    exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0))
                                }) {
                                    Icon(
                                        Icons.Default.Replay10,
                                        stringResource(R.string.content_desc_rewind_10),
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }

                            // Play / Pause
                            Box(
                                modifier =
                                    Modifier
                                        .size(68.dp)
                                        .background(Color.White.copy(alpha = 0.20f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    showControls = true
                                }) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        if (isPlaying) stringResource(R.string.action_pause) else stringResource(R.string.action_resume),
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                            }

                            // Forward 10s
                            Box(
                                modifier =
                                    Modifier
                                        .size(52.dp)
                                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(onClick = {
                                    exoPlayer.seekTo(
                                        (exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration),
                                    )
                                }) {
                                    Icon(
                                        Icons.Default.Forward10,
                                        stringResource(R.string.content_desc_forward_10),
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                        }

                        // Bottom seek bar + time
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                        ),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    formatDurationMs(currentPositionMs),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    formatDurationMs(durationMs),
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Slider(
                                value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f,
                                onValueChange = { fraction ->
                                    isSeeking = true
                                    currentPositionMs = (fraction * durationMs).toLong()
                                },
                                onValueChangeFinished = {
                                    exoPlayer.seekTo(currentPositionMs)
                                    isSeeking = false
                                    showControls = true
                                },
                                colors =
                                    SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                    ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
