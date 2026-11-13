package com.kiongozi.app.beatmap

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

data class SourceInfo(val displayName: String, val sizeBytes: Long)

data class CachedSong(
    val key: String,
    val title: String,
    val bpm: Double,
    val durationMs: Long,
    val uri: String?,
    val lastUsedMs: Long,
)

/**
 * Charts live in filesDir/charts/:
 *   <key>.json      generated beatmap (all 4 difficulties, both game modes)
 *   <key>.analysis  beat grid + 50 fps features, so charts can be rebuilt instantly if the
 *                   generator rules change (no AI re-run)
 *   <key>.meta      tiny summary for the recent-songs list
 * key = SHA-256(display name | size | first 64 KB of the file), first 32 hex chars.
 */
class ChartCacheManager(private val context: Context) {
    private val dir = File(context.filesDir, "charts").apply { mkdirs() }

    fun describe(uri: Uri): SourceInfo {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        return SourceInfo(name ?: uri.lastPathSegment ?: "unknown", size)
    }

    fun keyFor(uri: Uri, info: SourceInfo): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${info.displayName}|${info.sizeBytes}|".toByteArray())
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                md.update(buf, 0, total)
            }
        } catch (_: Exception) {
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    fun loadBeatmap(key: String): Beatmap? {
        val f = File(dir, "$key.json")
        if (!f.exists()) return null
        return try {
            val b = Beatmap.fromJson(JSONObject(f.readText()))
            if (b.generatorVersion == ChartGenerator.VERSION) b else null
        } catch (_: Exception) {
            null
        }
    }

    fun loadAnalysis(key: String): Analysis? {
        val f = File(dir, "$key.analysis")
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { s ->
                val magic = s.readInt()
                if (magic != MAGIC_V1 && magic != MAGIC) return null
                val duration = s.readLong()
                val bpm = s.readDouble()
                val bpb = s.readInt()
                val beats = LongArray(s.readInt()) { s.readLong() }
                val downs = LongArray(s.readInt()) { s.readLong() }
                val frames = s.readInt()
                val flux = FloatArray(frames) { s.readFloat() }
                val centroid = FloatArray(frames) { s.readFloat() }
                val energy = FloatArray(frames) { s.readFloat() }
                if (magic == MAGIC && s.readBoolean()) {
                    val bf = Array(3) { FloatArray(frames) { s.readFloat() } }
                    val be = Array(3) { FloatArray(frames) { s.readFloat() } }
                    val bc = Array(3) { FloatArray(frames) { s.readFloat() } }
                    Analysis(duration, bpm, bpb, beats, downs, flux, centroid, energy, bf, be, bc)
                } else {
                    Analysis(duration, bpm, bpb, beats, downs, flux, centroid, energy)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save(key: String, beatmap: Beatmap, analysis: Analysis?, sourceUri: String?) {
        writeAtomic(File(dir, "$key.json")) { it.writeText(beatmap.toJson().toString()) }
        if (analysis != null) {
            writeAtomic(File(dir, "$key.analysis")) { f ->
                DataOutputStream(f.outputStream().buffered()).use { s ->
                    s.writeInt(MAGIC)
                    s.writeLong(analysis.durationMs)
                    s.writeDouble(analysis.bpm)
                    s.writeInt(analysis.beatsPerBar)
                    s.writeInt(analysis.beatsMs.size)
                    analysis.beatsMs.forEach { s.writeLong(it) }
                    s.writeInt(analysis.downbeatsMs.size)
                    analysis.downbeatsMs.forEach { s.writeLong(it) }
                    s.writeInt(analysis.frameCount)
                    analysis.flux.forEach { s.writeFloat(it) }
                    analysis.centroid.forEach { s.writeFloat(it) }
                    analysis.energy.forEach { s.writeFloat(it) }
                    s.writeBoolean(analysis.hasBands)
                    if (analysis.hasBands) {
                        analysis.bandFlux!!.forEach { arr -> arr.forEach { s.writeFloat(it) } }
                        analysis.bandEnergy!!.forEach { arr -> arr.forEach { s.writeFloat(it) } }
                        analysis.bandCentroid!!.forEach { arr -> arr.forEach { s.writeFloat(it) } }
                    }
                }
            }
        }
        val prevUri = sourceUri ?: readMeta(key)?.optString("uri")?.takeIf { it.isNotEmpty() }
        writeMeta(key, beatmap, prevUri, System.currentTimeMillis())
    }

    fun markUsed(key: String) {
        val m = readMeta(key) ?: return
        m.put("lastUsedMs", System.currentTimeMillis())
        writeAtomic(File(dir, "$key.meta")) { it.writeText(m.toString()) }
    }

    fun listRecents(): List<CachedSong> {
        val files = dir.listFiles { f -> f.name.endsWith(".meta") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                CachedSong(
                    key = f.name.removeSuffix(".meta"),
                    title = o.getString("title"),
                    bpm = o.getDouble("bpm"),
                    durationMs = o.getLong("durationMs"),
                    uri = o.optString("uri").takeIf { it.isNotEmpty() },
                    lastUsedMs = o.optLong("lastUsedMs", 0L),
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.lastUsedMs }
    }

    fun delete(key: String) {
        File(dir, "$key.json").delete()
        File(dir, "$key.analysis").delete()
        File(dir, "$key.meta").delete()
    }

    private fun readMeta(key: String): JSONObject? = try {
        JSONObject(File(dir, "$key.meta").readText())
    } catch (_: Exception) {
        null
    }

    private fun writeMeta(key: String, b: Beatmap, uri: String?, now: Long) {
        val o = JSONObject()
        o.put("title", b.title)
        o.put("bpm", b.bpm)
        o.put("durationMs", b.durationMs)
        o.put("lastUsedMs", now)
        if (uri != null) o.put("uri", uri)
        writeAtomic(File(dir, "$key.meta")) { it.writeText(o.toString()) }
    }

    private fun writeAtomic(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        write(tmp)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private companion object {
        const val MAGIC_V1 = 0x4B474131 // "KGA1" (Batch 2: no frequency layers)
        const val MAGIC = 0x4B474132    // "KGA2"
    }
}
