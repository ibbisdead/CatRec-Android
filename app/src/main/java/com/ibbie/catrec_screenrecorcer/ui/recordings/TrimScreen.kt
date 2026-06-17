package com.ibbie.catrec_screenrecorcer.ui.recordings

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.service.ClipMerger
import com.ibbie.catrec_screenrecorcer.utils.contentUriReadableForPlayback
import com.ibbie.catrec_screenrecorcer.utils.formatDurationMs
import com.ibbie.catrec_screenrecorcer.utils.navigationUriArgToUri
import com.ibbie.catrec_screenrecorcer.utils.trySilentDeleteMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    encodedUri: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val videoUri = remember(encodedUri) { navigationUriArgToUri(encodedUri) }
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
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.trim_title), fontWeight = FontWeight.Bold) },
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
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        false -> {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.trim_title), fontWeight = FontWeight.Bold) },
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
            val toastPlayerUnavailable = stringResource(R.string.player_video_unavailable)
            val toastTrimTooShort = stringResource(R.string.trim_too_short)
            val toastTrimDeleted = stringResource(R.string.trim_result_deleted)
            val toastTrimDeleteFailed = stringResource(R.string.trim_result_delete_failed)
            val toastTrimFailedRetry = stringResource(R.string.trim_failed_retry)
            val shareTrimChooserTitle = stringResource(R.string.trim_result_share_title)

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
            var isTrimming by remember { mutableStateOf(false) }
            var trimProgress by remember { mutableFloatStateOf(0f) }
            var trimResult by remember { mutableStateOf<TrimResult?>(null) }

            trimResult?.let { result ->
                TrimResultDialog(
                    result = result,
                    onDismiss = { trimResult = null },
                    onPlay = {
                        trimResult = null
                        navController.navigate("player?videoUri=${Uri.encode(result.uri.toString())}")
                    },
                    onDelete = {
                        scope.launch {
                            val deleted =
                                withContext(Dispatchers.IO) {
                                    trySilentDeleteMedia(context, result.uri)
                                }
                            if (deleted) {
                                trimResult = null
                                Toast.makeText(context, toastTrimDeleted, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, toastTrimDeleteFailed, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onShare = {
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, result.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(Intent.createChooser(intent, shareTrimChooserTitle))
                    },
                )
            }

            // Wait for duration to be known
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
                                    toastPlayerUnavailable,
                                    Toast.LENGTH_LONG,
                                ).show()
                            navController.popBackStack()
                        }
                    }
                exoPlayer.addListener(listener)
                onDispose { exoPlayer.removeListener(listener) }
            }

            // Track position
            LaunchedEffect(exoPlayer) {
                while (true) {
                    currentPositionMs = exoPlayer.currentPosition
                    delay(500)
                }
            }

            val startMs = (startFraction * durationMs).toLong()
            val endMs = (endFraction * durationMs).toLong()

            Scaffold(
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.trim_title), fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    // Video preview
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color.Black),
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

                    // Trim controls
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Start trim handle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.trim_start_label, formatDurationMs(startMs)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                            TextButton(onClick = {
                                startFraction = (currentPositionMs.toFloat() / durationMs).coerceIn(0f, endFraction - 0.01f)
                            }) {
                                Text(stringResource(R.string.trim_set_to_current))
                            }
                        }
                        Slider(
                            value = startFraction,
                            onValueChange = { v ->
                                startFraction = v.coerceIn(0f, endFraction - 0.01f)
                                exoPlayer.seekTo((startFraction * durationMs).toLong())
                            },
                            valueRange = 0f..1f,
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // End trim handle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.trim_end_label, formatDurationMs(endMs)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Medium,
                            )
                            TextButton(onClick = {
                                endFraction = (currentPositionMs.toFloat() / durationMs).coerceIn(startFraction + 0.01f, 1f)
                            }) {
                                Text(stringResource(R.string.trim_set_to_current))
                            }
                        }
                        Slider(
                            value = endFraction,
                            onValueChange = { v ->
                                endFraction = v.coerceIn(startFraction + 0.01f, 1f)
                                exoPlayer.seekTo((endFraction * durationMs).toLong())
                            },
                            valueRange = 0f..1f,
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.secondary,
                                    activeTrackColor = MaterialTheme.colorScheme.secondary,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Duration summary
                        Text(
                            stringResource(
                                R.string.trim_duration_summary,
                                formatDurationMs(endMs - startMs),
                                formatDurationMs(startMs),
                                formatDurationMs(endMs),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(8.dp))

                        // Trim button
                        Button(
                            onClick = {
                                if (endMs - startMs < 500L) {
                                    Toast.makeText(context, toastTrimTooShort, Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isTrimming = true
                                scope.launch {
                                    val result =
                                        trimVideo(context, videoUri, startMs, endMs) { progress ->
                                            trimProgress = progress
                                    }
                                    isTrimming = false
                                    if (result != null) {
                                        exoPlayer.pause()
                                        trimResult = result
                                    } else {
                                        Toast.makeText(context, toastTrimFailedRetry, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isTrimming && (endMs - startMs) >= 500L,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isTrimming) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.trim_progress, (trimProgress * 100).toInt()))
                            } else {
                                Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.trim_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class TrimResult(
    val uri: Uri,
    val fileName: String,
)

@Composable
private fun TrimResultDialog(
    result: TrimResult,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = result.uri) {
        value = loadTrimResultThumbnail(context, result.uri)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 432.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 80.dp, height = 58.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(onClick = onPlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        thumbnail?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.56f),
                            contentColor = Color.White,
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.trim_result_play),
                                modifier =
                                    Modifier
                                        .size(38.dp)
                                        .padding(7.dp),
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.trim_result_open_file, result.fileName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_delete))
                    }
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
        }
    }
}

