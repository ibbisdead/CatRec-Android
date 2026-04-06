package com.ibbie.catrec_screenrecorcer.ui.recording

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.ibbie.catrec_screenrecorcer.ui.components.CatRecordButton
import com.ibbie.catrec_screenrecorcer.ui.components.GlassPill

/**
 * Wide window: vertical guidelines at 20% / 80%, square HUD, 1:1 record control.
 */
@Composable
fun RecordingHudWide(
    fpsLabel: String,
    fpsValue: String,
    resLabel: String,
    resValue: String,
    mbpsLabel: String,
    mbpsValue: String,
    audioLabel: String,
    audioValue: String,
    isRecordingOrBuffering: Boolean,
    recordEnabled: Boolean,
    onRecordClick: () -> Unit,
    scalePulseFromRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val buttonScale by animateFloatAsState(
        targetValue = if (scalePulseFromRecording) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "HudButtonScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(buttonScale)
    ) {
        ConstraintLayout(modifier = Modifier.fillMaxSize()) {
            val g20 = createGuidelineFromStart(0.2f)
            val g80 = createGuidelineFromStart(0.8f)

            val fpsRef = createRef()
            val resRef = createRef()
            val recordRef = createRef()
            val mbpsRef = createRef()
            val audioRef = createRef()

            GlassPill(
                label = fpsLabel,
                value = fpsValue,
                modifier = Modifier.constrainAs(fpsRef) {
                    top.linkTo(parent.top, margin = 12.dp)
                    start.linkTo(g20)
                    end.linkTo(g20)
                    width = Dimension.wrapContent
                    height = Dimension.wrapContent
                }
            )
            GlassPill(
                label = resLabel,
                value = resValue,
                modifier = Modifier.constrainAs(resRef) {
                    top.linkTo(parent.top, margin = 12.dp)
                    start.linkTo(g80)
                    end.linkTo(g80)
                    width = Dimension.wrapContent
                    height = Dimension.wrapContent
                }
            )

            CatRecordButton(
                isRecording = isRecordingOrBuffering,
                isEnabled = recordEnabled,
                onClick = onRecordClick,
                modifier = Modifier.constrainAs(recordRef) {
                    centerHorizontallyTo(parent)
                    centerVerticallyTo(parent)
                    width = Dimension.percent(0.46f)
                    height = Dimension.ratio("1:1")
                }
            )

            GlassPill(
                label = mbpsLabel,
                value = mbpsValue,
                modifier = Modifier.constrainAs(mbpsRef) {
                    bottom.linkTo(parent.bottom, margin = 12.dp)
                    start.linkTo(g20)
                    end.linkTo(g20)
                    width = Dimension.wrapContent
                    height = Dimension.wrapContent
                }
            )
            GlassPill(
                label = audioLabel,
                value = audioValue,
                modifier = Modifier.constrainAs(audioRef) {
                    bottom.linkTo(parent.bottom, margin = 12.dp)
                    start.linkTo(g80)
                    end.linkTo(g80)
                    width = Dimension.wrapContent
                    height = Dimension.wrapContent
                }
            )
        }
    }
}

/**
 * Compact width (&lt; 600dp): stacked rows; record control stays 1:1.
 */
@Composable
fun RecordingHudCompact(
    fpsLabel: String,
    fpsValue: String,
    resLabel: String,
    resValue: String,
    mbpsLabel: String,
    mbpsValue: String,
    audioLabel: String,
    audioValue: String,
    isRecordingOrBuffering: Boolean,
    recordEnabled: Boolean,
    onRecordClick: () -> Unit,
    scalePulseFromRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val buttonScale by animateFloatAsState(
        targetValue = if (scalePulseFromRecording) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "HudCompactButtonScale"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                GlassPill(label = fpsLabel, value = fpsValue)
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                GlassPill(label = resLabel, value = resValue)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(1f)
                .scale(buttonScale),
            contentAlignment = Alignment.Center
        ) {
            CatRecordButton(
                isRecording = isRecordingOrBuffering,
                isEnabled = recordEnabled,
                onClick = onRecordClick,
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                GlassPill(label = mbpsLabel, value = mbpsValue)
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                GlassPill(label = audioLabel, value = audioValue)
            }
        }
    }
}
