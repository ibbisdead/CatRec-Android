package com.ibbie.catrec_screenrecorcer.data.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsConfigCache
import com.ibbie.catrec_screenrecorcer.data.StopBehaviorKeys
import com.ibbie.catrec_screenrecorcer.service.ScreenRecordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DefaultRecordingSessionRepository(
    private val settingsCache: SettingsConfigCache,
) : RecordingSessionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessionState = MutableStateFlow<RecordingLifecycleState>(RecordingLifecycleState.Idle)
    override val sessionState = _sessionState.asStateFlow()

    override val errorEvents: SharedFlow<RecordingError> = RecordingEngineEventBus.errorEvents

    init {
        scope.launch {
            combine(
                RecordingState.isPrepared,
                RecordingState.isRecording,
                RecordingState.isBuffering,
                RecordingState.isRecordingPaused,
            ) { prepared, recording, buffering, paused ->
                when {
                    recording && paused -> RecordingLifecycleState.Paused
                    recording || buffering -> RecordingLifecycleState.Recording
                    prepared -> RecordingLifecycleState.Preparing
                    else -> RecordingLifecycleState.Idle
                }
            }.distinctUntilChanged()
                .collect { _sessionState.value = it }
        }
    }

    override fun createSessionConfigForFullRecording(
        context: Context,
        resultCode: Int,
    ): SessionConfig {
        // Synchronous read from [SettingsConfigCache]. The cache is warmed at process
        // start; if it has not yet received its first snapshot we fall back to
        // [SettingsConfigCache.Snapshot.DEFAULTS] (identical to the DataStore flow
        // defaults) — never blocks the main thread.
        val snap = settingsCache.current()
        val (w, h) =
            estimateCapturePixels(
                context.applicationContext,
                effectiveResolution(snap.captureMode, snap.gifRecorderPresetId, snap.resolution),
                snap.recordingOrientation,
            )
        val audio =
            when {
                snap.recordAudio && snap.internalAudio -> SessionAudioSource.MICROPHONE_AND_INTERNAL
                snap.recordAudio -> SessionAudioSource.MICROPHONE
                snap.internalAudio -> SessionAudioSource.INTERNAL
                else -> SessionAudioSource.NONE
            }
        return SessionConfig(
            widthPx = w,
            heightPx = h,
            bitrateBitsPerSecond = effectiveBitrateBits(snap.captureMode, snap.gifRecorderPresetId, snap.bitrateMbps),
            frameRate = effectiveFps(snap.captureMode, snap.gifRecorderPresetId, snap.fps),
            audioSource = audio,
            mediaProjectionResultCode = resultCode,
            recordSingleApp = snap.recordSingleAppEnabled,
        )
    }

    override fun createSessionConfigForBuffer(
        context: Context,
        resultCode: Int,
    ): SessionConfig {
        val snap = settingsCache.current()
        val (w, h) =
            estimateCapturePixels(
                context.applicationContext,
                snap.resolution,
                snap.recordingOrientation,
            )
        val audio =
            when {
                snap.recordAudio && snap.internalAudio -> SessionAudioSource.MICROPHONE_AND_INTERNAL
                snap.recordAudio -> SessionAudioSource.MICROPHONE
                snap.internalAudio -> SessionAudioSource.INTERNAL
                else -> SessionAudioSource.NONE
            }
        return SessionConfig(
            widthPx = w,
            heightPx = h,
            bitrateBitsPerSecond = (snap.bitrateMbps * 1_000_000).toInt(),
            frameRate = snap.fps.toInt(),
            audioSource = audio,
            mediaProjectionResultCode = resultCode,
            recordSingleApp = snap.recordSingleAppEnabled,
        )
    }

    private fun effectiveResolution(
        mode: String,
        presetId: String,
        defaultResolution: String,
    ): String =
        if (mode == CaptureMode.GIF) {
            GifRecordingPresets.byId(presetId).resolutionSetting
        } else {
            defaultResolution
        }

    private fun effectiveFps(
        mode: String,
        presetId: String,
        defaultFps: Float,
    ): Int =
        if (mode == CaptureMode.GIF) {
            GifRecordingPresets.byId(presetId).recordingFps
        } else {
            defaultFps.toInt()
        }

    private fun effectiveBitrateBits(
        mode: String,
        presetId: String,
        defaultBitrateMbps: Float,
    ): Int =
        if (mode == CaptureMode.GIF) {
            GifRecordingPresets.byId(presetId).bitrateBitsPerSec
        } else {
            (defaultBitrateMbps * 1_000_000).toInt()
        }

    override fun startRecording(
        context: Context,
        config: SessionConfig,
        mediaProjectionGrantIntent: Intent,
    ) {
        // Synchronous by design — see [createSessionConfigForFullRecording] docs.
        val intent =
            buildFullRecordingStartIntent(
                context.applicationContext,
                config,
                mediaProjectionGrantIntent,
            )
        context.applicationContext.startForegroundService(intent)
    }

    override fun startBufferSession(
        context: Context,
        config: SessionConfig,
        mediaProjectionGrantIntent: Intent,
    ) {
        val intent =
            buildBufferStartIntent(
                context.applicationContext,
                config,
                mediaProjectionGrantIntent,
            )
        context.applicationContext.startForegroundService(intent)
    }

    override fun prepareOverlaySession(
        context: Context,
        resultCode: Int,
        projectionIntent: Intent,
    ) {
        val ctx = context.applicationContext
        ctx.startForegroundService(
            Intent(ctx, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_PREPARE
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, cloneProjectionIntent(projectionIntent))
            },
        )
    }

    override fun revokePrepare(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_REVOKE_PREPARE
            },
        )
    }

    override fun stop(context: Context) {
        val ctx = context.applicationContext
        when {
            RecordingState.isRecording.value ->
                ctx.startService(
                    Intent(ctx, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP },
                )
            RecordingState.isBuffering.value ->
                ctx.startService(
                    Intent(ctx, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP_BUFFER },
                )
            else ->
                ctx.startService(
                    Intent(ctx, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP },
                )
        }
    }

    override fun pause(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_PAUSE
            },
        )
    }

    override fun resume(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_RESUME
            },
        )
    }

    override fun saveClip(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_SAVE_CLIP
            },
        )
    }

    private fun buildFullRecordingStartIntent(
        ctx: Context,
        config: SessionConfig,
        mediaProjectionGrantIntent: Intent,
    ): Intent {
        val snap = settingsCache.current()
        val gif = snap.captureMode == CaptureMode.GIF
        val gifPreset = if (gif) GifRecordingPresets.byId(snap.gifRecorderPresetId) else null
        return Intent(ctx, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_SESSION_CONFIG, config)
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, config.mediaProjectionResultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, cloneProjectionIntent(mediaProjectionGrantIntent))
            putExtra(ScreenRecordService.EXTRA_FPS, effectiveFps(snap.captureMode, snap.gifRecorderPresetId, snap.fps))
            putExtra(ScreenRecordService.EXTRA_BITRATE, effectiveBitrateBits(snap.captureMode, snap.gifRecorderPresetId, snap.bitrateMbps))
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENABLED, snap.recordAudio)
            putExtra(ScreenRecordService.EXTRA_INTERNAL_AUDIO_ENABLED, snap.internalAudio)
            putExtra(ScreenRecordService.EXTRA_AUDIO_BITRATE, snap.audioBitrateKbps * 1000)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SAMPLE_RATE, snap.audioSampleRate)
            putExtra(ScreenRecordService.EXTRA_AUDIO_CHANNELS, snap.audioChannels)
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENCODER, snap.audioEncoder)
            putExtra(ScreenRecordService.EXTRA_SEPARATE_MIC_RECORDING, snap.separateMicRecording)
            putExtra(ScreenRecordService.EXTRA_SHOW_CAMERA, snap.cameraOverlay)
            putExtra(ScreenRecordService.EXTRA_CAMERA_SIZE, snap.cameraOverlaySize)
            putExtra(ScreenRecordService.EXTRA_CAMERA_X_FRACTION, snap.cameraXFraction)
            putExtra(ScreenRecordService.EXTRA_CAMERA_Y_FRACTION, snap.cameraYFraction)
            putExtra(ScreenRecordService.EXTRA_CAMERA_LOCK_POSITION, snap.cameraLockPosition)
            putExtra(ScreenRecordService.EXTRA_CAMERA_FACING, snap.cameraFacing)
            putExtra(ScreenRecordService.EXTRA_CAMERA_ASPECT_RATIO, snap.cameraAspectRatio)
            putExtra(ScreenRecordService.EXTRA_CAMERA_OPACITY, snap.cameraOpacity)
            putExtra(ScreenRecordService.EXTRA_SHOW_WATERMARK, snap.showWatermark)
            putStringArrayListExtra(
                ScreenRecordService.EXTRA_STOP_BEHAVIOR,
                ArrayList(StopBehaviorKeys.migrateSet(snap.stopBehavior).toList()),
            )
            putExtra(ScreenRecordService.EXTRA_SAVE_LOCATION, snap.saveLocationUri)
            putExtra(ScreenRecordService.EXTRA_VIDEO_ENCODER, snap.videoEncoder)
            putExtra(ScreenRecordService.EXTRA_SHOW_FLOATING_CONTROLS, snap.floatingControls)
            putExtra(
                ScreenRecordService.EXTRA_HIDE_FLOATING_ICON_WHILE_RECORDING,
                snap.hideFloatingIconWhileRecording,
            )
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, effectiveResolution(snap.captureMode, snap.gifRecorderPresetId, snap.resolution))
            putExtra(ScreenRecordService.EXTRA_FILENAME_PATTERN, snap.filenamePattern)
            putExtra(ScreenRecordService.EXTRA_COUNTDOWN, snap.countdown)
            putExtra(ScreenRecordService.EXTRA_KEEP_SCREEN_ON, snap.keepScreenOn)
            putExtra(ScreenRecordService.EXTRA_RECORDING_ORIENTATION, snap.recordingOrientation)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_LOCATION, snap.watermarkLocation)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_IMAGE_URI, snap.watermarkImageUri)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_SHAPE, snap.watermarkShape)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_OPACITY, snap.watermarkOpacity)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_SIZE, snap.watermarkSize)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_X_FRACTION, snap.watermarkXFraction)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_Y_FRACTION, snap.watermarkYFraction)
            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_FORMAT, snap.screenshotFormat)
            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_QUALITY, snap.screenshotQuality)
            putExtra(ScreenRecordService.EXTRA_GIF_SESSION, gif)
            if (gif && gifPreset != null) {
                putExtra(ScreenRecordService.EXTRA_GIF_MAX_DURATION_SEC, gifPreset.maxDurationSec)
                putExtra(ScreenRecordService.EXTRA_GIF_SCALE_WIDTH, gifPreset.maxWidth)
                putExtra(ScreenRecordService.EXTRA_GIF_OUTPUT_FPS, gifPreset.exportFps)
                putExtra(ScreenRecordService.EXTRA_GIF_MAX_COLORS, gifPreset.maxColors)
                putExtra(ScreenRecordService.EXTRA_GIF_DITHER_KIND, gifPreset.paletteDither.name)
            }
        }
    }

    private fun buildBufferStartIntent(
        ctx: Context,
        config: SessionConfig,
        mediaProjectionGrantIntent: Intent,
    ): Intent {
        val snap = settingsCache.current()
        return Intent(ctx, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START_BUFFER
            putExtra(ScreenRecordService.EXTRA_SESSION_CONFIG, config)
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, config.mediaProjectionResultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, cloneProjectionIntent(mediaProjectionGrantIntent))
            putExtra(ScreenRecordService.EXTRA_FPS, snap.fps.toInt())
            putExtra(ScreenRecordService.EXTRA_BITRATE, (snap.bitrateMbps * 1_000_000).toInt())
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENABLED, snap.recordAudio)
            putExtra(ScreenRecordService.EXTRA_INTERNAL_AUDIO_ENABLED, snap.internalAudio)
            putExtra(ScreenRecordService.EXTRA_AUDIO_BITRATE, snap.audioBitrateKbps * 1000)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SAMPLE_RATE, snap.audioSampleRate)
            putExtra(ScreenRecordService.EXTRA_AUDIO_CHANNELS, snap.audioChannels)
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENCODER, snap.audioEncoder)
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, snap.resolution)
            putExtra(ScreenRecordService.EXTRA_VIDEO_ENCODER, snap.videoEncoder)
            putExtra(ScreenRecordService.EXTRA_CLIPPER_DURATION_MINUTES, snap.clipperDurationMinutes)
            putExtra(ScreenRecordService.EXTRA_COUNTDOWN, snap.countdown)
        }
    }

    companion object {
        fun estimateCapturePixels(
            context: Context,
            resolution: String,
            orientation: String,
        ): Pair<Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (nativeWidth, nativeHeight) =
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = wm.currentWindowMetrics.bounds
                    Pair(b.width(), b.height())
                } else {
                    @Suppress("DEPRECATION")
                    val d = wm.defaultDisplay
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    d.getRealMetrics(dm)
                    Pair(dm.widthPixels, dm.heightPixels)
                }

            val (baseW, baseH) =
                when (orientation) {
                    "Portrait" -> Pair(min(nativeWidth, nativeHeight), max(nativeWidth, nativeHeight))
                    "Landscape" -> Pair(max(nativeWidth, nativeHeight), min(nativeWidth, nativeHeight))
                    else -> Pair(nativeWidth, nativeHeight)
                }
            val aspectRatio = baseW.toFloat() / baseH.toFloat()

            return when {
                resolution == "Native" -> {
                    val w = (baseW / 16) * 16
                    val h = (baseH / 16) * 16
                    w to h
                }
                resolution.contains("x") -> {
                    val parts = resolution.split("x")
                    val w = parts.getOrNull(0)?.toIntOrNull() ?: baseW
                    val h = parts.getOrNull(1)?.toIntOrNull() ?: baseH
                    ((w / 16) * 16) to ((h / 16) * 16)
                }
                else -> {
                    val targetHeight =
                        when {
                            resolution.contains("2160") || resolution.contains("4K") -> 2160
                            resolution.contains("1440") || resolution.contains("2K") -> 1440
                            resolution.contains("1080") -> 1080
                            resolution.contains("720") -> 720
                            resolution.contains("480") -> 480
                            resolution.contains("360") -> 360
                            else -> baseH
                        }
                    val targetWidth = (targetHeight * aspectRatio).roundToInt()
                    ((targetWidth / 16) * 16) to ((targetHeight / 16) * 16)
                }
            }
        }
    }
}
