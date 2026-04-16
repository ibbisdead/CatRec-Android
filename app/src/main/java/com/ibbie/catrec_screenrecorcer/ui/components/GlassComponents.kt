package com.ibbie.catrec_screenrecorcer.ui.components

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibbie.catrec_screenrecorcer.ui.theme.CrimsonRed
import com.ibbie.catrec_screenrecorcer.ui.theme.isLightTheme

// ─── Accent Color CompositionLocals ──────────────────────────────────────────

/**
 * The primary accent color for all glass components (solid Color).
 * Defaults to CrimsonRed. Override in NavGraph via CompositionLocalProvider.
 */
val LocalAccentColor = compositionLocalOf { CrimsonRed }

/**
 * Brush used for rim borders on glass cards.
 * When gradient mode is active this is a two-color linear gradient;
 * otherwise a vertical fade of the accent color.
 */
val LocalAccentBrush =
    compositionLocalOf<Brush> {
        Brush.verticalGradient(
            listOf(CrimsonRed.copy(alpha = 0.4f), CrimsonRed.copy(alpha = 0.1f)),
        )
    }

/** Darkens a color by the given factor (0 = black, 1 = unchanged). */
fun Color.darkened(factor: Float = 0.3f) = Color(red * factor, green * factor, blue * factor, alpha)

// ─── Performance Mode CompositionLocal ───────────────────────────────────────

/**
 * When true, all GlassCards skip real-time blur and use static frosted gradient.
 * Value is logical OR of: user preference + hardware auto-detection.
 */
val LocalPerformanceMode = compositionLocalOf { false }

// ─── Device & Power Capability Checks ────────────────────────────────────────

/**
 * Returns true when the hardware cannot support real-time blur:
 *  - Android below API 31, OR
 *  - Less than 6 GB physical RAM
 *
 * Exposed so SettingsScreen can show "already on performance mode" warnings.
 */
@Composable
fun rememberIsLowEndDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L * 1024L) < 6
    }
}

/**
 * True when the device supports blur AND performance mode is off.
 * Z-axis rule: Background > Glass Card > Content (max 3 layers).
 */
@Composable
fun rememberCanUseBlur(): Boolean {
    val performanceMode = LocalPerformanceMode.current
    val context = LocalContext.current
    // performanceMode is a key so the block re-runs whenever the user toggles the setting.
    return remember(performanceMode) {
        if (performanceMode) return@remember false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L * 1024L) >= 6
    }
}

/**
 * True when the device is in Battery Saver mode.
 * When true, the Crimson Pulse animation on the record button is disabled.
 */
@Composable
fun rememberIsBatterySaver(): Boolean {
    val context = LocalContext.current
    return remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isPowerSaveMode
    }
}

// ─── GlassCard ───────────────────────────────────────────────────────────────

/**
 * Core reusable glass-morphism card.
 *
 * Layers (Z-axis, max 3):
 *   1. Background (app screen)
 *   2. Glass surface — semi-transparent dark gradient + 1dp accent rim light
 *   3. Content
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    disableBlur: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.isLightTheme()
    val canUseBlur = rememberCanUseBlur() && !disableBlur
    val rimBrush = LocalAccentBrush.current
    val shape = RoundedCornerShape(cornerRadius)

    val surfaceBrush =
        if (light) {
            Brush.verticalGradient(
                listOf(
                    scheme.surface.copy(alpha = 0.92f),
                    scheme.surfaceVariant.copy(alpha = 0.88f),
                    scheme.surface.copy(alpha = 0.92f),
                ),
            )
        } else if (canUseBlur) {
            Brush.verticalGradient(listOf(Color(0x770A0A0A), Color(0xBB0A0A0A)))
        } else {
            Brush.verticalGradient(listOf(Color(0xBB0A0A0A), Color(0xCC0A0A0A), Color(0xBB0A0A0A)))
        }

    Box(modifier = modifier.clip(shape)) {
        if (canUseBlur && !light) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color(0x550A0A0A))
                        .blur(radius = 20.dp),
            )
        }
        Column(
            modifier =
                Modifier
                    .background(brush = surfaceBrush)
                    .border(width = 1.dp, brush = rimBrush, shape = shape),
        ) {
            content()
        }
    }
}

// ─── GlassPill ───────────────────────────────────────────────────────────────

/**
 * Small floating pill for HUD stats (FPS, Resolution, Bitrate, Audio mode).
 */
