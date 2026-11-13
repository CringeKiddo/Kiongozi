package com.kiongozi.app.game.diva

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.SparseIntArray
import android.view.MotionEvent
import com.kiongozi.app.game.EventKind
import com.kiongozi.app.game.GameSession
import com.kiongozi.app.game.GameSurfaceView
import com.kiongozi.app.game.Judgment
import com.kiongozi.app.game.JudgmentEvent
import com.kiongozi.app.game.TrackedNote
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Gameplay engine B: Project DIVA-style flying targets.
 *
 *  - Each note is a stationary target silhouette (triangle / circle / cross / square) with a
 *    clock-hand timer ring that closes in and completes exactly at the note time.
 *  - A matching coloured icon flies along a quadratic Bezier arc and reaches the target at the note time.
 *    Its position is p = 1 - (noteTime - songTime) / approachMs, i.e. a pure function of the audio clock.
 *  - Four big thumb buttons sit in the bottom corners; press the button that matches the target's shape.
 */
@SuppressLint("ViewConstructor")
class DivaGameView(context: Context, session: GameSession) : GameSurfaceView(context, session) {

    // ButtonType order: 0 TRIANGLE, 1 CIRCLE, 2 CROSS, 3 SQUARE
    private val btnColors = intArrayOf(0xFF4DE08A.toInt(), 0xFFFF4D5E.toInt(), 0xFF4DA3FF.toInt(), 0xFFFF7AD9.toInt())

    // geometry
    private var playLeft = 0f
    private var playTop = 0f
    private var playW = 0f
    private var playH = 0f
    private var targetR = 0f
    private var btnR = 0f
    private val btnX = FloatArray(4)
    private val btnY = FloatArray(4)
    private var flyDist = 0f

    // input / feedback state
    private val pointerBtn = SparseIntArray()
    private val btnDown = IntArray(4)
    private val btnGlow = FloatArray(4)
    private var firstIdx = 0
    private var lastSongMs = 0.0

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()
    private val pos = FloatArray(2)

    override fun onSizeKnown() {
        val portrait = h >= w
        btnR = min(w, h) * (if (portrait) 0.125f else 0.105f)
        val mX = 14f * dp
        val bottomPad = 22f * dp
        btnX[0] = mX + btnR;            btnY[0] = h - bottomPad - btnR * 3.3f   // triangle (left, upper)
        btnX[3] = mX + btnR * 2.9f;     btnY[3] = h - bottomPad - btnR          // square   (left, lower)
        btnX[1] = w - mX - btnR;        btnY[1] = h - bottomPad - btnR * 3.3f   // circle   (right, upper)
        btnX[2] = w - mX - btnR * 2.9f; btnY[2] = h - bottomPad - btnR          // cross    (right, lower)

        if (portrait) {
            playLeft = w * 0.09f
            playTop = topInset + 104f * dp
            playW = w * 0.82f
            playH = max(btnY[0] - btnR - 28f * dp - playTop, h * 0.25f)
        } else {
            playLeft = w * 0.2f
            playTop = topInset + 44f * dp
            playW = w * 0.6f
            playH = h * 0.78f - playTop
        }
        targetR = (min(playW, playH) * 0.085f).coerceIn(22f * dp, 40f * dp)
        flyDist = max(w, h) * 0.62f
    }

    // ----------------------------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------------------------
    override fun drawGame(canvas: Canvas, songMs: Double, dtSec: Float) {
        if (btnR <= 0f) return
        if (songMs < lastSongMs - 500.0) firstIdx = 0 // restarted
        lastSongMs = songMs

        val notes = judgment.notes
        val approach = session.approachMs.toDouble()
        while (firstIdx < notes.size) {
            val n = notes[firstIdx]
            if (n.done && songMs - n.resolvedAtMs > 600.0) firstIdx++ else break
        }

        // pass 1: targets, timer rings, hold / hit / miss states
        for (i in firstIdx until notes.size) {
            val n = notes[i]
            val lead = n.timeMs - songMs
            if (lead > approach) break
            drawTarget(canvas, n, songMs, lead, approach)
        }
        // pass 2: flying icons on top
        for (i in firstIdx until notes.size) {
            val n = notes[i]
            val lead = n.timeMs - songMs
            if (lead > approach) break
            if (!n.headJudged) drawFlyingIcon(canvas, n, lead, approach)
        }

        drawThumbButtons(canvas, dtSec)
    }

