package com.ibbie.catrec_screenrecorcer.utils

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ibbie.catrec_screenrecorcer.BuildConfig
import com.ibbie.catrec_screenrecorcer.service.InternalAudioHealthTracker
import com.ibbie.catrec_screenrecorcer.service.PlaybackCaptureConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight Crashlytics session metadata + **targeted non-fatals** for suspicious audio failures.
 *
 * Writes custom keys only; non-fatals fire **once per condition per recording session**, never from tight loops.
 *
 * Privacy: sanitized exception messages (truncated), no PCM, filenames, URIs, or user text beyond mode flags.
 */
object AudioRecordingCrashlyticsReporter {
    enum class RecordingKind { FULL, BUFFER }

    private val lock = Any()

    /** Negative read thresholds before we consider reads "repeated" for Crashlytics. */
    private const val READ_ERR_NON_FATAL_THRESHOLD = 50L

    @Volatile private var sessionActive = false

    /** User intent audio mode passed at session begin (NONE / MIC / INTERNAL / MIXED). */
    @Volatile private var beginRequestedAudioModeName: String = "NONE"

    @Volatile private var beginUserRequestedAnyAudio = false

    @Volatile private var beginSeparateMicRequested = false

    @Volatile private var beginRecordingKind = RecordingKind.FULL

    @Volatile private var lastAudioWarningReason: String = ""

    private val nfCreationMicReported = AtomicBoolean(false)

    private val nfCreationInternalReported = AtomicBoolean(false)

    /** Partial MIXED downgrade (start failed one leg then continued). */
    private val nfPartialStartRecoverableReported = AtomicBoolean(false)

    private val nfInternalPlaybackSilenceReported = AtomicBoolean(false)

    private val nfInternalPersistentSilenceReported = AtomicBoolean(false)

    private val nfInternalRecoveredAfterRebuildReported = AtomicBoolean(false)

    private val nfInternalLateRecoveryReported = AtomicBoolean(false)

    private val nfInternalRecordStartFailedReported = AtomicBoolean(false)

    private val nfResolvedNoneWhileRequestedReported = AtomicBoolean(false)

    private val nfZeroMuxAudioReported = AtomicBoolean(false)

    private val nfZeroSeparateMicReported = AtomicBoolean(false)

    private val nfRepeatedReadsReported = AtomicBoolean(false)

    private val nfSuspiciousStartupExceptionReported = AtomicBoolean(false)

    fun beginSession(
        recordingKind: RecordingKind,
        recordAudioPermissionGranted: Boolean,
        requestedAudioModeName: String,
        userSeparateMicRecording: Boolean,
        sampleRate: Int,
        userChannelCount: Int,
        audioBitrate: Int,
        audioEncoderProfile: String,
        userMicEnabled: Boolean,
        userInternalCaptureEnabled: Boolean,
        recordingEngineModeName: String = "unknown",
    ) {
        synchronized(lock) {
            nfCreationMicReported.set(false)
            nfCreationInternalReported.set(false)
            nfPartialStartRecoverableReported.set(false)
            nfInternalPlaybackSilenceReported.set(false)
            nfInternalPersistentSilenceReported.set(false)
            nfInternalRecoveredAfterRebuildReported.set(false)
            nfInternalLateRecoveryReported.set(false)
            nfInternalRecordStartFailedReported.set(false)
            nfResolvedNoneWhileRequestedReported.set(false)
            nfZeroMuxAudioReported.set(false)
            nfZeroSeparateMicReported.set(false)
            nfRepeatedReadsReported.set(false)
            nfSuspiciousStartupExceptionReported.set(false)

            sessionActive = true
            beginRecordingKind = recordingKind
            beginRequestedAudioModeName = requestedAudioModeName
            beginUserRequestedAnyAudio = requestedAudioModeName != "NONE"
            beginSeparateMicRequested = userSeparateMicRecording
            lastAudioWarningReason = ""

            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey(Keys.APP_VER, BuildConfig.VERSION_NAME)
            crash.setCustomKey(Keys.APP_CODE, BuildConfig.VERSION_CODE.toString())
            crash.setCustomKey(Keys.SDK_INT, Build.VERSION.SDK_INT.toString())
            crash.setCustomKey(Keys.REL, Build.VERSION.RELEASE ?: "?")
            crash.setCustomKey(Keys.MFG, sanitize(Build.MANUFACTURER))
            crash.setCustomKey(Keys.MODEL, sanitize(Build.MODEL))
            crash.setCustomKey(
                Keys.REC_TYPE,
                if (recordingKind == RecordingKind.FULL) {
                    "full"
                } else {
                    "buffer"
                },
            )
            crash.setCustomKey(Keys.PER_REC_AUD, recordAudioPermissionGranted.toString())
            crash.setCustomKey(Keys.SET_MIC, userMicEnabled.toString())
            crash.setCustomKey(Keys.SET_INT, userInternalCaptureEnabled.toString())
            crash.setCustomKey(Keys.SET_SEP_MIC, userSeparateMicRecording.toString())
            crash.setCustomKey(Keys.AUD_SR, sampleRate.toString())
            crash.setCustomKey(Keys.AUD_CH, userChannelCount.toString())
            crash.setCustomKey(Keys.AUD_BR, audioBitrate.toString())
            crash.setCustomKey(Keys.AUD_ENC, sanitize(audioEncoderProfile))
            crash.setCustomKey(Keys.REQ_AUD_MOD, requestedAudioModeName)
            crash.setCustomKey(Keys.ENG_MODE, sanitize(recordingEngineModeName))
            crash.setCustomKey(Keys.PLAYBACK_USAGES, PlaybackCaptureConfig.MATCHED_USAGES_LOG)

            neutralizeRuntimeAudioKeys(crash)

            crash.log(
                "[CatRecArc] session_begin typ=${if (recordingKind == RecordingKind.FULL) "full" else "buffer"} req=$requestedAudioModeName engine=$recordingEngineModeName",
            )
        }
    }

