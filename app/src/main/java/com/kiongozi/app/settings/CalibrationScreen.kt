package com.kiongozi.app.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiongozi.app.game.AudioSyncEngine
import com.kiongozi.app.game.PcmInfo
import com.kiongozi.app.settings.SettingsRepository
import com.kiongozi.app.ui.common.NeonTextButton
import com.kiongozi.app.ui.theme.Neon
import com.kiongozi.app.ui.theme.NeonBackground
import com.kiongozi.app.ui.title.NeonButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val BEAT_MS = 500.0
private const val CLICKS = 16          // first 4 = count-in, the other 12 are measured
private const val COUNT_IN = 4
private const val END_MS = CLICKS * BEAT_MS + 300.0

/**
 * Audio Sync Wizard. A 120 BPM click track plays through the SAME audio path as the game; tap along.
 * Each tap is compared with the nearest click on the audio clock. The (trimmed) average tells how much
 * later than the clock you hear the audio (phone speaker, wired, Bluetooth) and becomes the Audio Offset.
 * Note: it also includes your natural tapping tendency, so fine-tune afterwards in the pause menu.
 */
@Composable
fun CalibrationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository.get(context) }

    var phase by remember { mutableStateOf(0) }   // 0 intro, 1 running, 2 result
    var engine by remember { mutableStateOf<AudioSyncEngine?>(null) }
    val deltas = remember { mutableStateListOf<Double>() }
    var songT by remember { mutableStateOf(-9999.0) }
    var resultMs by remember { mutableStateOf(0) }
    var spreadMs by remember { mutableStateOf(0.0) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { engine?.release() } }
    BackHandler {
        engine?.release()
        onClose()
    }

    fun finish() {
        engine?.release()
        if (deltas.size < 5) {
            message = "Not enough taps (${deltas.size}). Try again and tap with every click after the count-in."
            phase = 0
            return
        }
        val sorted = deltas.sorted()
        val trim = (sorted.size * 0.2).toInt()
        val core = sorted.subList(trim, sorted.size - trim)
        val mean = core.average()
        val variance = core.sumOf { (it - mean) * (it - mean) } / core.size
        resultMs = mean.roundToInt().coerceIn(-200, 200)
        spreadMs = sqrt(variance)
        phase = 2
    }

    fun start() {
        scope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { createMetronome(context) }
                engine?.release()
                val e = AudioSyncEngine(pcm)
                e.audioOffsetMs = 0
                e.musicVolume = 1f
                deltas.clear()
                message = null
                songT = -9999.0
                engine = e
                e.start()
                phase = 1
            } catch (ex: Exception) {
                message = "Couldn't start audio: ${ex.message}"
            }
        }
    }

    // drive the clock + beat flash + end detection every frame
    LaunchedEffect(phase, engine) {
        val e = engine ?: return@LaunchedEffect
        if (phase != 1) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            e.poll()
            songT = e.songTimeMs()
            if (songT > END_MS) {
                finish()
                break
            }
        }
    }

    fun onTap(uptimeMs: Long) {
        val e = engine ?: return
        if (phase != 1) return
        val t = e.songTimeAtUptimeMs(uptimeMs)
        val k = Math.round(t / BEAT_MS).toInt()
        if (k < COUNT_IN || k >= CLICKS) return
        val d = t - k * BEAT_MS
        if (abs(d) <= 250.0) deltas.add(d)
    }

    NeonBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("AUDIO SYNC WIZARD", color = Neon.Cyan, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Spacer(Modifier.height(18.dp))

            when (phase) {
                0 -> {
                    Text(
                        "Tap anywhere in time with the metronome clicks.\nThe first 4 clicks are a count-in, then tap on every click (12 taps).\n\nUse the same headphones or speaker you play with.",
                        color = Neon.TextDim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    )
                    val m = message
                    if (m != null) {
                        Spacer(Modifier.height(14.dp))
                        Text(m, color = Neon.Red, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(28.dp))
                    NeonButton("START", onClick = { start() })
                    Spacer(Modifier.height(10.dp))
                    NeonTextButton("CANCEL", Neon.TextDim) { onClose() }
                }
                1 -> {
                    val inBeat = if (songT >= 0.0) ((songT % BEAT_MS) + BEAT_MS) % BEAT_MS else 9999.0
                    val flash = if (songT in 0.0..(CLICKS * BEAT_MS)) (1f - (inBeat / 200.0).toFloat()).coerceIn(0f, 1f) else 0f
                    val counting = songT < COUNT_IN * BEAT_MS
                    Text(
                        if (songT < 0) "GET READY…" else if (counting) "COUNT-IN" else "TAP!",
                        color = if (counting) Neon.TextDim else Neon.Lime, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                    )
                    Text("${deltas.size} / ${CLICKS - COUNT_IN}", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 24.dp)
                            .border(2.dp, Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Magenta)), RoundedCornerShape(24.dp))
                            .background(Neon.Surface.copy(alpha = 0.85f), RoundedCornerShape(24.dp))
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        onTap(down.uptimeMillis)
                                        waitForUpOrCancellation()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension * (0.18f + 0.12f * flash)
                            drawCircle(Neon.Cyan.copy(alpha = 0.15f + 0.6f * flash), radius = r * 1.25f)
                            drawCircle(Neon.Magenta.copy(alpha = 0.25f + 0.7f * flash), radius = r)
                        }
                        Text("TAP", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                    }
                }
                else -> {
                    Spacer(Modifier.height(12.dp))
                    Text("YOUR AUDIO OFFSET", color = Neon.TextDim, fontSize = 12.sp, letterSpacing = 3.sp)
                    Text(
                        "%+d ms".format(resultMs), color = Neon.Cyan, fontSize = 54.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    )
                    Text("consistency ±%d ms (lower is better)".format(spreadMs.roundToInt()), color = Neon.TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (spreadMs > 45) "Your taps were uneven. For a trustworthy result, try again."
                        else "Positive = you hear the audio later than the clock, so notes will arrive later.",
                        color = Neon.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(26.dp))
                    NeonButton("APPLY", onClick = {
                        scope.launch {
                            repo.update { it.copy(audioOffsetMs = resultMs) }
                            onClose()
                        }
                    })
                    Spacer(Modifier.height(10.dp))
                    NeonTextButton("TRY AGAIN") { phase = 0 }
                    Spacer(Modifier.height(10.dp))
                    NeonTextButton("CANCEL", Neon.TextDim) { onClose() }
                }
            }
        }
    }
}

/** 16 clicks at 120 BPM (accent on every 4th), 44.1 kHz stereo 16-bit, written to cacheDir. */
private fun createMetronome(context: Context): PcmInfo {
    val rate = 44100
    val beat = rate / 2
    val frames = CLICKS * beat + rate / 2
    val data = ShortArray(frames * 2)
    for (k in 0 until CLICKS) {
        val start = k * beat
        val freq = if (k % 4 == 0) 1700.0 else 1100.0
        for (i in 0 until 1800) {
            val t = i.toDouble() / rate
            val v = (sin(2 * PI * freq * t) * exp(-t * 110.0) * 0.85 * 32767.0).toInt().toShort()
            val idx = (start + i) * 2
            data[idx] = v
            data[idx + 1] = v
        }
    }
    val dir = File(context.cacheDir, "pcm").apply { mkdirs() }
    val f = File(dir, "calibration_click.raw")
    val bb = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (s in data) bb.putShort(s)
    f.writeBytes(bb.array())
    return PcmInfo(f, rate, frames.toLong())
}
