package com.ibbie.catrec_screenrecorcer.service

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.setPadding
import com.ibbie.catrec_screenrecorcer.MainActivity
import com.ibbie.catrec_screenrecorcer.R

/**
 * Lightweight chip after a screenshot: preview top-end + share / edit, without blocking the whole screen.
 */
class ScreenshotPostActionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriStr.isNullOrEmpty()) {
            finish()
            return
        }
        val uri = Uri.parse(uriStr)

        val margin = dp(12)
        val statusInset = statusBarHeight() + dp(8)

        val root =
            FrameLayout(this).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(0x55000000)
                setOnClickListener { finish() }
            }

        val chip =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { /* consume; do not finish */ }
            }

        val preview =
            ImageView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(132), dp(176)).apply {
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
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(8) }
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
                val padH = dp(16)
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
                contentDescription = getString(labelRes)
                val icon =
                    ImageView(this@ScreenshotPostActionActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        setImageResource(iconRes)
                        imageTintList =
                            android.content.res.ColorStateList
                                .valueOf(0xFFFFFFFF.toInt())
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                val label =
                    TextView(this@ScreenshotPostActionActivity).apply {
                        text = getString(labelRes)
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 13f
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
            pillAction(android.R.drawable.ic_menu_share, R.string.action_share, marginEndDp = 10) {
                shareImage(uri)
                finish()
            },
        )
        row.addView(
            pillAction(R.drawable.ic_brush_auto_fix, R.string.screenshot_post_edit, marginEndDp = 0) {
                openNativeEditor(uri)
                finish()
            },
        )

        chip.addView(preview)
        chip.addView(hint)
        chip.addView(row)

        val lp =
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = statusInset
                    marginEnd = margin
                }
        root.addView(chip, lp)
        setContentView(root)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    companion object {
        const val EXTRA_IMAGE_URI = "EXTRA_IMAGE_URI"
    }
}