    fun notifySessionEnded() {
        synchronized(lock) {
            sessionActive = false
        }
        FirebaseCrashlytics.getInstance().log("[CatRecArc] session_ended")
    }

    /** After both sources are validated (or degraded) — main recorder. */
    fun onFullCaptureReady(
        capturedModeName: String,
        mainMuxModeName: String,
        micRecordPresent: Boolean,
        internalRecordPresent: Boolean,
        effectiveChannelCount: Int,
        aacConfiguredChannels: Int,
    ) {
        if (!sessionActive || beginRecordingKind != RecordingKind.FULL) return
        val crash = FirebaseCrashlytics.getInstance()
        crash.setCustomKey(Keys.CAPT_MOD, capturedModeName)
        crash.setCustomKey(Keys.MUX_MAIN, mainMuxModeName)
        crash.setCustomKey(Keys.EFF_CH, effectiveChannelCount.toString())
        crash.setCustomKey(Keys.AAC_CH, aacConfiguredChannels.toString())
        crash.setCustomKey(Keys.MIC_INIT, micRecordPresent.toString())
        crash.setCustomKey(Keys.INT_INIT, internalRecordPresent.toString())
        crash.setCustomKey(Keys.MIC_ST_OK, if (needsMicRecording(capturedModeName, mainMuxModeName)) micRecordPresent.toString() else "n/a")
        crash.setCustomKey(
            Keys.INT_ST_OK,
            if (needsInternalRecording(capturedModeName, mainMuxModeName)) internalRecordPresent.toString() else "n/a",
        )
        crash.log(
            "[CatRecArc] full_capture_ready cap=$capturedModeName mux=$mainMuxModeName mic=$micRecordPresent int=$internalRecordPresent",
        )
    }

    fun onBufferCaptureReady(
        capturedModeName: String,
        micRecordPresent: Boolean,
        internalRecordPresent: Boolean,
        effectiveChannelCount: Int,
        aacConfiguredChannels: Int,
    ) {
        if (!sessionActive || beginRecordingKind != RecordingKind.BUFFER) return
        val crash = FirebaseCrashlytics.getInstance()
        crash.setCustomKey(Keys.CAPT_MOD, capturedModeName)
        crash.setCustomKey(Keys.MUX_MAIN, capturedModeName)
        crash.setCustomKey(Keys.EFF_CH, effectiveChannelCount.toString())
        crash.setCustomKey(Keys.AAC_CH, aacConfiguredChannels.toString())
        crash.setCustomKey(Keys.MIC_INIT, micRecordPresent.toString())
        crash.setCustomKey(Keys.INT_INIT, internalRecordPresent.toString())
        crash.setCustomKey(Keys.MIC_ST_OK, if (capturedModeName.contains("MIC")) micRecordPresent.toString() else "n/a")
        crash.setCustomKey(
            Keys.INT_ST_OK,
            if (capturedModeName == "INTERNAL" || capturedModeName == "MIXED") internalRecordPresent.toString() else "n/a",
        )
        crash.log("[CatRecArc] buffer_capture_ready cap=$capturedModeName mic=$micRecordPresent int=$internalRecordPresent")
    }

    fun noteCreationFailureMic(
        throwable: Throwable?,
        extraContext: String? = null,
    ) {
        noteCreationInner("mic", throwable, nfCreationMicReported, extraContext)
    }

    fun noteCreationFailureInternal(
        throwable: Throwable?,
        extraContext: String? = null,
    ) {
        noteCreationInner("internal", throwable, nfCreationInternalReported, extraContext)
    }

    private fun noteCreationInner(
        which: String,
        throwable: Throwable?,
        oncePerLeg: AtomicBoolean,
        extraContext: String?,
    ) {
        if (!sessionActive) return
        setLastWarning("$which creation_failed")
        FirebaseCrashlytics.getInstance().setCustomKey(
            "arc_aud_fail_recent",
            sanitize("$which:" + throwableToSafeExcerpt(throwable, extraContext)),
        )
        if (!oncePerLeg.compareAndSet(false, true)) return
        val crash = FirebaseCrashlytics.getInstance()
        crash.setCustomKey("arc_mic_init", if (which == "mic") "FAILED" else "see_arc_mic_ini")
        crash.setCustomKey("arc_int_init", if (which == "internal") "FAILED" else "see_arc_int_ini")
        reportNonFatal(
            IllegalStateException("AudioRecord creation failed ($which): " + throwableToSafeExcerpt(throwable, extraContext)),
            "[CatRecArc] nf creation $which",
        )
    }

