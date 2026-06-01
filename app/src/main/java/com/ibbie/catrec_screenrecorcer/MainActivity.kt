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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdManager
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressionReason
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdSuppressor
import com.ibbie.catrec_screenrecorcer.ads.MobileAdsInitializer
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.navigation.CatRecNavGraph
import com.ibbie.catrec_screenrecorcer.service.OverlayService
import com.ibbie.catrec_screenrecorcer.ui.adaptive.LocalWindowSizeClass
import com.ibbie.catrec_screenrecorcer.ui.theme.CatRecScreenRecorderTheme
import com.ibbie.catrec_screenrecorcer.utils.ExitUiCoordinator
import com.ibbie.catrec_screenrecorcer.utils.LocaleHelper
import com.ibbie.catrec_screenrecorcer.utils.PermissionManager
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

        const val ACTION_START_RECORDING_FROM_OVERLAY =
            "com.ibbie.catrec_screenrecorcer.START_RECORDING_FROM_OVERLAY"
        const val ACTION_START_BUFFER_FROM_OVERLAY =
            "com.ibbie.catrec_screenrecorcer.START_BUFFER_FROM_OVERLAY"
        const val EXTRA_SUPPRESS_APP_OPEN_AD =
            "com.ibbie.catrec_screenrecorcer.SUPPRESS_APP_OPEN_AD"
        const val EXTRA_ROUTE_REASON = "com.ibbie.catrec_screenrecorcer.ROUTE_REASON"
        const val ROUTE_REASON_ROUTED_RECORDING_ACTION = "routed_recording_action"

        const val ACTION_FINISH_UI = "com.ibbie.catrec_screenrecorcer.FINISH_UI"

        fun markRoutedRecordingAppOpenSuppressed(trigger: String) {
            Log.d(TAG, "routed action suppression started trigger=$trigger")
            AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.ROUTED_RECORDING_ACTION)
        }

        fun addRoutedRecordingSuppressionExtras(intent: Intent): Intent {
            intent.putExtra(EXTRA_SUPPRESS_APP_OPEN_AD, true)
            intent.putExtra(EXTRA_ROUTE_REASON, ROUTE_REASON_ROUTED_RECORDING_ACTION)
            return intent
        }

        fun isRoutedRecordingActionIntent(intent: Intent?): Boolean {
            if (intent == null) return false
            return intent.action == ACTION_START_RECORDING_FROM_OVERLAY ||
                intent.action == ACTION_START_BUFFER_FROM_OVERLAY ||
                (
                    intent.getBooleanExtra(EXTRA_SUPPRESS_APP_OPEN_AD, false) &&
                        intent.getStringExtra(EXTRA_ROUTE_REASON) == ROUTE_REASON_ROUTED_RECORDING_ACTION
                )
        }
    }

    private val pendingImageEditorLock = Any()

    @Volatile
    private var pendingImageEditorUri: String? = null

    private val appOpenDecisionLock = Any()
    private val appOpenDecisionListeners = mutableSetOf<() -> Unit>()

    @Volatile
    private var appOpenDecisionGeneration: Int = 0

    fun currentAppOpenDecisionGeneration(): Int = appOpenDecisionGeneration

    fun addAppOpenDecisionCompleteListener(listener: () -> Unit): () -> Unit {
        synchronized(appOpenDecisionLock) {
            appOpenDecisionListeners.add(listener)
        }
        return {
            synchronized(appOpenDecisionLock) {
                appOpenDecisionListeners.remove(listener)
            }
        }
    }

    fun notifyAppOpenAdDecisionComplete() {
        val listeners =
            synchronized(appOpenDecisionLock) {
                appOpenDecisionGeneration += 1
                appOpenDecisionListeners.toList()
            }
        listeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                Log.w(TAG, "app-open decision listener failed", e)
            }
        }
    }

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

        consumeRoutedRecordingSuppressionIntent(intent, "onCreate")
        consumeOpenImageEditorIntent(intent)

        try {
            applyStoredLanguage()

            enableEdgeToEdge()

            val settingsRepository = SettingsRepository(applicationContext)
            val permissionManager = PermissionManager(applicationContext)
            val firstLaunch = permissionManager.isFirstAppLaunch()
            AppOpenAdManager.firstLaunchSession = firstLaunch
            if (firstLaunch) {
                permissionManager.markAppLaunchedOnce()
            } else {
                AppOpenAdSuppressor.clear(AppOpenAdSuppressionReason.FIRST_LAUNCH)
            }
            AppOpenAdManager.firstRunPermissionsComplete = permissionManager.isStartupPermissionFlowComplete()
            if (AppOpenAdManager.firstRunPermissionsComplete) {
                AppOpenAdSuppressor.clear(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
            } else {
                AppOpenAdSuppressor.enter(AppOpenAdSuppressionReason.FIRST_RUN_PERMISSIONS)
            }
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
                applyCrashlyticsCollectionEnabled(analyticsEnabled)
                applicationContext.syncFirebaseUserIdentity(analyticsEnabled)
                crashlyticsLog("App cold start")
                val appLang = settingsRepository.appLanguage.first()
                val floatingOn = settingsRepository.floatingControls.first()
                refreshCrashlyticsSessionKeys(appLang, floatingOn)
            }
            // AdMob init: [CatRecApplication] calls [MobileAdsInitializer]; app-open display is gated separately.

            if (BuildConfig.DEBUG && analyticsEnabled) {
                FirebaseAnalytics.getInstance(this).logEvent("debug_analytics_verification", null)
            }

            setContent {
                val repo = remember { SettingsRepository(applicationContext) }
                val themeSetting by repo.appTheme.collectAsState(initial = "System")
                val isDark =
                    when (themeSetting) {
                        "Light" -> false
                        "Dark" -> true
                        else -> isSystemInDarkTheme()
                    }

                CatRecScreenRecorderTheme(darkTheme = isDark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        AppOpenAdOnStartEffect(activity = this@MainActivity)
                        val configuration = LocalConfiguration.current
                        key(
                            configuration.screenWidthDp,
                            configuration.screenHeightDp,
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
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    override fun onResume() {
        super.onResume()
        AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.ANDROID_SETTINGS)
        AppOpenAdSuppressor.exit(AppOpenAdSuppressionReason.BILLING)
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(finishReceiver)
        } catch (_: Exception) {
            // Ignored
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRoutedRecordingSuppressionIntent(intent, "onNewIntent")
        consumeOpenImageEditorIntent(intent)
        // Latest action is consumed by FabRecordingBridge for routed overlay/notification/tile starts.
    }

    private fun consumeRoutedRecordingSuppressionIntent(
        intent: Intent?,
        trigger: String,
    ) {
        if (!isRoutedRecordingActionIntent(intent)) return
        markRoutedRecordingAppOpenSuppressed(trigger)
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
        val runShow = { foregroundEventId: Long ->
            Handler(Looper.getMainLooper()).post {
                AppOpenAdManager.showIfAvailable(activity, unitId, foregroundEventId) {
                    (activity as? MainActivity)?.notifyAppOpenAdDecisionComplete()
                }
            }
        }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    runShow(AppOpenAdManager.beginForegroundEvent())
                }
            }
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runShow(AppOpenAdManager.ensureForegroundEvent())
        }
        onDispose { lifecycle.removeObserver(observer) }
    }
}
