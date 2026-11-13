package com.kiongozi.app.beatmap

/**
 * Everything the chart generator needs, and everything worth caching so charts can be regenerated
 * instantly (when generator rules change) without re-running the slow neural network.
 * Features are at 50 fps (20 ms per frame). The three band arrays are the frequency layers
 * (0 = bass, 1 = mid / vocals / arpeggios, 2 = highs); they are null only for analyses cached by
 * the Batch 2 build, which are upgraded automatically (audio re-decoded, beats reused).
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
    val bandFlux: Array<FloatArray>? = null,
    val bandEnergy: Array<FloatArray>? = null,
    val bandCentroid: Array<FloatArray>? = null,
) {
    val frameCount: Int get() = flux.size
    val hasBands: Boolean get() = bandFlux != null && bandEnergy != null && bandCentroid != null

    companion object {
        const val FRAME_MS = 20
    }
}
