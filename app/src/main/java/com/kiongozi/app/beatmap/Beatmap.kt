package com.kiongozi.app.beatmap

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

enum class Difficulty(val label: String) {
    EASY("EASY"),
    NORMAL("NORMAL"),
    HARD("HARD"),
    EXPERT("EXPERT"),
}

/** Project DIVA-style face buttons. */
enum class ButtonType { TRIANGLE, CIRCLE, CROSS, SQUARE }

/**
 * One note. A single Note carries BOTH game modes' data so one chart serves either mode:
 *  - Mode A (5-lane): [lane] 0..4 (0 = lowest pitch/left, 4 = highest/right), [holdDurationMs].
 *  - Mode B (targets): [x],[y] normalized 0..1 (origin top-left), [entryAngleDeg] = direction the
 *    icon flies in FROM (0 = right edge, 90 = bottom, 180 = left, 270 = top; screen coords),
 *    [curve] = sideways bend of the approach path (-1..1, sign = bend direction), [button],
 *    [holdDurationMs].
 * Simultaneous notes (chords / doubles) are separate Notes sharing the same [timeMs].
 */
data class Note(
    val timeMs: Long,
    val lane: Int,
    val holdDurationMs: Long,
    val x: Float,
    val y: Float,
    val entryAngleDeg: Float,
    val curve: Float,
    val button: ButtonType,
    /** 4 = quarter, 8 = eighth, 16 = sixteenth grid position (informational). */
    val subdivision: Int,
)

data class Chart(val difficulty: Difficulty, val notes: List<Note>)

data class Beatmap(
    val songKey: String,
    val title: String,
    val durationMs: Long,
    val bpm: Double,
    val beatsPerBar: Int,
    val beatsMs: List<Long>,
    val downbeatsMs: List<Long>,
    val charts: List<Chart>,
    val generatorVersion: Int,
    val generatedAtMs: Long,
) {
    fun chart(d: Difficulty): Chart? = charts.firstOrNull { it.difficulty == d }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("format", FORMAT_VERSION)
        o.put("generatorVersion", generatorVersion)
        o.put("songKey", songKey)
        o.put("title", title)
        o.put("durationMs", durationMs)
        o.put("bpm", round(bpm * 100) / 100)
        o.put("beatsPerBar", beatsPerBar)
        o.put("generatedAtMs", generatedAtMs)
        o.put("beatsMs", JSONArray(beatsMs))
        o.put("downbeatsMs", JSONArray(downbeatsMs))
        o.put(
            "noteFormat",
            JSONArray(listOf("timeMs", "lane", "holdDurationMs", "x", "y", "entryAngleDeg", "curve", "button(0=TRIANGLE,1=CIRCLE,2=CROSS,3=SQUARE)", "subdivision"))
        )
        val cs = JSONObject()
        for (c in charts) {
            val arr = JSONArray()
            for (n in c.notes) {
                arr.put(
                    JSONArray(
                        listOf(
                            n.timeMs, n.lane, n.holdDurationMs,
                            r3(n.x), r3(n.y), r3(n.entryAngleDeg), r3(n.curve),
                            n.button.ordinal, n.subdivision,
                        )
                    )
                )
            }
            cs.put(c.difficulty.name, arr)
        }
        o.put("charts", cs)
        return o
    }

    companion object {
        const val FORMAT_VERSION = 1

        private fun r3(v: Float): Double = round(v * 1000.0) / 1000.0

        fun fromJson(o: JSONObject): Beatmap {
            val beats = o.getJSONArray("beatsMs").let { a -> List(a.length()) { a.getLong(it) } }
            val downs = o.getJSONArray("downbeatsMs").let { a -> List(a.length()) { a.getLong(it) } }
            val cs = o.getJSONObject("charts")
            val charts = ArrayList<Chart>()
            for (d in Difficulty.values()) {
                val arr = cs.optJSONArray(d.name) ?: continue
                val notes = List(arr.length()) { i ->
                    val n = arr.getJSONArray(i)
                    Note(
                        timeMs = n.getLong(0),
                        lane = n.getInt(1),
                        holdDurationMs = n.getLong(2),
                        x = n.getDouble(3).toFloat(),
                        y = n.getDouble(4).toFloat(),
                        entryAngleDeg = n.getDouble(5).toFloat(),
                        curve = n.getDouble(6).toFloat(),
                        button = ButtonType.values()[n.getInt(7).coerceIn(0, 3)],
                        subdivision = n.getInt(8),
                    )
                }
                charts.add(Chart(d, notes))
            }
            return Beatmap(
                songKey = o.getString("songKey"),
                title = o.getString("title"),
                durationMs = o.getLong("durationMs"),
                bpm = o.getDouble("bpm"),
                beatsPerBar = o.getInt("beatsPerBar"),
                beatsMs = beats,
                downbeatsMs = downs,
                charts = charts,
                generatorVersion = o.optInt("generatorVersion", 0),
                generatedAtMs = o.optLong("generatedAtMs", 0L),
            )
        }
    }
}
