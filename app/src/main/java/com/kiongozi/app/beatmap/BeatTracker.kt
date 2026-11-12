package com.kiongozi.app.beatmap

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import com.kiongozi.app.audio.FrameStore
import com.kiongozi.app.engine.Backend
import com.kiongozi.app.engine.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class NoBeatsException(message: String) : Exception(message)

data class BeatResult(
    val beatsMs: List<Long>,
    val downbeatsMs: List<Long>,
    val bpm: Double,
    val beatsPerBar: Int,
)

/**
 * Runs beat_this.onnx over a log-mel spectrogram in 30 s chunks (1500 frames) with a 6-frame
 * border (same scheme as beat_this: keep the middle of each chunk), then peak-picks beats and
 * downbeats and estimates BPM.
 *
 * Default backend is CPU: on the test phone NNAPI gave identical timing and output, i.e. it
 * silently fell back to CPU, and CPU has no crash risk.
 */
class BeatTracker(
    private val context: Context,
    private val backend: Backend = Backend.CPU,
    private val threads: Int = 4,
) : AutoCloseable {

    companion object {
        const val CHUNK = 1500
        const val BORDER = 6
        const val FRAME_MS = 20.0 // 50 fps
        private const val PEAK_RADIUS = 3
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun open() {
        val model = ModelStore.ensureModel(context)
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            if (backend == Backend.NNAPI) addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
        }
        env = e
        session = e.createSession(model.absolutePath, opts)
    }

    override fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
    }

    /** @param onProgress 0f..1f over chunks */
    suspend fun track(frames: FrameStore, onProgress: (Float) -> Unit): BeatResult =
        withContext(Dispatchers.Default) {
            val s = session ?: error("BeatTracker.open() not called")
            val e = env!!
            val t = frames.size
            if (t < 150) throw NoBeatsException("This audio is too short to find a beat")
            val nMels = frames.nMels

            // chunk starts (frame index of chunk[0]); mirrors beat_this split_piece(avoid_short_end)
            val starts = ArrayList<Int>()
            var st = -BORDER
            while (st < t - BORDER) {
                starts.add(st)
                st += CHUNK - 2 * BORDER
            }
            if (starts.size > 1) starts[starts.size - 1] = t - (CHUNK - BORDER)

            val beatLogits = FloatArray(t)
            val downLogits = FloatArray(t)
            val chunkArr = FloatArray(CHUNK * nMels)
            val direct = ByteBuffer.allocateDirect(CHUNK * nMels * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val inName = s.inputNames.first()
            val outBeat = FloatArray(CHUNK)
            val outDown = FloatArray(CHUNK)

            for ((ci, start) in starts.withIndex()) {
                ensureActive()
                onProgress(ci.toFloat() / starts.size)
                frames.copyFrames(start, CHUNK, chunkArr)
                direct.clear()
                direct.put(chunkArr)
                direct.rewind()
                OnnxTensor.createTensor(e, direct, longArrayOf(1, CHUNK.toLong(), nMels.toLong())).use { tensor ->
                    s.run(mapOf(inName to tensor)).use { result ->
                        val b = (result[0] as OnnxTensor).floatBuffer
                        val d = (result[1] as OnnxTensor).floatBuffer
                        b.get(outBeat, 0, min(CHUNK, b.remaining()))
                        d.get(outDown, 0, min(CHUNK, d.remaining()))
                    }
                }
                val lo = max(start + BORDER, 0)
                val hi = min(start + CHUNK - BORDER, t)
                for (f in lo until hi) {
                    beatLogits[f] = outBeat[f - start]
                    downLogits[f] = outDown[f - start]
                }
            }
            onProgress(1f)
            postProcess(beatLogits, downLogits)
        }

    private fun postProcess(beatLogits: FloatArray, downLogits: FloatArray): BeatResult {
        val beatFrames = pickPeaks(beatLogits)
        if (beatFrames.size < 4) throw NoBeatsException("Couldn't find a steady beat in this audio")
        val downFrames = pickPeaks(downLogits)

        // snap each downbeat to its nearest beat; drop duplicates
        val downSnapped = sortedSetOf<Int>()
        for (d in downFrames) {
            var best = 0
            var bestDist = Double.MAX_VALUE
            for (i in beatFrames.indices) {
                val dist = abs(beatFrames[i] - d)
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            downSnapped.add(best)
        }

        val beatsMs = beatFrames.map { (it * FRAME_MS).roundToLong() }
        val downMs = downSnapped.map { beatsMs[it] }

        // BPM: robust mean of beat intervals
        val intervals = (1 until beatsMs.size).map { (beatsMs[it] - beatsMs[it - 1]).toDouble() }.sorted()
        val median = intervals[intervals.size / 2]
        val good = intervals.filter { it in median * 0.7..median * 1.4 }
        val mean = if (good.isNotEmpty()) good.average() else median
        val bpm = 60000.0 / mean

        // beats per bar: median beat-count between consecutive downbeats
        var beatsPerBar = 4
        val idx = downSnapped.toList()
        if (idx.size >= 2) {
            val counts = (1 until idx.size).map { idx[it] - idx[it - 1] }.sorted()
            beatsPerBar = counts[counts.size / 2].coerceIn(2, 8)
        }
        return BeatResult(beatsMs, downMs, bpm, beatsPerBar)
    }

    /** beat_this "minimal" post-processing: logit > 0, local max within +/-3 frames, merge peaks 1 frame apart. */
    private fun pickPeaks(logits: FloatArray): List<Double> {
        val n = logits.size
        val peaks = ArrayList<Int>()
        for (f in 0 until n) {
            val v = logits[f]
            if (v <= 0f) continue
            var isMax = true
            for (d in -PEAK_RADIUS..PEAK_RADIUS) {
                if (d == 0) continue
                val j = f + d
                if (j < 0 || j >= n) continue
                val w = logits[j]
                if (w > v || (w == v && d < 0)) {
                    isMax = false
                    break
                }
            }
            if (isMax) peaks.add(f)
        }
        val merged = ArrayList<Double>()
        var i = 0
        while (i < peaks.size) {
            var j = i
            var sum = peaks[i].toDouble()
            var cnt = 1
            while (j + 1 < peaks.size && peaks[j + 1] - peaks[j] <= 1) {
                j++
                sum += peaks[j]
                cnt++
            }
            merged.add(sum / cnt)
            i = j + 1
        }
        return merged
    }
}