    fun noteMixedDowngradeRecoverable(failedLeg: String) {
        if (!sessionActive) return
        setLastWarning("mixed_start_$failedLeg _downgraded")
        if (!nfPartialStartRecoverableReported.compareAndSet(false, true)) return
        FirebaseCrashlytics.getInstance().setCustomKey(Keys.START_ISSUE, sanitize("partial_$failedLeg"))
        reportNonFatal(
            IllegalStateException("AudioRecord.partial_start_recoverable_$failedLeg"),
            "[CatRecArc] nf partial_start downgrade leg=$failedLeg",
        )
    }

    /** Internal playback capture produced sustained silence timeout (recording may continue). */
    fun notifyInternalPlaybackSilenceTimeout(kind: RecordingKind) {
        if (!sessionActive || beginRecordingKind != kind) return
        setLastWarning("internal_playback_silence_timeout")
        FirebaseCrashlytics.getInstance().setCustomKey(Keys.INT_SILENT, true.toString())
        if (BuildConfig.DEBUG && nfInternalPlaybackSilenceReported.compareAndSet(false, true)) {
            FirebaseCrashlytics.getInstance().log("[CatRecArc] debug internal_silence")
        }
    }

    fun logInternalAudioStage(
        kind: RecordingKind,
        stageName: String,
        health: InternalAudioHealthTracker.Snapshot,
    ) {
        if (!sessionActive || beginRecordingKind != kind) return
        postCrashlytics {
            if (!sessionActive || beginRecordingKind != kind) return@postCrashlytics
            applyInternalHealthKeys(FirebaseCrashlytics.getInstance(), health)
            FirebaseCrashlytics.getInstance().log("[CatRecArc] internal_audio_stage $stageName ${health.compactSummary()}")
        }
    }

    fun reportInternalAudioPersistentSilence(
        kind: RecordingKind,
        health: InternalAudioHealthTracker.Snapshot,
    ) {
        if (!sessionActive || beginRecordingKind != kind) return
        if (!nfInternalPersistentSilenceReported.compareAndSet(false, true)) return
        rememberLastWarning("internal_audio_persistent_silence")
        postCrashlytics {
            if (!sessionActive || beginRecordingKind != kind) return@postCrashlytics
            val crash = FirebaseCrashlytics.getInstance()
            applyInternalHealthKeys(crash, health)
            crash.setCustomKey(Keys.LAST_AUD_WRN, lastAudioWarningReason)
            crash.setCustomKey(Keys.INT_SILENT, true.toString())
            reportNonFatal(
                InternalAudioPersistentSilence("InternalAudioPersistentSilence ${health.compactSummary()}"),
                "[CatRecArc] nf_internal_audio_persistent_silence",
            )
        }
    }

    fun reportInternalAudioRecoveredAfterRebuild(
        kind: RecordingKind,
        health: InternalAudioHealthTracker.Snapshot,
    ) {
        if (!sessionActive || beginRecordingKind != kind) return
        if (!nfInternalRecoveredAfterRebuildReported.compareAndSet(false, true)) return
        rememberLastWarning("internal_audio_recovered_after_rebuild")
        postCrashlytics {
            if (!sessionActive || beginRecordingKind != kind) return@postCrashlytics
            val crash = FirebaseCrashlytics.getInstance()
            applyInternalHealthKeys(crash, health)
            crash.setCustomKey(Keys.LAST_AUD_WRN, lastAudioWarningReason)
            crash.setCustomKey(Keys.INT_RECOVERED, true.toString())
            crash.log("[CatRecArc] internal_audio_recovered_after_rebuild ${health.compactSummary()}")
        }
    }

    fun reportInternalAudioLateRecovery(
        kind: RecordingKind,
        health: InternalAudioHealthTracker.Snapshot,
    ) {
        if (!sessionActive || beginRecordingKind != kind) return
        if (!nfInternalLateRecoveryReported.compareAndSet(false, true)) return
        rememberLastWarning("internal_audio_late_recovery")
        postCrashlytics {
            if (!sessionActive || beginRecordingKind != kind) return@postCrashlytics
            val crash = FirebaseCrashlytics.getInstance()
            applyInternalHealthKeys(crash, health)
            crash.setCustomKey(Keys.LAST_AUD_WRN, lastAudioWarningReason)
            crash.setCustomKey(Keys.INT_RECOVERED, true.toString())
            crash.log("[CatRecArc] internal_audio_late_recovery ${health.compactSummary()}")
        }
    }

