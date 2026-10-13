package com.spinecare.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Replays a session's sensor timeline: the skeleton, framing state, metrics,
 *  cue and rep count at the playhead, drawn with the same OverlayRenderer as
 *  the live session and letterboxed to the camera frame's aspect ratio. */
class ReplayStageView(context: Context) : View(context) {
    var sample: SessionTimeline.Sample? = null
        set(v) { field = v; invalidate() }
    var target = 0
    /** false = skeleton only (the text is hidden by a tap on the picture). */
    var showText = true
        set(v) { field = v; invalidate() }
    private val frameBg = Paint().apply { color = 0xFF111827.toInt() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Ui.BG)
        val s = sample ?: return
        val fw = s.frameW.takeIf { it > 0 } ?: 480
        val fh = s.frameH.takeIf { it > 0 } ?: 640
        val scale = min(width.toFloat() / fw, height.toFloat() / fh)
        val aw = fw * scale; val ah = fh * scale
        val ox = (width - aw) / 2f; val oy = (height - ah) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.clipRect(0f, 0f, aw, ah)
        canvas.drawRect(0f, 0f, aw, ah, frameBg)
        if (showText) {
            OverlayRenderer.draw(canvas, s.pixels(), fw, fh, emptyList(), s.ready, metricsLine(s), s.cue,
                "${s.reps}/$target", s.mirror, aw, ah)
        } else OverlayRenderer.draw(canvas, s.pixels(), fw, fh, emptyList(), s.ready, "", "", "", s.mirror, aw, ah)
        canvas.restore()
    }

    companion object {
        fun metricsLine(s: SessionTimeline.Sample) =
            s.metrics.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${"%.2f".format(it.value)}" }
    }
}

/** Text layer over a clean recording (skeleton only in the video): the cue,
 *  metrics and rep count at the playhead, sized to the video. Hidden by a tap. */
class ReplayHudView(context: Context) : View(context) {
    var sample: SessionTimeline.Sample? = null
        set(v) { field = v; invalidate() }
    var target = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = sample ?: return
        OverlayRenderer.draw(canvas, null, 0, 0, emptyList(), s.ready, ReplayStageView.metricsLine(s), s.cue,
            "${s.reps}/$target", false)
    }
}

/** Metric-over-time chart for a replay: the exercise metric and (if the BLE
 *  posture nodes were connected) head/trunk pitch, each scaled to its own
 *  range, with rep ticks, cue ticks and a playhead. Tap or drag to seek. */
class TimelineChartView(context: Context) : View(context) {
    data class Series(val label: String, val color: Int, val values: FloatArray)

    private var times = LongArray(0)
    private var series = emptyList<Series>()
    private var reps = emptyList<Long>()
    private var cues = emptyList<Long>()
    private var duration = 1L
    var playhead = 0L
        set(v) { field = v; invalidate() }
    var onSeek: (Long) -> Unit = {}

    private val dp = context.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val tick = Paint().apply { strokeWidth = 2f * dp }
    private val head = Paint().apply { color = Color.WHITE; strokeWidth = 2f * dp }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * dp }
    private val grid = Paint().apply { color = 0xFF1F2937.toInt(); strokeWidth = 1f }

    fun setData(tl: SessionTimeline.Timeline, series: List<Series>) {
        times = LongArray(tl.samples.size) { tl.samples[it].t }
        this.series = series
        reps = tl.repTimes; cues = tl.cueTimes
        duration = tl.durationMs.coerceAtLeast(1L)
        invalidate()
    }

    private val top get() = 18f * dp
    private val bottom get() = height - 10f * dp

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Ui.SURFACE)
        val w = width.toFloat()
        fun x(t: Long) = t.toFloat() / duration * w
        canvas.drawLine(0f, bottom, w, bottom, grid)
        canvas.drawLine(0f, top, w, top, grid)

        // legend
        var lx = 6f * dp
        for (s in series) {
            label.color = s.color
            canvas.drawText(s.label, lx, 13f * dp, label)
            lx += label.measureText(s.label) + 14f * dp
        }

        for (s in series) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (v in s.values) if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
            if (lo > hi) continue
            val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
            line.color = s.color
            val path = Path(); var pen = false
            val step = maxOf(1, times.size / (width.coerceAtLeast(1)))   // ~1 point per pixel
            var i = 0
            while (i < times.size) {
                val v = s.values[i]
                if (v.isNaN()) pen = false
                else {
                    val y = bottom - (v - lo) / span * (bottom - top)
                    if (pen) path.lineTo(x(times[i]), y) else { path.moveTo(x(times[i]), y); pen = true }
                }
                i += step
            }
            canvas.drawPath(path, line)
        }

        tick.color = Ui.WARN
        for (t in cues) canvas.drawLine(x(t), bottom - 6f * dp, x(t), bottom, tick)
        tick.color = Ui.OK
        for (t in reps) canvas.drawLine(x(t), top, x(t), top + 8f * dp, tick)
        val px = x(playhead)
        canvas.drawLine(px, top, px, bottom, head)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val t = (e.x.coerceIn(0f, width.toFloat()) / width.coerceAtLeast(1) * duration).toLong()
                onSeek(t); return true
            }
        }
        return super.onTouchEvent(e)
    }
}
