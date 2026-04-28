package com.ibbie.catrec_screenrecorcer.utils

import android.content.Context
import androidx.core.content.edit

/**
 * When the user exits from a notification while another app is in the foreground, we must not
 * call [android.app.Activity.finishAffinity] immediately: on some devices that reorders the task
 * stack and causes a visible flicker or jump to the previous app. Instead we remember the request
 * and finish as soon as CatRec's task becomes visible again ([androidx.lifecycle.Lifecycle.State.STARTED]).
 */
object ExitUiCoordinator {
    internal const val PREFS_NAME = "catrec_exit_ui"
    private const val KEY_PENDING_FINISH_AFFINITY = "pending_finish_affinity"

    fun markPendingFinishAffinity(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_PENDING_FINISH_AFFINITY, true)
            }
    }

    /** Returns true once if a deferred exit was pending (and clears the flag). */
    fun consumePendingFinishAffinity(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING_FINISH_AFFINITY, false)) return false
        prefs.edit { putBoolean(KEY_PENDING_FINISH_AFFINITY, false) }
        return true
    }
}
