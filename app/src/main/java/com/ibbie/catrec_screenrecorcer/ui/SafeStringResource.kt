package com.ibbie.catrec_screenrecorcer.ui

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.ibbie.catrec_screenrecorcer.utils.recordCrashlyticsNonFatal
import java.util.IllegalFormatException

private const val TAG = "SafeStringResource"

/**
 * Same resolution as Compose `stringResource` (uses [LocalResources] and `getString`), with a safe
 * fallback: bad translations (e.g. wrong format specifiers) must not crash the UI.
 * [IllegalFormatException] is caught; `Log` + Firebase Crashlytics (non-fatal) receive the
 * details so bad translations are visible in production, then a safe fallback (template + raw
 * args) is returned.
 */
@Composable
fun safeStringResource(
    @StringRes id: Int,
    vararg formatArgs: Any,
): String {
    val resources = LocalResources.current
    return try {
        if (formatArgs.isEmpty()) {
            resources.getString(id)
        } else {
            resources.getString(id, *formatArgs)
        }
    } catch (e: IllegalFormatException) {
        val detail =
            "safeStringResource: getString format failed id=0x${Integer.toHexString(id)} (decimal $id) argCount=${formatArgs.size}"
        Log.e(TAG, detail, e)
        recordCrashlyticsNonFatal(e, detail)
        val template = runCatching { resources.getString(id) }.getOrNull().orEmpty()
        buildString {
            if (template.isNotEmpty()) append(template)
            if (formatArgs.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(formatArgs.joinToString(" ") { it.toString() })
            }
        }
    }
}