    fun reportInternalAudioRecordStartFailed(
        kind: RecordingKind,
        health: InternalAudioHealthTracker.Snapshot,
    ) {
        if (!sessionActive || beginRecordingKind != kind) return
        if (!nfInternalRecordStartFailedReported.compareAndSet(false, true)) return
        rememberLastWarning("internal_audio_record_start_failed")
        postCrashlytics {
            if (!sessionActive || beginRecordingKind != kind) return@postCrashlytics
            val crash = FirebaseCrashlytics.getInstance()
            applyInternalHealthKeys(crash, health)
            crash.setCustomKey(Keys.LAST_AUD_WRN, lastAudioWarningReason)
            reportNonFatal(
                InternalAudioRecordStartFailed("InternalAudioRecordStartFailed ${health.compactSummary()}"),
                "[CatRecArc] nf_internal_audio_record_start_failed",
            )
        }
    }

    /**
     * Start threw during engine setup — record one audio-scoped non-fatal when cues match.
     * @return **true** if handled as audio-scoped Crashlytics (caller may omit duplicate [FirebaseCrashlytics.recordException]).
     */
    fun tryReportSuspiciousAudioStartupThrowable(t: Throwable): Boolean {
        if (!sessionActive) return false
        val msg = (t.message ?: "") + "\n" + t.cause?.message.orEmpty()
        val audioCue =
            msg.contains("[CatRecAudioSession]") ||
                msg.contains("AudioRecord", ignoreCase = true) ||
                (t is SecurityException && msg.contains("RECORD_AUDIO", ignoreCase = true))
        if (!audioCue) return false
        if (!nfSuspiciousStartupExceptionReported.compareAndSet(false, true)) return true
        setLastWarning("startup_throw: " + t.javaClass.simpleName)
        FirebaseCrashlytics.getInstance().setCustomKey(Keys.START_ISSUE, "throw_" + sanitize(t.javaClass.simpleName))
        reportNonFatal(
            IllegalStateException("Recording audio startup abort: ${sanitizeThrowableMessage(msg)}"),
            "[CatRecArc] nf suspicious_startup_throw",
        )
        return true
    }

