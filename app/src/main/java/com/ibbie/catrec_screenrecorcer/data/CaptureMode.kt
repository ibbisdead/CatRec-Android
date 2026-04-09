package com.ibbie.catrec_screenrecorcer.data

/** Persisted capture mode and [RecordingState] overlay routing. */
object CaptureMode {
    const val RECORD = "RECORD"
    const val CLIPPER = "CLIPPER"
    const val GIF = "GIF"

    fun isValid(value: String): Boolean =
        value == RECORD || value == CLIPPER || value == GIF
}
