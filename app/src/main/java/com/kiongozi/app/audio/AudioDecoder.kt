package com.kiongozi.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DecodeResult(val totalSamples: Long, val durationMs: Long)

/**
 * Decodes any Android-supported audio file (MP3, WAV, FLAC, M4A/AAC, OGG...) to mono 22,050 Hz
 * float PCM, STREAMING: PCM is handed to [onSamples] in small blocks and never accumulated here,
 * so RAM stays flat no matter how long the song is.
 */
class AudioDecoder(private val context: Context) {
    companion object {
        const val TARGET_RATE = 22050
        const val MAX_DURATION_MIN = 15
        private const val MAX_DURATION_US = MAX_DURATION_MIN * 60L * 1_000_000L
        private const val STALL_MS = 20_000L
    }

    /**
     * @param onProgress 0f..1f (or stays 0 if the container doesn't report a duration)
     * @param onSamples  called on a background thread with (buffer, validCount); the buffer is reused,
     *                   so consume it before returning.
     */
    suspend fun decode(
        uri: Uri,
        onProgress: (Float) -> Unit,
        onSamples: (FloatArray, Int) -> Unit,
    ): DecodeResult = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw DecodeException("Couldn't open this file", e)
            }

            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIdx = i
                    fmt = f
                    break
                }
            }
            if (trackIdx < 0 || fmt == null) throw DecodeException("No audio track found in this file")
            extractor.selectTrack(trackIdx)

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs > MAX_DURATION_US) {
                throw DecodeException("Songs longer than $MAX_DURATION_MIN minutes aren't supported yet")
            }

            var srcRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT

            var totalOut = 0L
            var resampler: StreamingResampler? = null
            var resamplerRate = 0
            var mono = FloatArray(8192)
            var lastProgressAt = 0L

            val countingSink: (FloatArray, Int) -> Unit = { b, n ->
                totalOut += n
                onSamples(b, n)
            }

            fun handlePcm(bb: ByteBuffer, offset: Int, size: Int) {
                if (size <= 0) return
                val ch = channels.coerceAtLeast(1)
                val bytesPer = when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                    AudioFormat.ENCODING_PCM_8BIT -> 1
                    else -> 2
                }
                val frames = size / (bytesPer * ch)
                if (frames <= 0) return
                if (mono.size < frames) mono = FloatArray(frames)
                val dup = bb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                dup.limit(offset + size)
                dup.position(offset)
                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        val fb = dup.asFloatBuffer()
                        for (f in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until ch) acc += fb.get()
                            mono[f] = acc / ch
                        }
                    }
                    AudioFormat.ENCODING_PCM_8BIT -> {
                        for (f in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until ch) acc += ((dup.get().toInt() and 0xFF) - 128) / 128f
                            mono[f] = acc / ch
                        }
                    }
                    else -> {
                        val sb = dup.asShortBuffer()
                        for (f in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until ch) acc += sb.get().toFloat()
                            mono[f] = acc / (ch * 32768f)
                        }
                    }
                }
                if (resampler == null || resamplerRate != srcRate) {
                    resampler?.finish()
                    resampler = StreamingResampler(srcRate, TARGET_RATE, countingSink)
                    resamplerRate = srcRate
                }
                resampler!!.push(mono, frames)
            }

            fun reportProgress(sampleTimeUs: Long) {
                val now = SystemClock.elapsedRealtime()
                if (durationUs > 0 && now - lastProgressAt > 100) {
                    lastProgressAt = now
                    onProgress((sampleTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
            }

            if (mime == "audio/raw") {
                // WAV / raw PCM: no codec needed
                val bb = ByteBuffer.allocateDirect(64 * 1024)
                while (true) {
                    ensureActive()
                    bb.clear()
                    val n = extractor.readSampleData(bb, 0)
                    if (n < 0) break
                    reportProgress(extractor.sampleTime)
                    handlePcm(bb, 0, n)
                    extractor.advance()
                }
            } else {
                val c = try {
                    MediaCodec.createDecoderByType(mime).also { codec = it }
                } catch (e: Exception) {
                    throw DecodeException("This phone can't decode $mime", e)
                }
                c.configure(fmt, null, null, 0)
                c.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastActivity = SystemClock.elapsedRealtime()

                while (!outputDone) {
                    ensureActive()
                    var progressed = false

                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = c.getInputBuffer(inIdx)!!
                            val sz = extractor.readSampleData(inBuf, 0)
                            if (sz < 0) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val t = extractor.sampleTime
                                reportProgress(t)
                                c.queueInputBuffer(inIdx, 0, sz, t, 0)
                                extractor.advance()
                            }
                            progressed = true
                        }
                    }

                    val outIdx = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val outBuf = c.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) handlePcm(outBuf, info.offset, info.size)
                            c.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                            progressed = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = c.outputFormat
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            progressed = true
                        }
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (progressed) lastActivity = now
                    else if (now - lastActivity > STALL_MS) throw DecodeException("Decoder stalled")
                }
            }

            resampler?.finish()
            onProgress(1f)
            DecodeResult(totalOut, totalOut * 1000L / TARGET_RATE)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
