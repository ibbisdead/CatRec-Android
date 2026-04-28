package com.ibbie.catrec_screenrecorcer.ads

import android.app.Activity
import android.view.View

/**
 * Full-screen ads can leave focus on a view that is no longer under the activity decor. The next
 * traversal then hits [IllegalArgumentException] in [android.view.ViewRootImpl.scrollToRectOrFocus]
 * ("parameter must be a descendant of this view"). MIUI / Android 12 timing can draw before a
 * lone [View.post] runs, so we apply immediately and again on the next message.
 */
fun Activity.resetWindowFocusAfterFullscreenOverlay() {
    val root = window?.decorView ?: return
    val fix =
        Runnable {
            val focused = currentFocus
            if (focused != null && focused !== root && !focused.isUnderAncestor(root)) {
                focused.clearFocus()
            }
            root.clearFocus()
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            root.requestFocus()
        }
    fix.run()
    root.post(fix)
}

private fun View.isUnderAncestor(ancestor: View): Boolean {
    var v: View? = this
    while (v != null) {
        if (v === ancestor) return true
        v = v.parent as? View
    }
    return false
}
