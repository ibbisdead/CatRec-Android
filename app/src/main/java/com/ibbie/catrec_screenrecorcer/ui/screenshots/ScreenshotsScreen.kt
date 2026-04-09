package com.ibbie.catrec_screenrecorcer.ui.screenshots

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ui.components.GlassCard
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentBrush
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.components.LocalSuppressRecordFabForListSelection
import com.ibbie.catrec_screenrecorcer.ui.theme.rememberScreenBackgroundBrush
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScreenshotItem(
    val uri: Uri,
    val name: String,
    val date: String,
    val sizeKb: Long
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScreenshotsScreen() {
    val context = LocalContext.current
    val accent = LocalAccentColor.current

    var screenshots by remember { mutableStateOf<List<ScreenshotItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var screenshotToDelete by remember { mutableStateOf<ScreenshotItem?>(null) }
    /** Increment to force a reload while this tab is visible (toolbar refresh control). */
    var manualRefreshKey by remember { mutableIntStateOf(0) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    val isSelectionMode = selectedUris.isNotEmpty()
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    val suppressRecordFab = LocalSuppressRecordFabForListSelection.current
    val scope = rememberCoroutineScope()

    BackHandler(enabled = isSelectionMode) {
        selectedUris = emptySet()
    }

    DisposableEffect(isSelectionMode) {
        suppressRecordFab.value = isSelectionMode
        onDispose { suppressRecordFab.value = false }
    }

    // Reload when returning to this tab, after manual refresh, or when the key changes.
    LifecycleResumeEffect(manualRefreshKey) {
        isLoading = true
        val job = scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { loadScreenshots(context) }
                screenshots = list
            } finally {
                isLoading = false
            }
        }
        onPauseOrDispose { job.cancel() }
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
            title = { Text(stringResource(R.string.delete_screenshot_title)) },
            text = { Text(stringResource(R.string.delete_screenshot_message, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    try { context.contentResolver.delete(item.uri, null, null) } catch (_: Exception) {}
                    screenshots = screenshots.filter { it.uri != item.uri }
                    screenshotToDelete = null
                }) { Text(stringResource(R.string.action_delete), color = accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { screenshotToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
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
                        try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                    screenshots = screenshots.filter { it.uri !in toDelete }
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
                        text = stringResource(R.string.screenshots_empty_title),
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.screenshots_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = if (isSelectionMode) 88.dp else 12.dp
                    ),
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
                                text = stringResource(R.string.screenshots_header_format, screenshots.size),
                                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = { manualRefreshKey++ }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.content_desc_refresh),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    items(screenshots, key = { it.uri.toString() }) { item ->
                        ScreenshotCard(
                            item = item,
                            accent = accent,
                            isSelectionMode = isSelectionMode,
                            isSelected = item.uri in selectedUris,
                            onShare = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, item.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_screenshot_chooser_title)))
                            },
                            onDelete = { screenshotToDelete = item },
                            onOpen = {
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(item.uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try { context.startActivity(viewIntent) } catch (_: Exception) {}
                            },
                            onLongClick = { selectedUris = setOf(item.uri) },
                            onToggleSelect = {
                                selectedUris = if (item.uri in selectedUris) {
                                    selectedUris - item.uri
                                } else {
                                    selectedUris + item.uri
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
            val allSelected = screenshots.isNotEmpty() && selectedUris.size == screenshots.size
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
                            else screenshots.map { it.uri }.toSet()
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
                                type = "image/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, context.getString(R.string.multiselect_share_screenshots))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenshotCard(
    item: ScreenshotItem,
    accent: Color,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isSelected) Modifier.border(2.dp, accent, RoundedCornerShape(12.dp))
                else Modifier
            )
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 12.dp
        ) {
            Box(
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            if (isSelectionMode) onToggleSelect() else onOpen()
                        },
                        onLongClick = {
                            if (!isSelectionMode) onLongClick() else onToggleSelect()
                        }
                    )
            ) {
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

                // Options menu button (hidden in selection mode)
                if (!isSelectionMode) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.content_desc_options),
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
                                text = { Text(stringResource(R.string.action_open), color = Color.White) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = accent) },
                                onClick = { showMenu = false; onOpen() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share), color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = accent) },
                                onClick = { showMenu = false; onShare() }
                            )
                            HorizontalDivider(color = Color(0xFF333333))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = accent) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = accent) },
                                onClick = { showMenu = false; onDelete() }
                            )
                        }
                    }
                }

                // Selection overlay
                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(
                                if (isSelected) accent.copy(alpha = 0.30f) else Color(0x44000000)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(24.dp)
                            .background(
                                if (isSelected) accent else Color(0x88000000),
                                CircleShape
                            )
                            .border(
                                2.dp,
                                if (isSelected) accent else Color.White.copy(alpha = 0.7f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
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