    fun finalizeFullSession(
        finalCaptureModeName: String,
        mainMuxModeName: String,
        muxRequiredMainSamples: Boolean,
        mainMuxSamplesWritten: Boolean,
        separateMicTrackActive: Boolean,
        separateMicSamplesWritten: Boolean,
        pcmDropMain: Long,
        pcmDropSeparate: Long,
        readNegCount: Long,
        internalSilenceObservedThisSession: Boolean,
        micFallbackUsed: Boolean = false,
        internalRecoveredAfterInitialSilence: Boolean = false,
        audioInputEosQueued: Boolean = false,
        audioOutputEosObserved: Boolean = false,
        separateMicInputEosQueued: Boolean = false,
        separateMicOutputEosObserved: Boolean = false,
        internalPlaybackPcmReadsPositive: Long? = null,
        internalPlaybackPcmNonZeroBuffers: Long? = null,
        internalPlaybackPcmSilentBuffers: Long? = null,
        internalPlaybackPcmEverNonZero: Boolean? = null,
        internalAudioHealth: InternalAudioHealthTracker.Snapshot? = null,
    ) {
        if (!sessionActive || beginRecordingKind != RecordingKind.FULL) return
        synchronized(lock) {
            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey(Keys.CAPT_FIN, finalCaptureModeName)
            crash.setCustomKey(Keys.MUX_FIN, mainMuxModeName)
            crash.setCustomKey(Keys.MAIN_SAMPLES, mainMuxSamplesWritten.toString())
            crash.setCustomKey(Keys.SEP_SAMPLES, separateMicSamplesWritten.toString())
            crash.setCustomKey(Keys.PCM_DROP_M, pcmDropMain.toString())
            crash.setCustomKey(Keys.PCM_DROP_S, pcmDropSeparate.toString())
            crash.setCustomKey(Keys.READ_ERR, readNegCount.toString())
            crash.setCustomKey(Keys.INT_SILENT, internalSilenceObservedThisSession.toString())
            crash.setCustomKey(Keys.MIC_FALLBACK_USED, micFallbackUsed.toString())
            crash.setCustomKey(Keys.INT_RECOVERED, internalRecoveredAfterInitialSilence.toString())
            crash.setCustomKey(Keys.AUD_IN_EOS, audioInputEosQueued.toString())
            crash.setCustomKey(Keys.AUD_OUT_EOS, audioOutputEosObserved.toString())
            crash.setCustomKey(Keys.SEP_IN_EOS, separateMicInputEosQueued.toString())
            crash.setCustomKey(Keys.SEP_OUT_EOS, separateMicOutputEosObserved.toString())
            crash.setCustomKey(Keys.LAST_AUD_WRN, lastAudioWarningReason)
            internalAudioHealth?.let { applyInternalHealthKeys(crash, it) }

            if (beginUserRequestedAnyAudio && finalCaptureModeName == "NONE") {
                if (nfResolvedNoneWhileRequestedReported.compareAndSet(false, true)) {
                    setLastWarning("resolved_none_while_requested")
                    reportNonFatal(
                        IllegalStateException("Requested audio mode was $beginRequestedAudioModeName but capture ended NONE"),
                        "[CatRecArc] nf requested_audio_none_final",
                    )
                }
            }

            val needMainWritten = muxRequiredMainSamples && beginUserRequestedAnyAudio
            val needSeparateWritten =
                beginSeparateMicRequested &&
                    beginUserRequestedAnyAudio &&
                    separateMicTrackActive &&
                    finalCaptureModeName != "NONE"
            val suppressZeroMainMuxAsStartupCleanup =
                shouldSuppressZeroMainMuxAudioAsStartupCleanup(
                    needMainWritten = needMainWritten,
                    mainMuxSamplesWritten = mainMuxSamplesWritten,
                    audioInputEosQueued = audioInputEosQueued,
                    audioOutputEosObserved = audioOutputEosObserved,
                    internalAudioHealth = internalAudioHealth,
                )

            if (suppressZeroMainMuxAsStartupCleanup) {
                rememberLastWarning("zero_main_mux_startup_cleanup_suppressed")
                crash.log(
                    "[CatRecArc] suppress_zero_main_mux_audio startup_cleanup " +
                        internalAudioHealth?.compactSummary().orEmpty(),
                )
            } else if (needMainWritten && !mainMuxSamplesWritten) {
                if (nfZeroMuxAudioReported.compareAndSet(false, true)) {
                    setLastWarning("zero_main_mux_samples")
                    reportNonFatal(
                        IllegalStateException("Main mux audio track had no writes"),
                        "[CatRecArc] nf_zero_main_mux_audio",
                    )
                }
            }
            if (needSeparateWritten && !separateMicSamplesWritten) {
                if (nfZeroSeparateMicReported.compareAndSet(false, true)) {
                    setLastWarning("zero_separate_mic_samples")
                    reportNonFatal(
                        IllegalStateException("Separate mic track had no writes"),
                        "[CatRecArc] nf_zero_separate_mic",
                    )
                }
            }

            if (readNegCount >= READ_ERR_NON_FATAL_THRESHOLD) {
                if (nfRepeatedReadsReported.compareAndSet(false, true)) {
                    setLastWarning("repeated_read_errors n=$readNegCount")
                    reportNonFatal(
                        IllegalStateException("Repeated AudioRecord read errors n=$readNegCount"),
                        "[CatRecArc] nf_repeated_aud_read",
                    )
                }
            }

            val intPcmFmt =
                if (BuildConfig.DEBUG &&
                    internalPlaybackPcmReadsPositive != null &&
                    internalPlaybackPcmNonZeroBuffers != null &&
                    internalPlaybackPcmSilentBuffers != null &&
                    internalPlaybackPcmEverNonZero != null
                ) {
                    " intPCM_r=$internalPlaybackPcmReadsPositive nzBuf=$internalPlaybackPcmNonZeroBuffers silentBuf=$internalPlaybackPcmSilentBuffers everNz=$internalPlaybackPcmEverNonZero"
                } else {
                    ""
                }
            FirebaseCrashlytics.getInstance().log(
                "[CatRecArc] full_finalize capture=$finalCaptureModeName mux=$mainMuxModeName mainW=$mainMuxSamplesWritten sepW=$separateMicSamplesWritten fallback=$micFallbackUsed silence=$internalSilenceObservedThisSession recovered=$internalRecoveredAfterInitialSilence eosIn=$audioInputEosQueued eosOut=$audioOutputEosObserved readErr=$readNegCount$intPcmFmt",
            )
        }
    }

    internal fun shouldSuppressZeroMainMuxAudioAsStartupCleanup(
        needMainWritten: Boolean,
        mainMuxSamplesWritten: Boolean,
        audioInputEosQueued: Boolean,
        audioOutputEosObserved: Boolean,
        internalAudioHealth: InternalAudioHealthTracker.Snapshot?,
    ): Boolean {
        val h = internalAudioHealth ?: return false
        return needMainWritten &&
            !mainMuxSamplesWritten &&
            h.internalRequested &&
            h.internalAudioRecordCreated &&
            !h.internalAudioRecordStarted &&
            h.totalBytesRead == 0L &&
            h.positiveReadCount == 0L &&
            h.zeroReadCount == 0L &&
            h.negativeReadCount == 0L &&
            !audioInputEosQueued &&
            !audioOutputEosObserved
    }

