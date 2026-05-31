package com.ibbie.catrec_screenrecorcer.data

import android.util.Log

/**
 * Video color output mode persisted in DataStore.
 *
 * [FULL] — Full-range (0–255) SDR with BT.709 primaries and SDR transfer. **Default.**
 *   Matches the raw pixel values the GPU produces and is the most screen-accurate mode
 *   for typical screen content. GIF export skips any limited→full expansion because the
 *   source is already full-range.
 *   Note: on devices that ignore [android.media.MediaFormat.KEY_COLOR_RANGE] metadata
 *   the encoder may still output limited-range frames; this is a hardware quirk, not
 *   a pipeline bug.
 *
 * [STANDARD] — Limited-range (16–235) SDR with BT.709 primaries and SDR transfer.
 *   Compatibility mode originally added for devices (e.g. Poco X7 Pro with H.264)
 *   where the encoder ignores full-range metadata and playback looks washed-out.
 *   GIF export expands limited→full (16–235 → 0–255) to rebuild correct RGB values.
 *   When [STANDARD] is active, "Repair limited-range metadata" (Force Rec.709) can
 *   be enabled as an additional post-processing step via FFmpeg stream-copy.
 */
object ColorMode {
    private const val TAG = "ColorMode"

    const val STANDARD = "Standard"
    const val FULL = "Full"

    fun isValid(value: String): Boolean = value == STANDARD || value == FULL

    /**
     * Resolves the effective color mode from up to three sources, in priority order:
     * 1. [raw]    — from an Intent extra or other untrusted source; accepted only if [isValid].
     * 2. [cached] — from [SettingsConfigCache] snapshot; accepted only if [isValid].
     * 3. [FULL]   — guaranteed safe fallback (full-range, screen-accurate); never returns an invalid value.
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
            ?: FULL

        if (!isRawValid) {
            Log.d(TAG, "Resolved colorMode=$resolved (raw=$raw, cached=$cached)")
        }
        return resolved
    }
}
