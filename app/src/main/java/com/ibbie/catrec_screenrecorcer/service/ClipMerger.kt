package com.ibbie.catrec_screenrecorcer.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * MPEG-4 fast-merge: stream-copies raw encoded samples from multiple MP4 segments into a single
 * output file without re-encoding. This is the equivalent of `ffmpeg -c copy`.
 *
 * Assumptions:
 *  • All segments were produced by the same encoder session → identical codec parameters.
 *  • Each segment file starts at presentation time 0 (RollingBufferEngine normalises PTS).
 *  • Segments are ordered oldest → newest.
 *
 * The merger:
 *  1. Reads the first segment to discover track types and add them to the muxer.
 *  2. Accumulates a PTS offset equal to the last-seen sample PTS in each segment
 *     so the output timeline is continuous.
 *  3. Writes all remaining segments with that offset applied.
 */
object ClipMerger {
    private const val TAG = "ClipMerger"
    private const val BUFFER_SIZE = 1 * 1024 * 1024 // 1 MB read buffer

    /**
     * [MediaExtractor.getSampleFlags] uses [MediaExtractor] constants; [MediaMuxer.writeSampleData]
     * expects [MediaCodec.BufferInfo] flags. Map the overlapping semantics only.
     */
    internal fun sampleFlagsForMuxer(sampleFlags: Int): Int {
        var out = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            out =
                out or
                if (Build.VERSION.SDK_INT >= 34) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    @Suppress("DEPRECATION")
                    MediaCodec.BUFFER_FLAG_SYNC_FRAME
                }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0
        ) {
            out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return out
    }

    /**
     * Merges [inputFiles] into [outputFile].
     * @return true if at least one sample was written, false otherwise.
     */
    fun merge(
        inputFiles: List<File>,
        outputFile: File,
    ): Boolean {
        val validFiles = inputFiles.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) {
            Log.w(TAG, "No valid segment files to merge.")
            return false
        }

        outputFile.parentFile?.mkdirs()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var samplesWritten = 0

        // muxer track index per MIME type (e.g. "video/avc" → 0, "audio/mp4a-latm" → 1)
        val muxerTracks = mutableMapOf<String, Int>()

        var globalPtsOffsetUs = 0L // running PTS offset across segments

        val readBuffer = ByteBuffer.allocate(BUFFER_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()

        for ((segIdx, file) in validFiles.withIndex()) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)

                // ── Build a track-index map: extractor track → muxer track ──────────
                // extractor track index → mime
                val mimeByExtTrack = mutableMapOf<Int, String>()
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue

                    if (segIdx == 0) {
                        // First segment: register tracks with the muxer
                        if (!muxerTracks.containsKey(mime)) {
                            val muxerTrack = muxer.addTrack(fmt)
                            muxerTracks[mime] = muxerTrack
                            Log.d(TAG, "Added muxer track $muxerTrack for $mime")
                        }
                    }
                    // Only process tracks we actually added (in case a later segment has extras)
                    if (muxerTracks.containsKey(mime)) {
                        mimeByExtTrack[i] = mime
                        extractor.selectTrack(i)
                    }
                }

                if (segIdx == 0 && muxerTracks.isNotEmpty()) {
                    muxer.start()
                    muxerStarted = true
                }

                if (!muxerStarted) {
                    Log.w(TAG, "Muxer not started – skipping segment $segIdx")
                    continue
                }

                // ── Copy samples ──────────────────────────────────────────────────
                var maxPtsUs = 0L

                while (true) {
                    bufferInfo.size = extractor.readSampleData(readBuffer, 0)
                    if (bufferInfo.size < 0) break

                    val extTrack = extractor.sampleTrackIndex
                    val mime = mimeByExtTrack[extTrack]
                    val muxerTrack = if (mime != null) muxerTracks[mime] else null

                    if (mime == null || muxerTrack == null) {
                        extractor.advance()
                        continue
                    }

                    val rawPts = extractor.sampleTime
                    bufferInfo.presentationTimeUs = rawPts + globalPtsOffsetUs
                    bufferInfo.offset = 0
                    bufferInfo.flags = sampleFlagsForMuxer(extractor.sampleFlags)

                    readBuffer.position(0)
                    readBuffer.limit(bufferInfo.size)
                    muxer.writeSampleData(muxerTrack, readBuffer, bufferInfo)
                    samplesWritten++

                    if (rawPts > maxPtsUs) maxPtsUs = rawPts

                    extractor.advance()
                }

                // Advance the global offset by the duration of this segment.
                // Add one video-frame worth of padding to avoid PTS collisions.
                globalPtsOffsetUs += maxPtsUs + 33_333L // ≈ 1 frame @30 fps
                Log.d(TAG, "Segment $segIdx merged, maxPts=$maxPtsUs µs, nextOffset=$globalPtsOffsetUs µs")
            } catch (e: Exception) {
                Log.e(TAG, "Error merging segment ${file.name}: ${e.message}", e)
            } finally {
                extractor.release()
            }
        }

        return try {
            if (muxerStarted) muxer.stop()
            muxer.release()
            Log.d(TAG, "Merge complete: $samplesWritten samples → ${outputFile.name}")
            samplesWritten > 0
        } catch (e: Exception) {
            Log.e(TAG, "Muxer finalisation error: ${e.message}", e)
            false
        }
    }
}