    fun finalizeBufferSession(
        finalCaptureModeName: String,
        pcmSamplesQueued: Long,
        pcmDropCount: Long,
        readNegCount: Long,
        internalSilenceObservedThisSession: Boolean,
        internalRecoveredAfterInitialSilence: Boolean = false,
        internalPlaybackPcmReadsPositive: Long? = null,
        internalPlaybackPcmNonZeroBuffers: Long? = null,
        internalPlaybackPcmSilentBuffers: Long? = null,
        internalPlaybackPcmEverNonZero: Boolean? = null,
        internalAudioHealth: InternalAudioHealthTracker.Snapshot? = null,
    ) {
        if (!sessionActive || beginRecordingKind != RecordingKind.BUFFER) return
        synchronized(lock) {
            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey(Keys.CAPT_FIN, finalCaptureModeName)
            crash.setCustomKey(Keys.MUX_FIN, finalCaptureModeName)
            crash.setCustomKey(Keys.RBUF_PCM_Q, pcmSamplesQueued.toString())
            crash.setCustomKey(Keys.PCM_DROP_M, pcmDropCount.toString())
            crash.setCustomKey(Keys.READ_ERR, readNegCount.toString())
            crash.setCustomKey(Keys.INT_SILENT, internalSilenceObservedThisSession.toString())
            crash.setCustomKey(Keys.INT_RECOVERED, internalRecoveredAfterInitialSilence.toString())
            crash.setCustomKey(Keys.LAST_AUD_WRN, lastAudioWarningReason)
            internalAudioHealth?.let { applyInternalHealthKeys(crash, it) }

            if (beginUserRequestedAnyAudio && finalCaptureModeName == "NONE") {
                if (nfResolvedNoneWhileRequestedReported.compareAndSet(false, true)) {
                    setLastWarning("resolved_none_while_requested")
                    reportNonFatal(
                        IllegalStateException("Requested audio but rolling buffer capture ended NONE"),
                        "[CatRecArc] nf_requested_audio_none_buffer",
                    )
                }
            }

            val needWrites = beginUserRequestedAnyAudio && finalCaptureModeName != "NONE"
            if (needWrites && pcmSamplesQueued <= 0L) {
                if (nfZeroMuxAudioReported.compareAndSet(false, true)) {
                    setLastWarning("zero_buffer_pcm_feed")
                    reportNonFatal(
                        IllegalStateException("Rolling buffer PCM feed count was zero"),
                        "[CatRecArc] nf_zero_buffer_pcm",
                    )
                }
            }

            if (readNegCount >= READ_ERR_NON_FATAL_THRESHOLD) {
                if (nfRepeatedReadsReported.compareAndSet(false, true)) {
                    setLastWarning("buffer_repeated_read_errors n=$readNegCount")
                    reportNonFatal(
                        IllegalStateException("Rolling buffer AudioRecord read errors n=$readNegCount"),
                        "[CatRecArc] nf_buf_repeated_read",
                    )
                }
            }

            val intBufPcmFmt =
                if (BuildConfig.DEBUG &&
                    internalPlaybackPcmReadsPositive != null &&
                    internalPlaybackPcmNonZeroBuffers != null &&
                    internalPlaybackPcmSilentBuffers != null &&
                    internalPlaybackPcmEverNonZero != null
                ) {
                    " intPCM_r=$internalPlaybackPcmReadsPositive nzBuf=$internalPlaybackPcmNonZeroBuffers silentBuf=$internalPlaybackPcmSilentBuffers everNz=$internalPlaybackPcmEverNonZero"
                } else {
                    ""
                }
            FirebaseCrashlytics.getInstance().log(
                "[CatRecArc] buffer_finalize cap=$finalCaptureModeName pcmQ=$pcmSamplesQueued pcmDrop=$pcmDropCount readErr=$readNegCount silence=$internalSilenceObservedThisSession recovered=$internalRecoveredAfterInitialSilence$intBufPcmFmt",
            )
        }
    }

    /** Mic source expected for chosen configuration. */
    private fun needsMicRecording(
        capturedModeName: String,
        mainMuxModeName: String,
    ): Boolean {
        val cap = capturedModeName.contains("MIC")
        val mux = mainMuxModeName.contains("MIC")
        val sep = beginSeparateMicRequested
        return cap && (mux || sep)
    }

    private fun needsInternalRecording(
        capturedModeName: String,
        mainMuxModeName: String,
    ): Boolean {
        val capMix = capturedModeName == "MIXED"
        val capInt = capturedModeName == "INTERNAL"
        val muxInt = mainMuxModeName.contains("INTERNAL")
        val muxMix = mainMuxModeName == "MIXED"
        return (capMix && (muxMix || muxInt)) || (capInt && muxInt)
    }

