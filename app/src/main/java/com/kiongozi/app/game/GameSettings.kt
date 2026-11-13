package com.kiongozi.app.game

import android.content.Context
import com.kiongozi.app.beatmap.Difficulty

enum class FpsMode(val label: String, val hz: Int) {
    AUTO("AUTO", 0),
    FPS60("60", 60),
    FPS90("90", 90),
    FPS120("120", 120),
}

/** Persisted player settings (SharedPreferences). */
class GameSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kiongozi_settings", Context.MODE_PRIVATE)

    var fpsMode: FpsMode
        get() = FpsMode.values().firstOrNull { it.name == prefs.getString("fps", "AUTO") } ?: FpsMode.AUTO
        set(v) = prefs.edit().putString("fps", v.name).apply()

    /** -200..+200 ms. Positive = you hear the audio later than the clock says, so notes arrive later. */
    var audioOffsetMs: Int
        get() = prefs.getInt("offset", 0).coerceIn(-200, 200)
        set(v) = prefs.edit().putInt("offset", v.coerceIn(-200, 200)).apply()

    /** Time a note takes to travel from spawn to the judgment line. Lower = faster scroll. */
    var approachMs: Int
        get() = prefs.getInt("approach", 1400).coerceIn(700, 2400)
        set(v) = prefs.edit().putInt("approach", v.coerceIn(700, 2400)).apply()

    var noFail: Boolean
        get() = prefs.getBoolean("nofail", false)
        set(v) = prefs.edit().putBoolean("nofail", v).apply()

    var showFps: Boolean
        get() = prefs.getBoolean("showfps", true)
        set(v) = prefs.edit().putBoolean("showfps", v).apply()

    var difficulty: Difficulty
        get() = Difficulty.values().firstOrNull { it.name == prefs.getString("difficulty", "NORMAL") } ?: Difficulty.NORMAL
        set(v) = prefs.edit().putString("difficulty", v.name).apply()

    var mode: GameMode
        get() = GameMode.values().firstOrNull { it.name == prefs.getString("mode", "LANES") } ?: GameMode.LANES
        set(v) = prefs.edit().putString("mode", v.name).apply()
}
