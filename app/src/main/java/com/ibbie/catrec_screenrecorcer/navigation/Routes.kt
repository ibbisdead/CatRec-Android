package com.ibbie.catrec_screenrecorcer.navigation

sealed class Screen(val route: String, val title: String) {
    object Recording : Screen("recording", "Recording")
    object Screenshots : Screen("screenshots", "Screenshots")
    object Recordings : Screen("recordings", "Recordings")
    object Settings : Screen("settings", "Settings")
    object Support : Screen("support", "Support")
    object Tools : Screen("tools", "Tools")
    object Home : Screen("home", "Home")
    object Crop : Screen("crop/{imageUri}", "Crop Image")
    object Player : Screen("player?videoUri={videoUri}", "Player")
    object Trim : Screen("trim?videoUri={videoUri}", "Trim")
}