    private fun neutralizeRuntimeAudioKeys(crash: FirebaseCrashlytics) {
        crash.setCustomKey(Keys.CAPT_MOD, "pending")
        crash.setCustomKey(Keys.CAPT_FIN, "pending")
        crash.setCustomKey(Keys.MUX_MAIN, "pending")
        crash.setCustomKey(Keys.MUX_FIN, "pending")
        crash.setCustomKey(Keys.EFF_CH, "pending")
        crash.setCustomKey(Keys.AAC_CH, "pending")
        crash.setCustomKey(Keys.MIC_INIT, "pending")
        crash.setCustomKey(Keys.INT_INIT, "pending")
        crash.setCustomKey(Keys.MIC_ST_OK, "pending")
        crash.setCustomKey(Keys.INT_ST_OK, "pending")
        crash.setCustomKey(Keys.MAIN_SAMPLES, "pending")
        crash.setCustomKey(Keys.SEP_SAMPLES, "pending")
        crash.setCustomKey(Keys.PCM_DROP_M, "pending")
        crash.setCustomKey(Keys.PCM_DROP_S, "pending")
        crash.setCustomKey(Keys.READ_ERR, "pending")
        crash.setCustomKey(Keys.INT_SILENT, false.toString())
        crash.setCustomKey(Keys.MIC_FALLBACK_USED, false.toString())
        crash.setCustomKey(Keys.INT_RECOVERED, false.toString())
        crash.setCustomKey(Keys.AUD_IN_EOS, "pending")
        crash.setCustomKey(Keys.AUD_OUT_EOS, "pending")
        crash.setCustomKey(Keys.SEP_IN_EOS, "pending")
        crash.setCustomKey(Keys.SEP_OUT_EOS, "pending")
        crash.setCustomKey(Keys.LAST_AUD_WRN, "")
        crash.setCustomKey(Keys.START_ISSUE, "")
        crash.setCustomKey(Keys.RBUF_PCM_Q, "na")
        crash.setCustomKey(Keys.IAH_AR_STATE, "pending")
        crash.setCustomKey(Keys.IAH_REC_STATE, "pending")
        crash.setCustomKey(Keys.IAH_BUF, "pending")
        crash.setCustomKey(Keys.IAH_FIRST_POS, "pending")
        crash.setCustomKey(Keys.IAH_FIRST_NZ, "pending")
        crash.setCustomKey(Keys.IAH_FIRST_AUD, "pending")
        crash.setCustomKey(Keys.IAH_BYTES, "pending")
        crash.setCustomKey(Keys.IAH_READS, "pending")
        crash.setCustomKey(Keys.IAH_PEAK, "pending")
        crash.setCustomKey(Keys.IAH_RMS, "pending")
        crash.setCustomKey(Keys.IAH_STAGES, "pending")
        crash.setCustomKey(Keys.IAH_REBUILD, "pending")
        crash.setCustomKey(Keys.IAH_FLAGS, "pending")
        crash.setCustomKey(Keys.IAH_MUX, "pending")
    }

    private fun applyInternalHealthKeys(
        crash: FirebaseCrashlytics,
        h: InternalAudioHealthTracker.Snapshot,
    ) {
        crash.setCustomKey(Keys.ENG_MODE, sanitize(h.engineModeName))
        crash.setCustomKey(Keys.REC_TYPE, sanitize(h.recordingType))
        crash.setCustomKey(Keys.REQ_AUD_MOD, sanitize(h.requestedAudioModeName))
        crash.setCustomKey(Keys.SET_MIC, h.micRequested.toString())
        crash.setCustomKey(Keys.SET_INT, h.internalRequested.toString())
        crash.setCustomKey(Keys.SET_SEP_MIC, h.separateMicRequested.toString())
        crash.setCustomKey(Keys.PLAYBACK_USAGES, PlaybackCaptureConfig.MATCHED_USAGES_LOG)
        crash.setCustomKey(Keys.IAH_AR_STATE, h.audioRecordStateAfterCreate.toString())
        crash.setCustomKey(Keys.IAH_REC_STATE, h.recordingStateAfterStart.toString())
        crash.setCustomKey(Keys.IAH_BUF, "sr=${h.sampleRate},ch=${h.channelCount},enc=${h.encoding},b=${h.bufferSizeBytes}")
        crash.setCustomKey(Keys.IAH_FIRST_POS, h.firstPositiveReadElapsedMs.toString())
        crash.setCustomKey(Keys.IAH_FIRST_NZ, h.firstNonZeroPcmElapsedMs.toString())
        crash.setCustomKey(Keys.IAH_FIRST_AUD, h.firstAudiblePcmElapsedMs.toString())
        crash.setCustomKey(Keys.IAH_BYTES, h.totalBytesRead.toString())
        crash.setCustomKey(Keys.IAH_READS, "${h.positiveReadCount}/${h.zeroReadCount}/${h.negativeReadCount}")
        crash.setCustomKey(Keys.IAH_PEAK, h.peakAbs.toString())
        crash.setCustomKey(Keys.IAH_RMS, h.rmsEstimate.toString())
        crash.setCustomKey(Keys.IAH_STAGES, "${h.stage1SilentAt4s}/${h.stage2SilentAt10s}/${h.persistentSilentAt20s}")
        crash.setCustomKey(Keys.IAH_REBUILD, "${h.rebuildAttemptCount}/${h.rebuildSuccessCount}")
        crash.setCustomKey(
            Keys.IAH_FLAGS,
            "created=${h.internalAudioRecordCreated},started=${h.internalAudioRecordStarted}," +
                "recRebuild=${h.recoveredAfterRebuild},late=${h.recoveredLate},fb=${h.fallbackTriggered}," +
                "had=${h.finalHadInternalAudioSamples}",
        )
        crash.setCustomKey(Keys.IAH_MUX, "${h.muxerStarted}/${h.muxerAudioSamplesWritten}")
    }

