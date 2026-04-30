package com.ibbie.catrec_screenrecorcer.data

object Rec709CompatBrightnessCorrection {
    const val OFF = "OFF"
    const val LOW = "LOW"
    const val MEDIUM = "MEDIUM"

    fun isValid(value: String?): Boolean =
        value == OFF || value == LOW || value == MEDIUM

    fun resolve(
        value: String?,
        fallback: String = OFF,
    ): String =
        if (isValid(value)) value!! else fallback
}
