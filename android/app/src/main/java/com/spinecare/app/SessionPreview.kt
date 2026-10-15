package com.spinecare.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Thumbnails for the saved-sessions list: a frame from the middle of the
 *  recorded video (overlay burned in; the list crops it to fill the tile) or,
 *  without video, the skeleton at the deepest point of the movement, zoomed to
 *  fill the tile, plus a sparkline of the exercise metric. */
object SessionPreview {
    private const val W = 320
    private const val H = 240
    private val cache = LruCache<String, Bitmap>(40)

    /** Blocking (call off the main thread); null when there is nothing to show. */
    fun thumbnail(video: File?, timeline: File?): Bitmap? {
        val key = (video?.name ?: "") + "|" + (timeline?.name ?: "")
        cache.get(key)?.let { return it }
        val bmp = video?.takeIf { it.exists() }?.let { videoFrame(it) }
            ?: timeline?.takeIf { it.exists() }?.let { SessionTimeline.read(it) }?.let { render(it) }
        bmp?.let { cache.put(key, it) }
        return bmp
    }

    private fun videoFrame(f: File): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = r.getFrameAtTime(durMs * 1000 / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
            val s = min(W.toFloat() / frame.width, H.toFloat() / frame.height)
            Bitmap.createScaledBitmap(frame, (frame.width * s).toInt().coerceAtLeast(1), (frame.height * s).toInt().coerceAtLeast(1), true)
        } finally { runCatching { r.release() } }
    }.getOrNull()

    /** Sample to show: the largest exercise signal, else the middle sample with landmarks. */
    fun peak(tl: SessionTimeline.Timeline): SessionTimeline.Sample? =
        tl.samples.filter { it.signal != null && it.landmarks.isNotEmpty() }.maxByOrNull { abs(it.signal!!) }
            ?: tl.samples.filter { it.landmarks.isNotEmpty() }.let { it.getOrNull(it.size / 2) }

    /** Bounds (x0, y0, x1, y1, as drawn, i.e. mirrored when [mirror]) of the visible
     *  landmarks of a [fw]x[fh] frame, at least a fifth of the frame in each direction. */
    fun bodyBox(px: Map<String, Metrics.P>, fw: Int, fh: Int, mirror: Boolean): List<Float>? {
        val vis = px.values.filter { it.c > Metrics.VIS }
        if (vis.size < 2) return null
        var x0 = vis.minOf { it.x }.toFloat(); var x1 = vis.maxOf { it.x }.toFloat()
        if (mirror) { val a = fw - x1; x1 = fw - x0; x0 = a }
        var y0 = vis.minOf { it.y }.toFloat(); var y1 = vis.maxOf { it.y }.toFloat()
        val minW = fw / 5f; val minH = fh / 5f
        if (x1 - x0 < minW) { val m = (x0 + x1) / 2; x0 = m - minW / 2; x1 = m + minW / 2 }
        if (y1 - y0 < minH) { val m = (y0 + y1) / 2; y0 = m - minH / 2; y1 = m + minH / 2 }
        // the head reaches above the ears and nose
        y0 -= (y1 - y0) * 0.08f
        return listOf(x0, y0, x1, y1)
    }

    fun render(tl: SessionTimeline.Timeline): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Ui.SURFACE)
        // fill the thumbnail: zoom onto the body instead of letterboxing the whole frame
        peak(tl)?.let { s ->
            val fw = s.frameW.takeIf { it > 0 } ?: 480
            val fh = s.frameH.takeIf { it > 0 } ?: 640
            val px = s.pixels()
            val box = bodyBox(px, fw, fh, s.mirror)
            c.save()
            if (box != null) {
                val (x0, y0, x1, y1) = box
                val k = min(W / ((x1 - x0) * 1.3f), H / ((y1 - y0) * 1.3f))
                c.translate(W / 2f - (x0 + x1) / 2f * k, H / 2f - (y0 + y1) / 2f * k)
                c.scale(k, k)
                OverlayRenderer.draw(c, px, fw, fh, emptyList(), true, "", "", "", s.mirror, fw.toFloat(), fh.toFloat())
            } else {
                OverlayRenderer.draw(c, px, fw, fh, emptyList(), true, "", "", "", s.mirror, W.toFloat(), H.toFloat())
            }
            c.restore()
        }
        // sparkline of the exercise metric along the bottom
        val vals = tl.samples.mapNotNull { s -> s.metrics[tl.metric]?.let { s.t to it } }
        if (vals.size >= 2) {
            val lo = vals.minOf { it.second }; val hi = vals.maxOf { it.second }
            val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
            val dur = tl.durationMs.coerceAtLeast(1L).toFloat()
            val top = H * 0.72f; val bottom = H - 6f
            val path = Path()
            vals.forEachIndexed { i, (t, v) ->
                val x = t / dur * W; val y = bottom - ((v - lo) / span).toFloat() * (bottom - top)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Ui.PRIMARY })
            val tick = Paint().apply { color = Ui.OK; strokeWidth = 3f }
            for (t in tl.repTimes) { val x = t / dur * W; c.drawLine(x, top - 8f, x, top, tick) }
        }
        return bmp
    }
}
