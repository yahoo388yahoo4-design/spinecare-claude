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
 *  recorded video (overlay burned in) or, without video, the skeleton at the
 *  deepest point of the movement plus a sparkline of the exercise metric. */
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

    fun render(tl: SessionTimeline.Timeline): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Ui.SURFACE)
        peak(tl)?.let { s ->
            val fw = s.frameW.takeIf { it > 0 } ?: 480
            val fh = s.frameH.takeIf { it > 0 } ?: 640
            val sc = min(W.toFloat() / fw, H.toFloat() / fh)
            val aw = fw * sc; val ah = fh * sc
            c.save(); c.translate((W - aw) / 2f, (H - ah) / 2f)
            OverlayRenderer.draw(c, s.pixels(), fw, fh, emptyList(), true, "", "", "", s.mirror, aw, ah)
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
