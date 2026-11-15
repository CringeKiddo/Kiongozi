package com.kiongozi.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kiongozi.app.beatmap.Difficulty
import com.kiongozi.app.game.FpsMode
import com.kiongozi.app.game.GameMode
import com.kiongozi.app.game.GameSettings
import com.kiongozi.app.game.HiddenMode
import com.kiongozi.app.game.HitSoundType
import com.kiongozi.app.game.LaneView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val Context.kiongoziDataStore: DataStore<Preferences> by preferencesDataStore(name = "kiongozi_settings")

/**
 * All preferences, persisted across restarts with Jetpack DataStore.
 *
 *   val settings by repo.flow.collectAsState(GameSettings())      // Compose
 *   repo.update { it.copy(musicVolume = 50) }                      // suspend
 *   val snapshot = repo.current()                                  // suspend, one-shot read
 */
class SettingsRepository private constructor(context: Context) {
    private val store = context.kiongoziDataStore

    val flow: Flow<GameSettings> = store.data.map { it.toSettings() }

    suspend fun current(): GameSettings = flow.first()

    suspend fun update(transform: (GameSettings) -> GameSettings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }

        private val K_FPS = stringPreferencesKey("fps_mode")
        private val K_SHOW_FPS = booleanPreferencesKey("show_fps")
        private val K_SPEED = floatPreferencesKey("scroll_speed")
        private val K_LANE_VIEW = stringPreferencesKey("lane_view")
        private val K_LANE_W = floatPreferencesKey("lane_width_scale")
        private val K_NOTE = floatPreferencesKey("note_scale")
        private val K_AUDIO_OFFSET = intPreferencesKey("audio_offset_ms")
        private val K_VISUAL_OFFSET = intPreferencesKey("visual_offset_ms")
        private val K_MUSIC_VOL = intPreferencesKey("music_volume")
        private val K_HIT_VOL = intPreferencesKey("hit_volume")
        private val K_HIT_SOUND = stringPreferencesKey("hit_sound")
        private val K_BG_DIM = intPreferencesKey("bg_dim")
        private val K_BLUR = booleanPreferencesKey("blur_art")
        private val K_EARLY_LATE = booleanPreferencesKey("show_early_late")
        private val K_FCAP = booleanPreferencesKey("fc_ap_indicator")
        private val K_MIRROR = booleanPreferencesKey("mirror")
        private val K_NOFAIL = booleanPreferencesKey("no_fail")
        private val K_HIDDEN = stringPreferencesKey("hidden_mode")
        private val K_AUTO = booleanPreferencesKey("auto_play")
        private val K_DIFF = stringPreferencesKey("difficulty")
        private val K_MODE = stringPreferencesKey("game_mode")

        private inline fun <reified T : Enum<T>> parse(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default

        private fun Preferences.toSettings(): GameSettings {
            val d = GameSettings()
            return GameSettings(
                fpsMode = parse(this[K_FPS], d.fpsMode),
                showFps = this[K_SHOW_FPS] ?: d.showFps,
                scrollSpeed = (((this[K_SPEED] ?: d.scrollSpeed) * 2f).roundToInt() / 2f).coerceIn(1f, 9f),
                laneView = parse(this[K_LANE_VIEW], d.laneView),
                laneWidthScale = (this[K_LANE_W] ?: d.laneWidthScale).coerceIn(0.6f, 1.0f),
                noteScale = (this[K_NOTE] ?: d.noteScale).coerceIn(0.6f, 1.5f),
                audioOffsetMs = (this[K_AUDIO_OFFSET] ?: d.audioOffsetMs).coerceIn(-200, 200),
                visualOffsetMs = (this[K_VISUAL_OFFSET] ?: d.visualOffsetMs).coerceIn(-100, 100),
                musicVolume = (this[K_MUSIC_VOL] ?: d.musicVolume).coerceIn(0, 100),
                hitVolume = (this[K_HIT_VOL] ?: d.hitVolume).coerceIn(0, 100),
                hitSound = parse(this[K_HIT_SOUND], d.hitSound),
                bgDim = (this[K_BG_DIM] ?: d.bgDim).coerceIn(0, 95),
                blurArt = this[K_BLUR] ?: d.blurArt,
                showEarlyLate = this[K_EARLY_LATE] ?: d.showEarlyLate,
                fcApIndicator = this[K_FCAP] ?: d.fcApIndicator,
                mirror = this[K_MIRROR] ?: d.mirror,
                noFail = this[K_NOFAIL] ?: d.noFail,
                hiddenMode = parse(this[K_HIDDEN], d.hiddenMode),
                autoPlay = this[K_AUTO] ?: d.autoPlay,
                difficulty = parse(this[K_DIFF], d.difficulty),
                mode = parse(this[K_MODE], d.mode),
            )
        }

        private fun GameSettings.writeTo(p: androidx.datastore.preferences.core.MutablePreferences) {
            p[K_FPS] = fpsMode.name
            p[K_SHOW_FPS] = showFps
            p[K_SPEED] = scrollSpeed
            p[K_LANE_VIEW] = laneView.name
            p[K_LANE_W] = laneWidthScale
            p[K_NOTE] = noteScale
            p[K_AUDIO_OFFSET] = audioOffsetMs
            p[K_VISUAL_OFFSET] = visualOffsetMs
            p[K_MUSIC_VOL] = musicVolume
            p[K_HIT_VOL] = hitVolume
            p[K_HIT_SOUND] = hitSound.name
            p[K_BG_DIM] = bgDim
            p[K_BLUR] = blurArt
            p[K_EARLY_LATE] = showEarlyLate
            p[K_FCAP] = fcApIndicator
            p[K_MIRROR] = mirror
            p[K_NOFAIL] = noFail
            p[K_HIDDEN] = hiddenMode.name
            p[K_AUTO] = autoPlay
            p[K_DIFF] = difficulty.name
            p[K_MODE] = mode.name
        }
    }
}
