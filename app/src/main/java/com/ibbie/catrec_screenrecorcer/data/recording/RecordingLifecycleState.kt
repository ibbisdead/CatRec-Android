package com.ibbie.catrec_screenrecorcer.data.recording

/**
 * High-level capture lifecycle for Compose / ViewModel (distinct from the process-wide
 * [com.ibbie.catrec_screenrecorcer.data.RecordingState] object used by [ScreenRecordService]).
 */
sealed class RecordingLifecycleState {
    data object Idle : RecordingLifecycleState()

    /** Active screen capture (file or rolling buffer). */
    data object Recording : RecordingLifecycleState()

    data object Paused : RecordingLifecycleState()
}
