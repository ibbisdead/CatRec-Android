package com.ibbie.catrec_screenrecorcer.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

internal object ForegroundServiceLaunch {
    fun start(
        context: Context,
        intent: Intent,
        source: String,
    ): Boolean =
        try {
            context.startForegroundService(intent)
            true
        } catch (e: RuntimeException) {
            if (!isStartNotAllowed(e)) throw e
            Log.w(LOG_TAG, "Foreground service start blocked source=$source action=${intent.action}", e)
            FirebaseCrashlytics.getInstance().log(
                "fgs_start_not_allowed source=$source action=${intent.action} api=${Build.VERSION.SDK_INT}",
            )
            false
        }

    fun isStartNotAllowed(e: RuntimeException): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val className = e.javaClass.name
        val message = e.message.orEmpty()
        return className == "android.app.ForegroundServiceStartNotAllowedException" ||
            message.contains("startForegroundService() not allowed", ignoreCase = true) ||
            message.contains("mAllowStartForeground false", ignoreCase = true)
    }

    private const val LOG_TAG = "ForegroundSvcLaunch"
}
