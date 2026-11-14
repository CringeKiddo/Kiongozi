package com.kiongozi.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiongozi.app.game.FpsMode
import com.kiongozi.app.game.FrameRateController
import com.kiongozi.app.game.GameSettings
import com.kiongozi.app.game.HitSoundType
import com.kiongozi.app.game.HitSounds
import com.kiongozi.app.game.LaneView
import com.kiongozi.app.settings.SettingsRepository
import com.kiongozi.app.ui.common.NeonChip
import com.kiongozi.app.ui.common.NeonSlider
import com.kiongozi.app.ui.common.NeonTextButton
import com.kiongozi.app.ui.common.NeonToggle
import com.kiongozi.app.ui.common.SectionTitle
import com.kiongozi.app.ui.theme.Neon
import com.kiongozi.app.ui.theme.NeonBackground
import com.kiongozi.app.ui.title.NeonButton
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit, onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository.get(context) }
    val s by repo.flow.collectAsState(initial = GameSettings())
    fun upd(t: (GameSettings) -> GameSettings) {
        scope.launch { repo.update(t) }
    }

    // preview player for the hit-sound TEST button
    val preview = remember { arrayOfNulls<HitSounds>(1) }
    DisposableEffect(s.hitSound, s.hitVolume) {
        val hs = HitSounds(context, s.hitSound, s.hitVolume)
        preview[0] = hs
        onDispose { hs.release() }
    }

    BackHandler { onBack() }

    val maxHz = remember { FrameRateController.maxRefreshRate(context as? android.app.Activity).toInt() }

    NeonBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                "‹  BACK", color = Neon.TextDim, fontSize = 13.sp, letterSpacing = 3.sp,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("SETTINGS", color = Neon.Cyan, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)

            // ---------------- DISPLAY ----------------
            SectionTitle("DISPLAY")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FpsMode.values().forEach { f ->
                    NeonChip(f.label, s.fpsMode == f, if (f == FpsMode.AUTO) "MAX $maxHz Hz" else null) { upd { it.copy(fpsMode = f) } }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Timing never depends on FPS: notes are placed from the audio clock. A rate above your screen's maximum is capped.",
                color = Neon.TextDim, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            NeonToggle("LIVE FPS COUNTER", "Small overlay in the top corner during play", s.showFps) { v -> upd { it.copy(showFps = v) } }

            // ---------------- GAMEPLAY ----------------
            SectionTitle("GAMEPLAY")
            NeonSlider(
                "NOTE SCROLL SPEED", s.scrollSpeed, 1f..9f, steps = 15,
                display = { "%.1fx".format(it) },
            ) { v -> upd { it.copy(scrollSpeed = (v * 2f).roundToInt() / 2f) } }
            Text("Applies to KALPA lane speed and DIVA icon flight speed.", color = Neon.TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Text("5-LANE VIEW", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LaneView.values().forEach { v -> NeonChip(v.label, s.laneView == v) { upd { it.copy(laneView = v) } } }
            }
            Spacer(Modifier.height(8.dp))
            NeonSlider(
                "LANE WIDTH", s.laneWidthScale, 0.6f..1f, steps = 7,
                display = { "${(it * 100).roundToInt()}%" },
            ) { v -> upd { it.copy(laneWidthScale = v) } }
            NeonSlider(
                "NOTE SIZE", s.noteScale, 0.6f..1.5f, steps = 17,
                display = { "${(it * 100).roundToInt()}%" },
            ) { v -> upd { it.copy(noteScale = v) } }

            // ---------------- TIMING ----------------
            SectionTitle("TIMING")
            NeonSlider(
                "AUDIO LATENCY OFFSET", s.audioOffsetMs.toFloat(), -200f..200f,
                display = { "%+d ms".format(it.roundToInt()) },
            ) { v -> upd { it.copy(audioOffsetMs = v.roundToInt()) } }
            Text(
                "Positive = you hear the audio later than the game clock (Bluetooth), so notes arrive later.",
                color = Neon.TextDim, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            NeonButton("TAP TO CALIBRATE", onClick = onCalibrate)
            Spacer(Modifier.height(14.dp))
            NeonSlider(
                "VISUAL OFFSET", s.visualOffsetMs.toFloat(), -100f..100f,
                display = { "%+d ms".format(it.roundToInt()) },
            ) { v -> upd { it.copy(visualOffsetMs = v.roundToInt()) } }
            Text("Shifts only what is drawn; judging stays on the audio clock.", color = Neon.TextDim, fontSize = 11.sp)

            // ---------------- AUDIO ----------------
            SectionTitle("AUDIO")
            NeonSlider(
                "MUSIC VOLUME", s.musicVolume.toFloat(), 0f..100f,
                display = { "${it.roundToInt()}%" },
            ) { v -> upd { it.copy(musicVolume = v.roundToInt()) } }
            NeonSlider(
                "HIT SOUND VOLUME", s.hitVolume.toFloat(), 0f..100f,
                display = { "${it.roundToInt()}%" },
            ) { v -> upd { it.copy(hitVolume = v.roundToInt()) } }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HitSoundType.values().forEach { t -> NeonChip(t.label, s.hitSound == t) { upd { it.copy(hitSound = t) } } }
            }
            Spacer(Modifier.height(8.dp))
            NeonTextButton("TEST HIT SOUND") { preview[0]?.play() }

            // ---------------- VISUAL ----------------
            SectionTitle("VISUAL")
            NeonSlider(
                "BACKGROUND DIM", s.bgDim.toFloat(), 0f..95f,
                display = { "${it.roundToInt()}%" },
            ) { v -> upd { it.copy(bgDim = v.roundToInt()) } }
            NeonToggle("ALBUM ART BLUR", "Off = sharper cover art behind the lanes", s.blurArt) { v -> upd { it.copy(blurArt = v) } }
            NeonToggle("FAST / SLOW INDICATOR", "Small label under each non-perfect judgment", s.showEarlyLate) { v -> upd { it.copy(showEarlyLate = v) } }
            NeonToggle("FC / AP INDICATOR", "Gold border = all perfect, cyan = full combo, fades on first miss", s.fcApIndicator) { v -> upd { it.copy(fcApIndicator = v) } }

            Spacer(Modifier.height(26.dp))
            NeonTextButton("RESET ALL SETTINGS", Neon.Red) { scope.launch { repo.reset() } }
            Spacer(Modifier.height(32.dp))
        }
    }
}
