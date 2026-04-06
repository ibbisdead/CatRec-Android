package com.ibbie.catrec_screenrecorcer.ui.recordings

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.ui.components.GlassCard
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentBrush
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.theme.isLightTheme
import com.ibbie.catrec_screenrecorcer.ui.theme.rememberScreenBackgroundBrush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateMs: Long,
    val durationMs: Long,
    val hasSeparateAudio: Boolean = false
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(navController: NavController) {
    val context = LocalContext.current
    val accent = LocalAccentColor.current
    val repository = remember { SettingsRepository(context) }
    val saveLocationUri by repository.saveLocationUri.collectAsState(initial = null)

    var recordings by remember { mutableStateOf<List<RecordingEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    val isSelectionMode = selectedUris.isNotEmpty()
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) {
        selectedUris = emptySet()
    }

    LaunchedEffect(saveLocationUri) {
        isLoading = true
        recordings = withContext(Dispatchers.IO) { loadRecordings(context, saveLocationUri) }
        isLoading = false
    }

    if (showBulkDeleteDialog) {
        val count = selectedUris.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            icon = {
                Icon(Icons.Default.Delete, null, tint = accent, modifier = Modifier.size(28.dp))
            },
            title = { Text(stringResource(R.string.multiselect_delete_title, count)) },
            text = { Text(stringResource(R.string.multiselect_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDeleteDialog = false
                    val toDelete = selectedUris.toSet()
                    toDelete.forEach { uri ->
                        try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {
                            try { DocumentFile.fromSingleUri(context, uri)?.delete() } catch (_: Exception) {}
                        }
                    }
                    recordings = recordings.filter { it.uri !in toDelete }
                    selectedUris = emptySet()
                }) {
                    Text(stringResource(R.string.action_delete), color = accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val screenBg = rememberScreenBackgroundBrush()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = screenBg)
    ) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp)
                }
            }

            recordings.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = accent.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.recordings_empty_title),
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.recordings_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = if (isSelectionMode) 88.dp else 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.recordings_header_format, recordings.size),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(recordings, key = { it.uri.toString() }) { entry ->
                        RecordingCard(
                            entry = entry,
                            isSelectionMode = isSelectionMode,
                            isSelected = entry.uri in selectedUris,
                            onPlay = {
                                navController.navigate("player?videoUri=${Uri.encode(entry.uri.toString())}")
                            },
                            onShare = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, entry.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Recording"))
                            },
                            onTrim = {
                                navController.navigate("trim?videoUri=${Uri.encode(entry.uri.toString())}")
                            },
                            onDelete = {
                                try {
                                    context.contentResolver.delete(entry.uri, null, null)
                                } catch (_: Exception) {
                                    try {
                                        DocumentFile.fromSingleUri(context, entry.uri)?.delete()
                                    } catch (_: Exception) {}
                                }
                                recordings = recordings.filter { it.uri != entry.uri }
                            },
                            onLongClick = {
                                selectedUris = setOf(entry.uri)
                            },
                            onToggleSelect = {
                                selectedUris = if (entry.uri in selectedUris) {
                                    selectedUris - entry.uri
                                } else {
                                    selectedUris + entry.uri
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Multi-select action bar ───────────────────────────────────────────
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val rimBrush = LocalAccentBrush.current
            val allSelected = recordings.isNotEmpty() && selectedUris.size == recordings.size
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xCC0A0A0A), Color(0xEE0A0A0A)))
                    )
                    .border(1.dp, rimBrush, RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedUris = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                    }
                    Text(
                        text = stringResource(R.string.multiselect_selected_count, selectedUris.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            selectedUris = if (allSelected) emptySet()
                            else recordings.map { it.uri }.toSet()
                        }
                    ) {
                        Text(
                            text = if (allSelected) stringResource(R.string.multiselect_deselect_all)
                            else stringResource(R.string.multiselect_select_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                    }
                    IconButton(
                        onClick = {
                            val uriList = ArrayList(selectedUris.toList())
                            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "video/mp4"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, context.getString(R.string.multiselect_share_recordings))
                            )
                        },
                        enabled = selectedUris.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share), tint = accent.copy(alpha = 0.85f))
                    }
                    IconButton(
                        onClick = { showBulkDeleteDialog = true },
                        enabled = selectedUris.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = accent)
                    }
                }
            }
        }
    }
}

