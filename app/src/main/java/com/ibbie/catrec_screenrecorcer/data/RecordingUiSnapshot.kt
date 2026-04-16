package com.ibbie.catrec_screenrecorcer.data

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
)
