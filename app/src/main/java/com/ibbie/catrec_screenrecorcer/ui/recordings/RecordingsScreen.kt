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
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.ui.components.GlassCard
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateMs: Long,
    val durationMs: Long
)

@Composable
fun RecordingsScreen(navController: NavController) {
    val context = LocalContext.current
    val accent = LocalAccentColor.current
    val repository = remember { SettingsRepository(context) }
    val saveLocationUri by repository.saveLocationUri.collectAsState(initial = null)

    var recordings by remember { mutableStateOf<List<RecordingEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(saveLocationUri) {
        isLoading = true
        recordings = withContext(Dispatchers.IO) { loadRecordings(context, saveLocationUri) }
        isLoading = false
    }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            recordings = withContext(Dispatchers.IO) { loadRecordings(context, saveLocationUri) }
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1A0008), Color(0xFF0A0A0A)),
                    radius = 900f
                )
            )
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
                        text = "NO RECORDINGS YET",
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp),
                        color = Color(0xFF555555),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Recordings will appear here after capture",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF444444)
                    )
                }
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header
                    item {
                        Text(
                            text = "LIBRARY  ·  ${recordings.size}",
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                            color = Color(0xFF555555),
                            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(recordings, key = { it.uri.toString() }) { entry ->
                        RecordingCard(
                            entry = entry,
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
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun loadRecordings(context: android.content.Context, saveLocationUri: String?): List<RecordingEntry> {
    val results = mutableListOf<RecordingEntry>()

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
                val id  = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                results.add(
                    RecordingEntry(
                        uri         = uri,
                        displayName = cursor.getString(nameCol) ?: "Unknown",
                        sizeBytes   = cursor.getLong(sizeCol),
                        dateMs      = cursor.getLong(dateCol) * 1000L,
                        durationMs  = cursor.getLong(durCol)
                    )
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("RecordingsScreen", "MediaStore query failed", e)
    }

    if (!saveLocationUri.isNullOrEmpty()) {
        try {
            val existingNames = results.map { it.displayName }.toSet()
            DocumentFile.fromTreeUri(context, Uri.parse(saveLocationUri))
                ?.listFiles()
                ?.filter { it.name?.endsWith(".mp4") == true && it.name !in existingNames }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { doc ->
                    results.add(
                        RecordingEntry(
                            uri         = doc.uri,
                            displayName = doc.name ?: "Unknown",
                            sizeBytes   = doc.length(),
                            dateMs      = doc.lastModified(),
                            durationMs  = 0L
                        )
                    )
                }
        } catch (e: Exception) {
            android.util.Log.e("RecordingsScreen", "SAF query failed", e)
        }
    }

    return results.sortedByDescending { it.dateMs }
}

@Composable
fun RecordingCard(
    entry: RecordingEntry,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit
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
            title = { Text("Delete Recording?") },
            text = { Text("\"${entry.displayName}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete", color = accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Thumbnail ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(width = 84.dp, height = 58.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF160007))
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
            }

            Spacer(Modifier.width(12.dp))

            // ── File info ────────────────────────────────────────────────────
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

            // ── Action icons ─────────────────────────────────────────────────
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

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