private suspend fun loadTrimResultThumbnail(
    context: android.content.Context,
    uri: Uri,
): Bitmap? =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(320, 180), null)
            }.getOrNull()?.let { return@withContext it }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    500_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    320,
                    180,
                )
            } else {
                @Suppress("DEPRECATION")
                retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

private suspend fun trimVideo(
    context: android.content.Context,
    inputUri: Uri,
    startMs: Long,
    endMs: Long,
    onProgress: (Float) -> Unit,
): TrimResult? =
    withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var insertedUri: Uri? = null
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        val cr = context.contentResolver
        try {
            extractor.setDataSource(context, inputUri, null)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFileName = "Trim_$timestamp.mp4"

            val contentValues =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(
                            MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + File.separator + "CatRec",
                        )
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }

            val outUri =
                cr.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    contentValues,
                ) ?: return@withContext null
            insertedUri = outUri

            pfd =
                cr.openFileDescriptor(outUri, "w") ?: run {
                    runCatching { cr.delete(outUri, null, null) }
                    insertedUri = null
                    return@withContext null
                }

            val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val trackCount = extractor.trackCount
            val trackMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[i] = mux.addTrack(format)
                }
            }

            mux.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val durationUs = (endUs - startUs).coerceAtLeast(1L)

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            for (track in trackMap.keys) extractor.selectTrack(track)

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val muxerTrack = trackMap[trackIndex]
                if (muxerTrack == null) {
                    extractor.advance()
                    continue
                }

                val sampleTime = extractor.sampleTime
                if (sampleTime !in 0..endUs) break

                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break

                info.presentationTimeUs = (sampleTime - startUs).coerceAtLeast(0L)
                info.flags = ClipMerger.sampleFlagsForMuxer(extractor.sampleFlags)

                mux.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()

                val elapsed = (sampleTime - startUs).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    onProgress((elapsed.toFloat() / durationUs).coerceIn(0f, 1f))
                }
            }

            mux.stop()
            mux.release()
            muxer = null
            pfd.close()
            pfd = null

            if (Build.VERSION.SDK_INT >= 29) {
                val n =
                    cr.update(
                        outUri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                if (n <= 0) {
                    runCatching { cr.delete(outUri, null, null) }
                    insertedUri = null
                    return@withContext null
                }
            }

            withContext(Dispatchers.Main) { onProgress(1f) }
            TrimResult(outUri, outputFileName)
        } catch (e: Exception) {
            android.util.Log.e("TrimScreen", "Trim failed", e)
            insertedUri?.let { u -> runCatching { cr.delete(u, null, null) } }
            null
        } finally {
            runCatching {
                muxer?.run {
                    try {
                        stop()
                    } catch (_: Exception) {
                    }
                    release()
                }
            }
            runCatching { pfd?.close() }
            extractor.release()
        }
    }
