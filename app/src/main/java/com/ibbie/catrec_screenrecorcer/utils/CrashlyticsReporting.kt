package com.ibbie.catrec_screenrecorcer.utils

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Custom keys visible under Crashlytics → Issues → Keys / session metadata. */
object CrashlyticsKeys {
    const val APP_LANGUAGE = "app_language"
    const val FLOATING_CONTROLS_ENABLED = "floating_controls_enabled"
}

/** Breadcrumb for the Logs tab (last ~64 KB before a crash). */
fun crashlyticsLog(message: String) {
    FirebaseCrashlytics.getInstance().log(message)
}

/**
 * Records a non-fatal for the dashboard; optional log line is written first.
 */
fun recordCrashlyticsNonFatal(
    e: Exception,
    logMessage: String? = null,
) {
    val crash = FirebaseCrashlytics.getInstance()
    logMessage?.let { crash.log(it) }
    crash.recordException(e)
}

fun Context.refreshCrashlyticsSessionKeys(
    appLanguageCode: String,
    floatingControlsEnabled: Boolean,
) {
    FirebaseCrashlytics.getInstance().apply {
        setCustomKey(CrashlyticsKeys.APP_LANGUAGE, appLanguageCode)
        setCustomKey(CrashlyticsKeys.FLOATING_CONTROLS_ENABLED, floatingControlsEnabled)
    }
}
