@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

package com.ibbie.catrec_screenrecorcer.ui.adaptive

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * [WindowSizeClass] for the activity window (multi-window / resize aware).
 * Defaults suit previews when not provided.
 */
val LocalWindowSizeClass =
    compositionLocalOf {
        WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
    }
