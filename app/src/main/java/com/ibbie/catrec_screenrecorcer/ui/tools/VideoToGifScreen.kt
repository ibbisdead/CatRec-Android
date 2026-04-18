package com.ibbie.catrec_screenrecorcer.ui.tools

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets
import com.ibbie.catrec_screenrecorcer.service.GifExportPipeline
import com.ibbie.catrec_screenrecorcer.utils.contentUriReadableForPlayback
import com.ibbie.catrec_screenrecorcer.utils.formatElapsedMinutesSecondsMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoToGifScreen(
    encodedUri: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val videoUri = remember(encodedUri) { Uri.parse(Uri.decode(encodedUri)) }
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
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.gif_title), fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                            }
                        },
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        false -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.gif_title), fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                            }
                        },
                    )
                },
            ) { padding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.player_video_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        true -> {
            val exoPlayer =
                remember(videoUri) {
                    ExoPlayer.Builder(context).build().apply {
                        setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                            true,
                        )
                        setMediaItem(MediaItem.fromUri(videoUri))
                        prepare()
                        playWhenReady = false
                    }
                }
            DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

            var durationMs by remember { mutableLongStateOf(1L) }
            var startFraction by remember { mutableFloatStateOf(0f) }
            var endFraction by remember { mutableFloatStateOf(1f) }
            var currentPositionMs by remember { mutableLongStateOf(0L) }
            var isWorking by remember { mutableStateOf(false) }
            var qualityTier by remember { mutableIntStateOf(1) } // 0 = Low, 1 = Medium, 2 = High — same tiers as [GifRecordingPresets]
            val exportPreset = remember(qualityTier) { GifRecordingPresets.forVideoToGifTier(qualityTier) }
            var fps by remember { mutableIntStateOf(GifRecordingPresets.forVideoToGifTier(1).fps) }
            val fpsSliderMax = exportPreset.gifFpsSliderMax
            val fpsSliderMin = 3

            LaunchedEffect(qualityTier) {
                fps = exportPreset.fps
            }

            DisposableEffect(exoPlayer) {
                val listener =
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && durationMs <= 1L) {
                                durationMs = exoPlayer.duration.coerceAtLeast(1L)
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.player_video_unavailable),
                                    Toast.LENGTH_LONG,
                                ).show()
                            navController.popBackStack()
                        }
                    }
                exoPlayer.addListener(listener)
                onDispose { exoPlayer.removeListener(listener) }
            }
            LaunchedEffect(exoPlayer) {
                while (true) {
                    currentPositionMs = exoPlayer.currentPosition
                    delay(500)
                }
            }

            val startMs = (startFraction * durationMs).toLong()
            val endMs = (endFraction * durationMs).toLong()

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.gif_title), fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.gif_fps, fps), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = fps.toFloat(),
                            onValueChange = { v -> fps = v.toInt().coerceIn(fpsSliderMin, fpsSliderMax) },
                            valueRange = fpsSliderMin.toFloat()..fpsSliderMax.toFloat(),
                            steps = (fpsSliderMax - fpsSliderMin - 1).coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.gif_quality_label), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = qualityTier == 0,
                                onClick = { qualityTier = 0 },
                                label = { Text(stringResource(R.string.gif_quality_small)) },
                            )
                            FilterChip(
                                selected = qualityTier == 1,
                                onClick = { qualityTier = 1 },
                                label = { Text(stringResource(R.string.gif_quality_balanced)) },
                            )
                            FilterChip(
                                selected = qualityTier == 2,
                                onClick = { qualityTier = 2 },
                                label = { Text(stringResource(R.string.gif_quality_sharper)) },
                            )
                        }
                        Text(
                            stringResource(R.string.trim_start_label, formatElapsedMinutesSecondsMs(startMs)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = startFraction,
                            onValueChange = { v ->
                                startFraction = v.coerceIn(0f, endFraction - 0.02f)
                                exoPlayer.seekTo((startFraction * durationMs).toLong())
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.trim_end_label, formatElapsedMinutesSecondsMs(endMs)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = endFraction,
                            onValueChange = { v ->
                                endFraction = v.coerceIn(startFraction + 0.02f, 1f)
                                exoPlayer.seekTo((endFraction * durationMs).toLong())
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (endMs - startMs < 400L) {
                                    Toast.makeText(context, context.getString(R.string.trim_too_short), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isWorking = true
                                scope.launch {
                                    val ok =
                                        withContext(Dispatchers.IO) {
                                            GifExportPipeline.transcodeMp4ToGif(
                                                context,
                                                videoUri,
                                                exportPreset.maxWidth,
                                                fps,
                                                startMs = startMs,
                                                endMs = endMs,
                                                maxColors = exportPreset.maxColors,
                                                paletteDither = exportPreset.paletteDither,
                                            )
                                        }
                                    isWorking = false
                                    if (ok) {
                                        Toast.makeText(context, context.getString(R.string.editor_saved_ok), Toast.LENGTH_SHORT).show()
                                        navController.popBackStack()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.editor_failed), Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isWorking && endMs - startMs >= 400L,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isWorking) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.editor_saving))
                            } else {
                                Icon(Icons.Default.Gif, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.gif_convert))
                            }
                        }
                    }
                }
            }
        }
    }
}
