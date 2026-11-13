package com.kiongozi.app.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiongozi.app.game.GameResults
import com.kiongozi.app.game.Grade
import com.kiongozi.app.ui.theme.Neon
import com.kiongozi.app.ui.theme.NeonBackground
import com.kiongozi.app.ui.title.NeonButton

// --------------------------------------------------------------------------------------------
// Pause overlay: Resume, Restart, Quick audio offset adjust, Quit
// --------------------------------------------------------------------------------------------
@Composable
fun PauseOverlay(
    offsetMs: Int,
    onOffsetChange: (Int) -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onQuit: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC05010F))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .fillMaxWidth()
                .border(1.5.dp, Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Magenta)), RoundedCornerShape(18.dp))
                .background(Neon.Surface, RoundedCornerShape(18.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PAUSED", color = Neon.Cyan, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Spacer(Modifier.height(22.dp))
            NeonButton("RESUME", onClick = onResume)
            Spacer(Modifier.height(12.dp))
            NeonButton("RESTART", onClick = onRestart)

            Spacer(Modifier.height(22.dp))
            Text("QUICK AUDIO OFFSET", color = Neon.Magenta, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepButton("−10") { onOffsetChange(offsetMs - 10) }
                StepButton("−1") { onOffsetChange(offsetMs - 1) }
                Text(
                    "%+d ms".format(offsetMs), color = Color.White, fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp), textAlign = TextAlign.Center,
                )
                StepButton("+1") { onOffsetChange(offsetMs + 1) }
                StepButton("+10") { onOffsetChange(offsetMs + 10) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Notes feel LATE? lower it. Notes feel EARLY? raise it.",
                color = Neon.TextDim, fontSize = 11.sp, textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "QUIT", color = Neon.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                modifier = Modifier.clickable { onQuit() }.padding(12.dp),
            )
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, Neon.Cyan.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Neon.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

// --------------------------------------------------------------------------------------------
// Results
// --------------------------------------------------------------------------------------------
@Composable
fun ResultsScreen(title: String, r: GameResults, onRetry: () -> Unit, onExit: () -> Unit) {
    val scoreAnim by animateIntAsState(r.score, tween(1400), label = "score")
    val badgeScale = remember { Animatable(0.2f) }
    LaunchedEffect(Unit) { badgeScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 180f)) }

    NeonBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                if (r.failed) "SONG FAILED" else "SONG CLEARED",
                color = if (r.failed) Neon.Red else Neon.Lime, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)

            Spacer(Modifier.height(22.dp))
            RankBadge(r.grade, Modifier.scale(badgeScale.value))

            Spacer(Modifier.height(18.dp))
            Text("%07d".format(scoreAnim), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text("%.2f%%".format(r.accuracy), color = Neon.Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("MAX COMBO  ${r.maxCombo}", color = Neon.Magenta, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            if (r.noFail) {
                Spacer(Modifier.height(4.dp))
                Text("NO-FAIL ON", color = Neon.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
            }

            Spacer(Modifier.height(22.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Neon.Violet.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .background(Neon.Surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                CountRow("PERFECT", r.perfect, Color(0xFFFFE066))
                CountRow("GREAT", r.great, Color(0xFF00F0FF))
                CountRow("GOOD", r.good, Color(0xFF8D86FF))
                CountRow("MISS", r.miss, Color(0xFFFF4D6D))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.height(8.dp))
                CountRow("EARLY", r.early, Color(0xFF5CC8FF))
                CountRow("LATE", r.late, Color(0xFFFFA14D))
                CountRow("HOLD TICKS", r.holdTicks, Color.White)
            }

            Spacer(Modifier.height(26.dp))
            NeonButton("RETRY", onClick = onRetry)
            Spacer(Modifier.height(10.dp))
            Text(
                "BACK TO SONGS", color = Neon.TextDim, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onExit() }.padding(14.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CountRow(label: String, value: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(value.toString(), color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RankBadge(grade: Grade, modifier: Modifier = Modifier) {
    val (a, b) = when (grade) {
        Grade.SSS -> Color(0xFFFFE066) to Color(0xFFFF9F1C)
        Grade.SS -> Color(0xFFFFF3B0) to Color(0xFFC9A227)
        Grade.S -> Color(0xFF00F0FF) to Color(0xFF8A5CFF)
        Grade.A -> Color(0xFFB6FF3B) to Color(0xFF2EC4B6)
        Grade.B -> Color(0xFF8A5CFF) to Color(0xFF4D6BFF)
        Grade.C -> Color(0xFFB0A8C8) to Color(0xFF6A6485)
    }
    Box(
        modifier
            .size(132.dp)
            .border(5.dp, Brush.sweepGradient(listOf(a, b, a)), CircleShape)
            .background(Color(0xFF0E0820), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            grade.name,
            color = a, fontWeight = FontWeight.Black,
            fontSize = if (grade == Grade.SSS) 44.sp else 58.sp,
            letterSpacing = if (grade == Grade.SSS) 1.sp else 0.sp,
        )
    }
}
