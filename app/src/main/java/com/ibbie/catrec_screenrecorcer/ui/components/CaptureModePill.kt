package com.ibbie.catrec_screenrecorcer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.ui.theme.CaptureModeColors

private val iconButtonSize = 40.dp
private val gifButtonSize = 46.dp
private val iconSize = 22.dp

@Composable
fun CaptureModePill(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
    enabled: Boolean,
    isRecording: Boolean = false,
    isBuffering: Boolean = false,
    captureMode: String = CaptureMode.RECORD,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = surface.copy(alpha = 0.85f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PillIconCircle(
                selected = selectedMode == CaptureMode.RECORD,
                segmentTint = CaptureModeColors.RecordingRed,
                onSurface = onSurface,
                enabled = enabled,
                onClick = { onModeSelected(CaptureMode.RECORD) },
                contentDescription = stringResource(R.string.capture_mode_recording),
                icon = Icons.Default.Videocam,
                showStopInstead = isRecording && captureMode != CaptureMode.GIF,
            )
            PillIconCircle(
                selected = selectedMode == CaptureMode.CLIPPER,
                segmentTint = CaptureModeColors.ClipperYellow,
                onSurface = onSurface,
                enabled = enabled,
                onClick = { onModeSelected(CaptureMode.CLIPPER) },
                contentDescription = stringResource(R.string.capture_mode_clipper),
                icon = Icons.Default.ContentCut,
                showStopInstead = isBuffering,
            )
            PillGifCircle(
                selected = selectedMode == CaptureMode.GIF,
                segmentTint = CaptureModeColors.GifBlue,
                onSurface = onSurface,
                enabled = enabled,
                onClick = { onModeSelected(CaptureMode.GIF) },
                contentDescription = stringResource(R.string.capture_mode_gif),
                showStopInstead = isRecording && captureMode == CaptureMode.GIF,
            )
        }
    }
}

@Composable
private fun PillIconCircle(
    selected: Boolean,
    segmentTint: Color,
    onSurface: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    showStopInstead: Boolean,
) {
    val bg = if (selected) segmentTint.copy(alpha = 0.22f) else Color.Transparent
    val tint = if (selected) segmentTint else onSurface
    Box(
        modifier = Modifier
            .size(iconButtonSize)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (showStopInstead) Icons.Default.Stop else icon,
            contentDescription = if (showStopInstead) {
                stringResource(R.string.notif_action_stop)
            } else {
                contentDescription
            },
            tint = tint,
            modifier = Modifier.size(if (showStopInstead) (iconSize + 2.dp) else iconSize),
        )
    }
}

@Composable
private fun PillGifCircle(
    selected: Boolean,
    segmentTint: Color,
    onSurface: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    showStopInstead: Boolean,
) {
    val bg = if (selected) segmentTint.copy(alpha = 0.22f) else Color.Transparent
    val color = if (selected) segmentTint else onSurface
    Box(
        modifier = Modifier
            .size(gifButtonSize)
            .semantics { this.contentDescription = contentDescription }
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        if (showStopInstead) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(R.string.notif_action_stop),
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(
                text = "GIF",
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .clearAndSetSemantics { },
            )
        }
    }
}