    private fun targetXY(n: TrackedNote, out: FloatArray) {
        out[0] = playLeft + n.note.x * playW
        out[1] = playTop + n.note.y * playH
    }

    private fun drawTarget(canvas: Canvas, n: TrackedNote, songMs: Double, lead: Double, approach: Double) {
        targetXY(n, pos)
        val tx = pos[0]
        val ty = pos[1]
        val b = n.track
        val color = btnColors[b]
        val R = targetR

        if (!n.headJudged) {
            val appear = ((approach - lead) / (approach * 0.25)).coerceIn(0.0, 1.0).toFloat()
            val p = (1.0 - lead / approach).coerceIn(0.0, 1.0).toFloat()
            // faint fill + silhouette outline
            drawShape(canvas, b, tx, ty, R, color, true, 0f, (40 * appear).toInt())
            drawShape(canvas, b, tx, ty, R, color, false, 3.5f * dp, (235 * appear).toInt())
            if (n.isHold) {
                shapePaint.style = Paint.Style.STROKE
                shapePaint.strokeWidth = 2f * dp
                shapePaint.color = color
                shapePaint.alpha = (150 * appear).toInt()
                canvas.drawCircle(tx, ty, R * 1.18f, shapePaint)
                textPaint.color = color; textPaint.alpha = (220 * appear).toInt(); textPaint.textSize = 10f * dp
                canvas.drawText("HOLD", tx, ty + R * 2.05f, textPaint)
            }
            // timer ring closing in + clock hand sweeping a full circle, finishing exactly at the note time
            val ringR = R * (1.22f + 1.5f * (1f - p))
            shapePaint.style = Paint.Style.STROKE
            shapePaint.strokeWidth = 2.2f * dp
            shapePaint.color = Color.WHITE
            shapePaint.alpha = (90 + 90 * p).toInt()
            canvas.drawCircle(tx, ty, ringR, shapePaint)
            rect.set(tx - ringR, ty - ringR, tx + ringR, ty + ringR)
            shapePaint.strokeWidth = 4.5f * dp
            shapePaint.color = color
            shapePaint.alpha = 235
            canvas.drawArc(rect, -90f, 360f * p, false, shapePaint)
            val a = Math.toRadians((-90.0 + 360.0 * p))
            shapePaint.strokeWidth = 2.4f * dp
            shapePaint.color = Color.WHITE
            shapePaint.alpha = 235
            canvas.drawLine(tx, ty, tx + (cos(a) * ringR).toFloat(), ty + (sin(a) * ringR).toFloat(), shapePaint)
            return
        }

        val j = n.headJudgment
        if (j == Judgment.MISS) {
            val age = songMs - (n.timeMs + 135.0)
            if (age in 0.0..350.0) {
                val k = (1.0 - age / 350.0).toFloat()
                drawShape(canvas, b, tx, ty, R, Color.rgb(120, 114, 140), false, 3f * dp, (200 * k).toInt())
            }
            return
        }
        if (n.isHold && n.holding) {
            // glowing hold: filled target + progress arc until the release point
            drawShape(canvas, b, tx, ty, R, color, true, 0f, 150)
            drawShape(canvas, b, tx, ty, R, color, false, 4f * dp, 255)
            val prog = ((songMs - n.timeMs) / (n.endMs - n.timeMs)).coerceIn(0.0, 1.0).toFloat()
            val rr = R * 1.55f
            rect.set(tx - rr, ty - rr, tx + rr, ty + rr)
            shapePaint.style = Paint.Style.STROKE
            shapePaint.strokeWidth = 4.5f * dp
            shapePaint.color = Color.WHITE
            shapePaint.alpha = 90
            canvas.drawCircle(tx, ty, rr, shapePaint)
            shapePaint.color = color
            shapePaint.alpha = 255
            canvas.drawArc(rect, -90f, 360f * prog, false, shapePaint)
            return
        }
        // hit flash: expanding ring (cosmetic, driven by the audio clock)
        val age = songMs - n.resolvedAtMs
        if (age in 0.0..280.0) {
            val k = (age / 280.0).toFloat()
            shapePaint.style = Paint.Style.STROKE
            shapePaint.strokeWidth = 4f * dp * (1f - k)
            shapePaint.color = Color.WHITE
            shapePaint.alpha = (230 * (1f - k)).toInt()
            canvas.drawCircle(tx, ty, R * (1f + 1.1f * k), shapePaint)
            drawShape(canvas, b, tx, ty, R * (1f + 0.25f * k), color, true, 0f, (180 * (1f - k)).toInt())
        }
    }

