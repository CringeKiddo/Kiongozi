package com.kiongozi.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming band-limited (windowed-sinc) resampler for mono float PCM.
 * Anti-aliases before downsampling (important: 44.1/48 kHz -> 22.05 kHz would otherwise fold
 * high frequencies into the mel bands the beat model looks at). Memory use is constant.
 */
class StreamingResampler(
    private val inRate: Int,
    private val outRate: Int,
    private val sink: (FloatArray, Int) -> Unit,
) {
    private companion object {
        const val ZERO_CROSSINGS = 8
        const val TABLE_RES = 256
        const val OUT_BLOCK = 4096
    }

    private val passthrough = inRate == outRate
    private val ratio = inRate.toDouble() / outRate      // input samples per output sample
    private val cutoff = minOf(1.0, outRate.toDouble() / inRate) * 0.97
    private val halfWidth = ZERO_CROSSINGS / cutoff       // kernel half width in input samples
    private val table: FloatArray

    private var buf = FloatArray(1 shl 16)
    private var bufStart = 0L        // absolute input index of buf[0]
    private var bufLen = 0
    private var totalIn = 0L
    private var outIndex = 0L
    private val out = FloatArray(OUT_BLOCK)
    private var outCount = 0

    init {
        val size = (halfWidth * TABLE_RES).toInt() + 3
        table = FloatArray(size)
        for (i in 0 until size) {
            val d = i.toDouble() / TABLE_RES
            val x = cutoff * d
            val sinc = if (x < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            val w = d / halfWidth
            val win = if (w >= 1.0) 0.0 else 0.42 + 0.5 * cos(PI * w) + 0.08 * cos(2 * PI * w) // Blackman
            table[i] = (cutoff * sinc * win).toFloat()
        }
    }

    fun push(src: FloatArray, n: Int) {
        if (n <= 0) return
        if (passthrough) {
            sink(src, n)
            return
        }
        if (bufLen + n > buf.size) {
            compact()
            if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        }
        System.arraycopy(src, 0, buf, bufLen, n)
        bufLen += n
        totalIn += n
        process(false)
    }

    fun finish() {
        if (passthrough) return
        process(true)
        flushOut()
    }

    private fun process(final: Boolean) {
        while (true) {
            val pos = outIndex * ratio
            val nMin = ceil(pos - halfWidth).toLong()
            val nMax = floor(pos + halfWidth).toLong()
            if (final) {
                if (pos >= totalIn) break
            } else if (nMax >= bufStart + bufLen) {
                break
            }
            var acc = 0.0
            var wsum = 0.0
            var d = nMin - pos
            var n = nMin
            while (n <= nMax) {
                val x = abs(d) * TABLE_RES
                val i = x.toInt()
                val f = x - i
                val h = table[i] * (1.0 - f) + table[i + 1] * f
                wsum += h
                if (n >= 0 && n < totalIn) {
                    acc += h * buf[(n - bufStart).toInt()]
                }
                n++
                d += 1.0
            }
            out[outCount++] = if (wsum > 1e-9) (acc / wsum).toFloat() else 0f
            if (outCount == OUT_BLOCK) flushOut()
            outIndex++
        }
        compact()
    }

    private fun compact() {
        val keepFrom = floor(outIndex * ratio - halfWidth).toLong() - 1
        val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufStart += drop
            bufLen -= drop
        }
    }

    private fun flushOut() {
        if (outCount > 0) {
            sink(out, outCount)
            outCount = 0
        }
    }
}
