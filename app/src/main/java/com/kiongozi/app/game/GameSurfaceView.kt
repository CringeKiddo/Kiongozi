package com.kiongozi.app.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Base class for both game modes: SurfaceView lifecycle, a Choreographer-paced render thread
 * (hardware canvas), frame-rate hints, blurred album-art background, HUD, particles and popups.
 *
 * Every position is computed from [songMs] (the audio clock sampled at the frame's vsync time);
 * frame count and frame delta are only used for cosmetic animation (particles, glow fades).
 */
abstract class GameSurfaceView(context: Context, protected val session: GameSession) :
    SurfaceView(context), SurfaceHolder.Callback {

    protected val dp = resources.displayMetrics.density
    protected val judgment = session.judgment
    protected val sync = session.sync

    protected var w = 0
    protected var h = 0
    protected var topInset = 0f
    protected var songMs = 0.0
    var effectiveHz = 60f
        private set
    @Volatile var fpsMeasured = 0f
        private set

    protected val particles = ParticleSystem()
    protected val popups = PopupSystem(dp)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var choreographer: Choreographer? = null
    @Volatile private var running = false
    private var lastFrameNanos = 0L

    private var bg: Bitmap? = null
    private var bgSource: Bitmap? = null
    private var bgW = 0
    private var bgH = 0
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint().apply { color = Color.argb(165, 4, 1, 14) }

    // HUD
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val comboPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lastCombo = 0
    private var comboPopAge = 999f

    init {
        holder.addCallback(this)
        keepScreenOn = true
        isFocusable = true
    }

    // ----------------------------------------------------------------------------------------
    // Subclass API
    // ----------------------------------------------------------------------------------------
    protected abstract fun onSizeKnown()
    protected abstract fun drawGame(canvas: Canvas, songMs: Double, dtSec: Float)
    protected abstract fun onEvent(ev: JudgmentEvent)

    // ----------------------------------------------------------------------------------------
    // Surface lifecycle
    // ----------------------------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        effectiveHz = FrameRateController.apply(this, holder.surface, session.settings.fpsMode)
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width
        h = height
        val ins = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        topInset = max((ins?.top ?: 0).toFloat(), 10f * dp)
        onSizeKnown()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    private fun startLoop() {
        if (running) return
        running = true
        val t = HandlerThread("kiongozi-render", Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        thread = t
        val hdl = Handler(t.looper)
        handler = hdl
        hdl.post {
            val c = Choreographer.getInstance()
            choreographer = c
            c.postFrameCallback(frameCallback)
        }
    }

    private fun stopLoop() {
        running = false
        val t = thread
        handler?.post { choreographer?.removeFrameCallback(frameCallback) }
        t?.quitSafely()
        try {
            t?.join(1000)
        } catch (_: InterruptedException) {
        }
        thread = null
        handler = null
        choreographer = null
        lastFrameNanos = 0L
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            try {
                renderFrame(frameTimeNanos)
            } catch (_: Exception) {
                // surface went away mid-frame; the lifecycle callback will stop the loop
            }
            if (running) choreographer?.postFrameCallback(this)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Frame
    // ----------------------------------------------------------------------------------------
    private fun renderFrame(frameNanos: Long) {
        if (w == 0 || h == 0) return
        val dtMs = if (lastFrameNanos == 0L) 16.0 else ((frameNanos - lastFrameNanos) / 1e6).coerceIn(0.0, 60.0)
        lastFrameNanos = frameNanos
        if (dtMs > 1.0) fpsMeasured = if (fpsMeasured == 0f) (1000.0 / dtMs).toFloat() else fpsMeasured * 0.92f + (1000.0 / dtMs).toFloat() * 0.08f

        sync.poll()
        songMs = sync.songTimeAtMonoNs(frameNanos)
        if (sync.state == AudioSyncEngine.State.PLAYING) judgment.update(songMs)
        for (ev in judgment.drainEvents()) onEvent(ev)

        val dtSec = (dtMs / 1000.0).toFloat()
        particles.update(dtSec)
        popups.update(dtMs.toFloat())
        comboPopAge += dtMs.toFloat()

        val canvas = holder.lockHardwareCanvas() ?: return
        try {
            drawBackground(canvas)
            drawGame(canvas, songMs, dtSec)
            particles.draw(canvas)
            popups.draw(canvas)
            drawHud(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Background: blurred, dimmed album art, or a dark neon gradient
    // ----------------------------------------------------------------------------------------
    private fun drawBackground(canvas: Canvas) {
        val src = session.background
        if (bg == null || bgW != w || bgH != h || bgSource !== src) {
            bg?.recycle()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val grad = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w * 0.4f, h.toFloat(),
                    intArrayOf(0xFF1B0638.toInt(), 0xFF0B0220.toInt(), 0xFF05010F.toInt()),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grad)
            if (src != null && !src.isRecycled) {
                // center-crop
                val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
                val dw = src.width * scale
                val dh = src.height * scale
                val l = (w - dw) / 2f
                val t = (h - dh) / 2f
                rect.set(l, t, l + dw, t + dh)
                c.drawBitmap(src, null, rect, bgPaint)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrimPaint)
            }
            bg = b
            bgSource = src
            bgW = w
            bgH = h
        }
        canvas.drawBitmap(bg!!, 0f, 0f, null)
    }

    // ----------------------------------------------------------------------------------------
    // HUD (score, accuracy, health, combo, fps)
    // ----------------------------------------------------------------------------------------
    private fun drawHud(canvas: Canvas) {
        val s = judgment.stats()
        val left = 16f * dp
        var y = topInset + 26f * dp

        scorePaint.textSize = 26f * dp
        scorePaint.textAlign = Paint.Align.LEFT
        canvas.drawText("%07d".format(s.score), left, y, scorePaint)

        y += 20f * dp
        smallPaint.color = 0xFF00F0FF.toInt()
        smallPaint.textSize = 14f * dp
        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("%.2f%%".format(s.accuracy), left, y, smallPaint)

        // health bar
        y += 10f * dp
        val bw = 150f * dp
        val bh = 8f * dp
        barPaint.style = Paint.Style.FILL
        barPaint.color = Color.argb(120, 255, 255, 255)
        rect.set(left, y, left + bw, y + bh)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, barPaint)
        barPaint.color = when {
            s.health > 50 -> 0xFFB6FF3B.toInt()
            s.health > 25 -> 0xFFFFC83B.toInt()
            else -> 0xFFFF4D6D.toInt()
        }
        rect.set(left, y, left + bw * (s.health / 100.0).toFloat(), y + bh)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, barPaint)
        if (session.settings.noFail) {
            smallPaint.color = 0xFF8D86A8.toInt()
            smallPaint.textSize = 10f * dp
            canvas.drawText("NO-FAIL", left + bw + 8f * dp, y + bh, smallPaint)
        }

        // combo
        if (s.combo != lastCombo) {
            if (s.combo > lastCombo) comboPopAge = 0f
            lastCombo = s.combo
        }
        if (s.combo >= 2) {
            val pop = 1f + 0.22f * max(0f, 1f - comboPopAge / 110f)
            comboPaint.textSize = 54f * dp * pop
            comboPaint.alpha = 230
            canvas.drawText(s.combo.toString(), w / 2f, h * 0.36f, comboPaint)
            comboPaint.textSize = 12f * dp
            comboPaint.alpha = 160
            canvas.drawText("COMBO", w / 2f, h * 0.36f + 18f * dp, comboPaint)
        }

        if (session.settings.showFps) {
            smallPaint.color = Color.argb(150, 255, 255, 255)
            smallPaint.textSize = 10f * dp
            smallPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("%d FPS  ·  %d Hz".format(fpsMeasured.toInt(), effectiveHz.toInt()), w - 16f * dp, topInset + 70f * dp, smallPaint)
        }
    }

    /** Shared helper for both modes' judgment feedback. */
    protected fun popupFor(ev: JudgmentEvent, x: Float, y: Float, burstColor: Int) {
        when (ev.kind) {
            EventKind.HIT, EventKind.TAIL_OK -> {
                val j = ev.judgment
                val color = when (j) {
                    Judgment.PERFECT -> 0xFFFFE066.toInt()
                    Judgment.GREAT -> 0xFF00F0FF.toInt()
                    else -> 0xFF8D86FF.toInt()
                }
                val sub = if (ev.kind == EventKind.HIT && j != Judgment.PERFECT) {
                    if (ev.deltaMs < 0) "EARLY" else "LATE"
                } else null
                popups.spawn(if (j == Judgment.PERFECT) "PERFECT!" else j.label + "!", sub, color, x, y, ev.deltaMs < 0)
                particles.burst(x, y, burstColor, when (j) { Judgment.PERFECT -> 22; Judgment.GREAT -> 14; else -> 8 }, 260f * dp)
            }
            EventKind.MISS, EventKind.TAIL_BREAK -> popups.spawn("MISS", null, 0xFFFF4D6D.toInt(), x, y, false)
            EventKind.TICK -> particles.burst(x, y, burstColor, 2, 120f * dp)
        }
    }
}

// --------------------------------------------------------------------------------------------
// Particles: simulated with real time deltas, so FPS never changes how they look or last
// --------------------------------------------------------------------------------------------
class ParticleSystem {
    private val cap = 320
    private val px = FloatArray(cap); private val py = FloatArray(cap)
    private val vx = FloatArray(cap); private val vy = FloatArray(cap)
    private val life = FloatArray(cap); private val maxLife = FloatArray(cap)
    private val size = FloatArray(cap); private val color = IntArray(cap)
    private var next = 0
    private val rnd = java.util.Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun burst(x: Float, y: Float, c: Int, count: Int, speed: Float) {
        repeat(count) {
            val i = next
            next = (next + 1) % cap
            val a = rnd.nextFloat() * 6.2831855f
            val s = speed * (0.35f + rnd.nextFloat() * 0.65f)
            px[i] = x; py[i] = y
            vx[i] = kotlin.math.cos(a) * s; vy[i] = kotlin.math.sin(a) * s - speed * 0.15f
            maxLife[i] = 0.35f + rnd.nextFloat() * 0.35f
            life[i] = maxLife[i]
            size[i] = speed * (0.012f + rnd.nextFloat() * 0.02f)
            color[i] = c
        }
    }

    fun update(dt: Float) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            vy[i] += 520f * dt
            vx[i] *= (1f - 2.2f * dt).coerceAtLeast(0f)
        }
    }

    fun draw(canvas: Canvas) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            val k = (life[i] / maxLife[i]).coerceIn(0f, 1f)
            paint.color = color[i]
            paint.alpha = (255 * k).toInt()
            canvas.drawCircle(px[i], py[i], max(size[i] * (0.4f + 0.6f * k), 1f), paint)
        }
    }
}

