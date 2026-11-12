package com.kiongozi.app.ui.title

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiongozi.app.engine.ModelCheck
import com.kiongozi.app.engine.OnnxSmokeTest
import com.kiongozi.app.ui.theme.Neon
import com.kiongozi.app.ui.theme.NeonBackground
import kotlinx.coroutines.launch

@Composable
fun TitleScreen(onSelectSong: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var benchRunning by remember { mutableStateOf(false) }
    var modelCheck by remember { mutableStateOf<ModelCheck?>(null) }
    var liveLines by remember { mutableStateOf(listOf<String>()) }

    val transition = rememberInfiniteTransition(label = "title")
    val glow by transition.animateFloat(
        initialValue = 18f, targetValue = 44f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "glow",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    NeonBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(96.dp))
            Text(
                text = "KIONGOZI",
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Magenta)),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                    shadow = Shadow(color = Neon.Cyan.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = glow),
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "OFFLINE  ·  AI BEATMAPS  ·  YOUR MUSIC",
                color = Neon.TextDim, fontSize = 12.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(72.dp))

            NeonButton("PLAY A SONG", onClick = onSelectSong)

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (benchRunning) "benchmark running…" else "ENGINE BENCHMARK",
                color = Neon.TextDim, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier
                    .clickable(enabled = !benchRunning) {
                        benchRunning = true
                        modelCheck = null
                        liveLines = emptyList()
                        scope.launch {
                            modelCheck = OnnxSmokeTest.run(context) { liveLines = it }
                            benchRunning = false
                        }
                    }
                    .padding(8.dp),
            )

            val shown = modelCheck?.lines ?: liveLines
            if (shown.isNotEmpty()) {
                val accent = when {
                    modelCheck == null -> Neon.Violet
                    modelCheck!!.ok -> Neon.Lime
                    else -> Neon.Red
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .background(Neon.Surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = if (modelCheck == null) "NEURAL ENGINE  ·  testing…" else "NEURAL ENGINE",
                        color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        modifier = if (modelCheck == null) Modifier.alpha(pulse) else Modifier,
                    )
                    Spacer(Modifier.height(8.dp))
                    shown.forEach {
                        Text(it, color = Neon.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun NeonButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = if (enabled) listOf(Neon.Cyan, Neon.Magenta) else listOf(Neon.TextDim, Neon.TextDim)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Brush.horizontalGradient(colors), RoundedCornerShape(14.dp))
            .background(Neon.Surface.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Neon.Cyan else Neon.TextDim,
            fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
        )
    }
}
