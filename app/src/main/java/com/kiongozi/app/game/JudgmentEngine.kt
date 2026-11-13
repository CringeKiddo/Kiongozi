package com.kiongozi.app.game

import com.kiongozi.app.beatmap.Note
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Judgment(val label: String, val altLabel: String, val weight: Double) {
    PERFECT("PERFECT", "COOL", 1.0),
    GREAT("GREAT", "FINE", 0.8),
    GOOD("GOOD", "SAFE", 0.5),
    MISS("MISS", "MISS", 0.0),
}

enum class EventKind { HIT, MISS, TICK, TAIL_OK, TAIL_BREAK }

data class JudgmentEvent(
    val kind: EventKind,
    val judgment: Judgment,
    /** press time - note time in ms (negative = EARLY, positive = LATE). 0 for misses/ticks. */
    val deltaMs: Double,
    val track: Int,
    val noteIndex: Int,
    val songTimeMs: Double,
    val combo: Int,
)

enum class Grade { SSS, SS, S, A, B, C }

data class GameResults(
    val score: Int,
    val maxCombo: Int,
    val accuracy: Double,
    val perfect: Int,
    val great: Int,
    val good: Int,
    val miss: Int,
    val early: Int,
    val late: Int,
    val holdTicks: Int,
    val totalNotes: Int,
    val grade: Grade,
    val failed: Boolean,
    val noFail: Boolean,
)

/** Live numbers for the HUD. */
data class GameStats(
    val score: Int,
    val combo: Int,
    val maxCombo: Int,
    val accuracy: Double,
    val health: Double,
    val failed: Boolean,
    val complete: Boolean,
)

class TrackedNote(val index: Int, val note: Note, val track: Int) {
    val timeMs: Double = note.timeMs.toDouble()
    val endMs: Double = timeMs + note.holdDurationMs
    val isHold: Boolean = note.holdDurationMs > 0
    val totalTicks: Int = if (isHold) max(0, ((note.holdDurationMs - 50) / JudgmentEngine.TICK_MS).toInt()) else 0

    @Volatile var headJudged = false
    @Volatile var headJudgment: Judgment? = null
    @Volatile var holding = false
    @Volatile var tailResolved = !isHold
    @Volatile var broken = false
    @Volatile var done = false
    @Volatile var resolvedAtMs = 0.0
    var nextTick = 1
    var ticksHit = 0
}

/**
 * Timing windows (ms, either side of the note):  PERFECT/COOL 45 | GREAT/FINE 90 | GOOD/SAFE 135 | MISS beyond.
 * Works for both modes: a "track" is a lane (5-lane mode) or a button type (target mode).
 *
 * Holds: head is judged like a tap; while held, a tick every 100 ms scores; releasing within 135 ms of
 * the end (or holding through it) completes the hold, releasing earlier breaks it.
 *
 * Score: 0..1,000,000 = weighted share of every judgment unit (tap 1, hold head 1 + tail 1 + 0.25 per tick).
 * Weights: PERFECT 1.0, GREAT 0.8, GOOD 0.5, MISS 0. Accuracy is the same ratio over what's been resolved so far.
 * Health: +0.5 / +0.25 / 0 / -8 per judgment; reaching 0 fails the song unless No-Fail is on.
 */
