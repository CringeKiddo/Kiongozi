package com.kiongozi.app.game

import com.kiongozi.app.beatmap.Difficulty
import kotlin.math.pow

enum class FpsMode(val label: String, val hz: Int) {
    AUTO("AUTO", 0),
    FPS60("60", 60),
    FPS90("90", 90),
    FPS120("120", 120),
}

/** How the 5-lane mode is drawn. TILTED = classic perspective highway (default), FLAT = straight top-down lanes. */
enum class LaneView(val label: String) {
    PERSPECTIVE("TILTED"),
    FLAT("FLAT"),
}

/** Sudden+ hides notes in the top half of the lane (they fade in); Hidden fades them out past the middle. */
enum class HiddenMode(val label: String) {
    OFF("OFF"),
    SUDDEN("SUDDEN+"),
    HIDDEN("HIDDEN"),
    BOTH("BOTH"),
}

enum class HitSoundType(val label: String) {
    KEY("KEY"),
    TAMBOURINE("TAMBOURINE"),
    CLAP("CLAP"),
}

/**
 * Immutable snapshot of every user preference. Stored with Jetpack DataStore (see SettingsRepository);
 * the game takes one snapshot when a song starts, so nothing changes mid-song by accident.
 */
data class GameSettings(
    // display
    val fpsMode: FpsMode = FpsMode.AUTO,
    val showFps: Boolean = false,
    // gameplay
    val scrollSpeed: Float = 3.0f,          // 1.0x .. 9.0x in 0.5 steps
    val laneView: LaneView = LaneView.PERSPECTIVE,
    val laneWidthScale: Float = 1.0f,       // 0.6 .. 1.0
    val noteScale: Float = 1.0f,            // 0.6 .. 1.5
    // timing
    val audioOffsetMs: Int = 0,             // -200 .. +200, positive = you hear audio later, notes arrive later
    val visualOffsetMs: Int = 0,            // -100 .. +100, positive = notes drawn later (judging unchanged)
    // audio
    val musicVolume: Int = 100,             // 0 .. 100
    val hitVolume: Int = 70,                // 0 .. 100
    val hitSound: HitSoundType = HitSoundType.KEY,
    // visuals
    val bgDim: Int = 65,                    // 0 .. 95 (% darkness over the background)
    val blurArt: Boolean = true,
    val showEarlyLate: Boolean = true,      // FAST / SLOW under judgments
    val fcApIndicator: Boolean = true,      // glowing border: gold = all perfect, cyan = full combo
    // modifiers
    val mirror: Boolean = false,
    val noFail: Boolean = false,
    val hiddenMode: HiddenMode = HiddenMode.OFF,
    val autoPlay: Boolean = false,
    /** Turns every hold note into a normal tap note. */
    val noHolds: Boolean = false,
    // last picks
    val difficulty: Difficulty = Difficulty.NORMAL,
    val mode: GameMode = GameMode.LANES,
) {
    /** Time a note takes from spawn to the judgment line. 1.0x ~ 2.6 s ... 9.0x ~ 0.56 s. */
    val approachMs: Int get() = (2600.0 * scrollSpeed.toDouble().pow(-0.7)).toInt().coerceIn(450, 3000)
}
