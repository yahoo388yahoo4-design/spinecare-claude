package com.spinecare.app

import kotlin.math.max
import kotlin.math.min

/** Placement of the live guide box on the session screen: where it sits (as
 *  fractions of the free space, so it lands in the same spot after a rotation),
 *  how wide it is (dp; the height follows the demo's 3:4 frame) and whether it
 *  shows: automatically while the coach explains a move, always, or never.
 *  Saved between sessions by ServerConfig. Pure Kotlin. */
object GuideBox {
    const val MODE_AUTO = "auto"
    const val MODE_SHOWN = "shown"
    const val MODE_HIDDEN = "hidden"
    const val MIN_DP = 96f
    /** Height / width, the demo's 3:4 portrait frame, so the figure is never cut. */
    const val ASPECT = 4f / 3f

    data class State(val x: Float, val y: Float, val widthDp: Float, val mode: String)

    val DEFAULT = State(0.02f, 0.22f, 132f, MODE_AUTO)

    /** Box (left, top, width, height) in px inside a free area of [areaW]x[areaH] px. */
    fun layout(s: State, areaW: Int, areaH: Int, density: Float): IntArray {
        val w = clampWidth(s.widthDp * density, areaW, areaH, density)
        val h = w * ASPECT
        val left = s.x.coerceIn(0f, 1f) * max(0f, areaW - w)
        val top = s.y.coerceIn(0f, 1f) * max(0f, areaH - h)
        return intArrayOf(left.toInt(), top.toInt(), w.toInt(), h.toInt())
    }

    /** Widest box that still fits the area (90 % of it), never narrower than [MIN_DP]. */
    fun clampWidth(widthPx: Float, areaW: Int, areaH: Int, density: Float): Float {
        val maxW = max(1f, min(areaW * 0.9f, areaH * 0.9f / ASPECT))
        return widthPx.coerceIn(min(MIN_DP * density, maxW), maxW)
    }

    /** Keeps a box of [w]x[h] px at [left],[top] fully inside the area. */
    fun clampLeft(left: Float, w: Float, areaW: Int) = left.coerceIn(0f, max(0f, areaW - w))
    fun clampTop(top: Float, h: Float, areaH: Int) = top.coerceIn(0f, max(0f, areaH - h))

    /** State after the user moved or resized the box to ([left], [top], [w] px). */
    fun adjusted(s: State, left: Float, top: Float, w: Float, areaW: Int, areaH: Int, density: Float): State {
        val h = w * ASPECT
        val fx = if (areaW - w > 1f) (left / (areaW - w)).coerceIn(0f, 1f) else 0f
        val fy = if (areaH - h > 1f) (top / (areaH - h)).coerceIn(0f, 1f) else 0f
        return s.copy(x = fx, y = fy, widthDp = w / density)
    }

    /** Show/hide button: a visible box is hidden for good, a hidden one is kept on screen. */
    fun toggled(s: State, visibleNow: Boolean) = s.copy(mode = if (visibleNow) MODE_HIDDEN else MODE_SHOWN)

    /** Shown automatically while the coach explains a move (only in auto mode). */
    fun autoShows(s: State) = s.mode == MODE_AUTO
}