private fun loadRecordings(context: android.content.Context, saveLocationUri: String?): List<RecordingEntry> {
    val results = mutableListOf<RecordingEntry>()
    val micTimestamps = loadMicFileTimestamps(context)

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DURATION
    )

    val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0" to
                arrayOf("${Environment.DIRECTORY_MOVIES}/CatRec/%")
    } else {
        "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/Movies/CatRec/%")
    }

    try {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durCol  = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id   = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Unknown"
                val uri  = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val ts   = extractTimestampFromVideoName(name)
                results.add(
                    RecordingEntry(
                        uri               = uri,
                        displayName       = name,
                        sizeBytes         = cursor.getLong(sizeCol),
                        dateMs            = cursor.getLong(dateCol) * 1000L,
                        durationMs        = cursor.getLong(durCol),
                        hasSeparateAudio  = ts != null && micTimestamps.contains(ts)
                    )
                )
            }
        }
    } catch (e: Exception) {
        Log.e("RecordingsScreen", "MediaStore query failed", e)
    }

    if (!saveLocationUri.isNullOrEmpty()) {
        try {
            val existingNames = results.map { it.displayName }.toSet()
            val safDir = DocumentFile.fromTreeUri(context, Uri.parse(saveLocationUri))
            val safMicNames = safDir?.listFiles()
                ?.mapNotNull { it.name }
                ?.filter { it.startsWith("Mic_") && it.endsWith(".m4a") }
                ?.toSet() ?: emptySet()
            safDir
                ?.listFiles()
                ?.filter { it.name?.endsWith(".mp4") == true && it.name !in existingNames }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { doc ->
                    val name = doc.name ?: "Unknown"
                    val ts   = extractTimestampFromVideoName(name)
                    val expectedMic = if (ts != null) "Mic_$ts.m4a" else null
                    results.add(
                        RecordingEntry(
                            uri               = doc.uri,
                            displayName       = name,
                            sizeBytes         = doc.length(),
                            dateMs            = doc.lastModified(),
                            durationMs        = 0L,
                            hasSeparateAudio  = expectedMic != null && safMicNames.contains(expectedMic)
                        )
                    )
                }
        } catch (e: Exception) {
            Log.e("RecordingsScreen", "SAF query failed", e)
        }
    }

    return results.sortedByDescending { it.dateMs }
}

private fun extractTimestampFromVideoName(name: String): String? {
    val base = name.removeSuffix(".mp4")
        .removePrefix("CatRec_")
        .removePrefix("Record_")
    return if (base.matches(Regex("\\d{8}_\\d{6}"))) base else null
}

private fun loadMicFileTimestamps(context: android.content.Context): Set<String> {
    val timestamps = mutableSetOf<String>()

    val audioProjection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
    try {
        val (audioSel, audioArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val paths = buildList {
                add("${Environment.DIRECTORY_MUSIC}${File.separator}CatRec${File.separator}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add("${Environment.DIRECTORY_RECORDINGS}${File.separator}CatRec${File.separator}")
                }
            }
            val placeholders = paths.joinToString(" OR ") {
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            }
            "($placeholders) AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0" to
                    (paths.map { "$it%" } + listOf("Mic_%")).toTypedArray()
        } else {
            "${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?" to
                    arrayOf("%/CatRec/%Mic_%.m4a", "Mic_%")
        }

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            audioProjection, audioSel, audioArgs, null
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val micName = cursor.getString(nameCol) ?: continue
                val ts = micName.removePrefix("Mic_").removeSuffix(".m4a")
                if (ts.matches(Regex("\\d{8}_\\d{6}"))) timestamps.add(ts)
            }
        }
    } catch (e: Exception) {
        Log.e("RecordingsScreen", "Mic timestamps query failed", e)
    }

    return timestamps
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingCard(
    entry: RecordingEntry,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
) {
    val context = LocalContext.current
    val accent = LocalAccentColor.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var thumbnail by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.uri) {
        thumbnail = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(entry.uri, Size(160, 120), null)
                } else {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, entry.uri)
                    val bmp = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    bmp
                }
            } catch (_: Exception) { null }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(Icons.Default.Delete, null, tint = accent, modifier = Modifier.size(28.dp))
            },
            title = { Text(stringResource(R.string.delete_recording_title)) },
            text = { Text(stringResource(R.string.delete_recording_message, entry.displayName)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isSelected) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp))
                else Modifier
            )
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 14.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (isSelectionMode) onToggleSelect() else onPlay() },
                        onLongClick = { if (!isSelectionMode) onLongClick() else onToggleSelect() }
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Thumbnail ────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(width = 84.dp, height = 58.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (MaterialTheme.colorScheme.isLightTheme()) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color(0xFF160007)
                            }
                        )
                        .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnail != null) {
                        Image(
                            painter = BitmapPainter(thumbnail!!.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = accent.copy(alpha = 0.45f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    // Separate audio track badge
                    if (entry.hasSeparateAudio && !isSelectionMode) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .size(18.dp)
                                .background(Color(0xCC000000), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = stringResource(R.string.recording_has_separate_audio),
                                tint = accent,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    // Selection overlay
                    if (isSelectionMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isSelected) accent.copy(alpha = 0.35f) else Color(0x55000000)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        if (isSelected) accent else Color.Transparent,
                                        CircleShape
                                    )
                                    .border(
                                        2.dp,
                                        if (isSelected) accent else Color.White.copy(alpha = 0.8f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                // ── File info ─────────────────────────────────────────────────
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            append(Formatter.formatShortFileSize(context, entry.sizeBytes))
                            append("  ·  ")
                            append(SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(entry.dateMs)))
                            if (entry.durationMs > 0) {
                                append("  ·  ")
                                append(formatDuration(entry.durationMs))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF777777),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ── Action icons (hidden in selection mode) ───────────────────
                if (!isSelectionMode) {
                    IconButton(onClick = onTrim) {
                        Icon(Icons.Default.ContentCut, "Trim", tint = accent.copy(alpha = 0.65f))
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, "Share", tint = accent.copy(alpha = 0.65f))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = accent)
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
