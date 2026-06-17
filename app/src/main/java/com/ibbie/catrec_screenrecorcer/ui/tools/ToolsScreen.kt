package com.ibbie.catrec_screenrecorcer.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.navigation.Screen
import com.ibbie.catrec_screenrecorcer.ui.components.ProBadge
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

    fun navigateForTool(
        tool: PendingVideoTool,
        uri: Uri,
    ) {
        val enc = Uri.encode(uri.toString())
        when (tool) {
            PendingVideoTool.Trim -> navController.navigate("trim?videoUri=$enc")
            PendingVideoTool.Compress -> navController.navigate("compress?videoUri=$enc")
            PendingVideoTool.Gif -> navController.navigate("video_to_gif?videoUri=$enc")
        }
    }

    fun closeVideoSourcePicker(clearPendingTool: Boolean = true) {
        showSourceDialog = false
        showCatRecSheet = false
        if (clearPendingTool) pendingTool = null
    }

    val storageLauncher =
        rememberLauncherForActivityResult(
            PickVisualMedia(),
        ) { uri ->
            val tool = pendingTool
            closeVideoSourcePicker()
            if (uri != null && tool != null) navigateForTool(tool, uri)
        }

    val imageEditorLauncher =
        rememberLauncherForActivityResult(
            PickVisualMedia(),
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

    val tools =
        listOf(
            ToolItem(editImageTitle, Icons.Default.Image) {
                imageEditorLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            },
            ToolItem(trimTitle, Icons.Default.ContentCut) {
                pendingTool = PendingVideoTool.Trim
                showSourceDialog = true
                showCatRecSheet = false
            },
            ToolItem(compressTitle, Icons.Default.Compress) {
                pendingTool = PendingVideoTool.Compress
                showSourceDialog = true
                showCatRecSheet = false
            },
            ToolItem(gifTitle, Icons.Default.Gif, isPro = true) {
                pendingTool = PendingVideoTool.Gif
                showSourceDialog = true
                showCatRecSheet = false
            },
            ToolItem(mergeTitle, Icons.AutoMirrored.Filled.CallMerge, isPro = true) {
                navController.navigate(Screen.MergeVideos.route)
            },
        )

    if (showSourceDialog && pendingTool != null) {
        AlertDialog(
            onDismissRequest = {
                closeVideoSourcePicker()
            },
            title = { Text(stringResource(R.string.editor_pick_video_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeVideoSourcePicker()
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
                            closeVideoSourcePicker(clearPendingTool = false)
                            storageLauncher.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.editor_pick_storage))
                    }
                }
            },
        )
    }

    if (showCatRecSheet && pendingTool != null) {
        val tool = pendingTool
        ModalBottomSheet(onDismissRequest = {
            closeVideoSourcePicker()
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (tool != null) {
                                        navigateForTool(tool, entry.uri)
                                        closeVideoSourcePicker()
                                    }
                                },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.tools_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        items(tools) { tool ->
            EditorToolRow(tool)
        }
    }
}

data class ToolItem(
    val name: String,
    val icon: ImageVector,
    val isPro: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun EditorToolRow(tool: ToolItem) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .clickable { tool.onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = tool.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tool.isPro) {
                Spacer(Modifier.width(12.dp))
                ProBadge()
            }
        }
    }
}
