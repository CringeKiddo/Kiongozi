package com.kiongozi.app.game.lanes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.SparseIntArray
import android.view.MotionEvent
import com.kiongozi.app.game.EventKind
import com.kiongozi.app.game.GameSession
import com.kiongozi.app.game.GameSurfaceView
import com.kiongozi.app.game.Judgment
import com.kiongozi.app.game.JudgmentEvent
import com.kiongozi.app.game.LaneView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Gameplay engine A: KALPA-style 5-lane scroller, two looks:
 *   TILTED (default): a perspective highway that fills the screen width at the judgment line and
 *                     narrows toward the top, like the original game.
 *   FLAT:             straight top-down lanes.
 *
 * Both use the same rule: where a note is drawn depends ONLY on (noteTime - songTime), which comes
 * from the audio clock. FPS and view mode never change timing. Judged at each touch event's own timestamp.
 */
@SuppressLint("ViewConstructor")
class KalpaGameView(context: Context, session: GameSession) : GameSurfaceView(context, session) {

    private val laneColors = intArrayOf(
        0xFF00F0FF.toInt(), 0xFF6C7BFF.toInt(), 0xFFFF2BD6.toInt(), 0xFFFFA23B.toInt(), 0xFFB6FF3B.toInt(),
    )

    // ---- geometry ----
    private var persp = true
    private var cx = 0f
    private var laneW = 0f            // lane width AT the judgment line
    private var judgeY = 0f
    private var noteH = 0f
    private var pxPerMs = 0f          // flat mode only
    private var vpY = 0f              // perspective vanishing point (above the screen)
    private var kPersp = 4f           // depth strength: spawn scale = 1/(1+k)
    private var sTop = 1f             // lane scale at screen top / bottom
    private var sBottom = 1f
    private var beamTopY = 0f
    private var beamS = 1f
    private var beamShaders: Array<Shader?> = arrayOfNulls(5)

    // ---- input / feedback state ----
    private val pointerLane = SparseIntArray()
    private val laneDown = IntArray(5)
    private val beamAlpha = FloatArray(5)

    // ---- reusable drawing objects ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val beamPaint = Paint()
    private val rect = RectF()
    private val path = Path()
    private var firstIdx = 0
    private var lastSongMs = 0.0

