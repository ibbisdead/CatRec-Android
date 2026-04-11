package com.ibbie.catrec_screenrecorcer.ui.tools

import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.service.EditorVideoTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HeightPreset(
    val height: Int,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressVideoScreen(
    encodedUri: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val uri = remember(encodedUri) { Uri.parse(Uri.decode(encodedUri)) }
    val scope = rememberCoroutineScope()

    val (srcW, srcH) = remember(uri) { EditorVideoTransform.getVideoDisplaySize(context, uri) }
    val taller = maxOf(srcW, srcH)

    var fileSize by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { fileSize = it.statSize }
            }
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                durationMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { r.release() }
            }
        }
    }

    val inputBr =
        remember(fileSize, durationMs) {
            EditorVideoTransform.estimateInputVideoBitrateBps(fileSize, durationMs)
        }

    val presets =
        remember {
            listOf(
                HeightPreset(1440, "1440p"),
                HeightPreset(1080, "1080p"),
                HeightPreset(720, "720p"),
                HeightPreset(540, "540p"),
                HeightPreset(480, "480p"),
            )
        }

    var selectedHeight: Int? by remember { mutableStateOf(null) }
    var qualityTier by remember { mutableIntStateOf(1) }

    val effectiveOutH = selectedHeight ?: taller
    val targetBitrate =
        remember(inputBr, qualityTier, effectiveOutH) {
            EditorVideoTransform.targetBitrateForQuality(inputBr, qualityTier, effectiveOutH)
        }
    val estBytes =
        remember(targetBitrate, durationMs) {
            EditorVideoTransform.estimateOutputBytes(targetBitrate, durationMs)
        }

    var exporting by remember { mutableStateOf(false) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compress_title), fontWeight = FontWeight.Bold) },
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
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(vScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.compress_source, srcW, srcH),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.compress_estimated, Formatter.formatFileSize(context, estBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.compress_quality_label), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = qualityTier == 2,
                    onClick = { qualityTier = 2 },
                    label = { Text(stringResource(R.string.compress_quality_high)) },
                )
                FilterChip(
                    selected = qualityTier == 1,
                    onClick = { qualityTier = 1 },
                    label = { Text(stringResource(R.string.compress_quality_medium)) },
                )
                FilterChip(
                    selected = qualityTier == 0,
                    onClick = { qualityTier = 0 },
                    label = { Text(stringResource(R.string.compress_quality_low)) },
                )
            }
            Text(stringResource(R.string.compress_resolution_label), style = MaterialTheme.typography.titleSmall)
            FilterChip(
                selected = selectedHeight == null,
                onClick = { selectedHeight = null },
                label = { Text(stringResource(R.string.compress_original)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(hScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    val enabled = taller >= preset.height
                    FilterChip(
                        selected = selectedHeight == preset.height,
                        onClick = { if (enabled) selectedHeight = preset.height },
                        enabled = enabled,
                        label = { Text(preset.label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    exporting = true
                    scope.launch {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val name = "Compress_$ts.mp4"
                        val out =
                            EditorVideoTransform.compressVideo(
                                context = context,
                                inputUri = uri,
                                outputDisplayName = name,
                                targetHeight = selectedHeight,
                                videoBitrate = targetBitrate,
                            )
                        exporting = false
                        if (out != null) {
                            Toast.makeText(context, context.getString(R.string.editor_saved_ok), Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        } else {
                            Toast.makeText(context, context.getString(R.string.editor_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_saving))
                } else {
                    Icon(Icons.Default.Compress, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_export))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