    private fun setLastWarning(reason: String) {
        lastAudioWarningReason = sanitize(reason)
        FirebaseCrashlytics.getInstance().setCustomKey(Keys.LAST_AUD_WRN, lastAudioWarningReason)
    }

    private fun rememberLastWarning(reason: String) {
        lastAudioWarningReason = sanitize(reason)
    }

    private fun throwableToSafeExcerpt(
        t: Throwable?,
        extraContext: String?,
    ): String {
        val sb = StringBuilder()
        extraContext?.let { sb.append(sanitize(it)).append(' ') }
        if (t == null) return sb.toString().ifEmpty { "no_exception_detail" }
        sb.append(t.javaClass.simpleName).append(':').append(sanitizeThrowableMessage(t.message))
        val c = t.cause
        if (c != null) {
            sb
                .append("|cause=")
                .append(c.javaClass.simpleName)
                .append(':')
                .append(sanitizeThrowableMessage(c.message))
        }
        return sb.toString().take(220)
    }

    private fun sanitizeThrowableMessage(msg: String?): String =
        sanitize(
            msg
                ?.replace("\n", " ")
                ?.take(240) ?: "",
        )

    private fun sanitize(raw: String): String =
        raw
            .replace(Regex("(content://\\S+|file://\\S+|/storage/\\S+|/data/\\S+|\\(\\s*/.*\\)|\\(\\s*[A-Z]:\\\\[^)]*\\))"), "[path]")
            .take(200)

    private fun reportNonFatal(
        e: Exception,
        logLine: String,
    ) {
        recordCrashlyticsNonFatal(e, logLine)
    }

    private fun postCrashlytics(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private class InternalAudioPersistentSilence(
        message: String,
    ) : IllegalStateException(message)

    private class InternalAudioRecordStartFailed(
        message: String,
    ) : IllegalStateException(message)

    private object Keys {
        const val APP_VER = "arc_app_ver"
        const val APP_CODE = "arc_app_code"
        const val SDK_INT = "arc_sdk_int"
        const val REL = "arc_rel"
        const val MFG = "arc_mfg"
        const val MODEL = "arc_model"
        const val REC_TYPE = "arc_rec_typ"
        const val PER_REC_AUD = "arc_perm_mic"
        const val SET_MIC = "arc_set_mic"
        const val SET_INT = "arc_set_int"
        const val SET_SEP_MIC = "arc_set_sep"
        const val AUD_SR = "arc_aud_sr"
        const val AUD_CH = "arc_aud_ch"
        const val AUD_BR = "arc_aud_br"
        const val AUD_ENC = "arc_aud_enc"
        const val REQ_AUD_MOD = "arc_req_aud"
        const val ENG_MODE = "arc_eng"
        const val PLAYBACK_USAGES = "arc_pc_usg"
        const val CAPT_MOD = "arc_cap_mod"
        const val CAPT_FIN = "arc_cap_fin"
        const val MUX_MAIN = "arc_mux_main"
        const val MUX_FIN = "arc_mux_fin"
        const val EFF_CH = "arc_eff_ch"
        const val AAC_CH = "arc_aac_ch"
        const val MIC_INIT = "arc_mic_ini"
        const val INT_INIT = "arc_int_ini"
        const val MIC_ST_OK = "arc_mic_st"
        const val INT_ST_OK = "arc_int_st"
        const val MAIN_SAMPLES = "arc_main_smp"
        const val SEP_SAMPLES = "arc_sep_smp"
        const val PCM_DROP_M = "arc_pcm_drm"
        const val PCM_DROP_S = "arc_pcm_drs"
        const val READ_ERR = "arc_read_er"
        const val INT_SILENT = "arc_int_sil"
        const val MIC_FALLBACK_USED = "arc_mic_fb"
        const val INT_RECOVERED = "arc_int_recov"
        const val AUD_IN_EOS = "arc_aud_in_eos"
        const val AUD_OUT_EOS = "arc_aud_out_eos"
        const val SEP_IN_EOS = "arc_sep_in_eos"
        const val SEP_OUT_EOS = "arc_sep_out_eos"
        const val LAST_AUD_WRN = "arc_last_wnd"
        const val START_ISSUE = "arc_st_issue"
        const val RBUF_PCM_Q = "arc_buf_pcm"
        const val IAH_AR_STATE = "iah_ar"
        const val IAH_REC_STATE = "iah_rec"
        const val IAH_BUF = "iah_buf"
        const val IAH_FIRST_POS = "iah_fpos"
        const val IAH_FIRST_NZ = "iah_fnz"
        const val IAH_FIRST_AUD = "iah_faud"
        const val IAH_BYTES = "iah_bytes"
        const val IAH_READS = "iah_reads"
        const val IAH_PEAK = "iah_peak"
        const val IAH_RMS = "iah_rms"
        const val IAH_STAGES = "iah_stage"
        const val IAH_REBUILD = "iah_reb"
        const val IAH_FLAGS = "iah_flags"
        const val IAH_MUX = "iah_mux"
    }
}
