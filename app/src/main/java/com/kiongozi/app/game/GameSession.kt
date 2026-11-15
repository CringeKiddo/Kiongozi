package com.kiongozi.app.game

import android.content.Context
import android.graphics.Bitmap
import com.kiongozi.app.beatmap.Note
import kotlin.math.max

/** Mirror Mode: flips lanes and target X positions left-to-right. */
private fun mirrorNote(n: Note): Note = n.copy(
    lane = 4 - n.lane,
    x = 1f - n.x,
    entryAngleDeg = ((180f - n.entryAngleDeg) % 360f + 360f) % 360f,
    curve = -n.curve,
)

/** One play-through: audio clock + judging + hit sounds + settings snapshot + background. */
class GameSession(
    context: Context,
    val args: GameArgs,
    val pcm: PcmInfo,
    val settings: GameSettings,
) {
    val notes: List<Note> = (args.beatmap.chart(args.difficulty)?.notes ?: emptyList())
        .let { list -> if (settings.mirror) list.map { mirrorNote(it) } else list }
        // No-Holds modifier: every hold becomes a plain tap at its start time
        .let { list -> if (settings.noHolds) list.map { if (it.holdDurationMs > 0) it.copy(holdDurationMs = 0) else it } else list }

    val sync = AudioSyncEngine(pcm).also {
        it.audioOffsetMs = settings.audioOffsetMs
        it.musicVolume = settings.musicVolume / 100f
    }

    val judgment = JudgmentEngine(
        chartNotes = notes,
        trackOf = { n: Note -> if (args.mode == GameMode.LANES) n.lane else n.button.ordinal },
        trackCount = if (args.mode == GameMode.LANES) 5 else 4,
        noFail = settings.noFail,
    )

    val hitSounds = HitSounds(context.applicationContext, settings.hitSound, settings.hitVolume)

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
        hitSounds.release()
        background = null
    }
}
