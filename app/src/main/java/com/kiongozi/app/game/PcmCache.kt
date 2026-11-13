package com.kiongozi.app.game

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
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Decoded playback audio on disk: 16-bit little-endian stereo PCM at [sampleRate]. */
data class PcmInfo(val file: File, val sampleRate: Int, val frames: Long) {
    val durationMs: Long get() = frames * 1000L / sampleRate
}

/**
 * Playback needs full-quality audio (the analysis path is mono 22 kHz). The song is decoded ONCE to a
 * stereo PCM file in cacheDir/pcm and streamed from disk by AudioTrack, so RAM stays flat for any length.
 * Only the 3 most recent songs are kept.
 */
object PcmCache {
    private const val KEEP = 3

    suspend fun ensure(context: Context, uri: Uri, key: String, onProgress: (Float) -> Unit): PcmInfo =
        withContext(Dispatchers.Default) {
            val dir = File(context.cacheDir, "pcm").apply { mkdirs() }
            val existing = dir.listFiles { f -> f.name.startsWith("$key-") && f.name.endsWith(".pcm") }?.firstOrNull()
            if (existing != null) {
                val rate = existing.name.removePrefix("$key-").removeSuffix(".pcm").toIntOrNull()
                if (rate != null && existing.length() > 0) {
                    existing.setLastModified(System.currentTimeMillis())
                    return@withContext PcmInfo(existing, rate, existing.length() / 4)
                }
            }
            val tmp = File(dir, "$key.tmp")
            val rate = try {
                BufferedOutputStream(tmp.outputStream(), 64 * 1024).use { out ->
                    decodeToStereo16(context, uri, out, onProgress)
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            val finalFile = File(dir, "$key-$rate.pcm")
            if (finalFile.exists()) finalFile.delete()
            tmp.renameTo(finalFile)
            prune(dir, finalFile)
            PcmInfo(finalFile, rate, finalFile.length() / 4)
        }

    private fun prune(dir: File, keepFile: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
        files.filter { it != keepFile }.drop(KEEP - 1).forEach { it.delete() }
    }

    /** Returns the output sample rate. */
    private suspend fun decodeToStereo16(
        context: Context, uri: Uri, out: OutputStream, onProgress: (Float) -> Unit,
    ): Int = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw PlaybackDecodeException("Couldn't open the audio file (permission lost? pick the song again)", e)
            }
            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIdx = i
                    fmt = f
                    break
                }
            }
            if (trackIdx < 0 || fmt == null) throw PlaybackDecodeException("No audio track found")
            extractor.selectTrack(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L

            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT

            var outBytes = ByteArray(16384)
            var lastProgress = 0L

            fun reportProgress(us: Long) {
                val now = SystemClock.elapsedRealtime()
                if (durationUs > 0 && now - lastProgress > 100) {
                    lastProgress = now
                    onProgress((us.toFloat() / durationUs).coerceIn(0f, 1f))
                }
            }

            fun handlePcm(bb: ByteBuffer, offset: Int, size: Int) {
                val ch = channels.coerceAtLeast(1)
                val bytesPer = when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                    AudioFormat.ENCODING_PCM_8BIT -> 1
                    else -> 2
                }
                val frames = size / (bytesPer * ch)
                if (frames <= 0) return
                if (outBytes.size < frames * 4) outBytes = ByteArray(frames * 4)
                val dup = bb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                dup.limit(offset + size)
                dup.position(offset)
                val fb = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) dup.asFloatBuffer() else null
                val sb = if (encoding != AudioFormat.ENCODING_PCM_FLOAT && encoding != AudioFormat.ENCODING_PCM_8BIT) dup.asShortBuffer() else null
                var o = 0
                for (f in 0 until frames) {
                    var l = 0f
                    var r = 0f
                    for (c in 0 until ch) {
                        val v = when {
                            fb != null -> fb.get()
                            sb != null -> sb.get() / 32768f
                            else -> ((dup.get().toInt() and 0xFF) - 128) / 128f
                        }
                        if (c == 0) l = v
                        if (c == 1) r = v
                    }
                    if (ch == 1) r = l
                    val li = (l.coerceIn(-1f, 1f) * 32767f).toInt()
                    val ri = (r.coerceIn(-1f, 1f) * 32767f).toInt()
                    outBytes[o++] = li.toByte(); outBytes[o++] = (li shr 8).toByte()
                    outBytes[o++] = ri.toByte(); outBytes[o++] = (ri shr 8).toByte()
                }
                out.write(outBytes, 0, o)
            }

            if (mime == "audio/raw") {
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
                    throw PlaybackDecodeException("This phone can't decode $mime", e)
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
                            val ob = c.getOutputBuffer(outIdx)
                            if (ob != null && info.size > 0) handlePcm(ob, info.offset, info.size)
                            c.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                            progressed = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = c.outputFormat
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            progressed = true
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (progressed) lastActivity = now
                    else if (now - lastActivity > 20_000) throw PlaybackDecodeException("Decoder stalled")
                }
            }
            onProgress(1f)
            rate
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