/** Floating judgment text ("PERFECT!", "GREAT!", ...) with an EARLY / LATE tag. */
class PopupSystem(private val dp: Float) {
    private val cap = 14
    private val text = arrayOfNulls<String>(cap)
    private val sub = arrayOfNulls<String>(cap)
    private val color = IntArray(cap)
    private val x = FloatArray(cap); private val y = FloatArray(cap)
    private val age = FloatArray(cap) { 9999f }
    private val early = BooleanArray(cap)
    private var next = 0
    private val life = 650f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun spawn(t: String, s: String?, c: Int, px: Float, py: Float, isEarly: Boolean) {
        val i = next
        next = (next + 1) % cap
        text[i] = t; sub[i] = s; color[i] = c; x[i] = px; y[i] = py; age[i] = 0f; early[i] = isEarly
    }

    fun update(dtMs: Float) {
        for (i in 0 until cap) if (age[i] < life) age[i] += dtMs
    }

    fun draw(canvas: Canvas) {
        for (i in 0 until cap) {
            val a = age[i]
            if (a >= life) continue
            val k = a / life
            val pop = 1f + 0.35f * max(0f, 1f - a / 120f)
            val alpha = (255 * min(1f, (1f - k) * 2.2f)).toInt().coerceIn(0, 255)
            val yy = y[i] - 46f * dp * k
            paint.textSize = 20f * dp * pop
            paint.color = color[i]
            paint.alpha = alpha
            paint.setShadowLayer(10f * dp, 0f, 0f, color[i])
            canvas.drawText(text[i] ?: "", x[i], yy, paint)
            paint.clearShadowLayer()
            val s = sub[i]
            if (s != null) {
                paint.textSize = 11f * dp
                paint.color = if (early[i]) 0xFF5CC8FF.toInt() else 0xFFFFA14D.toInt()
                paint.alpha = alpha
                canvas.drawText(s, x[i], yy + 15f * dp, paint)
            }
        }
    }
}
