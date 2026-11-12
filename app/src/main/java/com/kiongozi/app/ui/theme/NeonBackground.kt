package com.kiongozi.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/** Dark gradient + scrolling synthwave perspective grid, shared by every screen. */
@Composable
fun NeonBackground(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "bg")
    val scroll by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "grid",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Neon.BgTop, Neon.Bg)))
    ) {
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
        content()
    }
}
