package com.kiongozi.app.beatmap

import android.content.Context
import android.net.Uri
import com.kiongozi.app.audio.AudioDecoder
import com.kiongozi.app.audio.DecodeException
import com.kiongozi.app.audio.MelParams
import com.kiongozi.app.audio.MelSpectrogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PrepareException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class PrepareResult(val beatmap: Beatmap, val fromCache: Boolean, val key: String, val sourceUri: String?)

/**
 * audio file -> decode -> log-mel -> beat_this -> chart -> cache.
 * Cache hit = instant. If only the generator rules changed, charts are rebuilt from the cached
 * analysis in well under a second (no neural network).
 */
class BeatmapPipeline(private val context: Context, private val cache: ChartCacheManager) {

    enum class Stage { DECODING, ANALYZING, BUILDING }

    data class Progress(val stage: Stage, val fraction: Float)

    /** Instant path for the "recent songs" list. Returns null if nothing usable is cached. */
    suspend fun loadFromCache(key: String): PrepareResult? = withContext(Dispatchers.IO) {
        cache.loadBeatmap(key)?.let {
            cache.markUsed(key)
            return@withContext PrepareResult(it, true, key, null)
        }
        val analysis = cache.loadAnalysis(key) ?: return@withContext null
        if (!analysis.hasBands) return@withContext null // Batch 2 cache: needs the frequency layers added
        val title = cache.listRecents().firstOrNull { it.key == key }?.title ?: "Song"
        val beatmap = ChartGenerator.generateAll(analysis, title, key)
        cache.save(key, beatmap, null, null)
        PrepareResult(beatmap, true, key, null)
    }

    suspend fun prepare(uri: Uri, onProgress: (Progress) -> Unit): PrepareResult = withContext(Dispatchers.Default) {
        val info = cache.describe(uri)
        val key = cache.keyFor(uri, info)
        val title = info.displayName.substringBeforeLast('.', info.displayName)

        loadFromCache(key)?.let { return@withContext it.copy(sourceUri = uri.toString()) }

        // ---- Stage 1: decode + log-mel in one streaming pass ----
        onProgress(Progress(Stage.DECODING, 0f))
        val mel = MelSpectrogram(MelParams.load(context))
        val decoded = try {
            AudioDecoder(context).decode(
                uri,
                onProgress = { onProgress(Progress(Stage.DECODING, it)) },
                onSamples = { buf, n -> mel.push(buf, n) },
            )
        } catch (e: DecodeException) {
            throw PrepareException(e.message ?: "Couldn't decode this audio", e)
        }
        mel.finish()
        ensureActive()
        if (mel.frameCount < 200) throw PrepareException("This audio is too short (need at least ~4 seconds)")

        // ---- Stage 2: neural beat tracking (skipped if an older cache already has the beats) ----
        val legacy = cache.loadAnalysis(key)
        onProgress(Progress(Stage.ANALYZING, 0f))
        val beat = if (legacy != null) {
            BeatResult(legacy.beatsMs.toList(), legacy.downbeatsMs.toList(), legacy.bpm, legacy.beatsPerBar)
        } else try {
            BeatTracker(context).use { tracker ->
                tracker.open()
                tracker.track(mel.store) { onProgress(Progress(Stage.ANALYZING, it)) }
            }
        } catch (e: NoBeatsException) {
            throw PrepareException(e.message ?: "No beat found", e)
        }
        onProgress(Progress(Stage.ANALYZING, 1f))
        ensureActive()

        // ---- Stage 3: chart ----
        onProgress(Progress(Stage.BUILDING, 0f))
        val f = mel.features()
        val analysis = Analysis(
            durationMs = decoded.durationMs,
            bpm = beat.bpm,
            beatsPerBar = beat.beatsPerBar,
            beatsMs = beat.beatsMs.toLongArray(),
            downbeatsMs = beat.downbeatsMs.toLongArray(),
            flux = f.flux,
            centroid = f.centroid,
            energy = f.energy,
            bandFlux = f.bandFlux,
            bandEnergy = f.bandEnergy,
            bandCentroid = f.bandCentroid,
        )
        val beatmap = ChartGenerator.generateAll(analysis, title, key)
        cache.save(key, beatmap, analysis, uri.toString())
        onProgress(Progress(Stage.BUILDING, 1f))
        PrepareResult(beatmap, false, key, uri.toString())
    }
}
