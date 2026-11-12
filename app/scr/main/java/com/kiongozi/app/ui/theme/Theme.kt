package com.kiongozi.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Neon {
    val Bg = Color(0xFF05010F)
    val BgTop = Color(0xFF14052E)
    val Surface = Color(0xFF0E0820)
    val Cyan = Color(0xFF00F0FF)
    val Magenta = Color(0xFFFF2BD6)
    val Violet = Color(0xFF8A5CFF)
    val Lime = Color(0xFFB6FF3B)
    val Red = Color(0xFFFF4D6D)
    val TextDim = Color(0xFF8D86A8)
}

@Composable
fun KiongoziTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan,
            secondary = Neon.Magenta,
            tertiary = Neon.Violet,
            background = Neon.Bg,
            surface = Neon.Surface,
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}
