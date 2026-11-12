package com.kiongozi.app.beatmap

/**
 * Everything the chart generator needs, and everything worth caching so charts can be regenerated
 * instantly (when generator rules change) without re-running the slow neural network.
 * Features are at 50 fps (20 ms per frame).
 */
class Analysis(
    val durationMs: Long,
    val bpm: Double,
    val beatsPerBar: Int,
    val beatsMs: LongArray,
    val downbeatsMs: LongArray,
    val flux: FloatArray,
    val centroid: FloatArray,
    val energy: FloatArray,
) {
    val frameCount: Int get() = flux.size

    companion object {
        const val FRAME_MS = 20
    }
}
