package com.kiongozi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kiongozi.app.ui.theme.KiongoziTheme
import com.kiongozi.app.ui.title.TitleScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KiongoziTheme {
                TitleScreen()
            }
        }
    }
}
