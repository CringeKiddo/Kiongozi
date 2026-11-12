package com.kiongozi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kiongozi.app.ui.prep.SongPrepController
import com.kiongozi.app.ui.prep.SongPrepScreen
import com.kiongozi.app.ui.theme.KiongoziTheme
import com.kiongozi.app.ui.title.TitleScreen

private enum class Screen { TITLE, SONG_PREP }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KiongoziTheme {
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val controller = remember { SongPrepController(context, scope) }
                var screen by remember { mutableStateOf(Screen.TITLE) }

                BackHandler(enabled = screen == Screen.SONG_PREP) {
                    controller.cancel()
                    screen = Screen.TITLE
                }

                when (screen) {
                    Screen.TITLE -> TitleScreen(onSelectSong = { screen = Screen.SONG_PREP })
                    Screen.SONG_PREP -> SongPrepScreen(
                        controller = controller,
                        onBack = {
                            controller.cancel()
                            screen = Screen.TITLE
                        },
                    )
                }
            }
        }
    }
}
