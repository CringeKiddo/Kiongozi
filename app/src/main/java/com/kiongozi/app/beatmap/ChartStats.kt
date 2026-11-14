package com.kiongozi.app.beatmap

import kotlin.math.max
import kotlin.math.roundToInt

data class ChartRating(
    val notes: Int,
    /** notes per second over the part of the song that actually has notes */
    val nps: Float,
    /** 1.0 .. 12.0 in 0.5 steps */
    val stars: Float,
)

/** Difficulty rating badge from note density (NPS), with extra weight for chords and holds. */
object ChartStats {
    fun rate(beatmap: Beatmap, d: Difficulty): ChartRating {
        val notes = beatmap.chart(d)?.notes
        if (notes.isNullOrEmpty()) return ChartRating(0, 0f, 1f)
        val first = notes.minOf { it.timeMs }
        val last = notes.maxOf { it.timeMs }
        val active = max(10.0, (last - first) / 1000.0)
        val distinctTimes = notes.map { it.timeMs }.distinct().size
        val chordExtra = notes.size - distinctTimes
        val holds = notes.count { it.holdDurationMs > 0 }
        val weighted = notes.size + 0.5 * chordExtra + 0.4 * holds
        val nps = (notes.size / active).toFloat()
        val stars = (((1.0 + 0.95 * (weighted / active)) * 2.0).roundToInt() / 2f).coerceIn(1f, 12f)
        return ChartRating(notes.size, nps, stars)
    }
}
