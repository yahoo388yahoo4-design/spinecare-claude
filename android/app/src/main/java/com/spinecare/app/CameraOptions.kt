package com.spinecare.app

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure camera-screen choices shared by the session and setup screens (and
 *  mirrored in the iOS app): screen orientation, which physical lens to use
 *  (ultra-wide, wide, tele) and zoom stops. No Android types, so it is unit-tested
 *  on the JVM. */
object CameraOptions {
    const val ORIENT_AUTO = 0        // follow the phone (respects the system rotation lock)
    const val ORIENT_PORTRAIT = 1
    const val ORIENT_LANDSCAPE = 2   // either landscape side

    fun nextOrientation(mode: Int) = (mode.coerceIn(0, 2) + 1) % 3

    /** I18n key for the orientation button. */
    fun orientationKey(mode: Int) = when (mode) {
        ORIENT_PORTRAIT -> "orient_portrait"
        ORIENT_LANDSCAPE -> "orient_landscape"
        else -> "orient_auto"
    }

    // ------------------------------------------------------------------ lenses

    enum class LensKind(val key: String) { ULTRA_WIDE("lens_ultra_wide"), WIDE("lens_wide"), TELE("lens_tele") }

    /** One camera the system exposes. [focalMm] and [sensorWidthMm] give its field
     *  of view; 0 when unknown (then it is treated like the main camera). */
    data class CameraDesc(val id: String, val front: Boolean, val focalMm: Float, val sensorWidthMm: Float)

    /** A lens the user can pick: bind [cameraId] and start at [zoomRatio].
     *  [relZoom] is the field of view relative to the main camera (0.5 = twice as wide). */
    data class Lens(val cameraId: String, val kind: LensKind, val relZoom: Float, val zoomRatio: Float = 1f)

    private fun fovScale(c: CameraDesc): Float? =
        if (c.focalMm > 0f && c.sensorWidthMm > 0f) c.focalMm / c.sensorWidthMm else null

    fun kindFor(relZoom: Float) = when {
        relZoom < 0.8f -> LensKind.ULTRA_WIDE
        relZoom > 1.4f -> LensKind.TELE
        else -> LensKind.WIDE
    }

    /** Lenses for one side of the phone, widest first. Cameras with the same
     *  field of view as one already listed are dropped (the main camera wins).
     *  Phones that expose a single logical back camera reach their ultra-wide
     *  through a zoom ratio below 1 ([mainMinZoom]), which becomes the
     *  ultra-wide entry when no separate ultra-wide camera is listed. */
    fun lenses(cams: List<CameraDesc>, front: Boolean, mainId: String?, mainMinZoom: Float = 1f): List<Lens> {
        val side = cams.filter { it.front == front }
        if (side.isEmpty()) return emptyList()
        val main = side.firstOrNull { it.id == mainId } ?: side.first()
        val mainScale = fovScale(main)
        val out = ArrayList<Lens>()
        for (c in listOf(main) + side.filter { it !== main }) {
            val s = fovScale(c)
            val rel = if (s == null || mainScale == null) 1f else s / mainScale
            if (out.any { abs(it.relZoom - rel) / maxOf(it.relZoom, rel) < 0.1f }) continue
            out.add(Lens(c.id, kindFor(rel), rel))
        }
        if (out.none { it.kind == LensKind.ULTRA_WIDE } && mainMinZoom < 0.95f)
            out.add(Lens(main.id, LensKind.ULTRA_WIDE, mainMinZoom, mainMinZoom))
        return out.sortedBy { it.relZoom }
    }

    /** Index of the lens after [current] (wraps); 0 when nothing is selected. */
    fun nextLens(lenses: List<Lens>, current: Int) = if (lenses.isEmpty()) 0 else (current + 1).mod(lenses.size)

    /** On a camera that offers several lenses through its zoom ratio, the lens
     *  matching [zoom]: the widest whose start ratio is at or below it. -1 when
     *  [cameraId] has no such entries. */
    fun lensForZoom(lenses: List<Lens>, cameraId: String, zoom: Float): Int {
        val same = lenses.withIndex().filter { it.value.cameraId == cameraId }
        if (same.size < 2) return -1
        return same.filter { it.value.zoomRatio <= zoom + 0.01f }.maxByOrNull { it.value.zoomRatio }?.index
            ?: same.minBy { it.value.zoomRatio }.index
    }

    // ------------------------------------------------------------------ zoom

    /** Zoom ratio limited to what the bound camera supports. */
    fun clampZoom(z: Float, min: Float, max: Float): Float =
        if (z.isNaN()) maxOf(min, 1f).coerceAtMost(max) else z.coerceIn(min, maxOf(min, max))

    /** Quick zoom stops for the zoom button, within the camera's range. */
    fun zoomStops(min: Float, max: Float): List<Float> {
        val out = ArrayList<Float>()
        if (min < 0.95f) out.add(min)
        for (z in floatArrayOf(1f, 2f, 3f, 5f, 10f)) if (z >= min - 0.01f && z <= max + 0.01f) out.add(z)
        if (out.isEmpty()) out.add(min)
        return out
    }

    /** The next stop above [current], wrapping to the widest. */
    fun nextZoomStop(current: Float, min: Float, max: Float): Float {
        val stops = zoomStops(min, max)
        return stops.firstOrNull { it > current + 0.05f } ?: stops.first()
    }

    /** "0.5×", "1×", "2.5×". */
    fun zoomLabel(z: Float): String {
        val r = (z * 10).roundToInt()
        return if (r % 10 == 0) "${r / 10}×" else "${r / 10}.${abs(r % 10)}×"
    }

    // ------------------------------------------------------------------ geometry

    /** Rectangle (left, top, width, height) that fits a [srcW]x[srcH] picture
     *  inside [dstW]x[dstH] without distorting it (letterbox / pillarbox). */
    fun fit(srcW: Int, srcH: Int, dstW: Float, dstH: Float): FloatArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0f || dstH <= 0f) return floatArrayOf(0f, 0f, dstW, dstH)
        val s = min(dstW / srcW, dstH / srcH)
        val w = srcW * s; val h = srcH * s
        return floatArrayOf((dstW - w) / 2f, (dstH - h) / 2f, w, h)
    }
}
