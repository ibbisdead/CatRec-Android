package com.ibbie.catrec_screenrecorcer

import android.app.Application
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.ads.AppOpenAdManager
import com.ibbie.catrec_screenrecorcer.billing.CatRecBillingManager
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.data.recording.DefaultRecordingSessionRepository
import com.ibbie.catrec_screenrecorcer.data.recording.RecordingSessionRepository
import com.ibbie.catrec_screenrecorcer.utils.LocaleHelper
import com.ibbie.catrec_screenrecorcer.utils.applyPersonalizedAdsEnabled
import com.ibbie.catrec_screenrecorcer.utils.syncFirebaseUserIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Wraps the application context with the user-selected locale so [Context.getString]
 * in Services, BroadcastReceivers, and other non-Activity code matches the in-app language.
 */
class CatRecApplication : Application() {
    lateinit var billingManager: CatRecBillingManager
        private set

    /** Single recording session facade (foreground service + lifecycle flows). */
    val recordingSessionRepository: RecordingSessionRepository by lazy {
        val settings = SettingsRepository(this)
        DefaultRecordingSessionRepository(settings)
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        val settingsRepository = SettingsRepository(this)
        runBlocking {
            applyPersonalizedAdsEnabled(settingsRepository.personalizedAdsEnabled.first())
        }
        applicationScope.launch {
            settingsRepository.adsDisabled.collect { disabled ->
                AppOpenAdManager.adsDisabled = disabled
            }
        }
        MobileAds.initialize(this) {
            AppOpenAdManager.load(this, getString(R.string.admob_app_open_unit_id))
        }
        billingManager = CatRecBillingManager(this)
        billingManager.start()
        // Arm Crashlytics explicitly so it is active before MainActivity loads the saved
        // consent preference. The Firebase Sessions SDK (which Crashlytics uses for data
        // transport) requires an explicit opt-in call when Firebase Analytics collection
        // has been disabled; without this, crash reports are silently dropped.
        // MainActivity will later call setCrashlyticsCollectionEnabled(false) if the user
        // previously declined the analytics consent prompt.
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
        // Avoid blocking process start; MainActivity re-syncs after reading consent/prefs.
        applicationScope.launch(Dispatchers.IO) {
            syncFirebaseUserIdentity(reportingEnabled = true)
        }
    }
}
