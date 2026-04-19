package com.ibbie.catrec_screenrecorcer.data.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.ibbie.catrec_screenrecorcer.data.CaptureMode
import com.ibbie.catrec_screenrecorcer.data.GifRecordingPresets
import com.ibbie.catrec_screenrecorcer.data.RecordingState
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DefaultRecordingSessionRepository(
    private val settingsRepository: SettingsRepository,
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

    override suspend fun createSessionConfigForFullRecording(
        context: Context,
        resultCode: Int,
        projectionIntent: Intent,
    ): SessionConfig {
        val mode = settingsRepository.captureMode.first()
        val presetId = settingsRepository.gifRecorderPresetId.first()
        val (w, h) =
            estimateCapturePixels(
                context.applicationContext,
                effectiveResolution(mode, presetId),
                settingsRepository.recordingOrientation.first(),
            )
        val fps = effectiveFps(mode, presetId)
        val bitrate = effectiveBitrateBits(mode, presetId)
        val audio =
            when {
                settingsRepository.recordAudio.first() && settingsRepository.internalAudio.first() ->
                    SessionAudioSource.MICROPHONE_AND_INTERNAL
                settingsRepository.recordAudio.first() -> SessionAudioSource.MICROPHONE
                settingsRepository.internalAudio.first() -> SessionAudioSource.INTERNAL
                else -> SessionAudioSource.NONE
            }
        return SessionConfig(
            widthPx = w,
            heightPx = h,
            bitrateBitsPerSecond = bitrate,
            frameRate = fps,
            audioSource = audio,
            mediaProjectionResultCode = resultCode,
            mediaProjectionGrantIntent = cloneProjectionIntent(projectionIntent),
            recordSingleApp = settingsRepository.recordSingleAppEnabled.first(),
        )
    }

    override suspend fun createSessionConfigForBuffer(
        context: Context,
        resultCode: Int,
        projectionIntent: Intent,
    ): SessionConfig {
        val (w, h) =
            estimateCapturePixels(
                context.applicationContext,
                settingsRepository.resolution.first(),
                settingsRepository.recordingOrientation.first(),
            )
        val audio =
            when {
                settingsRepository.recordAudio.first() && settingsRepository.internalAudio.first() ->
                    SessionAudioSource.MICROPHONE_AND_INTERNAL
                settingsRepository.recordAudio.first() -> SessionAudioSource.MICROPHONE
                settingsRepository.internalAudio.first() -> SessionAudioSource.INTERNAL
                else -> SessionAudioSource.NONE
            }
        return SessionConfig(
            widthPx = w,
            heightPx = h,
            bitrateBitsPerSecond = (settingsRepository.bitrate.first() * 1_000_000).toInt(),
            frameRate = settingsRepository.fps.first().toInt(),
            audioSource = audio,
            mediaProjectionResultCode = resultCode,
            mediaProjectionGrantIntent = cloneProjectionIntent(projectionIntent),
            recordSingleApp = settingsRepository.recordSingleAppEnabled.first(),
        )
    }

    private suspend fun effectiveResolution(
        mode: String,
        presetId: String,
    ): String =
        if (mode == CaptureMode.GIF) {
            GifRecordingPresets.byId(presetId).resolutionSetting
        } else {
            settingsRepository.resolution.first()
        }

    private suspend fun effectiveFps(
        mode: String,
        presetId: String,
    ): Int =
        if (mode == CaptureMode.GIF) {
            GifRecordingPresets.byId(presetId).fps
        } else {
            settingsRepository.fps.first().toInt()
        }

    private suspend fun effectiveBitrateBits(
        mode: String,
        presetId: String,
    ): Int =
        if (mode == CaptureMode.GIF) {
            GifRecordingPresets.byId(presetId).bitrateBitsPerSec
        } else {
            (settingsRepository.bitrate.first() * 1_000_000).toInt()
        }

    override fun startRecording(
        context: Context,
        config: SessionConfig,
    ) {
        scope.launch {
            val intent = buildFullRecordingStartIntent(context.applicationContext, config)
            withContext(Dispatchers.Main) {
                context.applicationContext.startForegroundService(intent)
            }
        }
    }

    override fun startBufferSession(
        context: Context,
        config: SessionConfig,
    ) {
        scope.launch {
            val intent = buildBufferStartIntent(context.applicationContext, config)
            withContext(Dispatchers.Main) {
                context.applicationContext.startForegroundService(intent)
            }
        }
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

    private suspend fun buildFullRecordingStartIntent(
        ctx: Context,
        config: SessionConfig,
    ): Intent {
        val mode = settingsRepository.captureMode.first()
        val presetId = settingsRepository.gifRecorderPresetId.first()
        val gif = mode == CaptureMode.GIF
        val gifPreset = if (gif) GifRecordingPresets.byId(presetId) else null
        return Intent(ctx, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_SESSION_CONFIG, config)
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, config.mediaProjectionResultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, config.mediaProjectionGrantIntent)
            putExtra(ScreenRecordService.EXTRA_FPS, effectiveFps(mode, presetId))
            putExtra(ScreenRecordService.EXTRA_BITRATE, effectiveBitrateBits(mode, presetId))
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENABLED, settingsRepository.recordAudio.first())
            putExtra(ScreenRecordService.EXTRA_INTERNAL_AUDIO_ENABLED, settingsRepository.internalAudio.first())
            putExtra(ScreenRecordService.EXTRA_AUDIO_BITRATE, settingsRepository.audioBitrate.first() * 1000)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SAMPLE_RATE, settingsRepository.audioSampleRate.first())
            putExtra(ScreenRecordService.EXTRA_AUDIO_CHANNELS, settingsRepository.audioChannels.first())
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENCODER, settingsRepository.audioEncoder.first())
            putExtra(ScreenRecordService.EXTRA_SEPARATE_MIC_RECORDING, settingsRepository.separateMicRecording.first())
            putExtra(ScreenRecordService.EXTRA_SHOW_CAMERA, settingsRepository.cameraOverlay.first())
            putExtra(ScreenRecordService.EXTRA_CAMERA_SIZE, settingsRepository.cameraOverlaySize.first())
            putExtra(ScreenRecordService.EXTRA_CAMERA_X_FRACTION, settingsRepository.cameraXFraction.first())
            putExtra(ScreenRecordService.EXTRA_CAMERA_Y_FRACTION, settingsRepository.cameraYFraction.first())
            putExtra(ScreenRecordService.EXTRA_CAMERA_LOCK_POSITION, settingsRepository.cameraLockPosition.first())
            putExtra(ScreenRecordService.EXTRA_CAMERA_FACING, settingsRepository.cameraFacing.first())
            putExtra(ScreenRecordService.EXTRA_CAMERA_ASPECT_RATIO, settingsRepository.cameraAspectRatio.first())
            putExtra(ScreenRecordService.EXTRA_CAMERA_OPACITY, settingsRepository.cameraOpacity.first())
            putExtra(ScreenRecordService.EXTRA_SHOW_WATERMARK, settingsRepository.showWatermark.first())
            putStringArrayListExtra(
                ScreenRecordService.EXTRA_STOP_BEHAVIOR,
                ArrayList(StopBehaviorKeys.migrateSet(settingsRepository.stopBehavior.first()).toList()),
            )
            putExtra(ScreenRecordService.EXTRA_SAVE_LOCATION, settingsRepository.saveLocationUri.first())
            putExtra(ScreenRecordService.EXTRA_VIDEO_ENCODER, settingsRepository.videoEncoder.first())
            putExtra(ScreenRecordService.EXTRA_SHOW_FLOATING_CONTROLS, settingsRepository.floatingControls.first())
            putExtra(
                ScreenRecordService.EXTRA_HIDE_FLOATING_ICON_WHILE_RECORDING,
                settingsRepository.hideFloatingIconWhileRecording.first(),
            )
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, effectiveResolution(mode, presetId))
            putExtra(ScreenRecordService.EXTRA_FILENAME_PATTERN, settingsRepository.filenamePattern.first())
            putExtra(ScreenRecordService.EXTRA_COUNTDOWN, settingsRepository.countdown.first())
            putExtra(ScreenRecordService.EXTRA_KEEP_SCREEN_ON, settingsRepository.keepScreenOn.first())
            putExtra(ScreenRecordService.EXTRA_RECORDING_ORIENTATION, settingsRepository.recordingOrientation.first())
            putExtra(ScreenRecordService.EXTRA_WATERMARK_LOCATION, settingsRepository.watermarkLocation.first())
            putExtra(ScreenRecordService.EXTRA_WATERMARK_IMAGE_URI, settingsRepository.watermarkImageUri.first())
            putExtra(ScreenRecordService.EXTRA_WATERMARK_SHAPE, settingsRepository.watermarkShape.first())
            putExtra(ScreenRecordService.EXTRA_WATERMARK_OPACITY, settingsRepository.watermarkOpacity.first())
            putExtra(ScreenRecordService.EXTRA_WATERMARK_SIZE, settingsRepository.watermarkSize.first())
            putExtra(ScreenRecordService.EXTRA_WATERMARK_X_FRACTION, settingsRepository.watermarkXFraction.first())
            putExtra(ScreenRecordService.EXTRA_WATERMARK_Y_FRACTION, settingsRepository.watermarkYFraction.first())
            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_FORMAT, settingsRepository.screenshotFormat.first())
            putExtra(ScreenRecordService.EXTRA_SCREENSHOT_QUALITY, settingsRepository.screenshotQuality.first())
            putExtra(ScreenRecordService.EXTRA_GIF_SESSION, gif)
            if (gif && gifPreset != null) {
                putExtra(ScreenRecordService.EXTRA_GIF_MAX_DURATION_SEC, gifPreset.maxDurationSec)
                putExtra(ScreenRecordService.EXTRA_GIF_SCALE_WIDTH, gifPreset.maxWidth)
                putExtra(ScreenRecordService.EXTRA_GIF_OUTPUT_FPS, gifPreset.fps)
                putExtra(ScreenRecordService.EXTRA_GIF_MAX_COLORS, gifPreset.maxColors)
                putExtra(ScreenRecordService.EXTRA_GIF_DITHER_KIND, gifPreset.paletteDither.name)
            }
        }
    }

    private suspend fun buildBufferStartIntent(
        ctx: Context,
        config: SessionConfig,
    ): Intent =
        Intent(ctx, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START_BUFFER
            putExtra(ScreenRecordService.EXTRA_SESSION_CONFIG, config)
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, config.mediaProjectionResultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, config.mediaProjectionGrantIntent)
            putExtra(ScreenRecordService.EXTRA_FPS, settingsRepository.fps.first().toInt())
            putExtra(ScreenRecordService.EXTRA_BITRATE, (settingsRepository.bitrate.first() * 1_000_000).toInt())
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENABLED, settingsRepository.recordAudio.first())
            putExtra(ScreenRecordService.EXTRA_INTERNAL_AUDIO_ENABLED, settingsRepository.internalAudio.first())
            putExtra(ScreenRecordService.EXTRA_AUDIO_BITRATE, settingsRepository.audioBitrate.first() * 1000)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SAMPLE_RATE, settingsRepository.audioSampleRate.first())
            putExtra(ScreenRecordService.EXTRA_AUDIO_CHANNELS, settingsRepository.audioChannels.first())
            putExtra(ScreenRecordService.EXTRA_AUDIO_ENCODER, settingsRepository.audioEncoder.first())
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, settingsRepository.resolution.first())
            putExtra(ScreenRecordService.EXTRA_VIDEO_ENCODER, settingsRepository.videoEncoder.first())
            putExtra(ScreenRecordService.EXTRA_CLIPPER_DURATION_MINUTES, settingsRepository.clipperDurationMinutes.first())
            putExtra(ScreenRecordService.EXTRA_COUNTDOWN, settingsRepository.countdown.first())
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
