package com.ibbie.catrec_screenrecorcer.ui.adaptive

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isUnspecified

/**
 * Slightly smaller type scale when the window is [narrow][androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact],
 * so phones at default display size fit tab chips, settings rows, and headers without wrapping
 * (tablets and unfolded widths keep the default scale).
 */
private const val COMPACT_TYPE_SCALE = 0.91f

private fun TextStyle.scaled(factor: Float): TextStyle =
    copy(
        fontSize = fontSize * factor,
        lineHeight = if (lineHeight.isUnspecified) lineHeight else lineHeight * factor,
    )

fun Typography.scaledForCompactWidth(factor: Float = COMPACT_TYPE_SCALE): Typography =
    copy(
        displayLarge = displayLarge.scaled(factor),
        displayMedium = displayMedium.scaled(factor),
        displaySmall = displaySmall.scaled(factor),
        headlineLarge = headlineLarge.scaled(factor),
        headlineMedium = headlineMedium.scaled(factor),
        headlineSmall = headlineSmall.scaled(factor),
        titleLarge = titleLarge.scaled(factor),
        titleMedium = titleMedium.scaled(factor),
        titleSmall = titleSmall.scaled(factor),
        bodyLarge = bodyLarge.scaled(factor),
        bodyMedium = bodyMedium.scaled(factor),
        bodySmall = bodySmall.scaled(factor),
        labelLarge = labelLarge.scaled(factor),
        labelMedium = labelMedium.scaled(factor),
        labelSmall = labelSmall.scaled(factor),
    )