    private fun drawFlyingIcon(canvas: Canvas, n: TrackedNote, lead: Double, approach: Double) {
        targetXY(n, pos)
        val tx = pos[0]
        val ty = pos[1]
        val p = (1.0 - lead / approach).coerceIn(0.0, 1.2).toFloat()   // linear in TIME -> arrives exactly on the beat
        val b = n.track
        val color = btnColors[b]
        val fade = min(1f, p / 0.10f)

        // trail
        for (k in 3 downTo 1) {
            val pp = p - k * 0.035f
            if (pp < 0f) continue
            bezier(n, pp, tx, ty, pos)
            shapePaint.style = Paint.Style.FILL
            shapePaint.color = color
            shapePaint.alpha = (70 * fade * (1f - k / 4f)).toInt()
            canvas.drawCircle(pos[0], pos[1], targetR * (0.78f - 0.14f * k), shapePaint)
        }
        bezier(n, p, tx, ty, pos)
        val ix = pos[0]
        val iy = pos[1]
        drawShape(canvas, b, ix, iy, targetR * 1.25f, color, true, 0f, (55 * fade).toInt())   // glow
        drawShape(canvas, b, ix, iy, targetR * 0.88f, color, true, 0f, (255 * fade).toInt())
        drawShape(canvas, b, ix, iy, targetR * 0.88f, Color.WHITE, false, 2f * dp, (230 * fade).toInt())
    }

    /** Quadratic Bezier from an off-screen spawn point to the target; the bend comes from the chart's curve. */
    private fun bezier(n: TrackedNote, p: Float, tx: Float, ty: Float, out: FloatArray) {
        val ang = Math.toRadians(n.note.entryAngleDeg.toDouble())
        val dx = cos(ang).toFloat()
        val dy = sin(ang).toFloat()
        val sx = tx + dx * flyDist
        val sy = ty + dy * flyDist
        val cx = (sx + tx) / 2f + (-dy) * n.note.curve * flyDist * 0.9f
        val cy = (sy + ty) / 2f + dx * n.note.curve * flyDist * 0.9f
        val u = 1f - p
        out[0] = u * u * sx + 2f * u * p * cx + p * p * tx
        out[1] = u * u * sy + 2f * u * p * cy + p * p * ty
    }

    /** Button shapes: 0 triangle, 1 circle, 2 cross, 3 square. */
    private fun drawShape(c: Canvas, b: Int, cx: Float, cy: Float, r: Float, color: Int, filled: Boolean, sw: Float, alpha: Int) {
        if (alpha <= 0) return
        shapePaint.color = color
        shapePaint.alpha = alpha.coerceIn(0, 255)
        shapePaint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        shapePaint.strokeWidth = sw
        when (b) {
            1 -> c.drawCircle(cx, cy, r, shapePaint)
            3 -> {
                rect.set(cx - r * 0.88f, cy - r * 0.88f, cx + r * 0.88f, cy + r * 0.88f)
                c.drawRoundRect(rect, r * 0.18f, r * 0.18f, shapePaint)
            }
            0 -> {
                path.reset()
                path.moveTo(cx, cy - r * 1.05f)
                path.lineTo(cx + r * 0.98f, cy + r * 0.72f)
                path.lineTo(cx - r * 0.98f, cy + r * 0.72f)
                path.close()
                c.drawPath(path, shapePaint)
            }
            else -> {
                shapePaint.style = Paint.Style.STROKE
                shapePaint.strokeWidth = if (filled) r * 0.5f else sw
                val d = r * 0.8f
                c.drawLine(cx - d, cy - d, cx + d, cy + d, shapePaint)
                c.drawLine(cx - d, cy + d, cx + d, cy - d, shapePaint)
            }
        }
    }

