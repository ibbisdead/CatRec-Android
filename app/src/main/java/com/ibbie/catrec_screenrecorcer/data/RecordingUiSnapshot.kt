package com.ibbie.catrec_screenrecorcer.data

/**
 * Subset of [RecordingState] flows combined for [com.ibbie.catrec_screenrecorcer.ui.recording.RecordingViewModel.recordingUiSnapshot].
 * Named fields avoid ambiguous [Triple] accessors when mapping into [RecordingUiSnapshot].
 */
data class PreparedPausedSavingState(
    val isPrepared: Boolean,
    val isRecordingPaused: Boolean,
    val isSaving: Boolean,
)

/**
 * Single snapshot for recording-related UI (FAB bridge + nav chrome) so composables
 * subscribe once instead of many independent collectors.
 */
data class RecordingUiSnapshot(
    val isRecording: Boolean = false,
    val isBuffering: Boolean = false,
    val captureMode: String = CaptureMode.RECORD,
    val recordAudio: Boolean = false,
    val internalAudio: Boolean = false,
    val recordSingleAppEnabled: Boolean = false,
    val isPrepared: Boolean = false,
    val isRecordingPaused: Boolean = false,
    val isSaving: Boolean = false,
)
