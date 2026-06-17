package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ibbie.catrec_screenrecorcer.BuildConfig

/**
 * Shared [AudioPlaybackCaptureConfiguration] for full recording ([ScreenRecorderEngine]) and rolling
 * buffer capture ([RollingBufferEngine]) so both paths cannot drift apart.
 *
 * **Eligible usages (non‑system playback capture)** — Android’s playback‑capture guide states that,
 * for another app’s audio to be capturable by a non‑system recorder, the *player’s* usage must be
 * [AudioAttributes.USAGE_MEDIA], [AudioAttributes.USAGE_GAME], or [AudioAttributes.USAGE_UNKNOWN],
 * alongside an allow‑capture policy. Other usages (e.g. voice communication) are not treated as
 * capturable mixed media in that model; they are deliberately **not** added here so we do not widen
 * capture into sensitive categories that the platform isolates by usage.
 *
 * Adding extra [AudioAttributes] usages therefore does **not** documentedly unlock more game audio
 * if the publisher sets an ineligible usage; it can only narrow or duplicate what we already match
 * relative to compliant players ([Builder.addMatchingUsage] OR‑matches streams).
 *
 * References: Android “Playback capture” guide — capturing app constraints and publisher usage.
 */
object PlaybackCaptureConfig {
    /** Stable log / analytics representation of usages passed to [Builder.addMatchingUsage]. */
    const val MATCHED_USAGES_LOG: String = "USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN"

    private val matchingUsages: IntArray =
        intArrayOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        )

    @RequiresApi(Build.VERSION_CODES.Q)
    fun build(mediaProjection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
        for (i in matchingUsages.indices) {
            builder.addMatchingUsage(matchingUsages[i])
        }
        return builder.build()
    }

    /**
     * Rebuilds an internal-playback [AudioRecord] using the **same** [PlaybackCaptureConfig.build]
     * configuration as the original session. Used by the engines after sustained silence to give
     * the AudioFlinger playback-capture mix a chance to re-attach to the foreground app's audio
     * session (helps with race conditions on some games / OEMs where the first AudioRecord opens
     * before the game's audio path is fully wired up to the playback-capture mix).
     *
     * Throws [IllegalStateException] if the new instance is not [AudioRecord.STATE_INITIALIZED].
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun rebuildInternalPlaybackAudioRecord(
        mediaProjection: MediaProjection,
        sampleRate: Int,
        channelMask: Int,
        bufferBytes: Int,
    ): AudioRecord {
        val playbackConfig = build(mediaProjection)
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
        val record =
            AudioRecord
                .Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release()
            } catch (_: Exception) {
            }
            throw IllegalStateException(
                "rebuildInternalPlaybackAudioRecord state=${record.state} (expected INITIALIZED=${AudioRecord.STATE_INITIALIZED})",
            )
        }
        return record
    }

    /** Whether any PCM16 byte in [buffer]\[0,[byteLen]) is non‑zero (cheap scan; does not log samples). */
    fun pcmCaptureBufferContainsNonZeroBytes(
        buffer: ByteArray,
        byteLen: Int,
    ): Boolean {
        val n = minOf(byteLen, buffer.size)
        for (i in 0 until n) {
            if (buffer[i] != 0.toByte()) return true
        }
        return false
    }

    fun describeChannelMaskForLog(mask: Int): String =
        when (mask) {
            AudioFormat.CHANNEL_IN_MONO -> "CHANNEL_IN_MONO"
            AudioFormat.CHANNEL_IN_STEREO -> "CHANNEL_IN_STEREO"
            else -> "mask=0x${mask.toUInt().toString(16)}"
        }

    /** One line after [AudioRecord.startRecording] succeeds for internal (playback capture) AudioRecord. */
    fun logInternalPlaybackRecordStarted(
        logTag: String,
        sessionDiagMarker: String,
        record: AudioRecord,
        configuredBufferBytes: Int,
        configuredChannelMask: Int,
        sampleRateFromBuilder: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        val buf =
            runCatching {
                if (Build.VERSION.SDK_INT >= 24 && record.bufferSizeInFrames > 0) {
                    record.bufferSizeInFrames * record.channelCount * 2 // PCM16
                } else {
                    configuredBufferBytes
                }
            }.getOrDefault(configuredBufferBytes)
        Log.i(
            logTag,
            "$sessionDiagMarker INTERNAL_PLAYBACK_RECORD_READY usages=[$MATCHED_USAGES_LOG] " +
                "sampleRate=${record.sampleRate} requestedSampleRate=$sampleRateFromBuilder " +
                "channelMask=${describeChannelMaskForLog(configuredChannelMask)}($configuredChannelMask) " +
                "channelCount=${record.channelCount} bufferSizeBytes=$buf " +
                "audioRecordState=${record.state} recordingState=${record.recordingState}",
        )
    }
}
