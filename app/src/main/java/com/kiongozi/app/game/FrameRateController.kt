package com.kiongozi.app.game

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Surface
import android.view.SurfaceView

/**
 * 60 / 90 / 120 FPS / Auto (max refresh).
 *  - Android 11+: Surface.setFrameRate() on the gameplay SurfaceView's surface (the proper API).
 *  - Also asks the window for a matching display mode (preferredDisplayModeId on API 23+), which is what
 *    actually switches the panel on older phones.
 * Rendering is paced by Choreographer vsync, and all game positions come from the audio clock, so the
 * FPS choice only changes smoothness, never timing or scroll speed.
 */
object FrameRateController {

    fun maxRefreshRate(activity: Activity?): Float {
        val d = activity?.windowManager?.defaultDisplay ?: return 60f
        val cur = d.mode
        var best = d.refreshRate
        for (m in d.supportedModes) {
            if (m.physicalWidth == cur.physicalWidth && m.physicalHeight == cur.physicalHeight && m.refreshRate > best) {
                best = m.refreshRate
            }
        }
        return best
    }

    /** @return the refresh rate that was requested (Hz) */
    fun apply(view: SurfaceView, surface: Surface, mode: FpsMode): Float {
        val activity = findActivity(view.context)
        val maxHz = maxRefreshRate(activity)
        val target = if (mode == FpsMode.AUTO) maxHz else mode.hz.toFloat().coerceAtMost(maxHz)

        if (Build.VERSION.SDK_INT >= 30 && surface.isValid) {
            try {
                surface.setFrameRate(target, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            } catch (_: Exception) {
            }
        }
        // Display-mode hint: pick the same-resolution mode whose refresh rate is closest to the target
        try {
            val window = activity?.window
            val display = activity?.windowManager?.defaultDisplay
            if (window != null && display != null) {
                val cur = display.mode
                val best = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .minByOrNull { kotlin.math.abs(it.refreshRate - target) }
                if (best != null) {
                    val lp = window.attributes
                    lp.preferredDisplayModeId = best.modeId
                    window.attributes = lp
                }
            }
        } catch (_: Exception) {
        }
        return target
    }

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
