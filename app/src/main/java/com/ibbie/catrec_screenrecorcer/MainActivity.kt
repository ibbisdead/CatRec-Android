package com.ibbie.catrec_screenrecorcer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.navigation.CatRecNavGraph
import com.ibbie.catrec_screenrecorcer.ui.theme.CatRecScreenRecorderTheme
import com.ibbie.catrec_screenrecorcer.utils.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    /**
     * Wrap the base context with the saved locale BEFORE any layout inflation or
     * resource lookup happens. This is what makes getString / stringResource return
     * the correct values-xx strings after a language change + recreate.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("CatRecCrash", "Uncaught exception on thread ${t.name}", e)
            oldHandler?.uncaughtException(t, e)
        }

        try {
            applyStoredLanguage()

            enableEdgeToEdge()

            val settingsRepository = SettingsRepository(applicationContext)
            val analyticsEnabled = runBlocking { settingsRepository.analyticsEnabled.first() }
            if (!analyticsEnabled) {
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE)
                        .build()
                )
            }
            MobileAds.initialize(this)

            setContent {
                val themeSetting by settingsRepository.appTheme.collectAsState(initial = "System")
                val isDark = when (themeSetting) {
                    "Light" -> false
                    "Dark" -> true
                    else -> isSystemInDarkTheme()
                }

                CatRecScreenRecorderTheme(darkTheme = isDark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        CatRecNavGraph()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the activity's intent so Composable observers (e.g. RecordingScreen's
        // ON_RESUME handler) can read the latest action (ACTION_START_RECORDING_FROM_OVERLAY).
        setIntent(intent)
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
        }
    }
}