    private fun drawThumbButtons(canvas: Canvas, dtSec: Float) {
        for (b in 0 until 4) {
            btnGlow[b] = if (btnDown[b] > 0) min(1f, btnGlow[b] + dtSec * 16f) else max(0f, btnGlow[b] - dtSec * 5f)
            val g = btnGlow[b]
            val cx = btnX[b]
            val cy = btnY[b]
            val c = btnColors[b]
            // glow halo
            shapePaint.style = Paint.Style.FILL
            shapePaint.color = c
            shapePaint.alpha = (110 * g).toInt()
            canvas.drawCircle(cx, cy, btnR * (1.12f + 0.18f * g), shapePaint)
            // body
            shapePaint.color = Color.argb(150, 6, 2, 20)
            canvas.drawCircle(cx, cy, btnR, shapePaint)
            shapePaint.color = c
            shapePaint.alpha = (70 + 120 * g).toInt()
            canvas.drawCircle(cx, cy, btnR, shapePaint)
            shapePaint.style = Paint.Style.STROKE
            shapePaint.strokeWidth = 3f * dp
            shapePaint.color = c
            shapePaint.alpha = 255
            canvas.drawCircle(cx, cy, btnR, shapePaint)
            drawShape(canvas, b, cx, cy, btnR * 0.42f, Color.WHITE, false, 4.5f * dp, (200 + 55 * g).toInt())
        }
    }

    // ----------------------------------------------------------------------------------------
    // Judgment feedback
    // ----------------------------------------------------------------------------------------
    override fun onEvent(ev: JudgmentEvent) {
        val n = judgment.notes.getOrNull(ev.noteIndex) ?: return
        targetXY(n, pos)
        val color = btnColors[n.track.coerceIn(0, 3)]
        popupFor(ev, pos[0], pos[1] - targetR * 1.7f, color)
        if (ev.kind == EventKind.HIT || ev.kind == EventKind.TAIL_OK) btnGlow[n.track.coerceIn(0, 3)] = 1f
    }

    // ----------------------------------------------------------------------------------------
    // Touch: four thumb buttons, multi-touch, hold support
    // ----------------------------------------------------------------------------------------
    private fun buttonAt(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (b in 0 until 4) {
            val d = hypot(x - btnX[b], y - btnY[b])
            if (d <= btnR * 1.3f && d < bestD) {
                best = b
                bestD = d
            }
        }
        return best
    }

    private fun press(pointerId: Int, x: Float, y: Float, eventTimeMs: Long) {
        val b = buttonAt(x, y)
        if (b < 0) return
        pointerBtn.put(pointerId, b)
        btnDown[b]++
        judgment.onPress(b, sync.songTimeAtUptimeMs(eventTimeMs))
    }

    private fun release(pointerId: Int, eventTimeMs: Long) {
        val idx = pointerBtn.indexOfKey(pointerId)
        if (idx < 0) return
        val b = pointerBtn.valueAt(idx)
        pointerBtn.removeAt(idx)
        btnDown[b] = max(0, btnDown[b] - 1)
        if (btnDown[b] == 0) judgment.onRelease(b, sync.songTimeAtUptimeMs(eventTimeMs))
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
                for (k in pointerBtn.size() - 1 downTo 0) release(pointerBtn.keyAt(k), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val cur = pointerBtn.get(id, -1)
                    if (cur < 0) continue
                    val now = buttonAt(e.getX(i), e.getY(i))
                    if (now != cur) {
                        release(id, e.eventTime)
                        if (now >= 0) press(id, e.getX(i), e.getY(i), e.eventTime)
                    }
                }
            }
        }
        return true
    }
}
