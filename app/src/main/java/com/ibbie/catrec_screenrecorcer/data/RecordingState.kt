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

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    fun updateDuration(duration: Long) {
        _recordingDuration.value = duration
    }

    fun setBuffering(buffering: Boolean) {
        _isBuffering.value = buffering
    }
}
