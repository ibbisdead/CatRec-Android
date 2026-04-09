package com.ibbie.catrec_screenrecorcer.ui.faq

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.utils.FeedbackEmail

private data class FeedbackIssueOption(
    val id: String,
    val labelRes: Int,
)

private val feedbackIssues = listOf(
    FeedbackIssueOption("recording_stopped", R.string.feedback_issue_recording_stopped),
    FeedbackIssueOption("crash", R.string.feedback_issue_crash),
    FeedbackIssueOption("no_sound", R.string.feedback_issue_no_sound),
    FeedbackIssueOption("video_black", R.string.feedback_issue_video_black),
    FeedbackIssueOption("cant_start", R.string.feedback_issue_cant_start),
    FeedbackIssueOption("overlay", R.string.feedback_issue_overlay),
    FeedbackIssueOption("gif_export", R.string.feedback_issue_gif_export),
    FeedbackIssueOption("battery_oem", R.string.feedback_issue_battery_oem),
    FeedbackIssueOption("storage", R.string.feedback_issue_storage),
    FeedbackIssueOption("quality", R.string.feedback_issue_quality),
    FeedbackIssueOption("other", R.string.feedback_issue_other),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(navController: NavController) {
    val context = LocalContext.current
    var selectedIssueIndex by remember { mutableIntStateOf(-1) }
    var description by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Uri>() }

    val pickImages = rememberLauncherForActivityResult(
        PickMultipleVisualMedia(maxItems = 5),
    ) { uris ->
        for (u in uris) {
            if (attachments.size < 5 && u !in attachments) attachments.add(u)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.feedback_issue_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                feedbackIssues.forEachIndexed { index, issue ->
                    FilterChip(
                        selected = selectedIssueIndex == index,
                        onClick = { selectedIssueIndex = index },
                        label = { Text(stringResource(issue.labelRes)) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.feedback_description_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                placeholder = { Text(stringResource(R.string.feedback_description_hint)) },
                minLines = 5,
                maxLines = 10,
            )

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.feedback_screenshots_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.feedback_screenshots_count, attachments.size, 5),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feedback_screenshots_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    AssistChip(
                        onClick = {
                            pickImages.launch(
                                PickVisualMediaRequest(PickVisualMedia.ImageOnly),
                            )
                        },
                        label = { Text(stringResource(R.string.feedback_add_screenshots)) },
                        leadingIcon = {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        },
                        enabled = attachments.size < 5,
                    )
                }
                items(attachments, key = { it.toString() }) { uri ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(96.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { attachments.remove(uri) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(28.dp),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    if (selectedIssueIndex < 0) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.feedback_select_issue),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@Button
                    }
                    val desc = description.trim()
                    if (desc.length < 8) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.feedback_description_too_short),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@Button
                    }
                    val issue = feedbackIssues[selectedIssueIndex]
                    val label = context.getString(issue.labelRes)
                    FeedbackEmail.send(context, label, desc, attachments.toList())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.feedback_send),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feedback_send_note, FeedbackEmail.ADDRESS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