    override fun onSizeKnown() {
        persp = session.settings.laneView == LaneView.PERSPECTIVE
        cx = w / 2f
        laneW = min(w / 5f, h * 0.34f)
        judgeY = if (persp) h * 0.86f else h * 0.80f
        noteH = max(laneW * 0.30f, 20f * dp)
        pxPerMs = (judgeY + noteH) / session.approachMs
        if (persp) {
            vpY = -h * 0.25f
            kPersp = 1f / 0.2f - 1f
            sTop = (0f - vpY) / (judgeY - vpY)
            sBottom = (h - vpY) / (judgeY - vpY)
            beamS = sOf(session.approachMs * 0.55)
            beamTopY = yOf(session.approachMs * 0.55, beamS)
        } else {
            sTop = 1f
            sBottom = 1f
            beamS = 1f
            beamTopY = judgeY - h * 0.42f
        }
        for (l in 0 until 5) {
            val c = laneColors[l]
            beamShaders[l] = LinearGradient(
                0f, judgeY, 0f, beamTopY,
                intArrayOf(Color.argb(150, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))),
                null, Shader.TileMode.CLAMP,
            )
        }
    }

    // ----------------------------------------------------------------------------------------
    // Projection: time-to-hit (ms) -> scale + screen y. Pure function of the audio clock.
    // ----------------------------------------------------------------------------------------
    private fun sOf(timeToHitMs: Double): Float {
        if (!persp) return 1f
        val z = (timeToHitMs / session.approachMs).toFloat()
        return if (z >= 0f) 1f / (1f + kPersp * z) else 1f - kPersp * z   // smooth continuation past the line
    }

    private fun yOf(timeToHitMs: Double, s: Float): Float =
        if (persp) vpY + (judgeY - vpY) * s else (judgeY - timeToHitMs * pxPerMs).toFloat()

    /** x of lane boundary b (0..5) at scale s. */
    private fun xOf(boundary: Float, s: Float): Float = cx + (boundary - 2.5f) * laneW * s

    // ----------------------------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------------------------
    override fun drawGame(canvas: Canvas, songMs: Double, dtSec: Float) {
        if (laneW <= 0f) return
        drawHighway(canvas)

        // light beams (fade with real time, not frames)
        for (l in 0 until 5) {
            beamAlpha[l] = if (laneDown[l] > 0) min(1f, beamAlpha[l] + dtSec * 14f) else max(0f, beamAlpha[l] - dtSec * 5f)
            if (beamAlpha[l] > 0.01f) {
                path.reset()
                path.moveTo(xOf(l.toFloat(), 1f), judgeY)
                path.lineTo(xOf(l + 1f, 1f), judgeY)
                path.lineTo(xOf(l + 1f, beamS), beamTopY)
                path.lineTo(xOf(l.toFloat(), beamS), beamTopY)
                path.close()
                beamPaint.shader = beamShaders[l]
                beamPaint.alpha = (255 * beamAlpha[l]).toInt()
                canvas.drawPath(path, beamPaint)
            }
        }

        drawJudgmentLine(canvas)
        drawNotes(canvas, songMs)
    }

    private fun drawHighway(canvas: Canvas) {
        val yTop = 0f
        val yBot = h.toFloat()
        fill.shader = null
        // dark backing
        fill.color = Color.argb(125, 3, 1, 12)
        path.reset()
        path.moveTo(xOf(0f, sTop), yTop); path.lineTo(xOf(5f, sTop), yTop)
        path.lineTo(xOf(5f, sBottom), yBot); path.lineTo(xOf(0f, sBottom), yBot)
        path.close()
        canvas.drawPath(path, fill)

        // per-lane tint (brighter while pressed)
        for (l in 0 until 5) {
            val c = laneColors[l]
            fill.color = Color.argb(if (laneDown[l] > 0) 52 else 18, Color.red(c), Color.green(c), Color.blue(c))
            path.reset()
            path.moveTo(xOf(l.toFloat(), sTop), yTop); path.lineTo(xOf(l + 1f, sTop), yTop)
            path.lineTo(xOf(l + 1f, sBottom), yBot); path.lineTo(xOf(l.toFloat(), sBottom), yBot)
            path.close()
            canvas.drawPath(path, fill)
        }

        // lane dividers
        stroke.strokeWidth = 1.2f * dp
        stroke.color = Color.argb(60, 255, 255, 255)
        for (b in 1..4) canvas.drawLine(xOf(b.toFloat(), sTop), yTop, xOf(b.toFloat(), sBottom), yBot, stroke)
        // glowing side rails
        for (edge in intArrayOf(0, 5)) {
            stroke.color = Color.argb(40, 0, 240, 255); stroke.strokeWidth = 8f * dp
            canvas.drawLine(xOf(edge.toFloat(), sTop), yTop, xOf(edge.toFloat(), sBottom), yBot, stroke)
            stroke.color = Color.argb(200, 0, 240, 255); stroke.strokeWidth = 2f * dp
            canvas.drawLine(xOf(edge.toFloat(), sTop), yTop, xOf(edge.toFloat(), sBottom), yBot, stroke)
        }
    }

    private fun drawJudgmentLine(canvas: Canvas) {
        val x0 = xOf(0f, 1f)
        val x1 = xOf(5f, 1f)
        // layered strokes fake a glow (blur filters are unreliable on hardware canvases)
        stroke.color = Color.argb(30, 0, 240, 255); stroke.strokeWidth = 22f * dp
        canvas.drawLine(x0, judgeY, x1, judgeY, stroke)
        stroke.color = Color.argb(70, 0, 240, 255); stroke.strokeWidth = 11f * dp
        canvas.drawLine(x0, judgeY, x1, judgeY, stroke)
        stroke.color = Color.argb(255, 210, 252, 255); stroke.strokeWidth = 3f * dp
        canvas.drawLine(x0, judgeY, x1, judgeY, stroke)

        for (l in 0 until 5) {
            val lx = xOf(l + 0.5f, 1f)
            val c = laneColors[l]
            stroke.strokeWidth = 2.5f * dp
            stroke.color = Color.argb(if (laneDown[l] > 0) 255 else 110, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawCircle(lx, judgeY, laneW * (if (laneDown[l] > 0) 0.34f else 0.28f), stroke)
        }
    }

    private fun drawNotes(canvas: Canvas, songMs: Double) {
        val notes = judgment.notes
        val approach = session.approachMs.toDouble()
        if (songMs < lastSongMs - 500.0) firstIdx = 0 // restarted
        lastSongMs = songMs
        while (firstIdx < notes.size) {
            val n = notes[firstIdx]
            if (n.done && songMs - n.endMs > 450.0) firstIdx++ else break
        }

        for (i in firstIdx until notes.size) {
            val n = notes[i]
            val tHead = n.timeMs - songMs
            if (tHead > approach * 1.08) break // sorted by time: everything after is further away

            val lane = n.track
            val c = laneColors[lane]
            val sH = sOf(tHead)
            val yH = yOf(tHead, sH)
            val x = xOf(lane + 0.5f, if (n.holding) 1f else sH)

            if (n.isHold) {
                if (n.tailResolved && !n.broken && !n.holding) continue // completed
                val tTail = n.endMs - songMs
                val sT = sOf(tTail)
                val yT = yOf(tTail, sT)
                val dead = n.broken || (n.headJudged && n.headJudgment == Judgment.MISS)
                val sHead = if (n.holding) 1f else sH
                val yHead = if (n.holding) judgeY else yH
                drawHold(canvas, n.holding, dead, lane, sHead, yHead, sT, yT, c)
            }
            if (!n.headJudged || (n.isHold && n.holding)) {
                val s = if (n.holding) 1f else sH
                val y = if (n.holding) judgeY else yH
                drawHead(canvas, x, y, s, c, 1f)
            } else if (n.headJudgment == Judgment.MISS && yH < h + noteH) {
                drawHead(canvas, x, yH, sH, Color.rgb(110, 105, 130), 0.55f) // missed notes keep falling, greyed
            }
        }
    }

    private fun drawHead(canvas: Canvas, cxNote: Float, y: Float, s: Float, c: Int, a: Float) {
        val hw = laneW * 0.43f * s
        val hh = max(noteH * s * (if (persp) 0.9f else 1f), 4f * dp)
        val r = hh * 0.38f
        val g = 5f * dp * s
        fill.color = Color.argb((60 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        rect.set(cxNote - hw - g, y - hh / 2 - g, cxNote + hw + g, y + hh / 2 + g)
        canvas.drawRoundRect(rect, r + g, r + g, fill)
        fill.color = Color.argb((255 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        rect.set(cxNote - hw, y - hh / 2, cxNote + hw, y + hh / 2)
        canvas.drawRoundRect(rect, r, r, fill)
        fill.color = Color.argb((190 * a).toInt(), 255, 255, 255)
        rect.set(cxNote - hw + 5f * dp * s, y - hh / 2 + 3f * dp * s, cxNote + hw - 5f * dp * s, y - hh / 2 + 3f * dp * s + max(2f * dp * s, hh * 0.16f))
        canvas.drawRoundRect(rect, 3f * dp * s, 3f * dp * s, fill)
    }

    private fun drawHold(
        canvas: Canvas, holding: Boolean, dead: Boolean, lane: Int,
        sHead: Float, yHead: Float, sTail: Float, yTail: Float, c: Int,
    ) {
        if (yHead <= yTail) return
        val color = if (dead) Color.rgb(110, 105, 130) else c
        val aBody = if (dead) 60 else if (holding) 200 else 120
        val half = laneW * 0.26f
        val xh = xOf(lane + 0.5f, sHead)
        val xt = xOf(lane + 0.5f, sTail)

        fun ribbon(widthMul: Float, extra: Float) {
            path.reset()
            path.moveTo(xt - (half * widthMul) * sTail - extra, yTail)
            path.lineTo(xt + (half * widthMul) * sTail + extra, yTail)
            path.lineTo(xh + (half * widthMul) * sHead + extra, yHead)
            path.lineTo(xh - (half * widthMul) * sHead - extra, yHead)
            path.close()
            canvas.drawPath(path, fill)
        }
        // outer glow
        fill.color = Color.argb(if (holding) 70 else 36, Color.red(color), Color.green(color), Color.blue(color))
        ribbon(1f, 6f * dp * sHead)
        // body
        fill.color = Color.argb(aBody, Color.red(color), Color.green(color), Color.blue(color))
        ribbon(1f, 0f)
        // bright core
        fill.color = Color.argb(if (holding) 230 else 110, 255, 255, 255)
        ribbon(0.28f, 0f)
        // tail cap
        if (yTail > -noteH * 2) {
            val capH = max(noteH * sTail * 0.44f, 3f * dp)
            val capW = (half + 3f * dp) * sTail
            fill.color = Color.argb(if (dead) 80 else 255, Color.red(color), Color.green(color), Color.blue(color))
            rect.set(xt - capW, yTail - capH / 2, xt + capW, yTail + capH / 2)
            canvas.drawRoundRect(rect, capH * 0.45f, capH * 0.45f, fill)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Judgment feedback
    // ----------------------------------------------------------------------------------------
    override fun onEvent(ev: JudgmentEvent) {
        if (laneW <= 0f) return
        val lane = ev.track.coerceIn(0, 4)
        popupFor(ev, xOf(lane + 0.5f, 1f), judgeY - 64f * dp, laneColors[lane])
        if (ev.kind == EventKind.HIT || ev.kind == EventKind.TAIL_OK) beamAlpha[lane] = 1f
    }

    // ----------------------------------------------------------------------------------------
    // Touch: all 5 lanes, any number of fingers. In tilted view the lanes narrow toward the top,
    // so a touch is mapped using the lane width at the touch's own height (what you see is what you hit).
    // ----------------------------------------------------------------------------------------
    private fun laneAt(x: Float, y: Float): Int {
        if (laneW <= 0f) return -1
        val s = if (persp) ((y - vpY) / (judgeY - vpY)).coerceIn(0.35f, 1.4f) else 1f
        val l = floor((x - cx) / (laneW * s) + 2.5f).toInt()
        return if (l in 0..4) l else -1
    }

    private fun press(pointerId: Int, x: Float, y: Float, eventTimeMs: Long) {
        val lane = laneAt(x, y)
        if (lane < 0) return
        pointerLane.put(pointerId, lane)
        laneDown[lane]++
        judgment.onPress(lane, sync.songTimeAtUptimeMs(eventTimeMs))
    }

    private fun release(pointerId: Int, eventTimeMs: Long) {
        val idx = pointerLane.indexOfKey(pointerId)
        if (idx < 0) return
        val lane = pointerLane.valueAt(idx)
        pointerLane.removeAt(idx)
        laneDown[lane] = max(0, laneDown[lane] - 1)
        if (laneDown[lane] == 0) judgment.onRelease(lane, sync.songTimeAtUptimeMs(eventTimeMs))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                press(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex), e.eventTime)
            MotionEvent.ACTION_CANCEL -> {
                for (k in pointerLane.size() - 1 downTo 0) release(pointerLane.keyAt(k), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val cur = pointerLane.get(id, -1)
                    val now = laneAt(e.getX(i), e.getY(i))
                    if (cur >= 0 && now != cur) {
                        release(id, e.eventTime)
                        if (now >= 0) press(id, e.getX(i), e.getY(i), e.eventTime)
                    }
                }
            }
        }
        return true
    }
}
