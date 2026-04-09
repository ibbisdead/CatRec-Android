package com.ibbie.catrec_screenrecorcer.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.ibbie.catrec_screenrecorcer.BuildConfig
import com.ibbie.catrec_screenrecorcer.R

object FeedbackEmail {
    const val ADDRESS = "ibbisdead666@gmail.com"

    fun buildBody(userDescription: String): String = buildString {
        appendLine(userDescription.trim())
        appendLine()
        appendLine("---")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    /**
     * Opens email chooser. Returns false if no app can handle the intent.
     */
    fun send(
        context: Context,
        issueTypeLabel: String,
        description: String,
        attachmentUris: List<Uri>,
    ): Boolean {
        val subject = "[CatRec] $issueTypeLabel"
        val body = buildBody(description)

        val intent = if (attachmentUris.isEmpty()) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachmentUris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(
                    context.contentResolver,
                    "attachment",
                    attachmentUris.first(),
                ).also { clip ->
                    attachmentUris.drop(1).forEach { u ->
                        clip.addItem(ClipData.Item(u))
                    }
                }
            }
        }

        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.feedback_send_chooser_title),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (attachmentUris.isNotEmpty()) {
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(chooser)
            true
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.feedback_no_email_app),
                Toast.LENGTH_LONG,
            ).show()
            false
        }
    }
}
