package com.kiongozi.app.ui.prep

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiongozi.app.beatmap.Beatmap
import com.kiongozi.app.beatmap.BeatmapPipeline.Stage
import com.kiongozi.app.beatmap.CachedSong
import com.kiongozi.app.beatmap.Difficulty
import com.kiongozi.app.game.GameArgs
import com.kiongozi.app.game.GameMode
import com.kiongozi.app.game.GameSettings
import com.kiongozi.app.game.HiddenMode
import com.kiongozi.app.settings.SettingsRepository
import com.kiongozi.app.ui.common.NeonChip
import com.kiongozi.app.ui.common.NeonSlider
import com.kiongozi.app.ui.common.NeonTextButton
import com.kiongozi.app.ui.common.NeonToggle
import com.kiongozi.app.ui.common.SectionTitle
import com.kiongozi.app.beatmap.ChartStats
import com.kiongozi.app.ui.theme.Neon
import com.kiongozi.app.ui.theme.NeonBackground
import com.kiongozi.app.ui.title.NeonButton
import kotlin.math.roundToInt

@Composable
fun SongPrepScreen(controller: SongPrepController, onBack: () -> Unit, onPlay: (GameArgs) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            controller.prepare(uri)
        }
    }
    val launchPicker = { picker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream")) }

    NeonBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                "‹  BACK", color = Neon.TextDim, fontSize = 13.sp, letterSpacing = 3.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            )

            Crossfade(targetState = controller.state::class, label = "prep") { kind ->
                when (kind) {
                    PrepState.Working::class -> {
                        val s = controller.state
                        if (s is PrepState.Working) LoadingView(s, onCancel = { controller.cancel() })
                    }
                    PrepState.Ready::class -> {
                        val s = controller.state
                        if (s is PrepState.Ready) ReadyView(s.beatmap, s.fromCache, s.audioUri, controller, onPlay = onPlay, onOpenSettings = onOpenSettings, onAnother = {
                            controller.reset()
                            launchPicker()
                        })
                    }
                    PrepState.Failed::class -> {
                        val s = controller.state
                        if (s is PrepState.Failed) FailedView(s.message, onRetry = { controller.reset(); launchPicker() })
                    }
                    else -> IdleView(controller.recents, onPick = { launchPicker() }, onOpen = { controller.openCached(it) })
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Idle: picker + recent songs
// ------------------------------------------------------------------------------------------
@Composable
private fun IdleView(recents: List<CachedSong>, onPick: () -> Unit, onOpen: (CachedSong) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        Text("SELECT SONG", color = Neon.Cyan, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Spacer(Modifier.height(6.dp))
        Text("MP3 · WAV · FLAC · M4A · OGG", color = Neon.TextDim, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(24.dp))
        NeonButton("BROWSE FILES", onClick = onPick)
        Spacer(Modifier.height(32.dp))

        Text("RECENT SONGS", color = Neon.Magenta, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        if (recents.isEmpty()) {
            Text(
                "Songs you've analyzed show up here and load instantly next time.",
                color = Neon.TextDim, fontSize = 13.sp, lineHeight = 18.sp,
            )
        } else {
            recents.take(20).forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .border(1.dp, Neon.Violet.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .background(Neon.Surface.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .clickable { onOpen(s) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text("${s.bpm.roundToInt()} BPM  ·  ${fmtDuration(s.durationMs)}", color = Neon.TextDim, fontSize = 12.sp)
                    }
                    Text("INSTANT", color = Neon.Lime, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ------------------------------------------------------------------------------------------
// Loading: Decoding Audio... -> Analyzing Beats with AI... -> Building Chart...
// ------------------------------------------------------------------------------------------
@Composable
private fun LoadingView(w: PrepState.Working, onCancel: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "load")
    val spin by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "spin",
    )
    val pulse by transition.animateFloat(
        0.85f, 1.05f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    val smooth by animateFloatAsState(w.overall, tween(400), label = "overall")

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 10f
                val pad = stroke * 2
                val d = size.minDimension - pad * 2
                val topLeft = Offset(pad, pad)
                drawCircle(Neon.Violet.copy(alpha = 0.25f), radius = d / 2 * pulse * 0.78f)
                drawArc(
                    brush = Brush.sweepGradient(listOf(Neon.Cyan, Neon.Magenta, Neon.Cyan)),
                    startAngle = spin, sweepAngle = 110f, useCenter = false,
                    topLeft = topLeft, size = Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Neon.Magenta, startAngle = -spin * 1.4f, sweepAngle = 60f, useCenter = false,
                    topLeft = Offset(pad + 18f, pad + 18f), size = Size(d - 36f, d - 36f),
                    style = Stroke(6f, cap = StrokeCap.Round),
                )
            }
            Text("${(smooth * 100).roundToInt()}%", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
        }

        Spacer(Modifier.height(28.dp))

        Crossfade(targetState = w.stage, label = "stage") { stage ->
            Text(
                when (stage) {
                    Stage.DECODING -> "Decoding Audio..."
                    Stage.ANALYZING -> "Analyzing Beats with AI..."
                    Stage.BUILDING -> "Building Chart..."
                },
                color = Neon.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(w.songName, color = Neon.TextDim, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)

        Spacer(Modifier.height(22.dp))
        // overall progress bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Neon.Surface, RoundedCornerShape(4.dp))
                .border(1.dp, Neon.Violet.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(smooth.coerceIn(0.02f, 1f))
                    .background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Magenta)), RoundedCornerShape(4.dp))
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StepLabel("DECODE", w.stage.ordinal >= 0, w.stage == Stage.DECODING)
            StepLabel("AI BEATS", w.stage.ordinal >= 1, w.stage == Stage.ANALYZING)
            StepLabel("CHART", w.stage.ordinal >= 2, w.stage == Stage.BUILDING)
        }

        Spacer(Modifier.height(22.dp))
        if (w.stage == Stage.ANALYZING) {
            Text(
                "First time only: this can take a minute or two per song.\nNext time it loads instantly.",
                color = Neon.TextDim, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            "CANCEL", color = Neon.Red, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onCancel() }.padding(12.dp),
        )
    }
}

@Composable
private fun StepLabel(text: String, reached: Boolean, active: Boolean) {
    Text(
        text,
        color = when {
            active -> Neon.Cyan
            reached -> Neon.Lime
            else -> Neon.TextDim
        },
        fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
    )
}

// ------------------------------------------------------------------------------------------
// Ready: summary + difficulty/mode selection (gameplay arrives in Batch 3/4)
// ------------------------------------------------------------------------------------------
@Composable
private fun ReadyView(
    beatmap: Beatmap,
    fromCache: Boolean,
    audioUri: Uri?,
    controller: SongPrepController,
    onPlay: (GameArgs) -> Unit,
    onAnother: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository.get(context) }
    val s by repo.flow.collectAsState(initial = GameSettings())
    fun upd(t: (GameSettings) -> GameSettings) {
        scope.launch { repo.update(t) }
    }

    val difficulty = s.difficulty
    val mode = s.mode
    val ratings = remember(beatmap) { Difficulty.values().associateWith { ChartStats.rate(beatmap, it) } }
    val rating = ratings[difficulty]
    val notes = beatmap.chart(difficulty)?.notes ?: emptyList()
    val holds = notes.count { it.holdDurationMs > 0 }
    val chords = notes.groupBy { it.timeMs }.count { it.value.size > 1 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(20.dp))
        Text(
            if (fromCache) "LOADED FROM CACHE" else "CHART READY",
            color = Neon.Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(beatmap.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Text(
            "${beatmap.bpm.roundToInt()} BPM  ·  ${beatmap.beatsPerBar}/4  ·  ${fmtDuration(beatmap.durationMs)}  ·  ${beatmap.beatsMs.size} beats",
            color = Neon.TextDim, fontSize = 13.sp,
        )

        // ---- difficulty with star rating from note density ----
        SectionTitle("DIFFICULTY")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Difficulty.values().forEach { d ->
                val r = ratings[d]
                NeonChip(d.label, difficulty == d, if (r != null) "★ %.1f".format(r.stars) else null) { upd { it.copy(difficulty = d) } }
            }
        }
        if (rating != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Level ★ %.1f of 12   ·   %.1f notes per second".format(rating.stars, rating.nps),
                color = Neon.TextDim, fontSize = 12.sp,
            )
        }

        // ---- game mode (same cached chart, instant switch) ----
        SectionTitle("MODE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameMode.values().forEach { m -> NeonChip(m.label, mode == m) { upd { it.copy(mode = m) } } }
        }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Neon.Cyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .background(Neon.Surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Stat("notes", notes.size.toString())
            Stat(if (s.noHolds) "holds (turned into taps)" else "holds", if (s.noHolds) "0" else holds.toString())
            Stat("chords / doubles", chords.toString())
            if (mode == GameMode.LANES) {
                val perLane = IntArray(5)
                notes.forEach { perLane[it.lane.coerceIn(0, 4)]++ }
                Stat("lanes L→R", perLane.joinToString(" · "))
            } else {
                Stat("buttons △ ○ ✕ □", com.kiongozi.app.beatmap.ButtonType.values().joinToString(" · ") { b -> notes.count { it.button == b }.toString() })
            }
        }

        // ---- beat grid multiplier ----
        SectionTitle("BEAT GRID")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f to "HALF-TIME", 1.0f to "NORMAL", 2.0f to "DOUBLE-TIME").forEach { (value, name) ->
                NeonChip("%.1fx".format(value), kotlin.math.abs(beatmap.gridMultiplier - value) < 0.01f, name) { controller.setGrid(value) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Chart is built on ${beatmap.bpm.roundToInt()} BPM. If the AI locked onto half or double tempo, switch here (rebuilds instantly).",
            color = Neon.TextDim, fontSize = 11.sp, lineHeight = 15.sp,
        )

        // ---- modifiers ----
        SectionTitle("MODIFIERS")
        NeonToggle("MIRROR MODE", "Flips lanes / target positions left-to-right", s.mirror) { v -> upd { it.copy(mirror = v) } }
        NeonToggle("NO-FAIL", "Song can't end early when health hits 0", s.noFail) { v -> upd { it.copy(noFail = v) } }
        NeonToggle("AUTO-PLAY (BOT)", "Watch the AI play its own chart", s.autoPlay) { v -> upd { it.copy(autoPlay = v) } }
        NeonToggle("NO HOLD NOTES", "Hold notes become normal taps", s.noHolds) { v -> upd { it.copy(noHolds = v) } }
        Spacer(Modifier.height(8.dp))
        Text("SUDDEN+ / HIDDEN", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HiddenMode.values().forEach { h -> NeonChip(h.label, s.hiddenMode == h) { upd { it.copy(hiddenMode = h) } } }
        }
        Spacer(Modifier.height(10.dp))
        NeonSlider(
            "NOTE SCROLL SPEED", s.scrollSpeed, 1f..9f, steps = 15,
            display = { "%.1fx".format(it) },
        ) { v -> upd { it.copy(scrollSpeed = (v * 2f).roundToInt() / 2f) } }

        Spacer(Modifier.height(20.dp))
        NeonButton("PLAY", enabled = audioUri != null) {
            onPlay(GameArgs(beatmap, audioUri, difficulty, mode))
        }
        if (audioUri == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The original audio file isn't linked to this saved chart. Pick the song again to play it.",
                color = Neon.Red, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(18.dp))
        NeonTextButton("REGENERATE CHART") { controller.regenerate(full = false) }
        Spacer(Modifier.height(8.dp))
        if (audioUri != null) {
            NeonTextButton("FULL RE-ANALYSIS (RE-RUN AI)", Neon.TextDim) { controller.regenerate(full = true) }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "Regenerate rebuilds the chart from the saved analysis in about a second. Full re-analysis deletes everything for this song and runs the AI again.",
            color = Neon.TextDim, fontSize = 11.sp, lineHeight = 15.sp,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "ALL SETTINGS", color = Neon.Cyan, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().clickable { onOpenSettings() }.padding(12.dp), textAlign = TextAlign.Center,
        )
        Text(
            "PICK ANOTHER SONG", color = Neon.TextDim, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().clickable { onAnother() }.padding(12.dp), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RowScope.Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) Neon.Magenta else Neon.TextDim
    Box(
        Modifier
            .weight(1f)
            .border(if (selected) 2.dp else 1.dp, c, RoundedCornerShape(10.dp))
            .background(if (selected) Neon.Magenta.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Neon.TextDim, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FailedView(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("COULDN'T BUILD THIS CHART", color = Neon.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Neon.TextDim, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        NeonButton("TRY ANOTHER FILE", onClick = onRetry)
    }
}

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
