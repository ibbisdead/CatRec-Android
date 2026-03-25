package com.ibbie.catrec_screenrecorcer.ui.screenshots

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ibbie.catrec_screenrecorcer.ui.components.GlassCard
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScreenshotItem(
    val uri: Uri,
    val name: String,
    val date: String,
    val sizeKb: Long
)

@Composable
fun ScreenshotsScreen() {
    val context = LocalContext.current
    val accent = LocalAccentColor.current

    var screenshots by remember { mutableStateOf<List<ScreenshotItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var screenshotToDelete by remember { mutableStateOf<ScreenshotItem?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        screenshots = withContext(Dispatchers.IO) { loadScreenshots(context) }
        isLoading = false
    }

    if (screenshotToDelete != null) {
        val item = screenshotToDelete!!
        AlertDialog(
            onDismissRequest = { screenshotToDelete = null },
            icon = {
                Icon(
                    Icons.Default.Delete, null,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Delete Screenshot?") },
            text = { Text("\"${item.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    try { context.contentResolver.delete(item.uri, null, null) } catch (_: Exception) {}
                    screenshots = screenshots.filter { it.uri != item.uri }
                    screenshotToDelete = null
                }) { Text("Delete", color = accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { screenshotToDelete = null }) { Text("Cancel") }
            }
        )
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

            screenshots.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = accent.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "NO SCREENSHOTS YET",
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp),
                        color = Color(0xFF555555),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tap the camera button in the overlay to capture screenshots",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF444444),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(span = { GridItemSpan(2) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp, start = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SCREENSHOTS  ·  ${screenshots.size}",
                                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                                color = Color(0xFF555555)
                            )
                            IconButton(onClick = { refreshTrigger++ }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = Color(0xFF555555),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    items(screenshots, key = { it.uri.toString() }) { item ->
                        ScreenshotCard(
                            item = item,
                            accent = accent,
                            onShare = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, item.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Screenshot"))
                            },
                            onDelete = { screenshotToDelete = item },
                            onOpen = {
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(item.uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try { context.startActivity(viewIntent) } catch (_: Exception) {}
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotCard(
    item: ScreenshotItem,
    accent: Color,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp
    ) {
        Box(modifier = Modifier.clickable { onOpen() }) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    item.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFAAAAAA),
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                Text(
                    "${item.sizeKb} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = Color(0xFF1E1E1E)
                ) {
                    DropdownMenuItem(
                        text = { Text("Open", color = Color.White) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = accent) },
                        onClick = { showMenu = false; onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Share, null, tint = accent) },
                        onClick = { showMenu = false; onShare() }
                    )
                    HorizontalDivider(color = Color(0xFF333333))
                    DropdownMenuItem(
                        text = { Text("Delete", color = accent) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = accent) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

private suspend fun loadScreenshots(context: android.content.Context): List<ScreenshotItem> {
    val items = mutableListOf<ScreenshotItem>()
    val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.RELATIVE_PATH
    )

    val selection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    } else {
        "${MediaStore.Images.Media.DATA} LIKE ?"
    }
    val selectionArgs = arrayOf("%CatRec%Screenshots%")

    try {
        context.contentResolver.query(
            collection, projection, selection, selectionArgs,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Screenshot"
                val dateMs = cursor.getLong(dateCol) * 1000L
                val sizeBytes = cursor.getLong(sizeCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(dateMs))
                items.add(ScreenshotItem(uri = uri, name = name, date = dateStr, sizeKb = sizeBytes / 1024))
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ScreenshotsScreen", "Failed to load screenshots", e)
    }
    return items
}
