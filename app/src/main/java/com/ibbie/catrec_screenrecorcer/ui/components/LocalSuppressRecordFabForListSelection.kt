package com.ibbie.catrec_screenrecorcer.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When true, the main record FAB is hidden so multiselect toolbars (share/delete) stay unobstructed.
 * [com.ibbie.catrec_screenrecorcer.navigation.CatRecNavGraph] provides the [MutableState] holder.
 */
val LocalSuppressRecordFabForListSelection: ProvidableCompositionLocal<MutableState<Boolean>> =
    staticCompositionLocalOf { mutableStateOf(false) }
