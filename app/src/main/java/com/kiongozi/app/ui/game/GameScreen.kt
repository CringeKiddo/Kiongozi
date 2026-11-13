package com.kiongozi.app.ui.game

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kiongozi.app.game.AlbumArt
import com.kiongozi.app.game.AppLifecycle
import com.kiongozi.app.game.GameArgs
import com.kiongozi.app.game.GameMode
import com.kiongozi.app.game.GameResults
import com.kiongozi.app.game.GameSession
import com.kiongozi.app.game.GameSettings
import com.kiongozi.app.game.PcmCache
import com.kiongozi.app.game.diva.DivaGameView
import com.kiongozi.app.game.lanes.KalpaGameView
import com.kiongozi.app.ui.theme.Neon
import com.kiongozi.app.ui.theme.NeonBackground
import com.kiongozi.app.ui.title.NeonButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface Phase {
    data class Preparing(val progress: Float) : Phase
    data object Playing : Phase
    data object Paused : Phase
    data class Resuming(val count: Int) : Phase
    data class Done(val results: GameResults) : Phase
    data class Error(val message: String) : Phase
}

@Composable
fun GameScreen(args: GameArgs, onExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings = remember { GameSettings(context) }

    var phase by remember { mutableStateOf<Phase>(Phase.Preparing(0f)) }
    var session by remember { mutableStateOf<GameSession?>(null) }
    var offsetUi by remember { mutableStateOf(settings.audioOffsetMs) }

    // ---- immersive full screen while playing ----
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val ctrl = if (window != null) WindowInsetsControllerCompat(window, view) else null
        if (window != null) WindowCompat.setDecorFitsSystemWindows(window, false)
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctrl?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // ---- decode playback audio (cached), create the session, start ----
    LaunchedEffect(args) {
        val uri = args.audioUri
        if (uri == null) {
            phase = Phase.Error("The original audio file isn't available for this song.\nPick it again from the file browser.")
            return@LaunchedEffect
        }
        try {
            val pcm = PcmCache.ensure(context, uri, args.beatmap.songKey) { phase = Phase.Preparing(it) }
            val s = GameSession(args, pcm, settings)
            session = s
            launch { s.background = AlbumArt.loadBlurred(context, uri) }
            s.start()
            phase = Phase.Playing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = Phase.Error(e.message ?: "Couldn't prepare the audio")
        }
    }

    // ---- end-of-song detection ----
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            delay(100)
            if (phase == Phase.Playing && s.isFinished()) {
                s.sync.pause()
                phase = Phase.Done(s.judgment.results())
            }
        }
    }

    fun doPause() {
        if (phase == Phase.Playing) {
            session?.sync?.pause()
            phase = Phase.Paused
        }
    }

    fun doResume() {
        val s = session ?: return
        if (phase != Phase.Paused) return
        scope.launch {
            for (c in 3 downTo 1) {
                phase = Phase.Resuming(c)
                delay(800)
            }
            s.sync.resume()
            phase = Phase.Playing
        }
    }

    fun doRestart() {
        val s = session ?: return
        s.restart()
        phase = Phase.Playing
    }

    fun quit() {
        session?.release()
        session = null
        onExit()
    }

    // auto-pause when the app leaves the foreground
    SideEffect { AppLifecycle.onPause = { doPause() } }
    DisposableEffect(Unit) {
        onDispose {
            AppLifecycle.onPause = null
            session?.release()
        }
    }

    BackHandler {
        when (phase) {
            Phase.Playing -> doPause()
            Phase.Paused -> doResume()
            is Phase.Resuming -> {}
            else -> quit()
        }
    }

    // ------------------------------------------------------------------------------------
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = session
        if (s != null && phase !is Phase.Done) {
            AndroidView(
                factory = { ctx ->
                    if (args.mode == GameMode.LANES) KalpaGameView(ctx, s) else DivaGameView(ctx, s)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (val p = phase) {
            is Phase.Preparing -> PreparingView(p.progress)
            is Phase.Error -> ErrorView(p.message, onBack = { quit() })
            Phase.Playing -> {
                // pause button (top-right)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .size(46.dp)
                        .background(Color(0x66000000), RoundedCornerShape(12.dp))
                        .clickable { doPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("II", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }
            Phase.Paused -> PauseOverlay(
                offsetMs = offsetUi,
                onOffsetChange = { v ->
                    val c = v.coerceIn(-200, 200)
                    offsetUi = c
                    settings.audioOffsetMs = c
                    session?.sync?.audioOffsetMs = c
                },
                onResume = { doResume() },
                onRestart = { doRestart() },
                onQuit = { quit() },
            )
            is Phase.Resuming -> Box(
                Modifier.fillMaxSize().background(Color(0x66000000))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                contentAlignment = Alignment.Center,
            ) {
                Text(p.count.toString(), color = Neon.Cyan, fontSize = 96.sp, fontWeight = FontWeight.Black)
            }
            is Phase.Done -> ResultsScreen(
                title = args.beatmap.title,
                r = p.results,
                onRetry = { doRestart() },
                onExit = { quit() },
            )
        }
    }
}

@Composable
private fun PreparingView(progress: Float) {
    NeonBackground {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text("PREPARING AUDIO", color = Neon.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).roundToInt()}%", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).background(Neon.Surface, RoundedCornerShape(4.dp))) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .height(8.dp)
                        .background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Magenta)), RoundedCornerShape(4.dp))
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Decoding full-quality audio for playback (first play only).",
                color = Neon.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    NeonBackground {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text("CAN'T START THIS SONG", color = Neon.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Text(message, color = Neon.TextDim, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            NeonButton("BACK", onClick = onBack)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
