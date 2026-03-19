package com.ibbie.catrec_screenrecorcer.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentBrush
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.components.LocalPerformanceMode
import com.ibbie.catrec_screenrecorcer.ui.components.rememberIsLowEndDevice
import com.ibbie.catrec_screenrecorcer.ui.theme.CrimsonRed
import com.ibbie.catrec_screenrecorcer.ui.player.PlayerScreen
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingScreen
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel
import com.ibbie.catrec_screenrecorcer.ui.recordings.RecordingsScreen
import com.ibbie.catrec_screenrecorcer.ui.recordings.TrimScreen
import com.ibbie.catrec_screenrecorcer.ui.screenshots.ScreenshotsScreen
import com.ibbie.catrec_screenrecorcer.ui.settings.CropScreen
import com.ibbie.catrec_screenrecorcer.ui.settings.SettingsScreen
import com.ibbie.catrec_screenrecorcer.ui.support.SupportScreen

@Composable
fun CatRecNavGraph(
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val sharedViewModel: RecordingViewModel = viewModel()

    // Effective performance mode = user preference OR hardware auto-detection
    val userPerformanceMode by sharedViewModel.performanceMode.collectAsState()
    val isLowEnd = rememberIsLowEndDevice()
    val effectivePerformanceMode = userPerformanceMode || isLowEnd

    // Accent color — parse stored hex strings into Compose Color
    val accentHex     by sharedViewModel.accentColor.collectAsState()
    val accentHex2    by sharedViewModel.accentColor2.collectAsState()
    val useGradient   by sharedViewModel.accentUseGradient.collectAsState()

    val accentColor = remember(accentHex) {
        runCatching { Color(android.graphics.Color.parseColor("#${accentHex.removePrefix("#").take(6)}")) }
            .getOrDefault(CrimsonRed)
    }
    val accentColor2 = remember(accentHex2) {
        runCatching { Color(android.graphics.Color.parseColor("#${accentHex2.removePrefix("#").take(6)}")) }
            .getOrDefault(Color(0xFFFF6600))
    }
    val accentBrush: Brush = remember(accentColor, accentColor2, useGradient) {
        if (useGradient) {
            Brush.linearGradient(listOf(accentColor.copy(alpha = 0.45f), accentColor2.copy(alpha = 0.45f)))
        } else {
            Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.40f), accentColor.copy(alpha = 0.10f)))
        }
    }

    // Build a dynamic MaterialTheme color scheme from the accent
    val baseScheme = MaterialTheme.colorScheme
    val dynamicScheme = remember(accentColor, baseScheme) {
        baseScheme.copy(
            primary              = accentColor,
            onPrimary            = Color.White,
            secondary            = accentColor,
            onSecondary          = Color.White,
            tertiary             = accentColor,
            onTertiary           = Color.White,
            primaryContainer     = accentColor.copy(alpha = 0.2f),
            onPrimaryContainer   = Color.White,
            outline              = accentColor.copy(alpha = 0.3f)
        )
    }

    val bottomBarScreens = listOf(
        Screen.Recording,
        Screen.Screenshots,
        Screen.Recordings,
        Screen.Settings,
        Screen.Support
    )

    val hideBottomBar = currentRoute?.let { route ->
        route.startsWith("crop") || route.startsWith("player") || route.startsWith("trim")
    } ?: false

    CompositionLocalProvider(
        LocalPerformanceMode provides effectivePerformanceMode,
        LocalAccentColor     provides accentColor,
        LocalAccentBrush     provides accentBrush
    ) {
    MaterialTheme(colorScheme = dynamicScheme) {
    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        bottomBar = {
            if (!hideBottomBar) {
                NavigationBar(
                    containerColor = Color(0xEE0A0A0A),
                    contentColor = Color.White
                ) {
                    bottomBarScreens.forEach { screen ->
                        val selected = navBackStackEntry?.destination?.hierarchy
                            ?.any { it.route == screen.route } == true

                        NavigationBarItem(
                            icon = {
                                val icon = when (screen) {
                                    Screen.Recording   -> Icons.Default.Videocam
                                    Screen.Screenshots -> Icons.Default.CameraAlt
                                    Screen.Recordings  -> Icons.Default.VideoLibrary
                                    Screen.Settings    -> Icons.Default.Settings
                                    Screen.Support     -> Icons.Outlined.Pets   // paw-print for support
                                    else               -> Icons.Default.Videocam
                                }
                                Icon(icon, contentDescription = screen.title)
                            },
                            label = { Text(screen.title) },
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = accentColor,
                                selectedTextColor   = accentColor,
                                indicatorColor      = accentColor.copy(alpha = 0.2f),
                                unselectedIconColor = Color(0xFF555555),
                                unselectedTextColor = Color(0xFF555555)
                            ),
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Recording.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Recording.route) {
                RecordingScreen(viewModel = sharedViewModel)
            }
            composable(Screen.Screenshots.route) {
                ScreenshotsScreen()
            }
            composable(Screen.Recordings.route) {
                RecordingsScreen(navController = navController)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = sharedViewModel, navController = navController)
            }
            composable(Screen.Support.route) {
                SupportScreen()
            }

            composable(
                route = Screen.Crop.route,
                arguments = listOf(navArgument("imageUri") { type = NavType.StringType })
            ) { backStackEntry ->
                val imageUri = backStackEntry.arguments?.getString("imageUri") ?: ""
                CropScreen(
                    imageUriString = imageUri,
                    onCropDone = { uri ->
                        sharedViewModel.setWatermarkImageUri(uri.toString())
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Player.route,
                arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
            ) { backStackEntry ->
                val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: ""
                PlayerScreen(encodedUri = encodedUri, navController = navController)
            }

            composable(
                route = Screen.Trim.route,
                arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
            ) { backStackEntry ->
                val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: ""
                TrimScreen(encodedUri = encodedUri, navController = navController)
            }
        }
    }
    } // end MaterialTheme
    } // end CompositionLocalProvider
}
