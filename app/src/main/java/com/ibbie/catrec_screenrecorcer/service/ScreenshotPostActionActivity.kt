package com.ibbie.catrec_screenrecorcer.service

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R

/**
 * After a screenshot: compact preview at the top-end with share and edit actions
 * (similar to quick-share thumbnails in common screen recorders).
 */
class ScreenshotPostActionActivity : AppCompatActivity() {
    private val dismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { if (!isFinishing) finish() }

    private fun cancelAutoDismiss() {
        dismissHandler.removeCallbacks(autoDismissRunnable)
    }

    private fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        dismissHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    override fun onDestroy() {
        cancelAutoDismiss()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriStr.isNullOrEmpty()) {
            finish()
            return
        }
        val uri = Uri.parse(uriStr)

        val margin = dp(12)
        val statusFallback = statusBarHeight() + dp(8)

        val root =
            FrameLayout(this).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(0x99000000.toInt())
                setOnClickListener {
                    cancelAutoDismiss()
                    finish()
                }
            }

        val chip =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { /* consume taps; do not close */ }
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> scheduleAutoDismiss()
                        MotionEvent.ACTION_UP -> v.performClick()
                    }
                    false
                }
            }

        val preview =
            ImageView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(120), dp(160)).apply {
                        bottomMargin = dp(10)
                    }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = AppCompatResources.getDrawable(this@ScreenshotPostActionActivity, R.drawable.bg_post_screenshot_preview)
                clipToOutline = true
                outlineProvider =
                    object : android.view.ViewOutlineProvider() {
                        override fun getOutline(
                            view: android.view.View,
                            outline: android.graphics.Outline,
                        ) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                        }
                    }
                try {
                    setImageURI(uri)
                } catch (_: Exception) {
                    setImageResource(R.mipmap.ic_launcher)
                }
            }

        val hint =
            TextView(this).apply {
                text = getString(R.string.screenshot_post_tap_outside)
                setTextColor(0xD0FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(10) }
            }

        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

        fun pillAction(
            iconRes: Int,
            labelRes: Int,
            marginEndDp: Int,
            onClick: () -> Unit,
        ): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background =
                    AppCompatResources.getDrawable(
                        this@ScreenshotPostActionActivity,
                        R.drawable.bg_post_action_pill,
                    )
                val padH = dp(14)
                val padV = dp(10)
                setPadding(padH, padV, padH, padV)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (marginEndDp > 0) marginEnd = dp(marginEndDp)
                        }
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                val labelText =
                    getString(labelRes).ifBlank {
                        if (labelRes == R.string.screenshot_post_edit) "Edit" else ""
                    }
                contentDescription = labelText
                val skipIconTint = iconRes == R.drawable.ic_post_action_edit
                val icon =
                    ImageView(this@ScreenshotPostActionActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val d = loadPillIconDrawable(iconRes)
                        setImageDrawable(d)
                        if (skipIconTint) {
                            imageTintList = null
                        } else {
                            imageTintList =
                                android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                        }
                    }
                val label =
                    TextView(this@ScreenshotPostActionActivity).apply {
                        text = labelText
                        setTextColor(0xFFFFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { marginStart = dp(8) }
                    }
                addView(icon)
                addView(label)
                setOnClickListener { onClick() }
            }

        row.addView(
            pillAction(
                android.R.drawable.ic_menu_share,
                R.string.action_share,
                marginEndDp = 8,
            ) {
                cancelAutoDismiss()
                shareImage(uri)
                finish()
            },
        )
        row.addView(
            pillAction(
                R.drawable.ic_post_action_edit,
                R.string.screenshot_post_edit,
                marginEndDp = 0,
            ) {
                cancelAutoDismiss()
                openNativeEditor(uri)
                finish()
            },
        )

        chip.addView(preview)
        chip.addView(hint)
        // Vertical LinearLayout defaults child width to MATCH_PARENT; WRAP_CONTENT lets the row size
        // to both pills so the second pill is not squeezed (Edit label would measure 0 width).
        chip.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        preview.elevation = dp(3).toFloat()

        chip.alpha = 0f
        chip.scaleX = 0.92f
        chip.scaleY = 0.92f
        chip.translationX = dp(36).toFloat()
        chip.translationY = -dp(6).toFloat()

        val chipLp =
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = statusFallback
                    marginEnd = margin
                }
        root.addView(chip, chipLp)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )
            (chip.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(8)
                marginEnd = margin + bars.right
            }
            chip.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(root)

        chip.post {
            chip
                .animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        scheduleAutoDismiss()
    }

    private fun shareImage(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: "image/*"
        val share =
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        try {
            startActivity(Intent.createChooser(share, getString(R.string.share_screenshot_chooser_title)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.screenshot_post_action_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNativeEditor(uri: Uri) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_IMAGE_EDITOR_URI, uri.toString())
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
    }

    /** Used by [onCreate] pill row; second path helps if [AppCompatResources] returns null for a vector. */
    private fun loadPillIconDrawable(iconRes: Int): Drawable? =
        AppCompatResources.getDrawable(this, iconRes)
            ?: ResourcesCompat.getDrawable(resources, iconRes, theme)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    companion object {
        const val EXTRA_IMAGE_URI = "EXTRA_IMAGE_URI"
        private const val AUTO_DISMISS_MS = 6000L
    }
}
