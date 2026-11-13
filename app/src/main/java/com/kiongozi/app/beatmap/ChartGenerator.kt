package com.kiongozi.app.beatmap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Procedural chart generator (v2: multi-layer).
 *
 * Grid:    beat_this beats, each split into 4 slots (quarter / 8th / 16th positions).
 * Layers:  the song is analysed in three frequency layers (bass / mid / high), each with its OWN
 *          onset detection normalised to its own loudness. A quiet arpeggio or vocal line in the
 *          mid layer is therefore found even when a loud bass sits underneath it.
 * Easy / Normal follow the main beat (+ strong 8ths). Hard / Expert react to the independent layers:
 *          bass hits go to the left lanes, the melody / arpeggio stream to the middle lanes, highs to the
 *          right lanes, and simultaneous layers become chords, so the chart is genuinely denser
 *          and more polyrhythmic as difficulty rises.
 * Holds:   sustained energy in the layer that triggered the note (so long vocal / lead notes become holds).
 * Targets: per-bar geometric paths (arc, wave, circle, diagonal, V, figure-8) for Project DIVA mode.
 * Deterministic for a given song (seeded from the song key).
 */
object ChartGenerator {
    /** Bump when rules change; cached charts with an older version get rebuilt from cached analysis. */
    const val VERSION = 2

    private const val BASS = 0
    private const val MID = 1
    private const val HIGH = 2

    private val PIf = PI.toFloat()

    private class Params(
        val minGapMs: Long,
        val thr8: FloatArray,     // per layer: flux ratio needed for an 8th slot; < 0 = layer ignored
        val thr16: FloatArray,    // per layer: flux ratio needed for a 16th slot; < 0 = layer ignored
        val thrChord: Float,      // single-layer "big hit" double threshold; < 0 = none
        val chordCooldown: Int,
        val maxChordSize: Int,
        val layerLanes: Boolean,  // lanes follow the frequency layers (Hard / Expert)
        val holdsEnabled: Boolean,
        val holdOnlyDownbeats: Boolean,
        val holdMinBeats: Int,
        val holdMaxBeats: Int,
        val holdCooldownBeats: Int,
        val sustainRatio: Float,
        val allowJacks: Boolean,
        val minJackGapMs: Long,
        val maxLaneJump: Int,
        val skipBeatsIfFast: Boolean,
        val notesDuringHold: Boolean,
    )

    private val OFF = floatArrayOf(-1f, -1f, -1f)

