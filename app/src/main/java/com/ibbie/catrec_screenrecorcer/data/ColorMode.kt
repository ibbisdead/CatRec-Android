package com.ibbie.catrec_screenrecorcer.data

import android.util.Log

/**
 * Video color output mode persisted in DataStore.
 *
 * [STANDARD] — Rec.709 limited-range SDR. Explicitly tags the bitstream with
 *   BT.709 primaries, limited (16–235) luma range and SDR transfer function so
 *   players (Google Photos, VLC, Premiere, DaVinci) interpret colours correctly.
 *   This is the default and fixes the washed-out / gray appearance caused by
 *   encoders silently outputting full-range data that players treat as limited.
 *
 * [FULL] — Full-range (0–255) SDR. BT.709 primaries, SDR transfer, full range.
 *   Use only when the target player or pipeline explicitly expects full-range.
 *   Note: on devices that ignore [android.media.MediaFormat.KEY_COLOR_RANGE] metadata
 *   the encoder may still output limited-range frames even in this mode. GIF export
 *   skips the limited→full expansion in this mode, so those frames would appear
 *   slightly darker/undersaturated in the resulting GIF. This is an expected
 *   device-level quirk, not a pipeline bug.
 */
object ColorMode {
    private const val TAG = "ColorMode"

    const val STANDARD = "Standard"
    const val FULL = "Full"

    fun isValid(value: String): Boolean = value == STANDARD || value == FULL

    /**
     * Resolves the effective color mode from up to three sources, in priority order:
     * 1. [raw]      — from an Intent extra or other untrusted source; accepted only if [isValid].
     * 2. [cached]   — from [SettingsConfigCache] snapshot; accepted only if [isValid].
     * 3. [STANDARD] — guaranteed safe fallback; never returns an invalid value.
     *
     * Logs a warning whenever [raw] is absent or carries an unrecognised value so that
     * mismatch between sender and receiver is visible in logcat without crashing.
     */
    fun resolve(raw: String?, cached: String?): String {
        val isRawValid = raw != null && isValid(raw)

        if (raw == null) {
            Log.w(TAG, "EXTRA_COLOR_MODE not present in intent; using cached/default")
        } else if (!isRawValid) {
            Log.w(TAG, "Invalid colorMode from intent: \"$raw\", falling back to cached/default")
        }

        val resolved = if (isRawValid) raw
            else cached?.takeIf(::isValid)
            ?: STANDARD

        if (!isRawValid) {
            Log.d(TAG, "Resolved colorMode=$resolved (raw=$raw, cached=$cached)")
        }
        return resolved
    }
}
