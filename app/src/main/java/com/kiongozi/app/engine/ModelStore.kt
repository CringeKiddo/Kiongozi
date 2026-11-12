package com.kiongozi.app.engine

import android.content.Context
import java.io.File

/** Makes sure beat_this.onnx exists as a real file in filesDir (ORT loads from a path). */
object ModelStore {
    private const val ASSET = "models/beat_this.onnx"

    fun ensureModel(context: Context): File {
        val target = File(context.filesDir, "models/beat_this.onnx")
        target.parentFile?.mkdirs()
        val lastUpdate = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (!target.exists() || target.lastModified() < lastUpdate) {
            val tmp = File(target.parentFile, "beat_this.onnx.tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
        return target
    }
}
