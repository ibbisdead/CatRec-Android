package com.ibbie.catrec_screenrecorcer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecordingState {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

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
     * The current recording mode selected in the app: "RECORD" or "CLIPPER".
     * The overlay reads this to decide which action to send when the record button is tapped.
     */
    private val _currentMode = MutableStateFlow("RECORD")
    val currentMode: StateFlow<String> = _currentMode.asStateFlow()

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
}
