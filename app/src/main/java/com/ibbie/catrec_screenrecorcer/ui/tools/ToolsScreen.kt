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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ibbie.catrec_screenrecorcer.service.OverlayService
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel

@Composable
fun ToolsScreen(
    viewModel: RecordingViewModel = viewModel()
) {
    val context = LocalContext.current
    val cameraOverlay by viewModel.cameraOverlay.collectAsState()
    val showWatermark by viewModel.showWatermark.collectAsState()

    // Overlay Tools
    val overlayTools = listOf(
        ToolItem("Camera Overlay", Icons.Default.CameraFront, isActive = cameraOverlay) {
            toggleOverlay(context, viewModel, "camera", !cameraOverlay)
        },
        ToolItem("Watermark", Icons.AutoMirrored.Filled.BrandingWatermark, isActive = showWatermark) {
            toggleOverlay(context, viewModel, "watermark", !showWatermark)
        }
    )
    
    // Editing Tools (UI Placeholders for now)
    val editingTools = listOf(
        ToolItem("Video Trim", Icons.Default.ContentCut, isActive = false) {
             Toast.makeText(context, "Video Trim: Coming Soon", Toast.LENGTH_SHORT).show()
        },
        ToolItem("Compress", Icons.Default.Compress, isActive = false) {
             Toast.makeText(context, "Video Compress: Coming Soon", Toast.LENGTH_SHORT).show()
        },
        ToolItem("To GIF", Icons.Default.Gif, isActive = false) {
             Toast.makeText(context, "Video to GIF: Coming Soon", Toast.LENGTH_SHORT).show()
        },
        ToolItem("Frame Extractor", Icons.Default.Image, isActive = false) {
             Toast.makeText(context, "Frame Extractor: Coming Soon", Toast.LENGTH_SHORT).show()
        },
        ToolItem("Merge Clips", Icons.AutoMirrored.Filled.CallMerge, isActive = false) {
             Toast.makeText(context, "Merge Clips: Coming Soon", Toast.LENGTH_SHORT).show()
        },
        ToolItem("Video Rename", Icons.Default.DriveFileRenameOutline, isActive = false) {
             Toast.makeText(context, "Rename: Use Recordings Tab", Toast.LENGTH_SHORT).show()
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
            Text("Quick Overlays", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        
        items(overlayTools) { tool ->
            ToolCard(tool)
        }
        
        // Section: Video Editing
        item(span = { GridItemSpan(2) }) {
            Text("Video Editing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
                    text = "Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun toggleOverlay(context: Context, viewModel: RecordingViewModel, type: String, enable: Boolean) {
    if (enable && !Settings.canDrawOverlays(context)) {
        Toast.makeText(context, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
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
