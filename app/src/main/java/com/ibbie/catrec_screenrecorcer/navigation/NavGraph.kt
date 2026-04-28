package com.ibbie.catrec_screenrecorcer.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.ui.adaptive.LocalWindowSizeClass
import com.ibbie.catrec_screenrecorcer.ui.adaptive.scaledForCompactWidth
import com.ibbie.catrec_screenrecorcer.ui.components.BannerAdRow
import com.ibbie.catrec_screenrecorcer.ui.components.CaptureModePill
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentBrush
import com.ibbie.catrec_screenrecorcer.ui.components.LocalAccentColor
import com.ibbie.catrec_screenrecorcer.ui.components.LocalPerformanceMode
import com.ibbie.catrec_screenrecorcer.ui.components.LocalSuppressRecordFabForListSelection
import com.ibbie.catrec_screenrecorcer.ui.components.StorageIndicator
import com.ibbie.catrec_screenrecorcer.ui.components.rememberIsLowEndDevice
import com.ibbie.catrec_screenrecorcer.ui.editor.ImageEditorScreen
import com.ibbie.catrec_screenrecorcer.ui.faq.FaqScreen
import com.ibbie.catrec_screenrecorcer.ui.faq.FeedbackScreen
import com.ibbie.catrec_screenrecorcer.ui.player.PlayerScreen
import com.ibbie.catrec_screenrecorcer.ui.recording.FabRecordingBridge
import com.ibbie.catrec_screenrecorcer.ui.recording.LocalFabRecordingControl
import com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel
import com.ibbie.catrec_screenrecorcer.ui.recordings.RecordingsScreen
import com.ibbie.catrec_screenrecorcer.ui.recordings.TrimScreen
import com.ibbie.catrec_screenrecorcer.ui.screenshots.ScreenshotsScreen
import com.ibbie.catrec_screenrecorcer.ui.settings.CropScreen
import com.ibbie.catrec_screenrecorcer.ui.settings.SettingsScreen
import com.ibbie.catrec_screenrecorcer.ui.support.SupportScreen
import com.ibbie.catrec_screenrecorcer.ui.theme.CaptureModeColors
import com.ibbie.catrec_screenrecorcer.ui.theme.CrimsonRed
import com.ibbie.catrec_screenrecorcer.ui.theme.isLightTheme
import com.ibbie.catrec_screenrecorcer.ui.tools.CompressVideoScreen
import com.ibbie.catrec_screenrecorcer.ui.tools.MergeVideosScreen
import com.ibbie.catrec_screenrecorcer.ui.tools.ToolsScreen
import com.ibbie.catrec_screenrecorcer.ui.tools.VideoToGifScreen
import androidx.core.graphics.toColorInt