class JudgmentEngine(
    chartNotes: List<Note>,
    private val trackOf: (Note) -> Int,
    private val trackCount: Int,
    var noFail: Boolean,
) {
    companion object {
        const val PERFECT_MS = 45.0
        const val GREAT_MS = 90.0
        const val GOOD_MS = 135.0
        const val TICK_MS = 100.0
        private const val TICK_WEIGHT = 0.25
    }

    val notes: List<TrackedNote> = chartNotes.mapIndexed { i, n -> TrackedNote(i, n, trackOf(n).coerceIn(0, trackCount - 1)) }
    private val byTrack: Array<ArrayList<TrackedNote>> = Array(trackCount) { ArrayList() }
    private val nextHead = IntArray(trackCount)
    private val held = arrayOfNulls<TrackedNote>(trackCount)
    private val lock = Any()
    private val events = ArrayList<JudgmentEvent>()

    private val totalPossible: Double
    private var earned = 0.0
    private var resolved = 0.0
    private var combo = 0
    private var maxCombo = 0
    private val counts = IntArray(4)
    private var early = 0
    private var late = 0
    private var ticksHit = 0
    private var health = 100.0
    private var failed = false
    private var remaining = 0

    init {
        for (n in notes) byTrack[n.track].add(n)
        var total = 0.0
        for (n in notes) total += 1.0 + if (n.isHold) 1.0 + TICK_WEIGHT * n.totalTicks else 0.0
        totalPossible = max(total, 1.0)
        remaining = notes.size
    }

    fun reset() = synchronized(lock) {
        for (n in notes) {
            n.headJudged = false; n.headJudgment = null; n.holding = false
            n.tailResolved = !n.isHold; n.broken = false; n.done = false; n.resolvedAtMs = 0.0
            n.nextTick = 1; n.ticksHit = 0
        }
        nextHead.fill(0)
        for (i in held.indices) held[i] = null
        events.clear()
        earned = 0.0; resolved = 0.0; combo = 0; maxCombo = 0
        counts.fill(0); early = 0; late = 0; ticksHit = 0
        health = 100.0; failed = false; remaining = notes.size
    }

    // ----------------------------------------------------------------------------------------
    // Input
    // ----------------------------------------------------------------------------------------
    fun onPress(track: Int, songMs: Double): JudgmentEvent? = synchronized(lock) {
        if (track !in 0 until trackCount) return null
        processMisses(songMs)
        val list = byTrack[track]
        var i = nextHead[track]
        while (i < list.size && list[i].headJudged) i++
        nextHead[track] = i
        if (i >= list.size) return null
        val n = list[i]
        val dt = songMs - n.timeMs
        if (dt < -GOOD_MS) return null // too early to count (stray tap)
        val j = classify(abs(dt))
        n.headJudged = true
        n.headJudgment = j
        n.resolvedAtMs = songMs
        if (dt < 0 && j != Judgment.PERFECT) early++ else if (dt > 0 && j != Judgment.PERFECT) late++
        if (n.isHold) {
            n.holding = true
            held[track] = n
        } else {
            finishNote(n)
        }
        award(j, 1.0)
        return emit(EventKind.HIT, j, dt, track, n.index, songMs)
    }

    fun onRelease(track: Int, songMs: Double) {
        synchronized(lock) {
            if (track !in 0 until trackCount) return@synchronized
            val n = held[track] ?: return@synchronized
            runTicks(n, songMs)
            if (!n.holding) return@synchronized
            n.holding = false
            held[track] = null
            n.resolvedAtMs = songMs
            if (songMs >= n.endMs - GOOD_MS) {
                val j = classify(abs(songMs - n.endMs))
                n.tailResolved = true
                award(j, 1.0)
                finishNote(n)
                emit(EventKind.TAIL_OK, j, songMs - n.endMs, track, n.index, songMs)
            } else {
                breakHold(n, track, songMs)
            }
        }
    }

    /** Call every frame: resolves missed notes, hold ticks and hold completion. */
    fun update(songMs: Double) = synchronized(lock) {
        processMisses(songMs)
        for (t in 0 until trackCount) {
            val n = held[t] ?: continue
            runTicks(n, songMs)
            if (n.holding && songMs >= n.endMs) {
                n.holding = false
                held[t] = null
                n.tailResolved = true
                n.resolvedAtMs = songMs
                award(Judgment.PERFECT, 1.0)
                finishNote(n)
                emit(EventKind.TAIL_OK, Judgment.PERFECT, 0.0, t, n.index, songMs)
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Output
    // ----------------------------------------------------------------------------------------
    fun drainEvents(): List<JudgmentEvent> = synchronized(lock) {
        if (events.isEmpty()) emptyList() else ArrayList(events).also { events.clear() }
    }

    fun stats(): GameStats = synchronized(lock) {
        GameStats(
            score = (1_000_000.0 * earned / totalPossible).roundToInt(),
            combo = combo, maxCombo = maxCombo,
            accuracy = accuracy(), health = health, failed = failed, complete = remaining <= 0,
        )
    }

    fun results(): GameResults = synchronized(lock) {
        val acc = if (remaining <= 0) 100.0 * earned / totalPossible else accuracy()
        GameResults(
            score = (1_000_000.0 * earned / totalPossible).roundToInt(),
            maxCombo = maxCombo, accuracy = acc,
            perfect = counts[0], great = counts[1], good = counts[2], miss = counts[3],
            early = early, late = late, holdTicks = ticksHit, totalNotes = notes.size,
            grade = gradeFor(acc), failed = failed, noFail = noFail,
        )
    }

    // ----------------------------------------------------------------------------------------
    // Internals (all called with the lock held)
    // ----------------------------------------------------------------------------------------
    private fun accuracy(): Double = if (resolved > 0) 100.0 * earned / resolved else 100.0

    private fun gradeFor(acc: Double): Grade = when {
        acc >= 99.5 -> Grade.SSS
        acc >= 98.0 -> Grade.SS
        acc >= 95.0 -> Grade.S
        acc >= 90.0 -> Grade.A
        acc >= 80.0 -> Grade.B
        else -> Grade.C
    }

    private fun classify(absDt: Double): Judgment = when {
        absDt <= PERFECT_MS -> Judgment.PERFECT
        absDt <= GREAT_MS -> Judgment.GREAT
        else -> Judgment.GOOD
    }

    private fun award(j: Judgment, units: Double) {
        earned += j.weight * units
        resolved += units
        counts[j.ordinal]++
        if (j == Judgment.MISS) {
            combo = 0
            health = max(0.0, health - 8.0)
        } else {
            combo++
            maxCombo = max(maxCombo, combo)
            health = min(100.0, health + if (j == Judgment.PERFECT) 0.5 else if (j == Judgment.GREAT) 0.25 else 0.0)
        }
        if (health <= 0.0 && !noFail) failed = true
    }

    private fun finishNote(n: TrackedNote) {
        if (!n.done) {
            n.done = true
            remaining--
        }
    }

    private fun emit(kind: EventKind, j: Judgment, dt: Double, track: Int, idx: Int, t: Double): JudgmentEvent {
        val e = JudgmentEvent(kind, j, dt, track, idx, t, combo)
        events.add(e)
        return e
    }

    private fun processMisses(songMs: Double) {
        for (t in 0 until trackCount) {
            val list = byTrack[t]
            var i = nextHead[t]
            while (i < list.size) {
                val n = list[i]
                if (n.headJudged) {
                    i++
                    continue
                }
                if (songMs <= n.timeMs + GOOD_MS) break
                n.headJudged = true
                n.headJudgment = Judgment.MISS
                n.resolvedAtMs = songMs
                award(Judgment.MISS, 1.0)
                emit(EventKind.MISS, Judgment.MISS, 0.0, t, n.index, songMs)
                if (n.isHold) {
                    // whole hold is lost: tail + remaining ticks count as missed
                    n.tailResolved = true
                    n.broken = true
                    counts[Judgment.MISS.ordinal]++
                    resolved += 1.0 + TICK_WEIGHT * n.totalTicks
                }
                finishNote(n)
                i++
            }
            nextHead[t] = i
        }
    }

    private fun runTicks(n: TrackedNote, songMs: Double) {
        while (n.nextTick <= n.totalTicks && n.timeMs + n.nextTick * TICK_MS <= songMs) {
            earned += TICK_WEIGHT
            resolved += TICK_WEIGHT
            ticksHit++
            n.ticksHit++
            n.nextTick++
            emit(EventKind.TICK, Judgment.PERFECT, 0.0, n.track, n.index, songMs)
        }
    }

    private fun breakHold(n: TrackedNote, track: Int, songMs: Double) {
        n.broken = true
        n.tailResolved = true
        val missedTicks = n.totalTicks - (n.nextTick - 1)
        resolved += TICK_WEIGHT * max(0, missedTicks)
        award(Judgment.MISS, 1.0)
        finishNote(n)
        emit(EventKind.TAIL_BREAK, Judgment.MISS, 0.0, track, n.index, songMs)
    }
}
