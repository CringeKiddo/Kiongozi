package com.kiongozi.app.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Low-latency hit sounds. The three sounds (Key / Tambourine / Clap) are SYNTHESISED on first use
 * (no audio assets needed), written once to cacheDir as tiny WAVs, and played through SoundPool,
 * which keeps them decoded in memory so every tap fires with minimal delay.
 */
class HitSounds(context: Context, private val type: HitSoundType, volumePercent: Int) {
    private val volume = (volumePercent / 100f).coerceIn(0f, 1f)
    private var pool: SoundPool? = null
    private var soundId = 0
    @Volatile private var loaded = false

    init {
        if (volume > 0f) {
            try {
                val file = ensureWav(context, type)
                val sp = SoundPool.Builder()
                    .setMaxStreams(8)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                sp.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
                soundId = sp.load(file.absolutePath, 1)
                pool = sp
            } catch (_: Exception) {
                pool = null
            }
        }
    }

    fun play() {
        val p = pool ?: return
        if (loaded) p.play(soundId, volume, volume, 1, 0, 1f)
    }

    fun release() {
        try {
            pool?.release()
        } catch (_: Exception) {
        }
        pool = null
    }

    companion object {
        private const val RATE = 44100
        private const val VERSION = 1

        private fun ensureWav(context: Context, type: HitSoundType): File {
            val f = File(context.cacheDir, "hit_${type.name.lowercase()}_v$VERSION.wav")
            if (f.exists() && f.length() > 100) return f
            val pcm = when (type) {
                HitSoundType.KEY -> synthKey()
                HitSoundType.TAMBOURINE -> synthTambourine()
                HitSoundType.CLAP -> synthClap()
            }
            writeWav(f, pcm)
            return f
        }

        private fun synthKey(): ShortArray {
            val n = RATE * 90 / 1000
            val out = FloatArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                out[i] = (sin(2 * PI * 1800 * t) * exp(-t * 200) + 0.45 * sin(2 * PI * 900 * t) * exp(-t * 130)).toFloat()
            }
            return normalize(out)
        }

        private fun synthTambourine(): ShortArray {
            val n = RATE * 260 / 1000
            val rnd = Random(7)
            val out = FloatArray(n)
            var prev = 0f
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val noise = rnd.nextFloat() * 2f - 1f
                val hp = noise - prev   // crude high-pass
                prev = noise
                val shimmer = (sin(2 * PI * 5200 * t) + sin(2 * PI * 6800 * t) + sin(2 * PI * 8300 * t)) / 3.0 *
                    (1.0 + 0.5 * sin(2 * PI * 55 * t))
                out[i] = (hp * 0.7 * exp(-t * 20) + shimmer * 0.8 * exp(-t * 16)).toFloat()
            }
            return normalize(out)
        }

        private fun synthClap(): ShortArray {
            val n = RATE * 220 / 1000
            val rnd = Random(11)
            val out = FloatArray(n)
            var lp = 0f
            val bursts = doubleArrayOf(0.0, 0.011, 0.024)
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val noise = rnd.nextFloat() * 2f - 1f
                lp += 0.55f * (noise - lp)           // soften the noise
                var env = exp(-t * 28) * 0.55          // tail
                for (b in bursts) if (t >= b) env += exp(-(t - b) * 140)
                out[i] = (lp * env).toFloat()
            }
            return normalize(out)
        }

        private fun normalize(x: FloatArray): ShortArray {
            var peak = 1e-6f
            for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
            val g = 0.85f * 32767f / peak
            return ShortArray(x.size) { (x[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
        }

        private fun writeWav(file: File, pcm: ShortArray) {
            val dataLen = pcm.size * 2
            val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
            bb.putInt(RATE); bb.putInt(RATE * 2); bb.putShort(2); bb.putShort(16)
            bb.put("data".toByteArray()); bb.putInt(dataLen)
            for (s in pcm) bb.putShort(s)
            RandomAccessFile(file, "rw").use {
                it.setLength(0)
                it.write(bb.array())
            }
        }
    }
}
