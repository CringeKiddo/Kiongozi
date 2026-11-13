package com.kiongozi.app.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Sample-accurate game clock built on AudioTrack.
 *
 * The clock is the number of frames the audio hardware has REALLY played (AudioTrack.getTimestamp:
 * a (frame position, System.nanoTime) pair), extrapolated with the monotonic clock between polls and
 * gently corrected against drift. All gameplay (note positions, hit judging) is a pure function of
 * [songTimeAtMonoNs], never of frame count, so FPS can never affect timing.
 *
 * Timeline: the stream starts with LEAD_IN_MS of silence, so song time starts negative and notes can scroll in.
 *   songTime = trackTime - LEAD_IN_MS - audioOffsetMs
 * audioOffsetMs: positive = you hear the audio later than the clock says (Bluetooth etc.), notes arrive later.
 */
class AudioSyncEngine(private val pcm: PcmInfo) {
    companion object {
        const val LEAD_IN_MS = 2500
        const val MIN_OFFSET_MS = -200
        const val MAX_OFFSET_MS = 200
        private const val POLL_INTERVAL_NS = 30_000_000L
    }

    enum class State { IDLE, PLAYING, PAUSED }

    private class Anchor(val monoNs: Long, val trackMs: Double)

    @Volatile
    var audioOffsetMs: Int = 0
        set(v) {
            field = v.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        }

    @Volatile
    var state: State = State.IDLE
        private set

    /** True once the whole file (plus tail silence) has been handed to the audio device. */
    @Volatile
    var writerDone: Boolean = false
        private set

    val durationMs: Long get() = pcm.durationMs

    private var track: AudioTrack? = null
    private var writer: Thread? = null

    @Volatile private var stopWriter = false
    @Volatile private var anchor: Anchor? = null
    @Volatile private var anchored = false
    @Volatile private var playStartNs = 0L
    @Volatile private var minTsNs = 0L
    @Volatile private var pausedSongMs = 0.0
    @Volatile private var pausedTrackMs = 0.0
    private var lastPollNs = 0L
    private var lastTsOkNs = 0L
    private val ts = AudioTimestamp()

    // ----------------------------------------------------------------------------------------
    fun start() {
        release()
        stopWriter = false
        writerDone = false
        val rate = pcm.sampleRate
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, rate * 4 * 150 / 1000)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        anchor = null
        anchored = false
        lastPollNs = 0L
        lastTsOkNs = 0L

        writer = Thread({ writeLoop(t) }, "audio-writer").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        val now = System.nanoTime()
        playStartNs = now
        minTsNs = now
        state = State.PLAYING
        t.play()
    }

    fun pause() {
        if (state != State.PLAYING) return
        val now = System.nanoTime()
        pausedSongMs = songTimeAtMonoNs(now)
        pausedTrackMs = trackTimeAt(now)
        state = State.PAUSED
        try {
            track?.pause()
        } catch (_: Exception) {
        }
    }

    fun resume() {
        if (state != State.PAUSED) return
        val now = System.nanoTime()
        anchor = Anchor(now, pausedTrackMs)
        anchored = false
        minTsNs = now              // ignore stale timestamps captured before the pause
        lastTsOkNs = now
        playStartNs = now
        state = State.PLAYING
        try {
            track?.play()
        } catch (_: Exception) {
        }
    }

    /** Restart the song from the beginning (clean teardown + new track). */
    fun restart() {
        start()
    }

    fun release() {
        stopWriter = true
        val t = track
        track = null
        try {
            t?.pause()
            t?.flush()
        } catch (_: Exception) {
        }
        writer?.interrupt()
        try {
            writer?.join(300)
        } catch (_: Exception) {
        }
        writer = null
        try {
            t?.stop()
        } catch (_: Exception) {
        }
        try {
            t?.release()
        } catch (_: Exception) {
        }
        state = State.IDLE
    }

    // ----------------------------------------------------------------------------------------
    // Clock
    // ----------------------------------------------------------------------------------------

    /** Refresh the clock anchor from the audio hardware. Cheap; call once per frame. */
    fun poll(nowNs: Long = System.nanoTime()) {
        if (state != State.PLAYING) return
        if (nowNs - lastPollNs < POLL_INTERVAL_NS) return
        lastPollNs = nowNs
        val t = track ?: return
        val rate = pcm.sampleRate

        var gotTs = false
        try {
            if (t.getTimestamp(ts) && ts.nanoTime >= minTsNs) {
                gotTs = true
                lastTsOkNs = nowNs
                applyMeasurement(ts.nanoTime, ts.framePosition * 1000.0 / rate, 25.0, 0.2)
            }
        } catch (_: Exception) {
        }
        if (!gotTs && nowNs - max(playStartNs, lastTsOkNs) > 600_000_000L) {
            // Fallback for devices without usable timestamps: head position (coarser, so smooth harder)
            try {
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                applyMeasurement(nowNs, head * 1000.0 / rate, 60.0, 0.1)
            } catch (_: Exception) {
            }
        }
    }

    private fun applyMeasurement(monoNs: Long, trackMs: Double, snapMs: Double, blend: Double) {
        val cur = anchor
        if (cur == null || !anchored) {
            anchor = Anchor(monoNs, trackMs)
            anchored = true
            return
        }
        val predicted = cur.trackMs + (monoNs - cur.monoNs) / 1e6
        val err = trackMs - predicted
        anchor = if (abs(err) > snapMs) Anchor(monoNs, trackMs) else Anchor(monoNs, predicted + err * blend)
    }

    private fun trackTimeAt(monoNs: Long): Double {
        val a = anchor
        return if (a != null) a.trackMs + (monoNs - a.monoNs) / 1e6
        else (monoNs - playStartNs) / 1e6 - 40.0 // before the first timestamp: rough estimate
    }

    /** Song time (ms) at an arbitrary System.nanoTime() instant. Negative during the lead-in. */
    fun songTimeAtMonoNs(monoNs: Long): Double {
        if (state == State.PAUSED) return pausedSongMs
        return trackTimeAt(monoNs) - LEAD_IN_MS - audioOffsetMs
    }

    /** Song time at a MotionEvent.eventTime (SystemClock.uptimeMillis timebase == System.nanoTime base). */
    fun songTimeAtUptimeMs(eventTimeMs: Long): Double = songTimeAtMonoNs(eventTimeMs * 1_000_000L)

    fun songTimeMs(): Double = songTimeAtMonoNs(System.nanoTime())

    // ----------------------------------------------------------------------------------------
    // Feeding the AudioTrack
    // ----------------------------------------------------------------------------------------
    private fun writeLoop(t: AudioTrack) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
        }
        val rate = pcm.sampleRate
        try {
            val silence = ByteArray(4096 * 4)
            var silentFrames = rate.toLong() * LEAD_IN_MS / 1000
            while (silentFrames > 0 && !stopWriter) {
                val frames = minOf(4096L, silentFrames).toInt()
                if (t.write(silence, 0, frames * 4) < 0) return
                silentFrames -= frames
            }
            FileInputStream(pcm.file).use { input ->
                val buf = ByteArray(16 * 1024)
                while (!stopWriter) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && !stopWriter) {
                        val w = t.write(buf, off, n - off)
                        if (w < 0) return
                        off += w
                    }
                }
            }
            // tail silence so the last samples are fully played out
            var tail = rate.toLong() / 2
            while (tail > 0 && !stopWriter) {
                val frames = minOf(4096L, tail).toInt()
                if (t.write(silence, 0, frames * 4) < 0) return
                tail -= frames
            }
            if (!stopWriter) writerDone = true
        } catch (_: Exception) {
            // track released while writing
        }
    }
}
