package com.ibbie.catrec_screenrecorcer.ui.recordings

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.utils.formatDurationMs
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
    navController: NavController
) {
    val context = LocalContext.current
    val videoUri = remember(encodedUri) { Uri.parse(Uri.decode(encodedUri)) }
    val scope = rememberCoroutineScope()

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
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

    // Wait for duration to be known
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durationMs <= 1L) {
                    durationMs = exoPlayer.duration.coerceAtLeast(1L)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Track position
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition
            delay(200)
        }
    }

    val startMs = (startFraction * durationMs).toLong()
    val endMs = (endFraction * durationMs).toLong()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trim_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Video preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Trim controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Start trim handle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.trim_start_label, formatDurationMs(startMs)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
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
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // End trim handle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.trim_end_label, formatDurationMs(endMs)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
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
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.secondary,
                        activeTrackColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.fillMaxWidth()
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                // Trim button
                Button(
                    onClick = {
                        if (endMs - startMs < 500L) {
                            Toast.makeText(context, context.getString(R.string.trim_too_short), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isTrimming = true
                        scope.launch {
                            val result = trimVideo(context, videoUri, startMs, endMs) { progress ->
                                trimProgress = progress
                            }
                            isTrimming = false
                            if (result != null) {
                                Toast.makeText(context, context.getString(R.string.trim_saved_success), Toast.LENGTH_SHORT).show()
                                navController.popBackStack()
                            } else {
                                Toast.makeText(context, context.getString(R.string.trim_failed_retry), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isTrimming && (endMs - startMs) >= 500L,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTrimming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
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

private suspend fun trimVideo(
    context: android.content.Context,
    inputUri: Uri,
    startMs: Long,
    endMs: Long,
    onProgress: (Float) -> Unit
): Uri? = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, inputUri, null)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFileName = "Trim_$timestamp.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + File.separator + "CatRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val outputUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues
        ) ?: return@withContext null

        val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")
            ?: return@withContext null

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackCount = extractor.trackCount
        val trackMap = mutableMapOf<Int, Int>() // extractor index -> muxer track

        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                trackMap[i] = muxer.addTrack(format)
            }
        }

        muxer.start()

        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val durationUs = (endUs - startUs).coerceAtLeast(1L)

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        // Select all relevant tracks
        for (track in trackMap.keys) extractor.selectTrack(track)

        // Seek all tracks to start (nearest sync point)
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
            if (sampleTime < 0 || sampleTime > endUs) break

            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break

            info.presentationTimeUs = (sampleTime - startUs).coerceAtLeast(0L)
            info.flags = extractor.sampleFlags

            muxer.writeSampleData(muxerTrack, buffer, info)
            extractor.advance()

            // Report progress
            val elapsed = (sampleTime - startUs).coerceAtLeast(0L)
            withContext(Dispatchers.Main) {
                onProgress((elapsed.toFloat() / durationUs).coerceIn(0f, 1f))
            }
        }

        muxer.stop()
        muxer.release()
        pfd.close()

        // Clear pending flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(outputUri, values, null, null)
        }

        withContext(Dispatchers.Main) { onProgress(1f) }
        outputUri
    } catch (e: Exception) {
        android.util.Log.e("TrimScreen", "Trim failed", e)
        null
    } finally {
        extractor.release()
    }
}
