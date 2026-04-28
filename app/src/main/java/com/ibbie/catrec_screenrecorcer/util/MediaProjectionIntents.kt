package com.ibbie.catrec_screenrecorcer.util

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

object MediaProjectionIntents {
    fun createScreenCaptureIntent(
        context: Context,
        recordSingleAppEnabled: Boolean,
    ): Intent {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= 34) {
            val config =
                if (recordSingleAppEnabled) {
                    MediaProjectionConfig.createConfigForUserChoice()
                } else {
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                }
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
    }
}