private const val NAV_GRAPH_LOG = "CatRecNavGraph"

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun CatRecNavGraph(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun drainQueuedImageEditor() {
        val act = context as? MainActivity ?: return
        val raw = act.peekQueuedImageEditorUri() ?: return
        val destination = "image_editor?imageUri=${Uri.encode(raw)}"
        val lifecycleState = lifecycleOwner.lifecycle.currentState

        // Readiness gates (any failed → leave queued, retry on next ON_RESUME):
        //  1. NavHost has attached its graph — `navController.graph` throws IllegalStateException
        //     before the NavHost composable's first composition, so we check access defensively.
        //  2. A current destination exists (host has composed at least once).
        //  3. Lifecycle is at least RESUMED — navigating on STARTED/CREATED can drop the
        //     transaction or race with a pending config change; wait until the host is fully
        //     interactive before committing the navigation.
        val graphAttached =
            try {
                navController.graph
                true
            } catch (_: IllegalStateException) {
                false
            }
        if (!graphAttached || navController.currentDestination == null ||
            !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            if (Log.isLoggable(NAV_GRAPH_LOG, Log.DEBUG)) {
                Log.d(
                    NAV_GRAPH_LOG,
                    "drainQueuedImageEditor skipped dest=$destination graphAttached=$graphAttached " +
                        "currentDestination=${navController.currentDestination?.route} lifecycle=$lifecycleState",
                )
            }
            return
        }

        try {
            navController.navigate(destination) {
                launchSingleTop = true
            }
            act.clearQueuedImageEditorUri(raw)
        } catch (e: IllegalArgumentException) {
            // Graph not yet attached / destination missing — leave queued, retry on next resume.
            Log.w(
                NAV_GRAPH_LOG,
                "drainQueuedImageEditor IAE dest=$destination currentDestination=" +
                    "${navController.currentDestination?.route} lifecycle=$lifecycleState",
                e,
            )
        } catch (e: IllegalStateException) {
            Log.w(
                NAV_GRAPH_LOG,
                "drainQueuedImageEditor ISE dest=$destination currentDestination=" +
                    "${navController.currentDestination?.route} lifecycle=$lifecycleState",
                e,
            )
        }
    }

    LaunchedEffect(navController) {
        drainQueuedImageEditor()
    }

    DisposableEffect(lifecycleOwner, navController) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) drainQueuedImageEditor()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sharedViewModel: RecordingViewModel = viewModel()

    // Effective performance mode = user preference OR hardware auto-detection
    val userPerformanceMode by sharedViewModel.performanceMode.collectAsState()
    val isLowEnd = rememberIsLowEndDevice()
    val effectivePerformanceMode = userPerformanceMode || isLowEnd

    // Accent color — parse stored hex strings into Compose Color
    val accentHex by sharedViewModel.accentColor.collectAsState()
    val accentHex2 by sharedViewModel.accentColor2.collectAsState()
    val useGradient by sharedViewModel.accentUseGradient.collectAsState()

    val accentColor =
        remember(accentHex) {
            runCatching { Color("#${accentHex.removePrefix("#").take(6)}".toColorInt()) }
                .getOrDefault(CrimsonRed)
        }
    val accentColor2 =
        remember(accentHex2) {
            runCatching { Color("#${accentHex2.removePrefix("#").take(6)}".toColorInt()) }
                .getOrDefault(Color(0xFFFF8C00))
        }
    val accentBrush: Brush =
        remember(accentColor, accentColor2, useGradient) {
            if (useGradient) {
                Brush.linearGradient(listOf(accentColor.copy(alpha = 0.45f), accentColor2.copy(alpha = 0.45f)))
            } else {
                Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.40f), accentColor.copy(alpha = 0.10f)))
            }
        }

    // Build a dynamic MaterialTheme color scheme from the accent (preserve light/dark on-* contrast)
    val baseScheme = MaterialTheme.colorScheme
    val dynamicScheme =
        remember(accentColor, baseScheme) {
            val light = baseScheme.isLightTheme()
            baseScheme.copy(
                primary = accentColor,
                onPrimary = Color.White,
                secondary = accentColor,
                onSecondary = Color.White,
                tertiary = accentColor,
                onTertiary = Color.White,
                primaryContainer = accentColor.copy(alpha = if (light) 0.14f else 0.2f),
                onPrimaryContainer = if (light) baseScheme.onSurface else Color.White,
                outline = accentColor.copy(alpha = if (light) 0.35f else 0.3f),
            )
        }

    val mainTabScreens =
        listOf(
            Screen.Recordings,
            Screen.Screenshots,
            Screen.Editor,
            Screen.Settings,
            Screen.Support,
        )

    val adsDisabled by sharedViewModel.adsDisabled.collectAsState()

    val hideChrome =
        currentRoute?.let { route ->
            route.startsWith("crop") ||
                route.startsWith("player") ||
                route.startsWith("trim") ||
                route.startsWith("image_editor") ||
                route == Screen.Faq.route ||
                route == Screen.Feedback.route
        } ?: false

    /** Full-screen editor flows where the FAB would cover tool UI (trim uses hideChrome; these keep the top bar). */
    val hideRecordFab =
        currentRoute?.let { route ->
            route.startsWith("trim") ||
                route.startsWith("compress") ||
                route.startsWith("video_to_gif") ||
                route.startsWith("image_editor") ||
                route == Screen.MergeVideos.route
        } ?: false

    val recordingUi by sharedViewModel.recordingUiSnapshot.collectAsState()
    val pillSelectedMode by remember {
        derivedStateOf {
            val s = recordingUi
            when {
                s.isBuffering -> CaptureMode.CLIPPER
                s.isRecording && s.captureMode == CaptureMode.GIF -> CaptureMode.GIF
                s.isRecording -> CaptureMode.RECORD
                else -> s.captureMode
            }
        }
    }
    val canSwitchCaptureMode by remember {
        derivedStateOf {
            val s = recordingUi
            !s.isRecording && !s.isBuffering
        }
    }

    // Immediate tab highlight while NavHost composes the destination (Compose ≠ React;
    // this is the local equivalent of optimistic UI / decoupling indicator from back stack).
    var pendingTabRoute by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && mainTabScreens.any { it.route == currentRoute }) {
            pendingTabRoute = null
        }
    }

    val suppressRecordFabForListSelection = remember { mutableStateOf(false) }
    val listSelectionSuppressesFab by suppressRecordFabForListSelection

    CompositionLocalProvider(
        LocalPerformanceMode provides effectivePerformanceMode,
        LocalAccentColor provides accentColor,
        LocalAccentBrush provides accentBrush,
        LocalSuppressRecordFabForListSelection provides suppressRecordFabForListSelection,
    ) {
        val widthCompact = LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact
        val parentTypography = MaterialTheme.typography
        val navTypography =
            remember(widthCompact, parentTypography) {
                if (widthCompact) parentTypography.scaledForCompactWidth() else parentTypography
            }
        MaterialTheme(colorScheme = dynamicScheme, typography = navTypography) {
            FabRecordingBridge(
                viewModel = sharedViewModel,
                recordingUiSnapshot = recordingUi,
            ) {
                val barScheme = MaterialTheme.colorScheme
                Scaffold(
                    containerColor = barScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        if (!hideChrome) {
                            val chipScroll = rememberScrollState()
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .windowInsetsPadding(
                                            WindowInsets.safeDrawing.only(
                                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                            ),
                                        )
                                        .padding(
                                            horizontal = if (widthCompact) 6.dp else 8.dp,
                                            vertical = if (widthCompact) 2.dp else 4.dp,
                                        ),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        modifier = Modifier.weight(1f),
                                        text = stringResource(R.string.brand_catrect),
                                        style =
                                            if (widthCompact) {
                                                MaterialTheme.typography.titleLarge
                                            } else {
                                                MaterialTheme.typography.headlineSmall
                                            },
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = if (widthCompact) 0.35.sp else 0.5.sp,
                                        color = barScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = {
                                            navController.navigate(Screen.Faq.route) { launchSingleTop = true }
                                        },
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.HelpOutline,
                                            contentDescription = stringResource(R.string.faq_content_description),
                                            tint = barScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                StorageIndicator()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    CaptureModePill(
                                        selectedMode = pillSelectedMode,
                                        onModeSelected = { sharedViewModel.setCaptureMode(it) },
                                        enabled = canSwitchCaptureMode,
                                        isRecording = recordingUi.isRecording,
                                        isBuffering = recordingUi.isBuffering,
                                        captureMode = recordingUi.captureMode,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(chipScroll),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    mainTabScreens.forEach { screen ->
                                        val selectedFromNav =
                                            navBackStackEntry
                                                ?.destination
                                                ?.hierarchy
                                                ?.any { it.route == screen.route } == true
                                        val selected =
                                            pendingTabRoute?.let { it == screen.route }
                                                ?: selectedFromNav
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                pendingTabRoute = screen.route
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            label = {
                                                AnimatedVisibility(visible = selected) {
                                                    Text(
                                                        stringResource(screen.titleRes),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style =
                                                            if (widthCompact) {
                                                                MaterialTheme.typography.labelMedium
                                                            } else {
                                                                MaterialTheme.typography.labelLarge
                                                            },
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                val icon =
                                                    when (screen) {
                                                        Screen.Screenshots -> Icons.Default.CameraAlt
                                                        Screen.Recordings -> Icons.Default.VideoLibrary
                                                        Screen.Editor -> Icons.Default.ContentCut
                                                        Screen.Settings -> Icons.Default.Settings
                                                        Screen.Support -> Icons.Outlined.Pets
                                                        else -> Icons.Default.Videocam
                                                    }
                                                val chipIconSize = if (widthCompact) 16.dp else 18.dp
                                                Icon(icon, contentDescription = null, modifier = Modifier.size(chipIconSize))
                                            },
                                            modifier =
                                                Modifier.padding(start = if (widthCompact) 2.dp else 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!hideChrome) {
                            Surface(
                                color = barScheme.surface,
                                tonalElevation = 1.dp,
                            ) {
                                Column(
                                    Modifier.windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                        ),
                                    ),
                                ) {
                                    BannerAdRow(adsDisabled = adsDisabled)
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        if (!hideChrome && !hideRecordFab && !listSelectionSuppressesFab) {
                            val fabBg =
                                when (pillSelectedMode) {
                                    CaptureMode.CLIPPER -> CaptureModeColors.ClipperYellow
                                    CaptureMode.GIF -> CaptureModeColors.GifBlue
                                    else -> CaptureModeColors.RecordingRed
                                }
                            val rec = recordingUi
                            val fabCd =
                                when {
                                    rec.isRecording || rec.isBuffering -> stringResource(R.string.notif_action_stop)
                                    pillSelectedMode == CaptureMode.CLIPPER -> stringResource(R.string.capture_mode_clipper)
                                    pillSelectedMode == CaptureMode.GIF -> stringResource(R.string.capture_mode_gif)
                                    else -> stringResource(R.string.fab_start_recording)
                                }
                            FloatingActionButton(
                                onClick = LocalFabRecordingControl.current,
                                containerColor = fabBg,
                                contentColor = Color.White,
                                modifier = Modifier.padding(bottom = 12.dp),
                            ) {
                                if (rec.isRecording || rec.isBuffering) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = fabCd,
                                        modifier = Modifier.size(28.dp),
                                    )
                                } else {
                                    when (pillSelectedMode) {
                                        CaptureMode.CLIPPER ->
                                            Icon(
                                                Icons.Default.ContentCut,
                                                contentDescription = fabCd,
                                                modifier = Modifier.size(26.dp),
                                            )
                                        CaptureMode.GIF ->
                                            Text(
                                                text = "GIF",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White,
                                                letterSpacing = 0.6.sp,
                                                modifier = Modifier,
                                            )
                                        else ->
                                            Icon(
                                                Icons.Default.Videocam,
                                                contentDescription = fabCd,
                                                modifier = Modifier.size(26.dp),
                                            )
                                    }
                                }
                            }
                        }
                    },
                    floatingActionButtonPosition = FabPosition.End,
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Recordings.route,
                        modifier =
                            Modifier
                                .padding(innerPadding)
                                .consumeWindowInsets(innerPadding),
                    ) {
                        composable(Screen.Screenshots.route) {
                            ScreenshotsScreen(
                                navController = navController,
                                viewModel = viewModel(),
                            )
                        }
                        composable(Screen.Recordings.route) {
                            RecordingsScreen(
                                navController = navController,
                                viewModel = viewModel(),
                            )
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(viewModel = sharedViewModel, navController = navController)
                        }
                        composable(Screen.Support.route) {
                            SupportScreen(viewModel = sharedViewModel, navController = navController)
                        }
                        composable(Screen.Editor.route) {
                            ToolsScreen(navController = navController)
                        }
                        composable(
                            route = Screen.Compress.route,
                            arguments = listOf(navArgument("videoUri") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: ""
                            CompressVideoScreen(encodedUri = encodedUri, navController = navController)
                        }
                        composable(
                            route = Screen.VideoToGif.route,
                            arguments = listOf(navArgument("videoUri") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: ""
                            VideoToGifScreen(encodedUri = encodedUri, navController = navController)
                        }
                        composable(Screen.MergeVideos.route) {
                            MergeVideosScreen(navController = navController)
                        }
                        composable(Screen.Faq.route) {
                            FaqScreen(navController = navController)
                        }
                        composable(Screen.Feedback.route) {
                            FeedbackScreen(navController = navController)
                        }

                        composable(
                            route = Screen.Crop.route,
                            arguments = listOf(navArgument("imageUri") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val imageUri = backStackEntry.arguments?.getString("imageUri") ?: ""
                            CropScreen(
                                imageUriString = imageUri,
                                onCropDone = { uri ->
                                    sharedViewModel.setWatermarkImageUri(uri.toString())
                                    navController.popBackStack()
                                },
                                onCancel = { navController.popBackStack() },
                            )
                        }

                        composable(
                            route = Screen.ImageEditor.route,
                            arguments = listOf(navArgument("imageUri") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val imageUri = backStackEntry.arguments?.getString("imageUri") ?: ""
                            ImageEditorScreen(encodedImageUri = imageUri, navController = navController)
                        }

                        composable(
                            route = Screen.Player.route,
                            arguments = listOf(navArgument("videoUri") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: ""
                            PlayerScreen(encodedUri = encodedUri, navController = navController)
                        }

                        composable(
                            route = Screen.Trim.route,
                            arguments = listOf(navArgument("videoUri") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: ""
                            TrimScreen(encodedUri = encodedUri, navController = navController)
                        }
                    }
                }
            } // end FabRecordingBridge
        } // end MaterialTheme
    } // end CompositionLocalProvider
}
