package com.ibbie.catrec_screenrecorcer.ui.components

import android.os.Environment
import android.os.StatFs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.ibbie.catrec_screenrecorcer.R
import kotlinx.coroutines.delay
import java.util.Locale

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
fun StorageIndicator(modifier: Modifier = Modifier) {
    var usedBytes by remember { mutableLongStateOf(0L) }
    var freeBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.totalBytes
                val avail = stat.availableBytes
                usedBytes = (total - avail).coerceAtLeast(0L)
                freeBytes = avail.coerceAtLeast(0L)
            }
            delay(30_000L)
        }
    }

    Text(
        text = stringResource(
            R.string.storage_indicator_format,
            formatBytes(usedBytes),
            formatBytes(freeBytes),
        ),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
