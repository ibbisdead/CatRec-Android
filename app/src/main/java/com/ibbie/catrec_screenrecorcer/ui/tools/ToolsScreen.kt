package com.ibbie.catrec_screenrecorcer.ui.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.service.OverlayService
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel

@Composable
fun ToolsScreen(
    viewModel: RecordingViewModel = viewModel()
) {
    val context = LocalContext.current
    val cameraOverlay by viewModel.cameraOverlay.collectAsState()
    val showWatermark by viewModel.showWatermark.collectAsState()

    val camTitle = stringResource(R.string.tools_camera_overlay)
    val wmTitle = stringResource(R.string.tools_watermark)
    val trimTitle = stringResource(R.string.tool_video_trim)
    val compressTitle = stringResource(R.string.tool_compress)
    val gifTitle = stringResource(R.string.tool_to_gif)
    val frameTitle = stringResource(R.string.tool_frame_extractor)
    val mergeTitle = stringResource(R.string.tool_merge_clips)
    val renameTitle = stringResource(R.string.tool_video_rename)

    // Overlay Tools
    val overlayTools = listOf(
        ToolItem(camTitle, Icons.Default.CameraFront, isActive = cameraOverlay) {
            toggleOverlay(context, viewModel, "camera", !cameraOverlay)
        },
        ToolItem(wmTitle, Icons.AutoMirrored.Filled.BrandingWatermark, isActive = showWatermark) {
            toggleOverlay(context, viewModel, "watermark", !showWatermark)
        }
    )

    // Editing Tools (UI Placeholders for now)
    val editingTools = listOf(
        ToolItem(trimTitle, Icons.Default.ContentCut, isActive = false) {
            Toast.makeText(context, context.getString(R.string.tools_toast_coming, trimTitle), Toast.LENGTH_SHORT).show()
        },
        ToolItem(compressTitle, Icons.Default.Compress, isActive = false) {
            Toast.makeText(context, context.getString(R.string.tools_toast_coming, compressTitle), Toast.LENGTH_SHORT).show()
        },
        ToolItem(gifTitle, Icons.Default.Gif, isActive = false) {
            Toast.makeText(context, context.getString(R.string.tools_toast_coming, gifTitle), Toast.LENGTH_SHORT).show()
        },
        ToolItem(frameTitle, Icons.Default.Image, isActive = false) {
            Toast.makeText(context, context.getString(R.string.tools_toast_coming, frameTitle), Toast.LENGTH_SHORT).show()
        },
        ToolItem(mergeTitle, Icons.AutoMirrored.Filled.CallMerge, isActive = false) {
            Toast.makeText(context, context.getString(R.string.tools_toast_coming, mergeTitle), Toast.LENGTH_SHORT).show()
        },
        ToolItem(renameTitle, Icons.Default.DriveFileRenameOutline, isActive = false) {
            Toast.makeText(context, context.getString(R.string.tools_toast_rename_hint), Toast.LENGTH_SHORT).show()
        }
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Quick Overlays
        item(span = { GridItemSpan(2) }) {
            Text(stringResource(R.string.tools_quick_overlays), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        
        items(overlayTools) { tool ->
            ToolCard(tool)
        }
        
        // Section: Video Editing
        item(span = { GridItemSpan(2) }) {
            Text(stringResource(R.string.tools_video_editing), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        
        items(editingTools) { tool ->
            ToolCard(tool)
        }
    }
}

data class ToolItem(
    val name: String, 
    val icon: ImageVector, 
    val isActive: Boolean,
    val onClick: () -> Unit
)

@Composable
fun ToolCard(tool: ToolItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f) // Slightly wider
            .clickable { tool.onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (tool.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (tool.isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = if (tool.isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            if (tool.isActive) {
                Text(
                    text = stringResource(R.string.label_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun toggleOverlay(context: Context, viewModel: RecordingViewModel, type: String, enable: Boolean) {
    if (enable && !Settings.canDrawOverlays(context)) {
        Toast.makeText(context, context.getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        return
    }

    if (type == "camera") viewModel.setCameraOverlay(enable)
    if (type == "watermark") viewModel.setShowWatermark(enable)

    val intent = Intent(context, OverlayService::class.java).apply {
        action = OverlayService.ACTION_SHOW_OVERLAYS
        putExtra(OverlayService.EXTRA_SHOW_CAMERA, if (type == "camera") enable else viewModel.cameraOverlay.value)
        putExtra(OverlayService.EXTRA_SHOW_WATERMARK, if (type == "watermark") enable else viewModel.showWatermark.value)
        putExtra(OverlayService.EXTRA_SHOW_CONTROLS, false)
    }
    try {
        context.startService(intent)
    } catch (e: Exception) {
        // Service might not be running; setting is saved for next time.
    }
}
