package com.kiongozi.app.ui.prep

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kiongozi.app.beatmap.Beatmap
import com.kiongozi.app.beatmap.BeatmapPipeline
import com.kiongozi.app.beatmap.CachedSong
import com.kiongozi.app.beatmap.ChartCacheManager
import com.kiongozi.app.beatmap.PrepareException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PrepState {
    data object Idle : PrepState

    data class Working(
        val songName: String,
        val stage: BeatmapPipeline.Stage,
        val stageFraction: Float,
        val overall: Float,
    ) : PrepState

    data class Ready(val beatmap: Beatmap, val audioUri: Uri?, val fromCache: Boolean) : PrepState

    data class Failed(val message: String) : PrepState
}

class SongPrepController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val cache = ChartCacheManager(appContext)
    private val pipeline = BeatmapPipeline(appContext, cache)
    private var job: Job? = null

    var state by mutableStateOf<PrepState>(PrepState.Idle)
        private set
    var recents by mutableStateOf<List<CachedSong>>(emptyList())
        private set

    init {
        refreshRecents()
    }

    fun refreshRecents() {
        scope.launch {
            recents = withContext(Dispatchers.IO) { cache.listRecents() }
        }
    }

    /** User picked a file: instant if cached, otherwise decode -> AI -> chart. */
    fun prepare(uri: Uri) {
        job?.cancel()
        val name = cache.describe(uri).displayName
        state = PrepState.Working(name, BeatmapPipeline.Stage.DECODING, 0f, 0f)
        job = scope.launch {
            try {
                val r = pipeline.prepare(uri) { p ->
                    state = PrepState.Working(name, p.stage, p.fraction, overall(p))
                }
                state = PrepState.Ready(r.beatmap, r.sourceUri?.let(Uri::parse) ?: uri, r.fromCache)
                refreshRecents()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PrepareException) {
                state = PrepState.Failed(e.message ?: "Something went wrong")
            } catch (e: Throwable) {
                state = PrepState.Failed("${e.javaClass.simpleName}: ${e.message?.take(160)}")
            }
        }
    }

    /** Tap on a recent song: loads the cached chart without touching the audio file. */
    fun openCached(song: CachedSong) {
        job?.cancel()
        state = PrepState.Working(song.title, BeatmapPipeline.Stage.BUILDING, 0.5f, 0.95f)
        job = scope.launch {
            try {
                val r = pipeline.loadFromCache(song.key)
                if (r != null) {
                    state = PrepState.Ready(r.beatmap, song.uri?.let(Uri::parse), true)
                    refreshRecents()
                } else if (song.uri != null) {
                    // old cache (needs the new frequency layers): rebuild from the audio file, reusing saved beats
                    prepare(Uri.parse(song.uri))
                } else {
                    state = PrepState.Failed("This saved chart is outdated. Pick the song again to rebuild it.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = PrepState.Failed("${e.javaClass.simpleName}: ${e.message?.take(160)}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state = PrepState.Idle
    }

    fun reset() {
        state = PrepState.Idle
    }

    private fun overall(p: BeatmapPipeline.Progress): Float = when (p.stage) {
        BeatmapPipeline.Stage.DECODING -> 0.20f * p.fraction
        BeatmapPipeline.Stage.ANALYZING -> 0.20f + 0.72f * p.fraction
        BeatmapPipeline.Stage.BUILDING -> 0.92f + 0.08f * p.fraction
    }
}
