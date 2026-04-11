package com.ibbie.catrec_screenrecorcer.utils

import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.ads.AdMobAdRequestFactory
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository

/** Firebase Analytics only — independent of AdMob personalization. */
fun Context.applyAnalyticsCollectionEnabled(enabled: Boolean) {
    FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(enabled)
}

/**
 * Firebase Crashlytics data collection.
 * Must be kept in sync with the analytics consent so the Firebase Sessions SDK
 * (shared by both products) is not left in a conflicting state.
 */
fun Context.applyCrashlyticsCollectionEnabled(enabled: Boolean) {
    FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
}

/**
 * Sets or clears Firebase user identifiers when Crashlytics/Analytics reporting is allowed.
 *
 * - [signedInUserId]: when non-null/non-blank, used as the custom user id (e.g. account id).
 * - Otherwise: a persisted random UUID per install (DataStore). We do not call
 *   [Firebase Installations](https://firebase.google.com/docs/projects/manage-installations)
 *   for user id: that API requires a network token exchange and often logs
 *   `FirebaseInstallationsException` / "Error getting authentication token" on offline
 *   or misconfigured devices even when the app handles failure.
 *
 * Clears SDK user IDs when [reportingEnabled] is false (user opted out).
 */
suspend fun Context.syncFirebaseUserIdentity(
    reportingEnabled: Boolean,
    signedInUserId: String? = null,
) {
    if (!reportingEnabled) {
        FirebaseCrashlytics.getInstance().setUserId("")
        FirebaseAnalytics.getInstance(this).setUserId(null)
        return
    }
    val id =
        when {
            !signedInUserId.isNullOrBlank() -> signedInUserId
            else -> SettingsRepository(this).getOrCreateFirebaseAnonymousUserId()
        }
    FirebaseCrashlytics.getInstance().setUserId(id)
    FirebaseAnalytics.getInstance(this).setUserId(id)
}

/**
 * Sets Crashlytics (and Analytics) user id: [signedInUserId] when logged in, otherwise
 * the persisted anonymous install UUID. Must match your consent: use the same
 * [reportingEnabled] as [applyCrashlyticsCollectionEnabled].
 */
suspend fun Context.setCrashlyticsUserId(
    reportingEnabled: Boolean,
    signedInUserId: String? = null,
) {
    syncFirebaseUserIdentity(reportingEnabled, signedInUserId)
}

/**
 * AdMob: when [personalized] is false, all ad requests must include the `npa=1` extra
 * ([AdMobAdRequestFactory]); do not use tag-for-under-age-of-consent for NPA — that is for minor-directed
 * inventory and causes severe NO_FILL if applied to general users.
 */
fun Context.applyPersonalizedAdsEnabled(personalized: Boolean) {
    AdMobAdRequestFactory.personalizedAdsEnabled = personalized
    MobileAds.setRequestConfiguration(RequestConfiguration.Builder().build())
}

fun Context.applyPrivacySettings(
    analyticsEnabled: Boolean,
    personalizedAdsEnabled: Boolean,
) {
    applyAnalyticsCollectionEnabled(analyticsEnabled)
    applyPersonalizedAdsEnabled(personalizedAdsEnabled)
}
