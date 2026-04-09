package com.ibbie.catrec_screenrecorcer.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.navigation.Screen
import com.ibbie.catrec_screenrecorcer.ui.recordings.RecordingEntry
import com.ibbie.catrec_screenrecorcer.ui.recordings.loadAppRecordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PendingVideoTool { Trim, Compress, Gif }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(navController: NavController) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val saveLocationUri by repository.saveLocationUri.collectAsState(initial = null)

    var showSourceDialog by remember { mutableStateOf(false) }
    var pendingTool by remember { mutableStateOf<PendingVideoTool?>(null) }
    var showCatRecSheet by remember { mutableStateOf(false) }

    fun navigateForTool(tool: PendingVideoTool, uri: Uri) {
        val enc = Uri.encode(uri.toString())
        when (tool) {
            PendingVideoTool.Trim -> navController.navigate("trim?videoUri=$enc")
            PendingVideoTool.Compress -> navController.navigate("compress?videoUri=$enc")
            PendingVideoTool.Gif -> navController.navigate("video_to_gif?videoUri=$enc")
        }
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val tool = pendingTool
        pendingTool = null
        if (uri != null && tool != null) navigateForTool(tool, uri)
    }

    val imageEditorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val enc = Uri.encode(uri.toString())
            navController.navigate("image_editor?imageUri=$enc") {
                launchSingleTop = true
            }
        }
    }

    val trimTitle = stringResource(R.string.tool_video_trim)
    val compressTitle = stringResource(R.string.tool_compress)
    val gifTitle = stringResource(R.string.tool_video_to_gif)
    val mergeTitle = stringResource(R.string.tool_merge_clips)

    val editImageTitle = stringResource(R.string.tool_edit_image)

    val imageTools = listOf(
        ToolItem(editImageTitle, Icons.Default.Image) {
            imageEditorLauncher.launch("image/*")
        },
    )

    val tools = listOf(
        ToolItem(trimTitle, Icons.Default.ContentCut) {
            pendingTool = PendingVideoTool.Trim
            showSourceDialog = true
        },
        ToolItem(compressTitle, Icons.Default.Compress) {
            pendingTool = PendingVideoTool.Compress
            showSourceDialog = true
        },
        ToolItem(gifTitle, Icons.Default.Gif) {
            pendingTool = PendingVideoTool.Gif
            showSourceDialog = true
        },
        ToolItem(mergeTitle, Icons.AutoMirrored.Filled.CallMerge) {
            navController.navigate(Screen.MergeVideos.route)
        },
    )

    if (showSourceDialog && pendingTool != null) {
        AlertDialog(
            onDismissRequest = {
                showSourceDialog = false
                pendingTool = null
            },
            title = { Text(stringResource(R.string.editor_pick_video_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSourceDialog = false
                        pendingTool = null
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showSourceDialog = false
                            showCatRecSheet = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.editor_pick_catrec))
                    }
                    TextButton(
                        onClick = {
                            showSourceDialog = false
                            storageLauncher.launch("video/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.editor_pick_storage))
                    }
                }
            },
        )
    }

    if (showCatRecSheet) {
        val tool = pendingTool
        ModalBottomSheet(onDismissRequest = {
            showCatRecSheet = false
            pendingTool = null
        }) {
            var entries by remember { mutableStateOf<List<RecordingEntry>>(emptyList()) }
            LaunchedEffect(Unit) {
                entries = withContext(Dispatchers.IO) { loadAppRecordings(context, saveLocationUri) }
            }
            Text(
                stringResource(R.string.editor_pick_catrec),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.padding(bottom = 32.dp)) {
                items(entries, key = { it.uri.toString() }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.displayName) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (tool != null) {
                                    navigateForTool(tool, entry.uri)
                                    pendingTool = null
                                }
                                showCatRecSheet = false
                            },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                stringResource(R.string.tools_image_editing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        gridItems(imageTools) { tool ->
            ToolCard(tool)
        }
        item(span = { GridItemSpan(2) }) {
            Spacer(Modifier.height(8.dp))
        }
        item(span = { GridItemSpan(2) }) {
            Text(
                stringResource(R.string.tools_video_editing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        gridItems(tools) { tool ->
            ToolCard(tool)
        }
    }
}

data class ToolItem(
    val name: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun ToolCard(tool: ToolItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .clickable { tool.onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
