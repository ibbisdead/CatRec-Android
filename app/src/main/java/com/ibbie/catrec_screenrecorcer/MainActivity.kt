package com.ibbie.catrec_screenrecorcer

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
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.navigation.CatRecNavGraph
import com.ibbie.catrec_screenrecorcer.ui.theme.CatRecScreenRecorderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
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
            MobileAds.initialize(this)

            val settingsRepository = SettingsRepository(applicationContext)

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

    private fun applyStoredLanguage() {
        try {
            val settingsRepository = SettingsRepository(applicationContext)
            // DataStore stores the BCP-47 locale tag directly (e.g. "en", "ar", "zh-CN").
            val savedCode = runBlocking { settingsRepository.appLanguage.first() }
            if (savedCode.isNotBlank() && savedCode != "system") {
                val localeList = LocaleListCompat.forLanguageTags(savedCode)
                AppCompatDelegate.setApplicationLocales(localeList)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not apply stored language", e)
        }
    }
}
