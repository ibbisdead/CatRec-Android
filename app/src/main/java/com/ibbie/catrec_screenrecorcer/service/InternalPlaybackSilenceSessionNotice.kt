package com.ibbie.catrec_screenrecorcer.service

/**
 * Non-blocking user notices for internal playback near-silence during full recording.
 * Surfaced on the main thread from [ScreenRecorderEngine] via [ScreenRecordService]; at most one
 * per category per session under current silence-timeout logic.
 */
enum class InternalPlaybackSilenceSessionNotice {
    /** Internal playback remained near-silent and auto mic fallback is off — recording continues. */
    NO_INTERNAL_AUDIO_CONTINUE_SILENT,

    /** Hot-swap to microphone succeeded after internal silence with auto fallback on. */
    MIC_FALLBACK_APPLIED,

    /** Auto mic fallback is on but [android.Manifest.permission.RECORD_AUDIO] is not granted. */
    MIC_FALLBACK_NEEDS_PERMISSION,
}
