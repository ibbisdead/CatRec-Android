package com.ibbie.catrec_screenrecorcer.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * In-memory snapshot of every [SettingsRepository] value consumed on the synchronous
 * activity-result → `startForegroundService` path. Feeding the cache from DataStore on a
 * background coroutine lets callers read the latest settings without blocking the main
 * thread (previously done via `runBlocking(Dispatchers.IO)` inside
 * [com.ibbie.catrec_screenrecorcer.data.recording.DefaultRecordingSessionRepository]).
 *
 * Android 12+ only keeps the foreground-service grant window open while the activity-
 * result call stack is unwinding — so blocking on DataStore or deferring to
 * `viewModelScope.launch` can both fail. The cache closes that gap.
 *
 * If the cache has not yet been populated (very first cold-start read before the
 * priming coroutine has emitted), callers fall back to [Snapshot.DEFAULTS] — the same
 * values [SettingsRepository] returns on a missing key.
 */
class SettingsConfigCache(
    private val repository: SettingsRepository,
) {
    /**
     * Immutable view of all preferences we read synchronously for building
     * [com.ibbie.catrec_screenrecorcer.data.recording.SessionConfig] / start intents.
     */
    data class Snapshot(
        val captureMode: String,
        val gifRecorderPresetId: String,
        val recordingOrientation: String,
        val recordAudio: Boolean,
        val internalAudio: Boolean,
        val recordSingleAppEnabled: Boolean,
        val resolution: String,
        val fps: Float,
        val bitrateMbps: Float,
        val audioBitrateKbps: Int,
        val audioSampleRate: Int,
        val audioChannels: String,
        val audioEncoder: String,
        val separateMicRecording: Boolean,
        val cameraOverlay: Boolean,
        val cameraOverlaySize: Int,
        val cameraXFraction: Float,
        val cameraYFraction: Float,
        val cameraLockPosition: Boolean,
        val cameraFacing: String,
        val cameraAspectRatio: String,
        val cameraOpacity: Int,
        val showWatermark: Boolean,
        val stopBehavior: Set<String>,
        val saveLocationUri: String?,
        val videoEncoder: String,
        val floatingControls: Boolean,
        val hideFloatingIconWhileRecording: Boolean,
        val filenamePattern: String,
        val countdown: Int,
        val keepScreenOn: Boolean,
        val watermarkLocation: String,
        val watermarkImageUri: String?,
        val watermarkShape: String,
        val watermarkOpacity: Int,
        val watermarkSize: Int,
        val watermarkXFraction: Float,
        val watermarkYFraction: Float,
        val screenshotFormat: String,
        val screenshotQuality: Int,
        val clipperDurationMinutes: Int,
    ) {
        companion object {
            /** Matches [SettingsRepository] Flow defaults. Used when the cache has not warmed yet. */
            val DEFAULTS = Snapshot(
                captureMode = CaptureMode.RECORD,
                gifRecorderPresetId = GifRecordingPresets.default.id,
                recordingOrientation = "Auto",
                recordAudio = false,
                internalAudio = false,
                recordSingleAppEnabled = false,
                resolution = "Native",
                fps = 30f,
                bitrateMbps = 10f,
                audioBitrateKbps = 128,
                audioSampleRate = 44100,
                audioChannels = "Mono",
                audioEncoder = "AAC-LC",
                separateMicRecording = false,
                cameraOverlay = false,
                cameraOverlaySize = 120,
                cameraXFraction = 0.05f,
                cameraYFraction = 0.1f,
                cameraLockPosition = false,
                cameraFacing = "Front",
                cameraAspectRatio = "Circle",
                cameraOpacity = 100,
                showWatermark = false,
                stopBehavior = setOf(StopBehaviorKeys.NOTIFICATION),
                saveLocationUri = null,
                videoEncoder = "H.264",
                floatingControls = false,
                hideFloatingIconWhileRecording = false,
                filenamePattern = "yyyyMMdd_HHmmss",
                countdown = 0,
                keepScreenOn = false,
                watermarkLocation = "Top Left",
                watermarkImageUri = null,
                watermarkShape = "Square",
                watermarkOpacity = 100,
                watermarkSize = 80,
                watermarkXFraction = 0.05f,
                watermarkYFraction = 0.05f,
                screenshotFormat = "JPEG",
                screenshotQuality = 90,
                clipperDurationMinutes = 1,
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** True once the cache has received at least one full snapshot from DataStore. */
    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * Subscribes to [SettingsRepository] flows and keeps [snapshot] up to date. Safe to
     * call multiple times — only the first invocation actually wires the collectors.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Prime once with a synchronous read so the first caller after start() but
            // before collect() has emitted still sees real values, not defaults.
            try {
                _snapshot.value = buildSnapshot()
                isReady = true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Initial snapshot priming failed; will retry via collector", e)
            }
        }
        // Combine in chunks (Kotlin combine caps at 5 args per overload). Each chunk
        // produces a partial update; the final combine merges them into a full snapshot.
        scope.launch {
            combineAll().collect { s ->
                _snapshot.value = s
                isReady = true
            }
        }
    }

    @Volatile
    private var started = false

    /** Current snapshot or [Snapshot.DEFAULTS] if the cache has not yet warmed. */
    fun current(): Snapshot = _snapshot.value ?: Snapshot.DEFAULTS

    private suspend fun buildSnapshot(): Snapshot = with(repository) {
        Snapshot(
            captureMode = captureMode.first(),
            gifRecorderPresetId = gifRecorderPresetId.first(),
            recordingOrientation = recordingOrientation.first(),
            recordAudio = recordAudio.first(),
            internalAudio = internalAudio.first(),
            recordSingleAppEnabled = recordSingleAppEnabled.first(),
            resolution = resolution.first(),
            fps = fps.first(),
            bitrateMbps = bitrate.first(),
            audioBitrateKbps = audioBitrate.first(),
            audioSampleRate = audioSampleRate.first(),
            audioChannels = audioChannels.first(),
            audioEncoder = audioEncoder.first(),
            separateMicRecording = separateMicRecording.first(),
            cameraOverlay = cameraOverlay.first(),
            cameraOverlaySize = cameraOverlaySize.first(),
            cameraXFraction = cameraXFraction.first(),
            cameraYFraction = cameraYFraction.first(),
            cameraLockPosition = cameraLockPosition.first(),
            cameraFacing = cameraFacing.first(),
            cameraAspectRatio = cameraAspectRatio.first(),
            cameraOpacity = cameraOpacity.first(),
            showWatermark = showWatermark.first(),
            stopBehavior = stopBehavior.first(),
            saveLocationUri = saveLocationUri.first(),
            videoEncoder = videoEncoder.first(),
            floatingControls = floatingControls.first(),
            hideFloatingIconWhileRecording = hideFloatingIconWhileRecording.first(),
            filenamePattern = filenamePattern.first(),
            countdown = countdown.first(),
            keepScreenOn = keepScreenOn.first(),
            watermarkLocation = watermarkLocation.first(),
            watermarkImageUri = watermarkImageUri.first(),
            watermarkShape = watermarkShape.first(),
            watermarkOpacity = watermarkOpacity.first(),
            watermarkSize = watermarkSize.first(),
            watermarkXFraction = watermarkXFraction.first(),
            watermarkYFraction = watermarkYFraction.first(),
            screenshotFormat = screenshotFormat.first(),
            screenshotQuality = screenshotQuality.first(),
            clipperDurationMinutes = clipperDurationMinutes.first(),
        )
    }

    /**
     * Re-emits a full [Snapshot] whenever any of the watched flows changes. DataStore
     * reads are debounced by [kotlinx.coroutines.flow.combine] — we only rebuild the
     * snapshot after a settled set of values is available for every key.
     */
    private fun combineAll() = with(repository) {
        // One `combine` call is limited to 5 flows, so we layer multiple combines and then
        // merge them into the final Snapshot.
        val video = combine(captureMode, gifRecorderPresetId, recordingOrientation, resolution, fps) { a, b, c, d, e ->
            VideoPart(a, b, c, d, e)
        }
        val audioFlags = combine(recordAudio, internalAudio, recordSingleAppEnabled, bitrate, audioBitrate) { a, b, c, d, e ->
            AudioFlagsPart(a, b, c, d, e)
        }
        val audio2 = combine(audioSampleRate, audioChannels, audioEncoder, separateMicRecording, videoEncoder) { a, b, c, d, e ->
            Audio2Part(a, b, c, d, e)
        }
        val camera = combine(cameraOverlay, cameraOverlaySize, cameraXFraction, cameraYFraction, cameraLockPosition) { a, b, c, d, e ->
            CameraPart(a, b, c, d, e)
        }
        val camera2 = combine(cameraFacing, cameraAspectRatio, cameraOpacity, showWatermark, stopBehavior) { a, b, c, d, e ->
            Camera2Part(a, b, c, d, e)
        }
        val watermark = combine(watermarkLocation, watermarkImageUri, watermarkShape, watermarkOpacity, watermarkSize) { a, b, c, d, e ->
            WatermarkPart(a, b, c, d, e)
        }
        val storageControls = combine(saveLocationUri, floatingControls, hideFloatingIconWhileRecording, filenamePattern, countdown) { a, b, c, d, e ->
            StorageControlsPart(a, b, c, d, e)
        }
        val misc = combine(keepScreenOn, watermarkXFraction, watermarkYFraction, screenshotFormat, screenshotQuality) { a, b, c, d, e ->
            MiscPart(a, b, c, d, e)
        }
        val tail = combine(clipperDurationMinutes, captureMode) { a, _ -> a }

        val groupA = combine(video, audioFlags, audio2, camera, camera2) { v, af, a2, c, c2 ->
            GroupA(v, af, a2, c, c2)
        }
        val groupB = combine(watermark, storageControls, misc, tail) { w, sc, m, t ->
            GroupB(w, sc, m, t)
        }
        combine(groupA, groupB) { a, b ->
            Snapshot(
                captureMode = a.video.captureMode,
                gifRecorderPresetId = a.video.gifRecorderPresetId,
                recordingOrientation = a.video.recordingOrientation,
                recordAudio = a.audioFlags.recordAudio,
                internalAudio = a.audioFlags.internalAudio,
                recordSingleAppEnabled = a.audioFlags.recordSingleAppEnabled,
                resolution = a.video.resolution,
                fps = a.video.fps,
                bitrateMbps = a.audioFlags.bitrateMbps,
                audioBitrateKbps = a.audioFlags.audioBitrateKbps,
                audioSampleRate = a.audio2.audioSampleRate,
                audioChannels = a.audio2.audioChannels,
                audioEncoder = a.audio2.audioEncoder,
                separateMicRecording = a.audio2.separateMicRecording,
                cameraOverlay = a.camera.cameraOverlay,
                cameraOverlaySize = a.camera.cameraOverlaySize,
                cameraXFraction = a.camera.cameraXFraction,
                cameraYFraction = a.camera.cameraYFraction,
                cameraLockPosition = a.camera.cameraLockPosition,
                cameraFacing = a.camera2.cameraFacing,
                cameraAspectRatio = a.camera2.cameraAspectRatio,
                cameraOpacity = a.camera2.cameraOpacity,
                showWatermark = a.camera2.showWatermark,
                stopBehavior = a.camera2.stopBehavior,
                saveLocationUri = b.storage.saveLocationUri,
                videoEncoder = a.audio2.videoEncoder,
                floatingControls = b.storage.floatingControls,
                hideFloatingIconWhileRecording = b.storage.hideFloatingIconWhileRecording,
                filenamePattern = b.storage.filenamePattern,
                countdown = b.storage.countdown,
                keepScreenOn = b.misc.keepScreenOn,
                watermarkLocation = b.watermark.watermarkLocation,
                watermarkImageUri = b.watermark.watermarkImageUri,
                watermarkShape = b.watermark.watermarkShape,
                watermarkOpacity = b.watermark.watermarkOpacity,
                watermarkSize = b.watermark.watermarkSize,
                watermarkXFraction = b.misc.watermarkXFraction,
                watermarkYFraction = b.misc.watermarkYFraction,
                screenshotFormat = b.misc.screenshotFormat,
                screenshotQuality = b.misc.screenshotQuality,
                clipperDurationMinutes = b.tailClipperDurationMinutes,
            )
        }
    }

    private data class VideoPart(
        val captureMode: String,
        val gifRecorderPresetId: String,
        val recordingOrientation: String,
        val resolution: String,
        val fps: Float,
    )
    private data class AudioFlagsPart(
        val recordAudio: Boolean,
        val internalAudio: Boolean,
        val recordSingleAppEnabled: Boolean,
        val bitrateMbps: Float,
        val audioBitrateKbps: Int,
    )
    private data class Audio2Part(
        val audioSampleRate: Int,
        val audioChannels: String,
        val audioEncoder: String,
        val separateMicRecording: Boolean,
        val videoEncoder: String,
    )
    private data class CameraPart(
        val cameraOverlay: Boolean,
        val cameraOverlaySize: Int,
        val cameraXFraction: Float,
        val cameraYFraction: Float,
        val cameraLockPosition: Boolean,
    )
    private data class Camera2Part(
        val cameraFacing: String,
        val cameraAspectRatio: String,
        val cameraOpacity: Int,
        val showWatermark: Boolean,
        val stopBehavior: Set<String>,
    )
    private data class WatermarkPart(
        val watermarkLocation: String,
        val watermarkImageUri: String?,
        val watermarkShape: String,
        val watermarkOpacity: Int,
        val watermarkSize: Int,
    )
    private data class StorageControlsPart(
        val saveLocationUri: String?,
        val floatingControls: Boolean,
        val hideFloatingIconWhileRecording: Boolean,
        val filenamePattern: String,
        val countdown: Int,
    )
    private data class MiscPart(
        val keepScreenOn: Boolean,
        val watermarkXFraction: Float,
        val watermarkYFraction: Float,
        val screenshotFormat: String,
        val screenshotQuality: Int,
    )
    private data class GroupA(
        val video: VideoPart,
        val audioFlags: AudioFlagsPart,
        val audio2: Audio2Part,
        val camera: CameraPart,
        val camera2: Camera2Part,
    )
    private data class GroupB(
        val watermark: WatermarkPart,
        val storage: StorageControlsPart,
        val misc: MiscPart,
        val tailClipperDurationMinutes: Int,
    )

    companion object {
        private const val LOG_TAG = "SettingsConfigCache"
    }
}
