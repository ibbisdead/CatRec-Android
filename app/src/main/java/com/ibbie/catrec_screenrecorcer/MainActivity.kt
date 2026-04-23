package com.ibbie.catrec_screenrecorcer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdManager
import com.ibbie.catrec_screenrecorcer.ads.MobileAdsInitializer
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.navigation.CatRecNavGraph
import com.ibbie.catrec_screenrecorcer.service.OverlayService
import com.ibbie.catrec_screenrecorcer.ui.adaptive.LocalWindowSizeClass
import com.ibbie.catrec_screenrecorcer.ui.theme.CatRecScreenRecorderTheme
import com.ibbie.catrec_screenrecorcer.utils.ExitUiCoordinator
import com.ibbie.catrec_screenrecorcer.utils.LocaleHelper
import com.ibbie.catrec_screenrecorcer.utils.applyCrashlyticsCollectionEnabled
import com.ibbie.catrec_screenrecorcer.utils.applyPrivacySettings
import com.ibbie.catrec_screenrecorcer.utils.crashlyticsLog
import com.ibbie.catrec_screenrecorcer.utils.recordCrashlyticsNonFatal
import com.ibbie.catrec_screenrecorcer.utils.refreshCrashlyticsSessionKeys
import com.ibbie.catrec_screenrecorcer.utils.syncFirebaseUserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        /** Opens the screen-capture dialog, then prepares projection and takes one screenshot. */
        const val EXTRA_REQUEST_SCREENSHOT_PROJECTION =
            "com.ibbie.catrec_screenrecorcer.REQUEST_SCREENSHOT_PROJECTION"

        /** Raw image URI string; NavGraph drains it once the graph is attached. */
        const val EXTRA_OPEN_IMAGE_EDITOR_URI = "com.ibbie.catrec_screenrecorcer.OPEN_IMAGE_EDITOR_URI"

        const val ACTION_FINISH_UI = "com.ibbie.catrec_screenrecorcer.FINISH_UI"
    }

    private val pendingImageEditorLock = Any()

    @Volatile
    private var pendingImageEditorUri: String? = null

    /**
     * Returns the pending image-editor URI without clearing it. The NavGraph drain must call
     * this, attempt navigation, and only on success invoke [clearQueuedImageEditorUri] with
     * the same value — so a skipped drain (e.g. because the NavHost has not yet attached its
     * graph on cold start) retries on the next `ON_RESUME` instead of dropping the request.
     */
    fun peekQueuedImageEditorUri(): String? =
        synchronized(pendingImageEditorLock) { pendingImageEditorUri }

    /**
     * Clears the pending URI iff it still matches [expected]. Matching prevents dropping a
     * newer enqueue that arrived while the drain was navigating.
     */
    fun clearQueuedImageEditorUri(expected: String) {
        synchronized(pendingImageEditorLock) {
            if (pendingImageEditorUri == expected) {
                pendingImageEditorUri = null
            }
        }
    }

    private fun consumeOpenImageEditorIntent(intent: Intent?) {
        val uriStr = intent?.getStringExtra(EXTRA_OPEN_IMAGE_EDITOR_URI)?.trim().orEmpty()
        if (uriStr.isEmpty()) return
        synchronized(pendingImageEditorLock) {
            pendingImageEditorUri = uriStr
        }
    }

    private enum class ConsentUiState {
        /** Resolving consent requirement */
        Checking,

        /** EEA/UK/CH/BR etc.: user must choose before using the app */
        AwaitingChoice,

        /** Prompt done or not required */
        Ready,
    }

    /**
     * Wrap the base context with the saved locale BEFORE any layout inflation or
     * resource lookup happens. This is what makes getString / stringResource return
     * the correct values-xx strings after a language change + recreate.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val finishReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action != ACTION_FINISH_UI) return
                val activity = context as? MainActivity ?: return
                // Defer one frame so we are past the notification PendingIntent / shade transition;
                // synchronous finishAffinity competes with the system for focus and can flicker.
                activity.window.decorView.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    activity.applyNotificationExitFinishIfNeeded()
                }
            }
        }

    /**
     * Handles [ACTION_FINISH_UI] from notification exit: finishes the task when CatRec is at least
     * [Lifecycle.State.STARTED] (visible, including under an expanded shade). If the user was in
     * another app, we defer until CatRec is shown again so we do not steal focus.
     */
    private fun applyNotificationExitFinishIfNeeded() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "notification exit: finishAffinity (lifecycle=${lifecycle.currentState})")
            }
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            finishAffinity()
        } else {
            ExitUiCoordinator.markPendingFinishAffinity(this)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "notification exit: defer finishAffinity (lifecycle=${lifecycle.currentState}) — " +
                        "will finish when CatRec task is visible again",
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            finishReceiver,
            android.content.IntentFilter(ACTION_FINISH_UI),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        consumeOpenImageEditorIntent(intent)

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("CatRecCrash", "Uncaught exception on thread ${t.name}", e)
            oldHandler?.uncaughtException(t, e)
        }

        try {
            applyStoredLanguage()

            enableEdgeToEdge()

            val settingsRepository = SettingsRepository(applicationContext)
            val analyticsEnabled: Boolean
            val personalizedAdsEnabled: Boolean
            val adsDisabled: Boolean
            runBlocking {
                analyticsEnabled = settingsRepository.analyticsEnabled.first()
                personalizedAdsEnabled = settingsRepository.personalizedAdsEnabled.first()
                adsDisabled = settingsRepository.adsDisabled.first()
                applicationContext.applyPrivacySettings(
                    analyticsEnabled,
                    personalizedAdsEnabled,
                    adsDisabled,
                )
                val consentDone = settingsRepository.analyticsConsentPromptCompleted.first()
                // Re-apply Crashlytics state from stored consent on every cold start
                // (only once the user has made an explicit choice — before that,
                // CatRecApplication.onCreate already armed Crashlytics).
                if (consentDone) {
                    applyCrashlyticsCollectionEnabled(analyticsEnabled)
                }
                // Anonymous user ID for Crashlytics: before consent, match CatRecApplication (reporting on).
                val reportingEnabled = if (consentDone) analyticsEnabled else true
                applicationContext.syncFirebaseUserIdentity(reportingEnabled)
                crashlyticsLog("App cold start")
                val appLang = settingsRepository.appLanguage.first()
                val floatingOn = settingsRepository.floatingControls.first()
                refreshCrashlyticsSessionKeys(appLang, floatingOn)
            }
            // AdMob init: [CatRecApplication] calls [MobileAdsInitializer]; completion may wait for BLUETOOTH_CONNECT (API 31+).

            if (BuildConfig.DEBUG && analyticsEnabled) {
                FirebaseAnalytics.getInstance(this).logEvent("debug_analytics_verification", null)
            }

            setContent {
                val repo = remember { SettingsRepository(applicationContext) }
                var consentState by remember { mutableStateOf(ConsentUiState.Checking) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    if (repo.analyticsConsentPromptCompleted.first()) {
                        consentState = ConsentUiState.Ready
                        return@LaunchedEffect
                    }
                    // First launch for everyone: prompt before using the app.
                    consentState = ConsentUiState.AwaitingChoice
                }

                val themeSetting by repo.appTheme.collectAsState(initial = "System")
                val isDark =
                    when (themeSetting) {
                        "Light" -> false
                        "Dark" -> true
                        else -> isSystemInDarkTheme()
                    }

                CatRecScreenRecorderTheme(darkTheme = isDark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        when (consentState) {
                            ConsentUiState.Checking -> {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .safeDrawingPadding(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            ConsentUiState.AwaitingChoice -> {
                                AlertDialog(
                                    onDismissRequest = { },
                                    title = { Text(stringResource(R.string.consent_analytics_title)) },
                                    text = { Text(stringResource(R.string.consent_analytics_message)) },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    repo.applyRegulatedConsentChoice(true)
                                                    consentState = ConsentUiState.Ready
                                                }
                                            },
                                        ) { Text(stringResource(R.string.consent_accept)) }
                                    },
                                    dismissButton = {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    repo.applyRegulatedConsentChoice(false)
                                                    consentState = ConsentUiState.Ready
                                                }
                                            },
                                        ) { Text(stringResource(R.string.consent_decline)) }
                                    },
                                )
                            }
                            ConsentUiState.Ready -> {
                                AppOpenAdOnStartEffect(activity = this@MainActivity)
                                val configuration = LocalConfiguration.current
                                val windowContainerSize = LocalWindowInfo.current.containerSize
                                key(
                                    windowContainerSize.width,
                                    windowContainerSize.height,
                                    configuration.orientation,
                                    configuration.screenLayout,
                                    configuration.uiMode,
                                ) {
                                    val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
                                    CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                                        CatRecNavGraph()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(finishReceiver)
        } catch (_: Exception) {
            // Ignored
        }
    }

    override fun onResume() {
        super.onResume()
        MobileAdsInitializer.initializeIfReady(this)
        if (ExitUiCoordinator.consumePendingFinishAffinity(this)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "notification exit: applying deferred finishAffinity from onResume")
            }
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            finishAffinity()
            return
        }
        (application as? CatRecApplication)?.billingManager?.refreshPurchasesIfConnected()
        lifecycleScope.launch {
            try {
                val floatingOn =
                    withContext(Dispatchers.IO) {
                        SettingsRepository(applicationContext).floatingControls.first()
                    }
                if (floatingOn && Settings.canDrawOverlays(this@MainActivity)) {
                    startService(
                        Intent(this@MainActivity, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_SHOW_IDLE_CONTROLS
                        },
                    )
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Idle overlay start skipped", e)
                recordCrashlyticsNonFatal(e, "MainActivity.onResume: idle overlay start failed")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenImageEditorIntent(intent)
        // Latest action for FabRecordingBridge (ACTION_START_RECORDING_FROM_OVERLAY).
    }

    private fun applyStoredLanguage() {
        try {
            val settingsRepository = SettingsRepository(applicationContext)
            val savedCode = runBlocking { settingsRepository.appLanguage.first() }

            // Keep SharedPreferences in sync with DataStore so attachBaseContext can
            // read the locale synchronously on the next cold start / recreate.
            LocaleHelper.persist(applicationContext, savedCode)

            if (savedCode.isNotBlank() && !savedCode.equals("system", ignoreCase = true)) {
                val localeList = LocaleListCompat.forLanguageTags(savedCode)
                if (!localeList.isEmpty) {
                    AppCompatDelegate.setApplicationLocales(localeList)
                }
            } else {
                if (AppCompatDelegate.getApplicationLocales() != LocaleListCompat.getEmptyLocaleList()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not apply stored language", e)
            recordCrashlyticsNonFatal(e, "MainActivity.applyStoredLanguage failed")
        }
    }
}

@Composable
private fun AppOpenAdOnStartEffect(activity: ComponentActivity) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val unitId = remember(activity) { activity.getString(R.string.admob_app_open_unit_id) }
    DisposableEffect(lifecycleOwner, unitId) {
        val runShow = {
            Handler(Looper.getMainLooper()).post {
                AppOpenAdManager.showIfAvailable(activity, unitId)
            }
        }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) runShow()
            }
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runShow()
        }
        onDispose { lifecycle.removeObserver(observer) }
    }
}