@Composable
fun GlassPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.isLightTheme()
    val canUseBlur = rememberCanUseBlur()
    val accent = LocalAccentColor.current
    val rimBrush = LocalAccentBrush.current
    val shape = RoundedCornerShape(50)
    val bgColor =
        when {
            light -> scheme.surfaceVariant.copy(alpha = 0.95f)
            canUseBlur -> Color(0x990A0A0A)
            else -> Color(0xBB0A0A0A)
        }

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(bgColor, shape)
                .border(width = 1.dp, brush = rimBrush, shape = shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
        }
    }
}

// ─── CatRecordButton ─────────────────────────────────────────────────────────

/**
 * CatRec record button — clean glass circle with accent pulse ring.
 */
@Composable
fun CatRecordButton(
    isRecording: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccentColor.current
    val isBatterySaver = rememberIsBatterySaver()
    val showPulse = isRecording && !isBatterySaver

    val infiniteTransition = rememberInfiniteTransition(label = "CatPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (showPulse) 1.25f else 1.0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "PulseScale",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = if (showPulse) 0.55f else 0.20f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "PulseAlpha",
    )

    val scheme = MaterialTheme.colorScheme
    val onMuted = scheme.onSurfaceVariant
    val rimColor =
        when {
            isRecording -> accent
            isEnabled -> accent.darkened(0.45f)
            else -> onMuted.copy(alpha = 0.5f)
        }
    val iconTint =
        when {
            isRecording -> accent
            isEnabled -> accent.copy(alpha = 0.85f)
            else -> onMuted
        }

    // Caller supplies bounded size (ConstraintLayout / Box + aspectRatio); inner stays circular.
    Box(modifier = modifier) {
        val innerFrac = 0.46f
        // ── Pulse ring ───────────────────────────────────────────────────────
        if (showPulse) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(innerFrac * pulseScale)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = pulseAlpha * 0.35f)),
            )
        }

        // ── Glass circle button ──────────────────────────────────────────────
        val circleBrush =
            if (scheme.isLightTheme()) {
                Brush.radialGradient(
                    colors =
                        listOf(
                            scheme.primary.copy(alpha = 0.12f),
                            scheme.surfaceVariant,
                        ),
                )
            } else {
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1A0008), Color(0xFF0A0A0A)),
                )
            }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(innerFrac)
                    .clip(CircleShape)
                    .background(brush = circleBrush)
                    .border(
                        width = if (isRecording) 2.dp else 1.5.dp,
                        color = rimColor,
                        shape = CircleShape,
                    ).clickable(enabled = isEnabled || isRecording, onClick = onClick),
        ) {
            Icon(
                imageVector =
                    when {
                        isRecording -> Icons.Default.Stop
                        !isEnabled -> Icons.Default.Lock
                        else -> Icons.Default.FiberManualRecord
                    },
                contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                tint = iconTint,
                modifier = Modifier.fillMaxSize(0.4f),
            )
        }
    }
}

// ─── GlassSlider ─────────────────────────────────────────────────────────────

@Composable
fun GlassSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    val accent = LocalAccentColor.current
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = accent.copy(alpha = 0.2f),
            ) {
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val tickSurface = MaterialTheme.colorScheme.surface
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors =
                SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    activeTickColor = tickSurface,
                    inactiveTrackColor = accent.darkened(0.35f),
                    inactiveTickColor = accent.darkened(0.2f),
                ),
        )
    }
}

// ─── GlassSectionHeader ──────────────────────────────────────────────────────

@Composable
fun GlassSectionHeader(title: String) {
    val accent = LocalAccentColor.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}
