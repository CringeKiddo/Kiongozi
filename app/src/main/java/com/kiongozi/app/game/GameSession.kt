package com.kiongozi.app.game

import android.graphics.Bitmap
import com.kiongozi.app.beatmap.Note
import kotlin.math.max

/** One play-through: audio clock + judging + settings + background, shared by the view and the UI. */
class GameSession(
    val args: GameArgs,
    val pcm: PcmInfo,
    val settings: GameSettings,
) {
    val notes: List<Note> = args.beatmap.chart(args.difficulty)?.notes ?: emptyList()
    val sync = AudioSyncEngine(pcm).also { it.audioOffsetMs = settings.audioOffsetMs }
    val judgment = JudgmentEngine(
        chartNotes = notes,
        trackOf = { n: Note -> if (args.mode == GameMode.LANES) n.lane else n.button.ordinal },
        trackCount = if (args.mode == GameMode.LANES) 5 else 4,
        noFail = settings.noFail,
    )

    @Volatile var background: Bitmap? = null
    val approachMs: Int get() = settings.approachMs

    /** Song time after which nothing else can happen. */
    val lastNoteEndMs: Double = notes.maxOfOrNull { it.timeMs + it.holdDurationMs }?.toDouble() ?: 0.0

    fun start() {
        judgment.reset()
        sync.start()
    }

    fun restart() {
        judgment.reset()
        sync.restart()
    }

    /** The song is over when every note is resolved, the player failed, or the audio has run out. */
    fun isFinished(): Boolean {
        val t = sync.songTimeMs()
        val s = judgment.stats()
        if (s.failed) return true
        if (s.complete && t > lastNoteEndMs + 700) return true
        if (sync.writerDone && t > pcm.durationMs + 600) return true
        return t > max(lastNoteEndMs, pcm.durationMs.toDouble()) + 4000
    }

    fun release() {
        sync.release()
        background = null
    }
}
