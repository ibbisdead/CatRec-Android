package com.ibbie.catrec_screenrecorcer.ui.tools

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.service.EditorVideoTransform
import com.ibbie.catrec_screenrecorcer.ui.recordings.RecordingEntry
import com.ibbie.catrec_screenrecorcer.ui.recordings.loadAppRecordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeVideosScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val saveLocationUri by repository.saveLocationUri.collectAsState(initial = null)

    val clips = remember { mutableStateListOf<Uri>() }
    var showCatRecSheet by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }

    val pickMulti =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetMultipleContents(),
        ) { uris ->
            uris.forEach { clips.add(it) }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.merge_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { showCatRecSheet = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.merge_add_catrec), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { pickMulti.launch("video/*") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.editor_pick_storage), maxLines = 1)
                }
            }
            TextButton(
                onClick = { clips.clear() },
                enabled = clips.isNotEmpty(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.merge_clear_all))
            }
            Text(
                stringResource(R.string.merge_need_two),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(clips, key = { i, u -> "$i-$u" }) { index, uri ->
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.merge_clip_label, index + 1),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { clips.removeAt(index) }) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    if (clips.size < 2) {
                        Toast.makeText(context, context.getString(R.string.merge_need_two), Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    merging = true
                    scope.launch {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val name = "Merged_$ts.mp4"
                        val out = EditorVideoTransform.mergeVideos(context, clips.toList(), name)
                        merging = false
                        if (out != null) {
                            Toast.makeText(context, context.getString(R.string.editor_saved_ok), Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        } else {
                            Toast.makeText(context, context.getString(R.string.editor_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !merging && clips.size >= 2,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
            ) {
                if (merging) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_saving))
                } else {
                    Text(stringResource(R.string.merge_run))
                }
            }
        }
    }

    if (showCatRecSheet) {
        ModalBottomSheet(onDismissRequest = { showCatRecSheet = false }) {
            var entries by remember { mutableStateOf<List<RecordingEntry>>(emptyList()) }
            LaunchedEffect(Unit) {
                entries = withContext(Dispatchers.IO) { loadAppRecordings(context, saveLocationUri) }
            }
            LazyColumn(Modifier.padding(bottom = 32.dp)) {
                items(entries, key = { it.uri.toString() }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.displayName) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clips.add(entry.uri)
                                    showCatRecSheet = false
                                },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