    private fun paramsFor(d: Difficulty): Params = when (d) {
        Difficulty.EASY -> Params(
            minGapMs = 350, thr8 = OFF, thr16 = OFF, thrChord = -1f, chordCooldown = 0, maxChordSize = 1,
            layerLanes = false, holdsEnabled = false, holdOnlyDownbeats = false, holdMinBeats = 0, holdMaxBeats = 0,
            holdCooldownBeats = 0, sustainRatio = 0f, allowJacks = false, minJackGapMs = 0,
            maxLaneJump = 2, skipBeatsIfFast = true, notesDuringHold = false,
        )
        Difficulty.NORMAL -> Params(
            minGapMs = 170, thr8 = floatArrayOf(2.0f, 2.0f, -1f), thr16 = OFF, thrChord = -1f, chordCooldown = 0, maxChordSize = 1,
            layerLanes = false, holdsEnabled = true, holdOnlyDownbeats = true, holdMinBeats = 2, holdMaxBeats = 3,
            holdCooldownBeats = 6, sustainRatio = 0.65f, allowJacks = false, minJackGapMs = 0,
            maxLaneJump = 3, skipBeatsIfFast = false, notesDuringHold = false,
        )
        Difficulty.HARD -> Params(
            minGapMs = 105, thr8 = floatArrayOf(1.5f, 1.4f, 2.6f), thr16 = floatArrayOf(2.4f, 1.8f, -1f),
            thrChord = 2.8f, chordCooldown = 4, maxChordSize = 2,
            layerLanes = true, holdsEnabled = true, holdOnlyDownbeats = false, holdMinBeats = 1, holdMaxBeats = 4,
            holdCooldownBeats = 3, sustainRatio = 0.6f, allowJacks = true, minJackGapMs = 200,
            maxLaneJump = 4, skipBeatsIfFast = false, notesDuringHold = true,
        )
        Difficulty.EXPERT -> Params(
            minGapMs = 75, thr8 = floatArrayOf(1.1f, 1.1f, 1.6f), thr16 = floatArrayOf(1.6f, 1.25f, 1.8f),
            thrChord = 2.2f, chordCooldown = 1, maxChordSize = 3,
            layerLanes = true, holdsEnabled = true, holdOnlyDownbeats = false, holdMinBeats = 1, holdMaxBeats = 4,
            holdCooldownBeats = 1, sustainRatio = 0.5f, allowJacks = true, minJackGapMs = 0,
            maxLaneJump = 4, skipBeatsIfFast = false, notesDuringHold = true,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Shared per-song precomputation
    // ---------------------------------------------------------------------------------------
    private class Shared(val a: Analysis) {
        val beats: LongArray = a.beatsMs
        val n = beats.size
        val frames = a.frameCount

        // per-layer features (fallback to overall features if a legacy analysis slipped through)
        val lf: Array<FloatArray> = a.bandFlux ?: arrayOf(a.flux, a.flux, FloatArray(frames))
        val le: Array<FloatArray> = a.bandEnergy ?: arrayOf(a.energy, a.energy, a.energy)
        val lc: Array<FloatArray> = a.bandCentroid ?: arrayOf(a.centroid, a.centroid, a.centroid)

        val layerRatio = Array(3) { FloatArray(frames) }
        val layerStrength = Array(3) { FloatArray(n * 4) }
        val layerGate = FloatArray(3)
        val layerCuts = Array(3) { FloatArray(3) }   // centroid quantiles 33% / 50% / 66% per layer
        val strength = FloatArray(n * 4)             // max over layers

        // overall features: used for Easy / Normal lanes and the loudness gate
        val fluxRatio = FloatArray(frames)
        val gate: Float
        val cuts = FloatArray(4)
        val downIdx = BooleanArray(n)
        val phase: Int
        val medianInterval: Double

        init {
            val iv = LongArray(max(n - 1, 1)) { if (n > 1) beats[it + 1] - beats[it] else 500L }
            iv.sort()
            medianInterval = iv[iv.size / 2].toDouble().coerceAtLeast(120.0)

            peakRatios(a.flux, fluxRatio)
            for (l in 0 until 3) {
                peakRatios(lf[l], layerRatio[l])
                assignToSlots(layerRatio[l], layerStrength[l])
                val se = le[l].copyOf()
                se.sort()
                layerGate[l] = if (se.isNotEmpty()) 0.15f * se[se.size / 2] else 0f

                val cv = ArrayList<Float>()
                for (f in 0 until frames) if (layerRatio[l][f] >= 1.5f) cv.add(layerCentroidAt(l, f))
                if (cv.size < 20) for (f in 0 until frames step 5) cv.add(lc[l][f])
                cv.sort()
                if (cv.isEmpty()) cv.add(0.5f)
                layerCuts[l][0] = cv[(cv.size * 33 / 100).coerceIn(0, cv.size - 1)]
                layerCuts[l][1] = cv[(cv.size * 50 / 100).coerceIn(0, cv.size - 1)]
                layerCuts[l][2] = cv[(cv.size * 66 / 100).coerceIn(0, cv.size - 1)]
            }
            for (gi in 0 until n * 4) strength[gi] = max(layerStrength[0][gi], max(layerStrength[1][gi], layerStrength[2][gi]))

            val sortedE = a.energy.copyOf()
            sortedE.sort()
            gate = if (sortedE.isNotEmpty()) 0.15f * sortedE[sortedE.size / 2] else 0f

            val cv = ArrayList<Float>()
            for (f in 0 until frames) if (fluxRatio[f] >= 1.5f) cv.add(centroidAt(f))
            for (i in 0 until n) cv.add(centroidAt(frameOf(beats[i])))
            if (cv.size < 20) for (f in 0 until frames step 5) cv.add(a.centroid[f])
            cv.sort()
            for (j in 0 until 4) cuts[j] = if (cv.isEmpty()) 0.5f else cv[((j + 1) * cv.size / 5).coerceIn(0, cv.size - 1)]

            var firstDown = -1
            for (db in a.downbeatsMs) {
                val idx = java.util.Arrays.binarySearch(beats, db)
                if (idx >= 0) {
                    downIdx[idx] = true
                    if (firstDown < 0 || idx < firstDown) firstDown = idx
                }
            }
            phase = if (firstDown >= 0) firstDown else 0
        }

        /** Local-peak onsets of a flux curve, as ratio to the local (+/- 2 s) mean flux. */
        private fun peakRatios(flux: FloatArray, out: FloatArray) {
            val prefix = DoubleArray(frames + 1)
            for (i in 0 until frames) prefix[i + 1] = prefix[i] + flux[i]
            for (f in 0 until frames) {
                var isPeak = true
                for (d in -2..2) {
                    if (d == 0) continue
                    val j = f + d
                    if (j < 0 || j >= frames) continue
                    if (flux[j] > flux[f] || (flux[j] == flux[f] && d < 0)) {
                        isPeak = false
                        break
                    }
                }
                if (!isPeak) continue
                val lo = max(0, f - 100)
                val hi = min(frames, f + 101)
                val mean = (prefix[hi] - prefix[lo]) / (hi - lo)
                val ratio = (flux[f] / (mean + 1e-4)).toFloat()
                if (ratio >= 1f) out[f] = ratio
            }
        }

        /** Assign every onset peak to its nearest beat-grid slot (keeps the strongest per slot). */
        private fun assignToSlots(ratio: FloatArray, out: FloatArray) {
            for (f in 0 until frames) {
                val r = ratio[f]
                if (r <= 0f) continue
                val t = f.toLong() * Analysis.FRAME_MS
                var i = java.util.Arrays.binarySearch(beats, t)
                if (i < 0) i = -i - 2
                if (i < 0) i = 0
                val interval = intervalAt(i)
                val u = (t - beats[i]).toDouble() / interval * 4.0
                val kk = u.roundToInt()
                if (kk < 0) continue
                val gi = when {
                    kk >= 4 -> if (i + 1 < n) (i + 1) * 4 else -1
                    i == n - 1 && kk > 0 -> -1
                    else -> i * 4 + kk
                }
                if (gi >= 0 && r > out[gi]) out[gi] = r
            }
        }

        fun intervalAt(i: Int): Long = when {
            i + 1 < n -> beats[i + 1] - beats[i]
            n >= 2 -> beats[n - 1] - beats[n - 2]
            else -> 500L
        }.coerceAtLeast(1L)

        fun slotTime(gi: Int): Long {
            val i = gi / 4
            val k = gi % 4
            return beats[i] + intervalAt(i) * k / 4
        }

        fun frameOf(ms: Long): Int = (ms / Analysis.FRAME_MS).toInt().coerceIn(0, max(frames - 1, 0))

        fun energyAt(f: Int): Float {
            var m = 0f
            for (j in max(0, f - 1)..min(frames - 1, f + 2)) m = max(m, a.energy[j])
            return m
        }

        fun layerEnergyAt(l: Int, f: Int): Float {
            var m = 0f
            for (j in max(0, f - 1)..min(frames - 1, f + 2)) m = max(m, le[l][j])
            return m
        }

        fun centroidAt(f: Int): Float {
            var s = 0f
            var c = 0
            for (j in max(0, f - 1)..min(frames - 1, f + 2)) {
                s += a.centroid[j]
                c++
            }
            return if (c > 0) s / c else 0.5f
        }

        fun layerCentroidAt(l: Int, f: Int): Float {
            var s = 0f
            var c = 0
            for (j in max(0, f - 1)..min(frames - 1, f + 2)) {
                s += lc[l][j]
                c++
            }
            return if (c > 0) s / c else 0.5f
        }

        /** Overall-centroid lane (Easy / Normal): low = left ... high = right, quantile balanced. */
        fun laneOf(c: Float): Int {
            var lane = 0
            for (cut in cuts) if (c > cut) lane++
            return lane
        }

        /** Layer-based lane: bass -> lanes 0-1, mid/arpeggio -> lanes 1-3, highs -> lanes 3-4. */
        fun laneForLayer(l: Int, f: Int): Int {
            val c = layerCentroidAt(l, f)
            val q = layerCuts[l]
            return when (l) {
                BASS -> if (c >= q[1]) 1 else 0
                MID -> 1 + (if (c < q[0]) 0 else if (c < q[2]) 1 else 2)
                else -> 3 + (if (c >= q[1]) 1 else 0)
            }
        }
    }

    private class Draft(
        val timeMs: Long, val k: Int, val beatIdx: Int, val strength: Float, val frame: Int,
        val mask: Int, val dom: Int,
    ) {
        var holdMs = 0L
        var removed = false
    }

    private class NoteOut(val timeMs: Long, val lane: Int, val holdMs: Long, val sub: Int)

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------
    fun generateAll(a: Analysis, title: String, songKey: String): Beatmap {
        val sh = Shared(a)
        val seed = songKey.hashCode().toLong()
        val charts = Difficulty.values().map { d ->
            val outs = buildLanes(sh, d)
            Chart(d, assignTargets(sh, outs, seed xor (d.ordinal * 7919L)))
        }
        return Beatmap(
            songKey = songKey,
            title = title,
            durationMs = a.durationMs,
            bpm = a.bpm,
            beatsPerBar = a.beatsPerBar,
            beatsMs = a.beatsMs.toList(),
            downbeatsMs = a.downbeatsMs.toList(),
            charts = charts,
            generatorVersion = VERSION,
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Timing + holds + lanes (Mode A) for one difficulty
    // ---------------------------------------------------------------------------------------
    private fun buildLanes(sh: Shared, d: Difficulty): List<NoteOut> {
        val p = paramsFor(d)
        val n = sh.n

        // 1) candidate slots: a slot qualifies if ANY layer has an onset above that layer's threshold
        val stride = if (p.skipBeatsIfFast) max(1, ceil(420.0 / sh.medianInterval).toInt()) else 1
        var cands = ArrayList<Draft>()
        for (gi in 0 until n * 4) {
            val i = gi / 4
            val k = gi % 4
            if (i == n - 1 && k > 0) continue
            var mask = 0
            if (k == 0) {
                if (p.skipBeatsIfFast && ((i - sh.phase) % stride + stride) % stride != 0) continue
                for (l in 0 until 3) if (sh.layerStrength[l][gi] >= 1.5f) mask = mask or (1 shl l)
            } else {
                val thr = if (k == 2) p.thr8 else p.thr16
                for (l in 0 until 3) if (thr[l] >= 0f && sh.layerStrength[l][gi] >= thr[l]) mask = mask or (1 shl l)
                if (mask == 0) continue
            }
            val t = sh.slotTime(gi)
            val f = sh.frameOf(t)
            if (sh.energyAt(f) < sh.gate) continue

            // dominant layer = strongest (among triggered layers if any)
            var dom = MID
            var best = -1f
            for (l in 0 until 3) {
                if (mask != 0 && (mask and (1 shl l)) == 0) continue
                val s = sh.layerStrength[l][gi]
                if (s > best) {
                    best = s
                    dom = l
                }
            }
            cands.add(Draft(t, k, i, sh.strength[gi], f, mask, dom))
        }

        // 2) minimum spacing (beats win over off-beats, then stronger onsets win)
        val spaced = ArrayList<Draft>()
        fun prio(x: Draft) = x.strength + if (x.k == 0) 10f else 0f
        for (c in cands) {
            val last = spaced.lastOrNull()
            if (last != null && c.timeMs - last.timeMs < p.minGapMs) {
                if (prio(c) > prio(last)) spaced[spaced.lastIndex] = c
            } else {
                spaced.add(c)
            }
        }
        cands = spaced

        // 3) holds: sustained energy in the layer that triggered the note, no new strong onset in that layer
        var holdEnd = Long.MIN_VALUE
        var lastHoldBeat = -1000
        for (c in cands) {
            if (c.removed) continue
            if (c.timeMs < holdEnd) {
                if (!p.notesDuringHold) c.removed = true
                continue
            }
            if (!p.holdsEnabled) continue
            val eligible = (c.k == 0 || (c.k == 2 && d == Difficulty.EXPERT)) &&
                (!p.holdOnlyDownbeats || sh.downIdx[c.beatIdx]) &&
                c.beatIdx - lastHoldBeat >= p.holdCooldownBeats
            if (!eligible) continue
            val beatMs = sh.intervalAt(c.beatIdx)
            val releaseGap = min(90L, beatMs / 4)
            var bestN = 0
            for (nb in 1..p.holdMaxBeats) {
                val endBeat = c.beatIdx + nb
                if (endBeat >= n) break
                val endMs = sh.beats[endBeat] - releaseGap
                if (endMs - c.timeMs < 300) continue
                if (sustained(sh, c, endMs, p.sustainRatio)) bestN = nb else break
            }
            if (bestN >= p.holdMinBeats && bestN > 0) {
                c.holdMs = sh.beats[c.beatIdx + bestN] - releaseGap - c.timeMs
                holdEnd = c.timeMs + c.holdMs
                lastHoldBeat = c.beatIdx + bestN
            }
        }

        // 4) lanes + chords
        val out = ArrayList<NoteOut>()
        val busyUntil = LongArray(5) { Long.MIN_VALUE }
        var prevLane = -1
        var prevC = 0.5f
        var prevT = Long.MIN_VALUE / 2
        var sinceChord = 1000
        for (c in cands) {
            if (c.removed) continue
            val cen = if (p.layerLanes) sh.layerCentroidAt(c.dom, c.frame) else sh.centroidAt(c.frame)
            var lane = if (p.layerLanes) sh.laneForLayer(c.dom, c.frame) else sh.laneOf(cen)
            val sub = when (c.k) { 0 -> 4; 2 -> 8; else -> 16 }
            if (prevLane >= 0) {
                val gap = c.timeMs - prevT
                val jackBlocked = lane == prevLane && (!p.allowJacks || gap < p.minJackGapMs)
                if (jackBlocked) {
                    val dir = if (cen >= prevC) 1 else -1
                    var cand = lane + dir
                    if (cand !in 0..4) cand = lane - dir
                    lane = cand
                }
                lane = limitJump(prevLane, lane, p.maxLaneJump)
            }
            lane = freeLane(lane, c.timeMs, busyUntil) ?: continue
            if (c.holdMs > 0) busyUntil[lane] = c.timeMs + c.holdMs
            out.add(NoteOut(c.timeMs, lane, c.holdMs, sub))

            sinceChord++
            val canChord = c.holdMs == 0L && p.maxChordSize >= 2 && sinceChord >= p.chordCooldown
            var added = 0
            val used = hashSetOf(lane)

            // (a) simultaneous layers -> chord, each layer on its own lane (polyphony = real difficulty)
            if (canChord && p.layerLanes) {
                for (l in 0 until 3) {
                    if (l == c.dom || (c.mask and (1 shl l)) == 0) continue
                    if (1 + added >= p.maxChordSize) break
                    val fl = freeLane(sh.laneForLayer(l, c.frame), c.timeMs, busyUntil)
                    if (fl != null && fl !in used) {
                        used.add(fl)
                        out.add(NoteOut(c.timeMs, fl, 0L, sub))
                        added++
                    }
                }
            }
            // (b) very strong single hit -> mirrored double (and a centre note on Expert)
            if (canChord && added == 0 && p.thrChord >= 0 && (c.k == 0 || c.k == 2) && c.strength >= p.thrChord) {
                var second = 4 - lane
                if (abs(second - lane) < 2) second = if (lane <= 2) lane + 2 else lane - 2
                second = second.coerceIn(0, 4)
                val sl = freeLane(second, c.timeMs, busyUntil)
                if (sl != null && sl !in used) {
                    used.add(sl)
                    out.add(NoteOut(c.timeMs, sl, 0L, sub))
                    added++
                    if (p.maxChordSize >= 3 && c.strength >= p.thrChord * 1.5f) {
                        val tl = freeLane(2, c.timeMs, busyUntil)
                        if (tl != null && tl !in used) {
                            out.add(NoteOut(c.timeMs, tl, 0L, sub))
                            added++
                        }
                    }
                }
            }
            if (added > 0) sinceChord = 0

            prevLane = lane
            prevC = cen
            prevT = c.timeMs
        }
        out.sortWith(compareBy({ it.timeMs }, { it.lane }))
        return out
    }

    private fun sustained(sh: Shared, c: Draft, endMs: Long, ratio: Float): Boolean {
        val l = c.dom
        val onsetE = sh.layerEnergyAt(l, c.frame)
        if (onsetE <= sh.layerGate[l]) return false
        val f0 = c.frame + 3
        val f1 = sh.frameOf(endMs) - 1
        if (f1 - f0 < 3) return false
        var f = f0
        while (f <= f1) {
            if (sh.le[l][f] < ratio * onsetE) return false
            if (sh.layerRatio[l][f] > 1.8f) return false
            f += 2
        }
        return true
    }

    private fun limitJump(prev: Int, lane: Int, maxJump: Int): Int {
        val diff = lane - prev
        return if (abs(diff) <= maxJump) lane else prev + if (diff > 0) maxJump else -maxJump
    }

    private fun freeLane(lane: Int, t: Long, busyUntil: LongArray): Int? {
        if (busyUntil[lane] <= t) return lane
        for (off in 1..4) {
            val up = lane + off
            val down = lane - off
            if (up <= 4 && busyUntil[up] <= t) return up
            if (down >= 0 && busyUntil[down] <= t) return down
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Mode B: Project DIVA-style target positions, entry angles, buttons
    // ---------------------------------------------------------------------------------------
    private fun assignTargets(sh: Shared, outs: List<NoteOut>, seed: Long): List<Note> {
        if (outs.isEmpty()) return emptyList()
        val rng = Random(seed)
        val a = sh.a

        val barStarts: LongArray = if (a.downbeatsMs.size >= 2) {
            a.downbeatsMs
        } else {
            val bpb = max(a.beatsPerBar, 1)
            LongArray((sh.n + bpb - 1) / bpb) { sh.beats[it * bpb] }
        }
        val nb = max(barStarts.size, 1)
        val patterns = IntArray(nb + 1)
        val flips = IntArray(nb + 1)
        var prevPat = -1
        for (b in 0..nb) {
            var pat = rng.nextInt(6)
            if (pat == prevPat) pat = (pat + 1) % 6
            patterns[b] = pat
            flips[b] = rng.nextInt(4)
            prevPat = pat
        }
        val lastBarLen = if (barStarts.size >= 2) barStarts[barStarts.size - 1] - barStarts[barStarts.size - 2]
        else (sh.medianInterval * max(a.beatsPerBar, 1)).toLong()

        val result = ArrayList<Note>(outs.size)
        var gi = 0
        var prevEntry = 0f
        var i = 0
        while (i < outs.size) {
            var j = i
            while (j + 1 < outs.size && outs[j + 1].timeMs == outs[i].timeMs) j++
            val m = j - i + 1
            val t = outs[i].timeMs

            // bar + progress through bar
            var bar = java.util.Arrays.binarySearch(barStarts, t)
            if (bar < 0) bar = -bar - 2
            val before = bar < 0
            if (bar < 0) bar = 0
            val start = if (barStarts.isNotEmpty()) barStarts[bar] else 0L
            val len = if (bar + 1 < barStarts.size) barStarts[bar + 1] - start else lastBarLen
            val u = if (before) 0f else ((t - start).toFloat() / max(len, 1L)).coerceIn(0f, 1f)

            var (x, y) = pattern(patterns[bar], u, flips[bar])
            x = x.coerceIn(0.08f, 0.92f)
            y = y.coerceIn(0.08f, 0.92f)

            val pts = ArrayList<Pair<Float, Float>>()
            pts.add(x to y)
            if (m >= 2) {
                var mx = 1f - x
                var my = 1f - y
                val dist = sqrt((mx - x) * (mx - x) + (my - y) * (my - y))
                if (dist < 0.25f) {
                    pts[0] = (x - 0.15f).coerceIn(0.08f, 0.92f) to y
                    mx = (x + 0.15f).coerceIn(0.08f, 0.92f)
                    my = y
                }
                pts.add(mx to my)
            }
            if (m >= 3) {
                val p0 = pts[0]
                pts.add((0.5f - (p0.second - 0.5f)).coerceIn(0.08f, 0.92f) to (0.5f + (p0.first - 0.5f)).coerceIn(0.08f, 0.92f))
            }

            val usedButtons = HashSet<ButtonType>()
            val curveSign = if (gi % 2 == 0) 1f else -1f
            for (q in 0 until m) {
                val o = outs[i + q]
                val (px, py) = pts[min(q, pts.size - 1)]
                var button = buttonFor(px, py)
                var guard = 0
                while (button in usedButtons && guard < 4) {
                    button = ButtonType.values()[(button.ordinal + 1) % 4]
                    guard++
                }
                usedButtons.add(button)

                val dx = px - 0.5f
                val dy = py - 0.5f
                val radius = sqrt(dx * dx + dy * dy)
                val entry = if (radius < 0.08f) (prevEntry + 90f) % 360f
                else ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f)
                if (q == 0) prevEntry = entry

                result.add(
                    Note(
                        timeMs = o.timeMs, lane = o.lane, holdDurationMs = o.holdMs,
                        x = px, y = py, entryAngleDeg = entry, curve = 0.35f * curveSign,
                        button = button, subdivision = o.sub,
                    )
                )
            }
            gi++
            i = j + 1
        }
        return result
    }

    private fun buttonFor(x: Float, y: Float): ButtonType {
        val dx = x - 0.5f
        val dy = y - 0.5f
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) ButtonType.CIRCLE else ButtonType.SQUARE
        } else {
            if (dy < 0) ButtonType.TRIANGLE else ButtonType.CROSS
        }
    }

    /** Smooth geometric paths over one bar; u in 0..1 is progress through the bar. */
    private fun pattern(p: Int, u: Float, flip: Int): Pair<Float, Float> {
        var x: Float
        var y: Float
        when (p) {
            0 -> { // arc
                x = 0.18f + 0.64f * u
                y = 0.62f - 0.30f * sin(PIf * u)
            }
            1 -> { // sine wave
                x = 0.15f + 0.70f * u
                y = 0.5f + 0.24f * sin(2f * PIf * 1.5f * u)
            }
            2 -> { // circle, starting at the top
                val th = -PIf / 2f + 2f * PIf * u
                x = 0.5f + 0.27f * cos(th)
                y = 0.5f + 0.27f * sin(th)
            }
            3 -> { // diagonal
                x = 0.18f + 0.64f * u
                y = 0.2f + 0.6f * u
            }
            4 -> { // V shape
                x = 0.2f + 0.6f * u
                y = 0.3f + 0.4f * abs(2f * u - 1f)
            }
            else -> { // figure-8
                x = 0.5f + 0.30f * sin(2f * PIf * u)
                y = 0.5f + 0.22f * sin(4f * PIf * u)
            }
        }
        if ((flip and 1) != 0) x = 1f - x
        if ((flip and 2) != 0) y = 1f - y
        return x to y
    }
}
