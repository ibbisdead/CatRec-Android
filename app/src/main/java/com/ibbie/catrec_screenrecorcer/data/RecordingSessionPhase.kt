package com.ibbie.catrec_screenrecorcer.data

/**
 * Coarse UX phase derived from [RecordingState] flags (single source of truth remains the flows;
 * this is for UI/tests that want one enum instead of three booleans).
 *
 * **Saved** is not a separate Android / DataStore flag today: after stop, you return to [Idle]
 * (projection revoked) or [Preparing] (overlay “ready” mode). Tests treat “Saved” as that
 * post-stop idle slice before prepare is cleared.
 */
enum class RecordingSessionPhase {
    Idle,
    Preparing,
    Recording,
    Buffering,

    /** Capture finished; no active recording/buffer (projection may still be “ready”). */
    Saved,
}

object RecordingPhaseMapper {
    /**
     * @param hadActiveCapture true once recording or buffering has been true this session
     *        (test / UI can track externally); used only to classify [Saved] vs [Idle].
     */
    fun derive(
        isPrepared: Boolean,
        isRecording: Boolean,
        isBuffering: Boolean,
        hadActiveCapture: Boolean,
    ): RecordingSessionPhase =
        when {
            isBuffering -> RecordingSessionPhase.Buffering
            isRecording -> RecordingSessionPhase.Recording
            isPrepared -> RecordingSessionPhase.Preparing
            hadActiveCapture -> RecordingSessionPhase.Saved
            else -> RecordingSessionPhase.Idle
        }
}
