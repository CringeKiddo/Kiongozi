package com.kiongozi.app.ui.title

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.kiongozi.app.engine.ModelCheck
import com.kiongozi.app.engine.OnnxSmokeTest
import com.kiongozi.app.ui.theme.Neon

enum class GameMode(val label: String) {
    LANES("5-LANE SCROLL"),
    DIVA("FLYING TARGETS"),
}

@Composable
fun TitleScreen() {
    val context = LocalContext.current
    var pickedSong by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(GameMode.LANES) }
    var modelCheck by remember { mutableStateOf<ModelCheck?>(null) }
    var liveLines by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        modelCheck = OnnxSmokeTest.run(context) { liveLines = it }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val doc = DocumentFile.fromSingleUri(context, uri)
            val name = doc?.name ?: "unknown file"
            val kb = (doc?.length() ?: 0L) / 1024
            pickedSong = "$name  ·  $kb KB"
        }
    }

    val transition = rememberInfiniteTransition(label = "title")
    val scroll by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "grid",
    )
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Neon.BgTop, Neon.Bg)))
    ) {
        // Synthwave perspective grid
        Canvas(Modifier.fillMaxSize()) {
            val horizon = size.height * 0.45f
            val rows = 14
            for (i in 0 until rows) {
                val p = (i + scroll) / rows
                val y = horizon + (size.height - horizon) * p * p
                drawLine(
                    color = Neon.Magenta.copy(alpha = 0.12f + 0.55f * p),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f + 3f * p,
                )
            }
            val cx = size.width / 2f
            for (i in -12..12) {
                drawLine(
                    color = Neon.Cyan.copy(alpha = 0.22f),
                    start = Offset(cx + i * size.width * 0.015f, horizon),
                    end = Offset(cx + i * size.width * 0.22f, size.height),
                    strokeWidth = 1.5f,
                )
            }
            drawLine(
                color = Neon.Violet.copy(alpha = 0.8f),
                start = Offset(0f, horizon),
                end = Offset(size.width, horizon),
                strokeWidth = 2f,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(72.dp))

            Text(
                text = "KIONGOZI",
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Magenta)),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                    shadow = Shadow(
                        color = Neon.Cyan.copy(alpha = 0.9f),
                        offset = Offset.Zero,
                        blurRadius = glow,
                    ),
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "OFFLINE  ·  AI BEATMAPS  ·  YOUR MUSIC",
                color = Neon.TextDim,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(56.dp))

            // Song picker
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        2.dp,
                        Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Magenta)),
                        RoundedCornerShape(14.dp),
                    )
                    .background(Neon.Surface.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .clickable {
                        picker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream"))
                    }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SELECT SONG",
                    color = Neon.Cyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = pickedSong ?: "MP3 · WAV · FLAC · M4A · OGG",
                color = if (pickedSong != null) Neon.Lime else Neon.TextDim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            // Mode chips (selection only for now; gameplay arrives in Batches 3 and 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameMode.values().forEach { m ->
                    ModeChip(m.label, selected = mode == m) { mode = m }
                }
            }

            Spacer(Modifier.height(36.dp))

            // Engine status card
            val check = modelCheck
            val accent = when {
                check == null -> Neon.Violet
                check.ok -> Neon.Lime
                else -> Neon.Red
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .background(Neon.Surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = when {
                        check == null -> "NEURAL ENGINE  ·  testing CPU + GPU…"
                        check.ok -> "NEURAL ENGINE  ·  READY (${check.fastest?.label})"
                        else -> "NEURAL ENGINE  ·  PROBLEM"
                    },
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = if (check == null) Modifier.alpha(pulse) else Modifier,
                )
                val shown = check?.lines ?: liveLines
                if (shown.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    shown.forEach {
                        Text(
                            text = it,
                            color = Neon.TextDim,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RowScope.ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) Neon.Magenta else Neon.TextDim
    Box(
        modifier = Modifier
            .weight(1f)
            .border(if (selected) 2.dp else 1.dp, c, RoundedCornerShape(10.dp))
            .background(
                if (selected) Neon.Magenta.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}
