package com.kiongozi.app.game.lanes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.SparseIntArray
import android.view.MotionEvent
import com.kiongozi.app.game.EventKind
import com.kiongozi.app.game.GameSession
import com.kiongozi.app.game.GameSurfaceView
import com.kiongozi.app.game.JudgmentEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Gameplay engine A: KALPA-style 5-lane vertical scroller.
 *
 * y = judgeY - (noteTime - songTime) * pxPerMs  -> position depends ONLY on the audio clock.
 * Multi-touch: every finger tracks its own lane; sliding to another lane releases the old lane and
 * presses the new one. Input is judged at the touch event's own timestamp, not the frame time.
 */
@SuppressLint("ViewConstructor")
class KalpaGameView(context: Context, session: GameSession) : GameSurfaceView(context, session) {

    private val laneColors = intArrayOf(
        0xFF00F0FF.toInt(), 0xFF6C7BFF.toInt(), 0xFFFF2BD6.toInt(), 0xFFFFA23B.toInt(), 0xFFB6FF3B.toInt(),
    )

    // geometry (computed in onSizeKnown)
    private var areaX = 0f
    private var laneW = 0f
    private var judgeY = 0f
    private var noteH = 0f
    private var pxPerMs = 0f
    private var beamShaders: Array<Shader?> = arrayOfNulls(5)

    // input state
    private val pointerLane = SparseIntArray()
    private val laneDown = IntArray(5)
    private val beamAlpha = FloatArray(5)

    // reusable paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val beamPaint = Paint()
    private val rect = RectF()
    private var firstIdx = 0
    private var lastSongMs = 0.0

