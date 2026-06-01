package com.ibbie.catrec_screenrecorcer.utils

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ibbie.catrec_screenrecorcer.BuildConfig

/**
 * Guarded diagnostics for MediaStore publish flows (scoped storage / API 29+).
 * Enable with a debug build or: `adb shell setprop log.tag.CatRecPublish D`.
 */
object MediaStorePublishDiagnostics {
    private const val TAG = "CatRecPublish"

    fun enabled(): Boolean = BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)

    fun log(
        stage: String,
        message: String,
    ) {
        if (enabled()) Log.d(TAG, "[$stage] $message")
    }

    data class RowSnapshot(
        val sizeBytes: Long?,
        val isPending: Int?,
        val relativePath: String?,
    )

    fun queryPublishedRow(
        cr: ContentResolver,
        uri: Uri,
    ): RowSnapshot? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            cr.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun longCol(name: String): Long? {
                    val i = c.getColumnIndex(name)
                    return if (i >= 0) c.getLong(i) else null
                }
                fun intCol(name: String): Int? {
                    val i = c.getColumnIndex(name)
                    return if (i >= 0) c.getInt(i) else null
                }
                fun strCol(name: String): String? {
                    val i = c.getColumnIndex(name)
                    return if (i >= 0) c.getString(i) else null
                }
                RowSnapshot(
                    sizeBytes = longCol(MediaStore.MediaColumns.SIZE),
                    isPending = intCol(MediaStore.MediaColumns.IS_PENDING),
                    relativePath = strCol(MediaStore.MediaColumns.RELATIVE_PATH),
                )
            }
        } catch (e: Exception) {
            log("query_row", "failed: ${e.message}")
            null
        }
    }

    /**
     * Approximates whether [AppRecordingsLoader] primary video query would include this URI:
     * pending cleared and path under Movies/CatRec when relative path is available.
     */
    fun catRecVideoLikelyVisibleInAppList(
        cr: ContentResolver,
        uri: Uri,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        val snap = queryPublishedRow(cr, uri) ?: return false
        if (snap.isPending != 0) return false
        val base = "${Environment.DIRECTORY_MOVIES}/CatRec"
        val rp = snap.relativePath?.trimEnd('/') ?: return (snap.sizeBytes ?: 0L) > 0L
        return rp == base || rp.startsWith("$base/") || rp.endsWith("CatRec")
    }

    fun logPostPublishVideo(
        cr: ContentResolver,
        uri: Uri,
        expectedMinBytes: Long,
        api: Int,
    ) {
        if (!enabled()) return
        val snap = queryPublishedRow(cr, uri)
        if (snap == null) {
            log("post_video", "api=$api uri=$uri row=null")
            return
        }
        val vis = catRecVideoLikelyVisibleInAppList(cr, uri)
        log(
            "post_video",
            "api=$api size=${snap.sizeBytes} pending=${snap.isPending} rel=${snap.relativePath} " +
                "minOk=${(snap.sizeBytes ?: 0L) >= expectedMinBytes} appListLike=$vis",
        )
    }
}
