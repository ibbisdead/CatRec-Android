package com.ibbie.catrec_screenrecorcer.data.recording

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-layer recording diagnostics (service → ViewModel → Compose).
 *
 * Collect [errorEvents] from a [androidx.lifecycle.ViewModel] with `viewModelScope`.
 */
object RecordingEngineEventBus {
    private val _errorEvents =
        MutableSharedFlow<RecordingError>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val errorEvents: SharedFlow<RecordingError> = _errorEvents.asSharedFlow()

    fun tryEmit(error: RecordingError): Boolean = _errorEvents.tryEmit(error)
}
