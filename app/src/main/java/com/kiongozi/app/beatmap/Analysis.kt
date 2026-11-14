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

/**
 * Beat Grid Multiplier: fixes beat_this locking onto half- or double-tempo.
 *   >= 1.5 (2.0x double-time): a new beat is inserted halfway between every pair of beats, BPM doubles.
 *   <= 0.75 (0.5x half-time):  every second beat is dropped (keeping the downbeats' phase), BPM halves.
 * The audio features are untouched; only the grid the chart is built on changes.
 */
fun Analysis.withGrid(multiplier: Float): Analysis {
    if (multiplier > 0.75f && multiplier < 1.5f) return this
    if (beatsMs.size < 4) return this
    if (multiplier >= 1.5f) {
        val nb = ArrayList<Long>(beatsMs.size * 2)
        for (i in beatsMs.indices) {
            nb.add(beatsMs[i])
            val next = if (i + 1 < beatsMs.size) beatsMs[i + 1] else beatsMs[i] + (beatsMs[i] - beatsMs[i - 1])
            nb.add((beatsMs[i] + next) / 2)
        }
        return Analysis(
            durationMs, bpm * 2.0, (beatsPerBar * 2).coerceIn(2, 8), nb.toLongArray(), downbeatsMs,
            flux, centroid, energy, bandFlux, bandEnergy, bandCentroid,
        )
    }
    // half-time: keep every second beat, aligned so surviving downbeats stay on the grid
    var phase = 0
    if (downbeatsMs.isNotEmpty()) {
        val idx = beatsMs.indexOfFirst { it == downbeatsMs[0] }
        if (idx >= 0) phase = idx
    }
    val kept = ArrayList<Long>()
    for (i in beatsMs.indices) if (((i - phase) % 2 + 2) % 2 == 0) kept.add(beatsMs[i])
    val keptSet = kept.toHashSet()
    var downs = downbeatsMs.filter { it in keptSet }
    if (downs.size < 2) downs = kept.filterIndexed { i, _ -> i % 4 == 0 }
    val bpb = Math.round(beatsPerBar / 2.0).toInt().coerceIn(2, 8)
    return Analysis(
        durationMs, bpm / 2.0, bpb, kept.toLongArray(), downs.toLongArray(),
        flux, centroid, energy, bandFlux, bandEnergy, bandCentroid,
    )
}
