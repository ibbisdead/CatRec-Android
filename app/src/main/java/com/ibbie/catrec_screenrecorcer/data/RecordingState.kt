package com.ibbie.catrec_screenrecorcer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RecordingState {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)

    /** True while the rolling-buffer engine is actively capturing. */
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    /**
     * True when ScreenRecordService holds a live MediaProjection token and is ready
     * for the overlay to start a recording without showing a permission dialog.
     */
    private val _isPrepared = MutableStateFlow(false)
    val isPrepared: StateFlow<Boolean> = _isPrepared.asStateFlow()

    /**
     * Capture mode: [CaptureMode.RECORD], [CaptureMode.CLIPPER], or [CaptureMode.GIF].
     * The overlay treats GIF like RECORD (start recording), not rolling buffer.
     * While the rolling buffer is active, this is driven to CLIPPER regardless of pill selection.
     */
    private val _currentMode = MutableStateFlow("RECORD")
    val currentMode: StateFlow<String> = _currentMode.asStateFlow()

    /** Mirrors recorder pause so UI and quick controls can show Pause vs Resume. */
    private val _isRecordingPaused = MutableStateFlow(false)
    val isRecordingPaused: StateFlow<Boolean> = _isRecordingPaused.asStateFlow()

    /** True when a recording or buffer is stopped and actively being muxed/written to disk. */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * Monotonically-incrementing counter bumped each time a screenshot is successfully saved to
     * MediaStore. UI screens can observe this as a [kotlinx.coroutines.flow.StateFlow] key to
     * trigger a list refresh without polling.
     */
    private val _screenshotSavedCount = MutableStateFlow(0)
    val screenshotSavedCount: StateFlow<Int> = _screenshotSavedCount.asStateFlow()

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    fun updateDuration(duration: Long) {
        _recordingDuration.value = duration
    }

    fun setBuffering(buffering: Boolean) {
        _isBuffering.value = buffering
    }

    fun setPrepared(prepared: Boolean) {
        _isPrepared.value = prepared
    }

    fun setMode(mode: String) {
        _currentMode.value = mode
    }

    fun setRecordingPaused(paused: Boolean) {
        _isRecordingPaused.value = paused
    }

    fun setSaving(saving: Boolean) {
        _isSaving.value = saving
    }

    fun onScreenshotSaved() {
        _screenshotSavedCount.update { it + 1 }
    }
}
