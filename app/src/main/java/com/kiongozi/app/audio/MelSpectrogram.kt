package com.kiongozi.app.audio

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Frontend parameters. Loaded from beat_this_spec.json (written by the CI export) when present. */
data class MelParams(
    val sampleRate: Int = 22050,
    val nFft: Int = 1024,
    val hop: Int = 441,
    val nMels: Int = 128,
    val fMin: Double = 30.0,
    val fMax: Double = 11000.0,
    val logMultiplier: Float = 1000f,
    /** Row-major [nFft/2+1][nMels], from the exact torchaudio filterbank (mel_filterbank_f32.bin). */
    val filterbank: FloatArray? = null,
) {
    companion object {
        fun load(context: Context): MelParams {
            var p = MelParams()
            try {
                val text = context.assets.open("models/beat_this_spec.json").bufferedReader().use { it.readText() }
                val fe = JSONObject(text).optJSONObject("frontend")
                if (fe != null) {
                    p = p.copy(
                        sampleRate = fe.optInt("sample_rate", p.sampleRate),
                        nFft = fe.optInt("n_fft", p.nFft),
                        hop = fe.optInt("hop_length", p.hop),
                        nMels = fe.optInt("n_mels", p.nMels),
                        fMin = fe.optDouble("f_min", p.fMin),
                        fMax = fe.optDouble("f_max", p.fMax),
                    )
                }
            } catch (_: Exception) {
            }
            try {
                val bytes = context.assets.open("models/mel_filterbank_f32.bin").use { it.readBytes() }
                val expected = (p.nFft / 2 + 1) * p.nMels
                if (bytes.size == expected * 4) {
                    val fb = FloatArray(expected)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fb)
                    p = p.copy(filterbank = fb)
                }
            } catch (_: Exception) {
            }
            return p
        }
    }
}

/** Frames of log-mel stored in blocks (no giant contiguous allocation). */
class FrameStore(val nMels: Int) {
    private companion object {
        const val FRAMES_PER_BLOCK = 512
    }

    private val blocks = ArrayList<FloatArray>()
    var size = 0
        private set

    fun append(frame: FloatArray) {
        val bi = size / FRAMES_PER_BLOCK
        if (bi == blocks.size) blocks.add(FloatArray(FRAMES_PER_BLOCK * nMels))
        System.arraycopy(frame, 0, blocks[bi], (size % FRAMES_PER_BLOCK) * nMels, nMels)
        size++
    }

    /** Copies [count] frames starting at [start] into dest (frame-major); out-of-range frames are zeros. */
    fun copyFrames(start: Int, count: Int, dest: FloatArray) {
        for (i in 0 until count) {
            val f = start + i
            val o = i * nMels
            if (f < 0 || f >= size) {
                java.util.Arrays.fill(dest, o, o + nMels, 0f)
            } else {
                System.arraycopy(blocks[f / FRAMES_PER_BLOCK], (f % FRAMES_PER_BLOCK) * nMels, dest, o, nMels)
            }
        }
    }
}

class GrowableFloats {
    private var a = FloatArray(4096)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray(): FloatArray = a.copyOf(size)
}

/**
 * Per-frame features (50 fps) used by the chart generator.
 * Three frequency layers, each analysed independently so a quiet part (e.g. an arpeggio or vocal)
 * is not drowned out by a loud one (e.g. the bass):
 *   0 = BASS  (~30-540 Hz: kick, bass)
 *   1 = MID   (~540-3000 Hz: vocals, lead, arpeggios, snare body)
 *   2 = HIGH  (~3-11 kHz: hats, shimmer, sibilance)
 */
class AudioFeatures(
    val flux: FloatArray,
    val centroid: FloatArray,
    val energy: FloatArray,
    val bandFlux: Array<FloatArray>,
    val bandEnergy: Array<FloatArray>,
    val bandCentroid: Array<FloatArray>,
)

