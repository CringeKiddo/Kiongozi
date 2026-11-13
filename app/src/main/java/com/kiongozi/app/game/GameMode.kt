package com.kiongozi.app.game

import android.net.Uri
import com.kiongozi.app.beatmap.Beatmap
import com.kiongozi.app.beatmap.Difficulty

enum class GameMode(val label: String) {
    LANES("5-LANE SCROLL"),
    DIVA("FLYING TARGETS"),
}

/** Everything needed to start a song. */
data class GameArgs(
    val beatmap: Beatmap,
    val audioUri: Uri?,
    val difficulty: Difficulty,
    val mode: GameMode,
)
