package com.ibbie.catrec_screenrecorcer.data

/**
 * Coarse UX phase derived from [RecordingState] flags (single source of truth remains the flows;
 * this is for UI/tests that want one enum instead of three booleans).
 *
 * **Saved** is not a separate Android / DataStore flag today: after stop, you return to [Idle].
 * Tests treat “Saved” as the post-stop idle slice after an active capture has ended.
 */
enum class RecordingSessionPhase {
    Idle,
    Recording,
    Buffering,

    /** Capture finished; no active recording/buffer. */
    Saved,
}

object RecordingPhaseMapper {
    /**
     * @param hadActiveCapture true once recording or buffering has been true this session
     *        (test / UI can track externally); used only to classify [Saved] vs [Idle].
     */
    fun derive(
        isRecording: Boolean,
        isBuffering: Boolean,
        hadActiveCapture: Boolean,
    ): RecordingSessionPhase =
        when {
            isBuffering -> RecordingSessionPhase.Buffering
            isRecording -> RecordingSessionPhase.Recording
            hadActiveCapture -> RecordingSessionPhase.Saved
            else -> RecordingSessionPhase.Idle
        }
}