/** In-place radix-2 complex FFT. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        val levels = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - levels)
        for (i in 0 until n / 2) {
            val a = 2.0 * PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = -sinT[k]
                    val xr = re[j + half]
                    val xi = im[j + half]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/**
 * Streaming log-mel extractor replicating beat_this's frontend (torchaudio MelSpectrogram +
 * log1p(1000 * x)):
 *   22,050 Hz mono | n_fft 1024 | hop 441 (50 fps) | periodic Hann | center=True, reflect padding |
 *   magnitude (power=1) scaled by 1/sqrt(n_fft) ("frame_length" normalisation) | 128 mel bins.
 * Also computes spectral flux, spectral centroid and energy per frame for the chart generator.
 *
 * Feed it with [push] as PCM arrives, call [finish] at the end. Results: [store] and [features].
 */
class MelSpectrogram(private val p: MelParams) {
    val store = FrameStore(p.nMels)
    val frameCount: Int get() = store.size

    private val nFreqs = p.nFft / 2 + 1
    private val half = p.nFft / 2
    private val fft = Fft(p.nFft)
    private val window = FloatArray(p.nFft) { (0.5 - 0.5 * cos(2.0 * PI * it / p.nFft)).toFloat() }
    private val re = FloatArray(p.nFft)
    private val im = FloatArray(p.nFft)
    private val mag = FloatArray(nFreqs)
    private val mel = FloatArray(p.nMels)
    private val logFrame = FloatArray(p.nMels)
    private val prevLog = FloatArray(p.nMels)
    private var havePrev = false
    private val magScale = (1.0 / sqrt(p.nFft.toDouble())).toFloat()

    // Sparse filterbank: for each mel, the contiguous FFT-bin range with non-zero weight
    private val fb: FloatArray = p.filterbank ?: computeSlaneyFilterbank(p)
    private val kStart = IntArray(p.nMels)
    private val kEnd = IntArray(p.nMels)

    private val flux = GrowableFloats()
    private val centroid = GrowableFloats()
    private val energy = GrowableFloats()

    // 3 frequency layers (see AudioFeatures); edges scale with nMels (20/80/128 for 128 mels)
    private val bandEdges = intArrayOf(0, Math.round(p.nMels * 20f / 128f), Math.round(p.nMels * 80f / 128f), p.nMels)
    private val bandOf = IntArray(p.nMels) { m -> if (m < bandEdges[1]) 0 else if (m < bandEdges[2]) 1 else 2 }
    private val bFlux = Array(3) { GrowableFloats() }
    private val bEnergy = Array(3) { GrowableFloats() }
    private val bCentroid = Array(3) { GrowableFloats() }
    private val bSum = DoubleArray(3)
    private val bW = DoubleArray(3)
    private val bFluxSum = FloatArray(3)

    // sample buffer
    private var buf = FloatArray(p.nFft * 16)
    private var bufStart = 0L
    private var bufLen = 0
    private var totalSamples = 0L
    private var nextFrame = 0

    init {
        for (m in 0 until p.nMels) {
            var s = -1
            var e = -1
            for (k in 0 until nFreqs) {
                if (fb[k * p.nMels + m] != 0f) {
                    if (s < 0) s = k
                    e = k
                }
            }
            kStart[m] = max(s, 0)
            kEnd[m] = if (s < 0) 0 else e + 1
        }
    }

    fun push(samples: FloatArray, n: Int) {
        if (n <= 0) return
        if (bufLen + n > buf.size) {
            dropOld()
            if (bufLen + n > buf.size) buf = buf.copyOf(max(buf.size * 2, bufLen + n))
        }
        System.arraycopy(samples, 0, buf, bufLen, n)
        bufLen += n
        totalSamples += n
        process(false)
    }

    fun finish() {
        process(true)
    }

    fun features(): AudioFeatures = AudioFeatures(
        flux.toArray(), centroid.toArray(), energy.toArray(),
        Array(3) { bFlux[it].toArray() }, Array(3) { bEnergy[it].toArray() }, Array(3) { bCentroid[it].toArray() },
    )

    private fun sampleAt(idx: Long, final: Boolean): Float {
        var i = idx
        if (i < 0) i = -i                                   // reflect at start
        if (final && i >= totalSamples) i = 2 * (totalSamples - 1) - i // reflect at end
        if (i < 0) i = 0
        if (i >= totalSamples) i = totalSamples - 1
        return buf[(i - bufStart).toInt()]
    }

