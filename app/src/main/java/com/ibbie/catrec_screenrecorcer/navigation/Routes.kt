package com.ibbie.catrec_screenrecorcer.navigation

import androidx.annotation.StringRes
import com.ibbie.catrec_screenrecorcer.R

sealed class Screen(val route: String, @param:StringRes val titleRes: Int) {
    object Recording   : Screen("recording",                       R.string.tab_recording)
    object Screenshots : Screen("screenshots",                     R.string.tab_screenshots)
    object Recordings  : Screen("recordings",                      R.string.tab_recordings)
    object Settings    : Screen("settings",                        R.string.tab_settings)
    object Support     : Screen("support",                         R.string.tab_support)
    object Tools       : Screen("tools",                           R.string.tab_tools)
    object Home        : Screen("home",                            R.string.app_name)
    object Crop        : Screen("crop/{imageUri}",                 R.string.app_name)
    object Player      : Screen("player?videoUri={videoUri}",      R.string.app_name)
    object Trim        : Screen("trim?videoUri={videoUri}",        R.string.app_name)
}