    override fun onSizeKnown() {
        val areaW = min(w * 0.98f, h * 0.9f)
        areaX = (w - areaW) / 2f
        laneW = areaW / 5f
        judgeY = h * 0.80f
        noteH = max(laneW * 0.30f, 20f * dp)
        pxPerMs = (judgeY + noteH) / session.approachMs
        val beamTop = judgeY - h * 0.42f
        for (l in 0 until 5) {
            val c = laneColors[l]
            beamShaders[l] = LinearGradient(
                0f, judgeY, 0f, beamTop,
                intArrayOf(Color.argb(150, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))),
                null, Shader.TileMode.CLAMP,
            )
        }
    }

    // ----------------------------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------------------------
    override fun drawGame(canvas: Canvas, songMs: Double, dtSec: Float) {
        if (laneW <= 0f) return

        // lane backing
        fill.shader = null
        fill.color = Color.argb(120, 3, 1, 12)
        canvas.drawRect(areaX, 0f, areaX + laneW * 5, h.toFloat(), fill)
        for (l in 0 until 5) {
            val c = laneColors[l]
            fill.color = Color.argb(if (laneDown[l] > 0) 46 else 16, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawRect(areaX + l * laneW, 0f, areaX + (l + 1) * laneW, h.toFloat(), fill)
        }
        stroke.strokeWidth = 1.2f * dp
        stroke.color = Color.argb(60, 255, 255, 255)
        for (l in 0..5) {
            val x = areaX + l * laneW
            canvas.drawLine(x, 0f, x, h.toFloat(), stroke)
        }

        // light beams (fade with real time, not frames)
        for (l in 0 until 5) {
            beamAlpha[l] = if (laneDown[l] > 0) min(1f, beamAlpha[l] + dtSec * 14f) else max(0f, beamAlpha[l] - dtSec * 5f)
            if (beamAlpha[l] > 0.01f) {
                beamPaint.shader = beamShaders[l]
                beamPaint.alpha = (255 * beamAlpha[l]).toInt()
                canvas.drawRect(areaX + l * laneW, judgeY - h * 0.42f, areaX + (l + 1) * laneW, judgeY, beamPaint)
            }
        }

        drawJudgmentLine(canvas)
        drawNotes(canvas, songMs)
    }

    private fun drawJudgmentLine(canvas: Canvas) {
        val x0 = areaX
        val x1 = areaX + laneW * 5
        // layered strokes fake a glow (blur filters are unreliable on hardware canvases)
        stroke.color = Color.argb(30, 0, 240, 255); stroke.strokeWidth = 22f * dp
        canvas.drawLine(x0, judgeY, x1, judgeY, stroke)
        stroke.color = Color.argb(70, 0, 240, 255); stroke.strokeWidth = 11f * dp
        canvas.drawLine(x0, judgeY, x1, judgeY, stroke)
        stroke.color = Color.argb(255, 210, 252, 255); stroke.strokeWidth = 3f * dp
        canvas.drawLine(x0, judgeY, x1, judgeY, stroke)

        // lane hit rings
        for (l in 0 until 5) {
            val cx = areaX + (l + 0.5f) * laneW
            val c = laneColors[l]
            stroke.strokeWidth = 2.5f * dp
            stroke.color = Color.argb(if (laneDown[l] > 0) 255 else 110, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawCircle(cx, judgeY, laneW * (if (laneDown[l] > 0) 0.34f else 0.28f), stroke)
        }
    }

    private fun drawNotes(canvas: Canvas, songMs: Double) {
        val notes = judgment.notes
        // advance the start pointer past notes that are fully gone (sorted by time, so this is O(1) amortised)
        while (firstIdx < notes.size) {
            val n = notes[firstIdx]
            val endY = judgeY - (n.endMs - songMs) * pxPerMs
            if (n.done && endY > h + noteH * 2) firstIdx++ else if (endY > h + noteH * 4) firstIdx++ else break
        }
        if (songMs < lastSongMs - 500.0) firstIdx = 0 // restarted
        lastSongMs = songMs

        for (i in firstIdx until notes.size) {
            val n = notes[i]
            val headY = (judgeY - (n.timeMs - songMs) * pxPerMs).toFloat()
            if (headY < -noteH * 2) break // sorted by time: everything after is even further up
            val tailY = (judgeY - (n.endMs - songMs) * pxPerMs).toFloat()
            val lane = n.track
            val c = laneColors[lane]
            val cx = areaX + (lane + 0.5f) * laneW

            if (n.isHold) {
                if (n.tailResolved && !n.broken && !n.holding) continue // completed
                drawHold(canvas, n.holding, n.broken || (n.headJudged && n.headJudgment == com.kiongozi.app.game.Judgment.MISS), cx, headY, tailY, c)
            }
            if (!n.headJudged || (n.isHold && n.holding)) {
                val y = if (n.holding) judgeY else headY
                drawHead(canvas, cx, y, c, 1f)
            } else if (n.headJudgment == com.kiongozi.app.game.Judgment.MISS && n.resolvedAtMs > 0 && headY < h + noteH) {
                drawHead(canvas, cx, headY, Color.rgb(110, 105, 130), 0.55f) // missed notes keep falling, greyed
            }
        }
    }

    private fun drawHead(canvas: Canvas, cx: Float, y: Float, c: Int, a: Float) {
        val hw = laneW * 0.43f
        val r = noteH * 0.38f
        // glow
        fill.color = Color.argb((60 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        rect.set(cx - hw - 5f * dp, y - noteH / 2 - 5f * dp, cx + hw + 5f * dp, y + noteH / 2 + 5f * dp)
        canvas.drawRoundRect(rect, r + 4f * dp, r + 4f * dp, fill)
        // body
        fill.color = Color.argb((255 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        rect.set(cx - hw, y - noteH / 2, cx + hw, y + noteH / 2)
        canvas.drawRoundRect(rect, r, r, fill)
        // highlight
        fill.color = Color.argb((190 * a).toInt(), 255, 255, 255)
        rect.set(cx - hw + 5f * dp, y - noteH / 2 + 3f * dp, cx + hw - 5f * dp, y - noteH / 2 + 3f * dp + max(2f * dp, noteH * 0.16f))
        canvas.drawRoundRect(rect, 3f * dp, 3f * dp, fill)
    }

    private fun drawHold(canvas: Canvas, holding: Boolean, dead: Boolean, cx: Float, headY: Float, tailY: Float, c: Int) {
        val top = max(tailY, -noteH)
        val bottom = if (holding) judgeY else headY
        if (bottom <= top) return
        val hw = laneW * 0.26f
        val color = if (dead) Color.rgb(110, 105, 130) else c
        val aBody = if (dead) 60 else if (holding) 200 else 120
        // outer glow
        fill.color = Color.argb(if (holding) 70 else 36, Color.red(color), Color.green(color), Color.blue(color))
        rect.set(cx - hw - 6f * dp, top, cx + hw + 6f * dp, bottom)
        canvas.drawRoundRect(rect, hw, hw, fill)
        // ribbon
        fill.color = Color.argb(aBody, Color.red(color), Color.green(color), Color.blue(color))
        rect.set(cx - hw, top, cx + hw, bottom)
        canvas.drawRoundRect(rect, hw * 0.8f, hw * 0.8f, fill)
        // bright core + edges
        fill.color = Color.argb(if (holding) 230 else 110, 255, 255, 255)
        rect.set(cx - hw * 0.28f, top, cx + hw * 0.28f, bottom)
        canvas.drawRoundRect(rect, hw * 0.28f, hw * 0.28f, fill)
        // tail cap (only once the end of the hold is on screen)
        if (tailY >= -noteH) {
            fill.color = Color.argb(if (dead) 80 else 255, Color.red(color), Color.green(color), Color.blue(color))
            rect.set(cx - hw - 3f * dp, top - noteH * 0.22f, cx + hw + 3f * dp, top + noteH * 0.22f)
            canvas.drawRoundRect(rect, noteH * 0.2f, noteH * 0.2f, fill)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Judgment feedback
    // ----------------------------------------------------------------------------------------
    override fun onEvent(ev: JudgmentEvent) {
        if (laneW <= 0f) return
        val lane = ev.track.coerceIn(0, 4)
        val x = areaX + (lane + 0.5f) * laneW
        popupFor(ev, x, judgeY - 64f * dp, laneColors[lane])
        if (ev.kind == EventKind.HIT || ev.kind == EventKind.TAIL_OK) beamAlpha[lane] = 1f
    }

    // ----------------------------------------------------------------------------------------
    // Touch: all 5 lanes, any number of fingers
    // ----------------------------------------------------------------------------------------
    private fun laneAt(x: Float): Int {
        if (laneW <= 0f) return -1
        val l = ((x - areaX) / laneW).toInt()
        return if (x < areaX || l !in 0..4) -1 else l
    }

    private fun press(pointerId: Int, x: Float, eventTimeMs: Long) {
        val lane = laneAt(x)
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
                press(e.getPointerId(i), e.getX(i), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                release(e.getPointerId(i), e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (k in pointerLane.size() - 1 downTo 0) release(pointerLane.keyAt(k), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val cur = pointerLane.get(id, -1)
                    val now = laneAt(e.getX(i))
                    if (cur >= 0 && now != cur) {
                        release(id, e.eventTime)
                        if (now >= 0) press(id, e.getX(i), e.eventTime)
                    }
                }
            }
        }
        return true
    }
}