    private fun process(final: Boolean) {
        while (true) {
            val t = nextFrame
            val startIdx = t.toLong() * p.hop - half
            val endIdx = startIdx + p.nFft - 1
            if (final) {
                if (totalSamples == 0L || t >= 1 + totalSamples / p.hop) break
            } else {
                if (endIdx >= totalSamples || totalSamples < half + 1) break
            }
            for (i in 0 until p.nFft) {
                re[i] = sampleAt(startIdx + i, final) * window[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            for (k in 0 until nFreqs) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * magScale
            emitFrame()
            nextFrame++
        }
        dropOld()
    }

    private fun emitFrame() {
        val nm = p.nMels
        java.util.Arrays.fill(bSum, 0.0)
        java.util.Arrays.fill(bW, 0.0)
        java.util.Arrays.fill(bFluxSum, 0f)
        var sum = 0.0
        var wsum = 0.0
        var fluxSum = 0f
        for (m in 0 until nm) {
            var acc = 0f
            for (k in kStart[m] until kEnd[m]) acc += fb[k * nm + m] * mag[k]
            mel[m] = acc
            sum += acc
            wsum += acc * m
            val l = ln1p((p.logMultiplier * acc).toDouble()).toFloat()
            logFrame[m] = l
            val b = bandOf[m]
            bSum[b] += acc.toDouble()
            bW[b] += acc.toDouble() * (m - bandEdges[b])
            if (havePrev) {
                val d = l - prevLog[m]
                if (d > 0f) {
                    fluxSum += d
                    bFluxSum[b] += d
                }
            }
        }
        System.arraycopy(logFrame, 0, prevLog, 0, nm)
        havePrev = true
        store.append(logFrame)
        flux.add(fluxSum / nm)
        energy.add((sum / nm).toFloat())
        centroid.add(if (sum > 1e-9) (wsum / sum / (nm - 1)).toFloat() else 0.5f)
        for (b in 0 until 3) {
            val width = max(bandEdges[b + 1] - bandEdges[b], 1)
            bFlux[b].add(bFluxSum[b] / width)
            bEnergy[b].add((bSum[b] / width).toFloat())
            bCentroid[b].add(if (bSum[b] > 1e-9) (bW[b] / bSum[b] / max(width - 1, 1)).toFloat() else 0.5f)
        }
    }

    /** Drop samples no future frame can need (keeps one extra half-window for end reflection). */
    private fun dropOld() {
        val nextStart = nextFrame.toLong() * p.hop - half
        val keepFrom = max(0L, nextStart - half)
        val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufStart += drop
            bufLen -= drop
        }
    }

    private companion object {
        /** Fallback only (norm=None, slaney mel scale), used if mel_filterbank_f32.bin is missing. */
        fun computeSlaneyFilterbank(p: MelParams): FloatArray {
            val nFreqs = p.nFft / 2 + 1
            val fSp = 200.0 / 3
            val minLogHz = 1000.0
            val minLogMel = minLogHz / fSp
            val logStep = ln(6.4) / 27.0
            fun hzToMel(f: Double) = if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logStep else f / fSp
            fun melToHz(m: Double) = if (m >= minLogMel) minLogHz * exp(logStep * (m - minLogMel)) else fSp * m

            val mMin = hzToMel(p.fMin)
            val mMax = hzToMel(p.fMax)
            val fPts = DoubleArray(p.nMels + 2) { melToHz(mMin + (mMax - mMin) * it / (p.nMels + 1)) }
            val allFreqs = DoubleArray(nFreqs) { p.sampleRate / 2.0 * it / (nFreqs - 1) }
            val fb = FloatArray(nFreqs * p.nMels)
            for (m in 0 until p.nMels) {
                val fDiffDown = fPts[m + 1] - fPts[m]
                val fDiffUp = fPts[m + 2] - fPts[m + 1]
                for (k in 0 until nFreqs) {
                    val down = -(fPts[m] - allFreqs[k]) / fDiffDown
                    val up = (fPts[m + 2] - allFreqs[k]) / fDiffUp
                    fb[k * p.nMels + m] = max(0.0, min(down, up)).toFloat()
                }
            }
            return fb
        }
    }
}
