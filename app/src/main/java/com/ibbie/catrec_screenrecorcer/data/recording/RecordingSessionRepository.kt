package com.ibbie.catrec_screenrecorcer.data.recording

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-wide recording session control. Implementations start/stop
 * [com.ibbie.catrec_screenrecorcer.service.ScreenRecordService] and mirror lifecycle into
 * [sessionState].
 */
interface RecordingSessionRepository {
    val sessionState: StateFlow<RecordingLifecycleState>

    /** Codec / projection / permission failures from engines and services. */
    val errorEvents: SharedFlow<RecordingError>

    /**
     * Begins full-screen (or single-app) recording using [config] for projection + core encode
     * parameters; remaining options are merged from persisted settings inside the implementation.
     */
    fun startRecording(
        context: Context,
        config: SessionConfig,
    )

    fun startBufferSession(
        context: Context,
        config: SessionConfig,
    )

    fun prepareOverlaySession(
        context: Context,
        resultCode: Int,
        projectionIntent: Intent,
    )

    fun revokePrepare(context: Context)

    /** Stops active recording, or rolling buffer if that is active. */
    fun stop(context: Context)

    fun pause(context: Context)

    fun resume(context: Context)

    fun saveClip(context: Context)

    /**
     * Builds a [SessionConfig] from current settings + [context] display metrics. Must be
     * callable synchronously from an activity-result callback so the subsequent
     * `startForegroundService` stays inside the foreground-service grant window that Android
     * 12+ only keeps open on the caller's original thread.
     */
    fun createSessionConfigForFullRecording(
        context: Context,
        resultCode: Int,
        projectionIntent: Intent,
    ): SessionConfig

    fun createSessionConfigForBuffer(
        context: Context,
        resultCode: Int,
        projectionIntent: Intent,
    ): SessionConfig
}
