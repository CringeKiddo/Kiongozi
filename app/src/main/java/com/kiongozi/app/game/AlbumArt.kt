package com.kiongozi.app.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts embedded cover art and returns a small, heavily blurred bitmap (about 72 px wide).
 * It is stretched over the screen later, which is itself a cheap extra blur. Null if the file has no art.
 */
object AlbumArt {
    suspend fun loadBlurred(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val bytes = mmr.embeddedPicture ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
            val w = 72
            val h = (72f * full.height / full.width).toInt().coerceIn(24, 144)
            val small = Bitmap.createScaledBitmap(full, w, h, true)
            if (small !== full) full.recycle()
            boxBlur(small, 3, 3)
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Simple repeated box blur on a tiny bitmap (fast enough on the main thread budget, done on IO). */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        var px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var tmp = IntArray(w * h)
        repeat(passes) {
            blurLine(px, tmp, w, h, radius, true)
            blurLine(tmp, px, w, h, radius, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var c = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) src[line * w + j] else src[j * w + line]
                    a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; c++
                }
                val o = ((a / c) shl 24) or ((rr / c) shl 16) or ((g / c) shl 8) or (b / c)
                if (horizontal) dst[line * w + i] = o else dst[i * w + line] = o
            }
        }
    }
}
