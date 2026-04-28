package com.ibbie.catrec_screenrecorcer.utils

import android.util.Log

object AppLogger {
    private val logs = mutableListOf<String>()

    fun e(
        tag: String,
        msg: String,
    ) {
        logs.add("E/$tag: $msg")
        Log.e(tag, msg)
    }

    fun w(
        tag: String,
        msg: String,
    ) {
        logs.add("W/$tag: $msg")
        Log.w(tag, msg)
    }

    fun dump(): String = logs.joinToString("\n")
}
