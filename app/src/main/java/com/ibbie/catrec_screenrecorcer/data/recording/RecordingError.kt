package com.ibbie.catrec_screenrecorcer.data.recording

/**
 * Hardware, codec, or permission failures surfaced from the recording layer to the UI.
 */
sealed interface RecordingError {
    data class HardwareEncoder(
        val source: String,
        val detail: String,
    ) : RecordingError

    /**
     * [MediaMuxer] failure (e.g. [android.media.MediaMuxer.writeSampleData]), muxer state,
     * or timestamp rules — distinct from hardware codec encode failures.
     */
    data class MediaMuxerFailure(
        val source: String,
        val detail: String,
    ) : RecordingError

    /** [MediaProjection.Callback.onStop] — token consumed, user left capture, or OS revoked (incl. API 35). */
    data class ProjectionStopped(
        val reason: String = "media_projection_on_stop",
    ) : RecordingError

    data class PermissionDenied(
        val what: String,
    ) : RecordingError
}

/** Category for a single-shot fatal callback from recording engines (maps to [RecordingError]). */
enum class RecordingFatalKind {
    HardwareVideoEncoder,
    MediaMuxer,
}
